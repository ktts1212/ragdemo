package com.example.ragdemo.document.parser.marker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Marker HTML → 纯文本 提取器（基于 Jsoup）。
 * <p>
 * 禁止简单正则去标签（会破坏 entity、粘连单元格）。职责（skill §14）：
 * HTML → normalized plain text、HTML entity decode、删除空节点。
 */
@Component
public class MarkerTextExtractor {

    /**
     * URL 中的 DOI 特征。Marker 常把 DOI 放在 {@code <a href>}，而可见文本被截断
     * （如 {@code href="https://doi.org/10.1038/xxx"> https://doi.org/1</a>}），
     * 仅取可见文本会丢失 DOI，故对含 DOI 的 href 追加其值。
     */
    private static final Pattern DOI_IN_URL = Pattern.compile("10\\.\\d{4,9}/");

    /**
     * HTML → 规范化纯文本（保留必要换行）。
     */
    public String extractText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        // 保留含 DOI 的 href（可见文本常被截断）
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href");
            if (!href.startsWith("#") && DOI_IN_URL.matcher(href).find()) {
                a.appendText(" " + href);
            }
        }
        // 块级元素之间保留换行，避免段落粘连
        doc.select("br").append("\\n");
        doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr").prepend("\\n");
        String text = doc.text().replace("\\n", "\n");
        return normalize(text);
    }

    /**
     * Table HTML → TSV（row \n、cell \t），保持行/列顺序。
     */
    public String extractTableText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        List<String> rows = new ArrayList<>();
        for (Element row : doc.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th, td")) {
                cells.add(normalize(cell.text()));
            }
            if (!cells.isEmpty()) {
                rows.add(String.join("\t", cells));
            }
        }
        if (rows.isEmpty()) {
            // 没有标准 tr/td 结构时退化为纯文本
            String text = normalize(doc.text());
            return text;
        }
        return String.join("\n", rows);
    }

    /**
     * 从 list HTML 提取列表项（<li>）。
     */
    public List<String> extractListItems(String html) {
        List<String> items = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return items;
        }
        Document doc = Jsoup.parse(html);
        for (Element li : doc.select("li")) {
            String text = normalize(li.text());
            if (!text.isBlank()) {
                items.add(text);
            }
        }
        return items;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" +", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
