package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.mapper.DocumentsMapper;
import com.example.ragdemo.mapper.IngestionTaskMapper;
import com.example.ragdemo.mapper.KnowledgeBasesMapper;
import com.example.ragdemo.model.dto.DocumentIngestionMessage;
import com.example.ragdemo.model.dto.KnowledgeDocumentDto;
import com.example.ragdemo.model.dto.MinioUploadResult;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.model.entity.IngestionTask;
import com.example.ragdemo.model.vo.DocumentVO;
import com.example.ragdemo.rag.ingest.ArtifactKeyBuilder;
import com.example.ragdemo.rag.ingest.ParseMode;
import com.example.ragdemo.rag.ingest.ParseModeResolver;
import com.example.ragdemo.service.DocumentService;
import com.example.ragdemo.service.MinioService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    /** 默认解析版本号（重跑解析时递增；artifact key 的 versions/{version} 段） */
    private static final int DEFAULT_VERSION = 1;

    private final DocumentsMapper documentsMapper;
    private final KnowledgeBasesMapper knowledgeBasesMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final MinioService minioService;
    private final KafkaProducerService kafkaProducerService;
    private final ParseModeResolver parseModeResolver;
    private final ArtifactKeyBuilder artifactKeyBuilder;

    public DocumentServiceImpl(DocumentsMapper documentsMapper,
            KnowledgeBasesMapper knowledgeBasesMapper,
            IngestionTaskMapper ingestionTaskMapper,
            MinioService minioService,
            KafkaProducerService kafkaProducerService,
            ParseModeResolver parseModeResolver,
            ArtifactKeyBuilder artifactKeyBuilder) {
        this.documentsMapper = documentsMapper;
        this.knowledgeBasesMapper = knowledgeBasesMapper;
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.minioService = minioService;
        this.kafkaProducerService = kafkaProducerService;
        this.parseModeResolver = parseModeResolver;
        this.artifactKeyBuilder = artifactKeyBuilder;
    }

    @Override
    public List<DocumentVO> selectDocumentList() {
        return documentsMapper.selectDocumentList();
    }

    @Override
    @Transactional
    public KnowledgeDocumentDto upload(MultipartFile file, Long knowledgeBaseId, ParseMode requestedMode) {
        validateFile(file);
        checkKnowledgeBaseExists(knowledgeBaseId);

        String originalFilename = file.getOriginalFilename();
        String ext = extractFileExt(originalFilename);
        String storedFileName = generateStoredFileName(ext);
        String contentType = file.getContentType();
        String checkSum = calculateSha256(file);

        // ① 三级决策：上传参数 > 配置默认 > 扩展名/MIME 自动判断（决策 D1）
        ParseMode parseMode = parseModeResolver.resolve(requestedMode, originalFilename, contentType);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(originalFilename);
        document.setStoredFileName(storedFileName);
        // 先插入以取得 documentId：ACADEMIC 链路的 MinIO key 依赖它（决策 D2），storage_path 上传后回填
        document.setStoragePath("");
        document.setFileSize(file.getSize());
        document.setContentType(contentType);
        document.setFileExt(ext);
        document.setChecksum(checkSum);
        document.setParseMode(parseMode.name());
        document.setVersion(DEFAULT_VERSION);
        document.setStatus("uploaded");
        document.setProgress(0);
        document.setChunkCount(0);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        documentsMapper.insert(document);

        // ② 计算 artifact key 并上传（ACADEMIC 走版本化层级；GENERIC 保持既有路径，零回归）
        String storagePath = parseMode == ParseMode.ACADEMIC
                ? artifactKeyBuilder.sourceKey(document.getId(), document.getVersion(), ext)
                : legacyStoragePath(knowledgeBaseId, storedFileName);
        minioService.upload(file, storagePath);
        documentsMapper.updateStoragePath(document.getId(), storagePath);

        // ③ 创建入库任务并投递 Kafka
        IngestionTask task = new IngestionTask();
        task.setDocumentId(document.getId());
        task.setKnowledgeBaseId(knowledgeBaseId);
        task.setStatus("pending");
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        ingestionTaskMapper.insert(task);

        kafkaProducerService.sendDocumentIngestionTask(new DocumentIngestionMessage(
                document.getId(), knowledgeBaseId, task.getId()));

        log.info("上传完成: documentId={}, parseMode={}, version={}, storagePath={}",
                document.getId(), parseMode, document.getVersion(), storagePath);
        return toDto(document);
    }

    /** 通用链路沿用既有命名（knowledge-base/{kbId}/{yyyyMMdd}/{uuid}{ext}）。 */
    private String legacyStoragePath(Long knowledgeBaseId, String storedFileName) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("knowledge-base/%d/%s/%s", knowledgeBaseId, dateStr, storedFileName);
    }

    @Override
    public List<KnowledgeDocumentDto> batchUpload(MultipartFile[] files, Long knowledgeBaseId) {
        return null;
    }

    // ==================== private helpers ====================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String ext = extractFileExt(originalFilename);
        Set<String> allowedExts = Set.of(".pdf", ".docx", ".txt", ".md");
        if (!allowedExts.contains(ext)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: " + ext + "，仅支持 " + String.join(", ", allowedExts));
        }

        log.info("文件校验通过: name={}, size={}, ext={}", originalFilename, file.getSize(), ext);
    }

    private void checkKnowledgeBaseExists(Long knowledgeBaseId) {
        boolean isExist = knowledgeBasesMapper.existsById(knowledgeBaseId);
        if (!isExist) {
            throw new IllegalArgumentException("知识库不存在: id=" + knowledgeBaseId);
        }
    }

    private String extractFileExt(String originalFilename) {
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex <= 0) {
            throw new IllegalArgumentException("文件名缺少扩展名: " + originalFilename);
        }
        return originalFilename.substring(dotIndex).toLowerCase();
    }

    private String calculateSha256(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream is = file.getInputStream();
                    DigestInputStream dis = new DigestInputStream(is, digest)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // 只消费流，digest 自动累积哈希
                }
            }

            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }

    private String generateStoredFileName(String fileExt) {
        return UUID.randomUUID().toString() + fileExt;
    }

    private KnowledgeDocumentDto toDto(KnowledgeDocument document) {
        String uploadTime = document.getCreatedAt() != null
                ? document.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : null;
        return new KnowledgeDocumentDto(
                document.getId(),
                document.getFileName(),
                document.getStatus(),
                uploadTime,
                document.getFileSize(),
                document.getContentType(),
                document.getProgress(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getKnowledgeBaseId());
    }
}
