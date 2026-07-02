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

    private final DocumentsMapper documentsMapper;
    private final KnowledgeBasesMapper knowledgeBasesMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final MinioService minioService;
    private final KafkaProducerService kafkaProducerService;

    public DocumentServiceImpl(DocumentsMapper documentsMapper,
            KnowledgeBasesMapper knowledgeBasesMapper,
            IngestionTaskMapper ingestionTaskMapper,
            MinioService minioService,
            KafkaProducerService kafkaProducerService) {
        this.documentsMapper = documentsMapper;
        this.knowledgeBasesMapper = knowledgeBasesMapper;
        this.ingestionTaskMapper = ingestionTaskMapper;
        this.minioService = minioService;
        this.kafkaProducerService = kafkaProducerService;
    }

    @Override
    public List<DocumentVO> selectDocumentList() {
        return documentsMapper.selectDocumentList();
    }

    @Override
    @Transactional
    public KnowledgeDocumentDto upload(MultipartFile file, Long knowledgeBaseId) {
        validateFile(file);
        checkKnowledgeBaseExists(knowledgeBaseId);
        KnowledgeDocument document = new KnowledgeDocument();
        String originalFilename = file.getOriginalFilename();
        String ext = extractFileExt(originalFilename);
        String storedFileName = generateStoredFileName(ext);
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String storagePath = String.format("knowledge-base/%d/%s/%s",
                knowledgeBaseId, dateStr, storedFileName);

        String checkSum = calculateSha256(file);
        MinioUploadResult minioUploadResult = minioService.upload(file, storagePath);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileName(originalFilename);
        document.setStoredFileName(storedFileName);
        document.setStoragePath(storagePath);
        document.setFileSize(file.getSize());
        document.setContentType(file.getContentType());
        document.setFileExt(ext);
        document.setChecksum(checkSum);
        document.setStatus("uploaded");
        document.setProgress(0);
        document.setChunkCount(0);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        documentsMapper.insert(document);

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

        return toDto(document);
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
