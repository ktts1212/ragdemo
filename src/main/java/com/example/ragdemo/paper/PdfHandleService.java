package com.example.ragdemo.paper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.mapper.PaperCatalogMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * 原始 PDF 授权句柄服务（Step 2 §3/§5.4/§5.5）。
 * <p>
 * <b>句柄是什么</b>：服务端签发的、模型不可理解的 opaque token，形如
 * {@code v1.<base64url(payload)>.<base64url(hmac-sha256)>}。payload 只含
 * {@code kind|documentId|knowledgeBaseId|version|page|expiry|nonce}，
 * <b>不含</b> MinIO objectKey / bucket / 任何直链 —— 所以即使 base64 被解开也拿不到内部地址。
 * <p>
 * <b>授权模型（三道关）</b>：
 * <ol>
 *   <li>HMAC 常量时间校验（篡改 ⇒ HANDLE_INVALID）；</li>
 *   <li>有效期校验（过期 ⇒ HANDLE_INVALID）；</li>
 *   <li>知识库范围 + <b>持久层再校验</b>：句柄中的 kbId 必须等于受信上下文 kbId
 *       （否则 NOT_FOUND_OR_FORBIDDEN），且文档在库中仍属于该 kb、版本一致、类型仍为 PDF。</li>
 * </ol>
 * 第 3 点意味着：句柄"领取时通过"不代表"之后一直有效"，每次使用都会重新授权（§5.4 要求）。
 * <p>
 * <b>密钥生命周期</b>：每个进程随机生成 32 字节（SecureRandom），不落盘 ⇒ 进程重启后所有旧句柄
 * 自动失效。这是有意的保守设计：句柄本就是短时凭证（默认 900s）。
 */
@Slf4j
@Service
public class PdfHandleService {

    /** 句柄种类：原始 PDF 与受控页面图像引用。 */
    public static final String KIND_PDF = "PDF";
    public static final String KIND_IMAGE = "IMG";

    private static final String TOKEN_PREFIX = "v1";
    private static final String FIELD_SEPARATOR = "\\|";
    private static final int NONCE_BYTES = 16;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SECRET_BYTES = 32;

    private final PaperCatalogMapper catalogMapper;
    private final byte[] hmacSecret;
    private final Clock clock;

    /**
     * 生产构造：随机密钥 + 系统时钟。
     * <p>显式标注 {@code @Autowired}：本类另有包级构造供测试注入固定密钥/时钟，
     * 有多个构造器时 Spring 必须知道选哪个。
     */
    @Autowired
    public PdfHandleService(PaperCatalogMapper catalogMapper) {
        this(catalogMapper, randomSecret(), Clock.systemUTC());
    }

    /** 测试用构造：可注入固定密钥与可控制时钟。 */
    PdfHandleService(PaperCatalogMapper catalogMapper, byte[] hmacSecret, Clock clock) {
        this.catalogMapper = catalogMapper;
        this.hmacSecret = hmacSecret.clone();
        this.clock = clock;
    }

    // ==================== 对外结果 ====================

    /** 签发的句柄（含展示用文件名；<b>不含</b> objectKey/bucket）。 */
    public record IssuedHandle(String handle, String kind, Long documentId, Integer documentVersion,
            Integer pageNumber, String fileName, String fileExtension, long expiresAtEpochSeconds) {
    }

    /** 校验通过的句柄（仅供内部 Service 使用；storagePath 不出工具 JSON）。 */
    public record VerifiedHandle(String kind, long documentId, int documentVersion, Integer pageNumber,
            String storagePath, String fileName) {
    }

    // ==================== 签发 ====================

    /** 签发原始 PDF 句柄（§5.4）。 */
    public IssuedHandle issuePdfHandle(PaperToolExecutionContext ctx, Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw PaperToolException.invalidArgument("documentId 必须为正整数");
        }
        KnowledgeDocument row = requirePdfDocument(ctx, documentId);
        long expiry = nowEpochSeconds() + ctx.limits().handleTtlSeconds();
        String handle = sign(KIND_PDF, row.getId(), ctx.knowledgeBaseId(), row.getVersion(), null, expiry);
        log.info("[PdfHandle] issued: scope={}, documentId={}, version={}, ttlSeconds={}",
                ctx.describeScope(), row.getId(), row.getVersion(), ctx.limits().handleTtlSeconds());
        return new IssuedHandle(handle, KIND_PDF, row.getId(), row.getVersion(), null,
                row.getFileName(), row.getFileExt(), expiry);
    }

    /**
     * 签发受控页面图像句柄（§5.5）。
     * <p>
     * 句柄<b>只</b>绑定 (documentId, version, page)：图像对象 key 由
     * {@link #pageImageObjectKey} 确定性推导，因此无需（也不允许）把 key 放进句柄。
     */
    public IssuedHandle issueImageHandle(PaperToolExecutionContext ctx, long documentId, int documentVersion,
            int pageNumber) {
        if (pageNumber <= 0) {
            throw PaperToolException.invalidArgument("pageNumber 必须为 1-based 正整数");
        }
        KnowledgeDocument row = requirePdfDocument(ctx, documentId);
        if (row.getVersion() == null || row.getVersion() != documentVersion) {
            throw PaperToolException.invalidArgument("documentVersion 与当前文档版本不一致");
        }
        long expiry = nowEpochSeconds() + ctx.limits().handleTtlSeconds();
        String handle = sign(KIND_IMAGE, row.getId(), ctx.knowledgeBaseId(), documentVersion, pageNumber, expiry);
        return new IssuedHandle(handle, KIND_IMAGE, row.getId(), documentVersion, pageNumber,
                row.getFileName(), row.getFileExt(), expiry);
    }

    // ==================== 校验 ====================

    /**
     * 校验并使用句柄：签名 → 有效期 → 知识库范围 → 持久层归属/版本/类型再校验。
     */
    public VerifiedHandle verify(PaperToolExecutionContext ctx, String handle) {
        if (handle == null || handle.isBlank()) {
            throw PaperToolException.handleInvalid("缺少 pdfHandle");
        }
        String token = handle.trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0])) {
            throw PaperToolException.handleInvalid("pdfHandle 格式非法");
        }

        byte[] payload = decodeQuietly(parts[1]);
        byte[] signature = decodeQuietly(parts[2]);
        if (payload == null || signature == null) {
            throw PaperToolException.handleInvalid("pdfHandle 格式非法");
        }
        if (!MessageDigest.isEqual(signature, hmac(payload))) {
            throw PaperToolException.handleInvalid("pdfHandle 校验失败");
        }

        String[] fields = new String(payload, StandardCharsets.UTF_8).split(FIELD_SEPARATOR);
        if (fields.length != 7) {
            throw PaperToolException.handleInvalid("pdfHandle 载荷非法");
        }
        String kind = fields[0];
        long documentId = parseLong(fields[1]);
        long handleKbId = parseLong(fields[2]);
        int version = (int) parseLong(fields[3]);
        Integer pageNumber = "".equals(fields[4]) ? null : (int) parseLong(fields[4]);
        long expiry = parseLong(fields[5]);

        if (expiry < nowEpochSeconds()) {
            throw PaperToolException.handleInvalid("pdfHandle 已过期，请重新获取");
        }
        // 有效签名但属于别的知识库 ⇒ 真实越权尝试：与"不存在"返回同一错误码，避免探测
        if (handleKbId != ctx.knowledgeBaseId()) {
            throw PaperToolException.notFoundOrForbidden("pdfHandle 对应文档");
        }

        KnowledgeDocument row = catalogMapper.selectDocumentInScope(ctx.knowledgeBaseId(), documentId);
        if (row == null) {
            throw PaperToolException.notFoundOrForbidden("document");
        }
        if (row.getVersion() == null || row.getVersion() != version) {
            throw PaperToolException.handleInvalid("pdfHandle 已绑定旧版本，文档已重新入库");
        }
        if (!isPdf(row)) {
            throw PaperToolException.unsupportedType("该文档不是 PDF，无法按页读取");
        }

        String storagePath;
        if (KIND_IMAGE.equals(kind)) {
            if (pageNumber == null || pageNumber <= 0) {
                throw PaperToolException.handleInvalid("图像句柄缺少合法页码");
            }
            storagePath = pageImageObjectKey(documentId, version, pageNumber);
        } else {
            if (row.getStoragePath() == null || row.getStoragePath().isBlank()) {
                throw PaperToolException.pdfTextUnavailable("该文档尚未记录原始文件位置（可能仍在入库）", null);
            }
            storagePath = row.getStoragePath();
        }
        return new VerifiedHandle(kind, documentId, version, pageNumber, storagePath, row.getFileName());
    }

    // ==================== key 推导（不暴露给模型） ====================

    /** 受控页面图像对象 key：{@code documents/{id}/versions/{v}/artifacts/page-images/page-{n}.png}。 */
    public static String pageImageObjectKey(long documentId, int documentVersion, int pageNumber) {
        return "documents/" + documentId + "/versions/" + documentVersion
                + "/artifacts/page-images/page-" + pageNumber + ".png";
    }

    // ==================== 内部 ====================

    private KnowledgeDocument requirePdfDocument(PaperToolExecutionContext ctx, long documentId) {
        KnowledgeDocument row = catalogMapper.selectDocumentInScope(ctx.knowledgeBaseId(), documentId);
        if (row == null) {
            throw PaperToolException.notFoundOrForbidden("document");
        }
        if (!isPdf(row)) {
            throw PaperToolException.unsupportedType("该文档不是 PDF（fileExt=" + row.getFileExt() + "）");
        }
        if (row.getStoragePath() == null || row.getStoragePath().isBlank()) {
            throw PaperToolException.pdfTextUnavailable("该文档尚未记录原始文件位置（可能仍在入库）", null);
        }
        return row;
    }

    private static boolean isPdf(KnowledgeDocument row) {
        return PaperCatalogService.isPdfExtension(row.getFileExt());
    }

    private String sign(String kind, long documentId, long knowledgeBaseId, int version,
            Integer pageNumber, long expiryEpochSeconds) {
        String nonce = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes(NONCE_BYTES));
        String payloadText = String.join("|", kind, String.valueOf(documentId),
                String.valueOf(knowledgeBaseId), String.valueOf(version),
                pageNumber == null ? "" : String.valueOf(pageNumber),
                String.valueOf(expiryEpochSeconds), nonce);
        byte[] payload = payloadText.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return TOKEN_PREFIX + "." + encoder.encodeToString(payload) + "." + encoder.encodeToString(hmac(payload));
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacSecret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw PaperToolException.internal("句柄签名计算失败", e);
        }
    }

    private static byte[] decodeQuietly(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw PaperToolException.handleInvalid("pdfHandle 载荷非法");
        }
    }

    private long nowEpochSeconds() {
        return Instant.now(clock).getEpochSecond();
    }

    private static byte[] randomSecret() {
        return randomBytes(SECRET_BYTES);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
