package com.example.ragdemo.paper;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com.example.ragdemo.agent.tools.PaperToolException;
import com.example.ragdemo.agent.tools.PaperToolExecutionContext;
import com.example.ragdemo.agent.tools.model.ExtractionMethod;
import com.example.ragdemo.service.MinioService;

import lombok.extern.slf4j.Slf4j;

/**
 * 原始 PDF 按页读取服务（Step 2 §5.5）。
 * <p>
 * <b>实现策略</b>
 * <ul>
 *   <li>只接受 {@link PdfHandleService.VerifiedHandle}：URL/磁盘路径/bucket/key 都不是入参，
 *       句柄已在 {@link PdfHandleService#verify} 中完成授权与再校验；</li>
 *   <li>文本层用已有依赖 PDFBox（随 langchain4j-document-parser-apache-pdfbox 传递引入），
 *       不新增重量级服务；</li>
 *   <li>硬限制：页数、文件大小、单次页数、字符数、渲染最长边、渲染字节数、提取超时；
 *       资源用 try-with-resources / 有界线程池，确保流与临时对象都能释放；</li>
 *   <li>OCR <b>未实现</b>：无文本层时返回稳定的 {@code OCR_REQUIRED}，绝不假装"只 OCR 一页"；</li>
 *   <li>页面图像只返回<b>受控句柄</b>，不把图片二进制塞进工具 JSON，也不宣称模型已视觉核验。</li>
 * </ul>
 */
@Slf4j
@Service
public class PdfPageReadingService {

    /** 渲染基准 DPI（之后按最长边上限缩放）。 */
    private static final int RENDER_DPI = 150;
    /** 单页 PNG 字节上限（防止 JSON 之外的资源被滥用）。 */
    private static final long MAX_PAGE_IMAGE_BYTES = 5L * 1024 * 1024;
    /** 乱码判定阈值：不可打印/替换字符占比。 */
    private static final double GARBLED_RATIO_THRESHOLD = 0.3;

    private final MinioService minioService;
    private final PdfHandleService pdfHandleService;

    public PdfPageReadingService(MinioService minioService, PdfHandleService pdfHandleService) {
        this.minioService = minioService;
        this.pdfHandleService = pdfHandleService;
    }

    // ==================== 对外结果 ====================

    /** 单页文本。 */
    public record PdfPageView(int pageNumber, String text, int charCount, boolean truncated,
            boolean textLayerPresent, List<String> warnings) {
        public PdfPageView {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 受控页面图像引用（只有句柄与尺寸，没有 objectKey、没有直链、没有二进制）。 */
    public record PageImageRef(int pageNumber, int widthPixels, int heightPixels, String imageHandle,
            List<String> warnings) {
        public PageImageRef {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 一次按页读取的结果。 */
    public record PdfPagesView(long documentId, Integer documentVersion, String fileName, int startPage,
            int endPage, int totalPages, ExtractionMethod extractionMethod, List<PdfPageView> pages,
            PageImageRef pageImage, List<String> warnings) {
        public PdfPagesView {
            pages = pages == null ? List.of() : List.copyOf(pages);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    // ==================== 主流程 ====================

    public PdfPagesView readPages(PaperToolExecutionContext ctx, PdfHandleService.VerifiedHandle handle,
            int startPage, int endPage, boolean includePageImage) {
        if (!PdfHandleService.KIND_PDF.equals(handle.kind())) {
            throw PaperToolException.invalidArgument("该句柄不是原始 PDF 句柄");
        }
        int pagesRequested = endPage - startPage + 1;
        int maxPagesPerCall = ctx.limits().readPdfMaxPages();
        if (pagesRequested > maxPagesPerCall) {
            throw PaperToolException.limitExceeded("一次读取页数", pagesRequested, maxPagesPerCall);
        }

        byte[] pdfBytes = downloadBounded(handle.storagePath(), ctx.limits().pdfMaxBytes());

        List<String> viewWarnings = new ArrayList<>();
        List<PdfPageView> pageViews = new ArrayList<>();
        PageImageRef pageImage = null;
        int totalPages = 0;
        int effectiveEnd = endPage;

        try (PDDocument document = PDDocument.load(pdfBytes)) {
            totalPages = document.getNumberOfPages();
            int maxDocumentPages = ctx.limits().pdfMaxPages();
            if (totalPages > maxDocumentPages) {
                throw PaperToolException.limitExceeded("PDF 页数", totalPages, maxDocumentPages);
            }
            if (startPage > totalPages) {
                throw PaperToolException.invalidArgument("startPage 超出文档页数（共 " + totalPages + " 页）");
            }
            effectiveEnd = Math.min(endPage, totalPages);
            if (effectiveEnd != endPage) {
                viewWarnings.add("CLAMPED_TO_LAST_PAGE: 结束页超出文档页数，已裁剪到第 " + totalPages + " 页");
            }

            int charBudget = ctx.limits().readPdfTextMaxChars();
            boolean anyTextFound = false;
            for (int page = startPage; page <= effectiveEnd; page++) {
                String rawText = extractPageText(document, page, ctx.limits().pdfReadTimeoutMs());
                String normalized = normalize(rawText);
                boolean textLayerPresent = !normalized.isEmpty();
                anyTextFound = anyTextFound || textLayerPresent;

                List<String> pageWarnings = new ArrayList<>();
                if (!textLayerPresent) {
                    pageWarnings.add("PAGE_TEXT_EMPTY: 该页无文本层（可能是扫描页），需要 OCR（本步未实现）");
                } else if (garbledRatio(normalized) > GARBLED_RATIO_THRESHOLD) {
                    pageWarnings.add("TEXT_MAY_BE_GARBLED: 不可打印字符比例偏高，文本层可能不可靠");
                }

                boolean truncated = false;
                String text = normalized;
                if (text.length() > charBudget) {
                    text = text.substring(0, Math.max(charBudget, 0));
                    truncated = true;
                    pageWarnings.add("TEXT_TRUNCATED: 已达单次字符上限 " + ctx.limits().readPdfTextMaxChars());
                    charBudget = 0;
                } else {
                    charBudget -= text.length();
                }
                pageViews.add(new PdfPageView(page, text, text.length(), truncated, textLayerPresent,
                        pageWarnings));
            }

            if (!anyTextFound) {
                // 稳定的能力缺口信号：不返回空壳成功，也不假装 OCR 过
                throw PaperToolException.ocrRequired(
                        "所选页无文本层，需要 OCR（本步未实现）；可改为获取页面图像引用供人工核对");
            }

            if (includePageImage) {
                pageImage = renderPageImage(ctx, handle, document, startPage, viewWarnings);
            }
        } catch (PaperToolException e) {
            throw e;
        } catch (Exception e) {
            throw PaperToolException.pdfTextUnavailable("PDF 读取失败（文件可能损坏或加密）", e);
        }

        log.info("[PdfPages] read: scope={}, documentId={}, pages={}-{}, textChars={}",
                ctx.describeScope(), handle.documentId(), startPage, endPage,
                pageViews.stream().mapToInt(PdfPageView::charCount).sum());
        return new PdfPagesView(handle.documentId(), handle.documentVersion(), handle.fileName(),
                startPage, effectiveEnd, totalPages, ExtractionMethod.PDF_TEXT, pageViews, pageImage,
                viewWarnings);
    }

    // ==================== 文本提取 ====================

    private String extractPageText(PDDocument document, int pageNumber, int timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pdf-text-extract");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<String> future = executor.submit(() -> {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                stripper.setSortByPosition(false);
                return stripper.getText(document);
            });
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw PaperToolException.pdfTextUnavailable("PDF 文本提取超时（>" + timeoutMs + "ms）", e);
        } catch (ExecutionException e) {
            throw PaperToolException.pdfTextUnavailable("PDF 文本提取失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PaperToolException.pdfTextUnavailable("PDF 文本提取被中断", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        unified = unified.replaceAll("\n{3,}", "\n\n");
        return unified.trim();
    }

    private static double garbledRatio(String text) {
        int control = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD' || (Character.isISOControl(c) && c != '\n' && c != '\t')) {
                control++;
            }
        }
        return (double) control / text.length();
    }

    // ==================== 页面图像（受控） ====================

    private PageImageRef renderPageImage(PaperToolExecutionContext ctx, PdfHandleService.VerifiedHandle handle,
            PDDocument document, int pageNumber, List<String> viewWarnings) {
        if (!ctx.limits().pageImageEnabled()) {
            viewWarnings.add("PAGE_IMAGE_UNAVAILABLE: 服务端未启用页面图像渲染");
            return null;
        }
        try {
            RenderedImage rendered = renderPagePng(document, pageNumber, ctx.limits().pdfImageMaxEdgePixels());
            String objectKey = PdfHandleService.pageImageObjectKey(handle.documentId(),
                    handle.documentVersion(), pageNumber);
            minioService.upload(rendered.png(), objectKey, "image/png");
            String imageHandle = pdfHandleService
                    .issueImageHandle(ctx, handle.documentId(), handle.documentVersion(), pageNumber).handle();
            List<String> warnings = new ArrayList<>();
            warnings.add("MODEL_CANNOT_VIEW_IMAGE: 页面图像只提供受控句柄，"
                    + "仅供人工核对或后续视觉工具使用，模型未做视觉核验");
            return new PageImageRef(pageNumber, rendered.widthPixels(), rendered.heightPixels(),
                    imageHandle, warnings);
        } catch (PaperToolException e) {
            log.warn("[PdfPages] 页面图像不可用: documentId={}, page={}, reason={}",
                    handle.documentId(), pageNumber, e.getMessage());
            viewWarnings.add("PAGE_IMAGE_UNAVAILABLE: " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("[PdfPages] 页面图像渲染失败: documentId={}, page={}", handle.documentId(), pageNumber);
            viewWarnings.add("PAGE_IMAGE_UNAVAILABLE: 页面图像渲染或存储失败");
            return null;
        }
    }

    private record RenderedImage(byte[] png, int widthPixels, int heightPixels) {
    }

    private RenderedImage renderPagePng(PDDocument document, int pageNumber, int maxEdgePixels) {
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, RENDER_DPI, ImageType.RGB);
            BufferedImage bounded = downscaleToMaxEdge(image, maxEdgePixels);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(bounded, "png", out);
            byte[] png = out.toByteArray();
            if (png.length > MAX_PAGE_IMAGE_BYTES) {
                throw PaperToolException.pageImageUnavailable("页面图像超出大小上限");
            }
            return new RenderedImage(png, bounded.getWidth(), bounded.getHeight());
        } catch (PaperToolException e) {
            throw e;
        } catch (Exception e) {
            throw PaperToolException.pageImageUnavailable("页面图像渲染失败（" + e.getClass().getSimpleName() + "）");
        }
    }

    private static BufferedImage downscaleToMaxEdge(BufferedImage image, int maxEdgePixels) {
        int longestEdge = Math.max(image.getWidth(), image.getHeight());
        if (longestEdge <= maxEdgePixels) {
            return image;
        }
        double scale = (double) maxEdgePixels / longestEdge;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    // ==================== 资源读取 ====================

    private byte[] downloadBounded(String objectKey, long maxBytes) {
        try (InputStream in = minioService.download(objectKey)) {
            if (in == null) {
                throw PaperToolException.pdfTextUnavailable("原始 PDF 不存在", null);
            }
            int cap = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
            byte[] data = in.readNBytes(cap);
            if (data.length > maxBytes) {
                throw PaperToolException.limitExceeded("PDF 文件大小(bytes)", data.length, maxBytes);
            }
            if (data.length == 0) {
                throw PaperToolException.pdfTextUnavailable("原始 PDF 为空", null);
            }
            return data;
        } catch (PaperToolException e) {
            throw e;
        } catch (Exception e) {
            throw PaperToolException.pdfTextUnavailable("原始 PDF 下载失败", e);
        }
    }
}
