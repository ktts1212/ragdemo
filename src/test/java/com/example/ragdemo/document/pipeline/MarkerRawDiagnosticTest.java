package com.example.ragdemo.document.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.example.ragdemo.document.marker.raw.MarkerBlock;
import com.example.ragdemo.document.marker.raw.MarkerBlockType;
import com.example.ragdemo.document.marker.raw.MarkerDocument;
import com.example.ragdemo.document.parser.marker.MarkerTextExtractor;
import com.example.ragdemo.infrastructure.marker.MarkerClient;
import com.example.ragdemo.infrastructure.marker.MarkerHttpClient;
import com.example.ragdemo.infrastructure.marker.MarkerProperties;
import com.example.ragdemo.infrastructure.marker.MarkerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Marker 原始输出结构诊断（QA 辅助，skill §32：raw JSON 与 Canonical 对照）。
 * <p>
 * 用途：当 Cleaner 结果异常（如 References 未提取）时，查看 Marker 到底把内容
 * 标成了什么 block_type / section title，从而定位是 Parser/Cleaner 规则问题还是
 * Marker 输出问题。
 * <p>
 * 运行：{@code mvn test -Dtest=MarkerRawDiagnosticTest -Dmarker.qa.pdf=<path>}
 */
class MarkerRawDiagnosticTest {

    private static final String MARKER_BASE_URL = "http://127.0.0.1:18001";

    @Test
    void dumpMarkerStructure() throws Exception {
        String pdfProperty = System.getProperty("marker.qa.pdf");
        Assumptions.assumeTrue(pdfProperty != null && !pdfProperty.isBlank(),
                "未提供 -Dmarker.qa.pdf，跳过");
        Path pdfPath = Path.of(pdfProperty);
        Assumptions.assumeTrue(Files.exists(pdfPath), "PDF 不存在: " + pdfPath);
        String fileName = pdfPath.getFileName().toString();

        MarkerProperties properties = new MarkerProperties();
        properties.setBaseUrl(MARKER_BASE_URL);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(900));
        MarkerClient client = new MarkerHttpClient(properties);
        Assumptions.assumeTrue(client.isAlive(), "Marker API 不可达，跳过");

        MarkerResponse response = client.parse(Files.readAllBytes(pdfPath), fileName);
        ObjectMapper objectMapper = new ObjectMapper();
        MarkerDocument document = objectMapper.treeToValue(response.data(), MarkerDocument.class);

        // 保存 raw JSON（去掉 images 以免巨大）
        Path outDir = Path.of("target/marker-raw");
        Files.createDirectories(outDir);
        ObjectMapper prettyMapper = new ObjectMapper();
        var tree = prettyMapper.readTree(response.data().toString());
        Files.writeString(outDir.resolve(fileName + ".json"),
                prettyMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));

        // 结构统计
        Map<String, Integer> typeHistogram = new LinkedHashMap<>();
        countTypes(document.getChildren(), typeHistogram);
        MarkerTextExtractor textExtractor = new MarkerTextExtractor();

        System.out.println("=========== Marker Raw Diagnostic: " + fileName + " ===========");
        System.out.println("top block_type : " + document.getBlockType());
        System.out.println("type histogram : " + typeHistogram);
        System.out.println("--- SectionHeaders ---");
        document.getChildren().stream()
                .flatMap(page -> page.getChildren().stream())
                .filter(b -> MarkerBlockType.from(b.getBlockType()) == MarkerBlockType.SECTION_HEADER)
                .forEach(header -> System.out.println("  [" + header.getId() + "] "
                        + textExtractor.extractText(header.getHtml())));
        System.out.println("--- Lists ---");
        document.getChildren().stream()
                .flatMap(page -> page.getChildren().stream())
                .filter(b -> MarkerBlockType.from(b.getBlockType()) == MarkerBlockType.LIST)
                .forEach(list -> System.out.println("  [" + list.getId() + "] childItems="
                        + (list.getChildren() == null ? 0 : list.getChildren().size())
                        + " htmlPreview=" + preview(textExtractor.extractText(list.getHtml()))));
        System.out.println("--- 含 reference/bibliograph 的任意文本块 ---");
        document.getChildren().stream()
                .flatMap(page -> page.getChildren().stream())
                .filter(b -> {
                    String t = (b.getHtml() == null ? "" : b.getHtml()).toLowerCase();
                    return t.contains("reference") || t.contains("bibliograph");
                })
                .forEach(b -> System.out.println("  type=" + b.getBlockType() + " id=" + b.getId()
                        + " text=" + preview(textExtractor.extractText(b.getHtml()))));
        System.out.println("rawJsonSaved : " + outDir.resolve(fileName + ".json").toAbsolutePath());
        System.out.println("=========================================================");
    }

    private void countTypes(List<MarkerBlock> blocks, Map<String, Integer> histogram) {
        if (blocks == null) {
            return;
        }
        for (MarkerBlock block : blocks) {
            String type = MarkerBlockType.from(block.getBlockType()).name();
            histogram.merge(type, 1, Integer::sum);
            countTypes(block.getChildren(), histogram);
        }
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }
}
