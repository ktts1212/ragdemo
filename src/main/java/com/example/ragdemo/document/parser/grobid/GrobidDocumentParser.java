package com.example.ragdemo.document.parser.grobid;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Affiliation;
import com.example.ragdemo.document.domain.Author;
import com.example.ragdemo.document.domain.CitationMarker;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.PaperMetadata;
import com.example.ragdemo.document.domain.ParserInfo;
import com.example.ragdemo.document.domain.PartialDate;
import com.example.ragdemo.document.domain.Reference;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.FigureBlock;
import com.example.ragdemo.document.domain.block.FormulaBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;
import com.example.ragdemo.document.domain.block.TableBlock;
import com.example.ragdemo.document.parser.grobid.exception.GrobidDocumentParseException;

import lombok.extern.slf4j.Slf4j;

/**
 * GROBID TEI XML → {@link AcademicDocument} 映射器。
 * <p>
 * 职责边界（遵循 grobid-document-parser-skill）：
 * <ul>
 *   <li>只做忠实映射：TEI 中是什么就映射成什么，不修复摘要边界、不强猜 section 层级、不改写正文</li>
 *   <li>缺字段不整篇失败：title/DOI/abstract/references/pageRange 缺失时保持 null/空集合</li>
 *   <li>namespace-aware + 防 XXE</li>
 *   <li>保留 XML 原始顺序（section / block 均按文档顺序）</li>
 *   <li>无 coords 不伪造页码</li>
 * </ul>
 * 线程安全：无实例字段保存解析状态，可作为 Spring singleton 使用。
 */
@Slf4j
@Component
public class GrobidDocumentParser {

    // ==================== 常量 ====================

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(\\d{4})\\b");
    private static final Pattern WHEN_PATTERN =
            Pattern.compile("^(\\d{4})(?:-(\\d{1,2}))?(?:-(\\d{1,2}))?");

    // ==================== 公开接口 ====================

    /**
     * 解析 GROBID 返回的 TEI XML。
     *
     * @param documentId 系统现有 documents.id（不从 TEI 生成）
     * @param teiXml     GrobidClient 返回的完整 TEI XML
     * @return 统一学术文档对象；可选字段缺失不抛异常
     * @throws GrobidDocumentParseException TEI 空/XML 非法/根不可识别时抛出
     */
    public AcademicDocument parse(String documentId, String teiXml) {
        long start = System.currentTimeMillis();
        if (documentId == null || documentId.isBlank()) {
            throw new GrobidDocumentParseException("documentId 不能为空");
        }
        if (teiXml == null || teiXml.isBlank()) {
            throw new GrobidDocumentParseException("teiXml 不能为空");
        }

        Document tei = parseXml(teiXml);
        ensureTeiRoot(tei);

        PaperMetadata metadata = parseMetadata(tei);
        String abstractText = parseAbstract(tei);
        List<Section> sections = parseBodySections(tei);
        List<Reference> references = parseReferences(tei);
        ParserInfo parserInfo = parseParserInfo(tei);

        AcademicDocument result = AcademicDocument.builder()
                .documentId(documentId)
                .metadata(metadata)
                .abstractText(abstractText)
                .sections(sections)
                .references(references)
                .parserInfo(parserInfo)
                .build();

        log.info("TEI 解析完成: documentId={}, titlePresent={}, authorCount={}, sectionCount={}, "
                        + "referenceCount={}, parserVersion={}, durationMs={}ms",
                documentId,
                metadata != null && metadata.getTitle() != null,
                metadata != null ? metadata.getAuthors().size() : 0,
                sections.size(),
                references.size(),
                parserInfo != null ? parserInfo.getParserVersion() : null,
                System.currentTimeMillis() - start);
        return result;
    }

    // ==================== XML 解析 ====================

    private Document parseXml(String teiXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // 防 XXE
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(teiXml)));
        } catch (ParserConfigurationException e) {
            throw new GrobidDocumentParseException("XML 解析器初始化失败", e);
        } catch (SAXException e) {
            throw new GrobidDocumentParseException("TEI XML 不是 well-formed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new GrobidDocumentParseException("TEI XML 解析失败: " + e.getMessage(), e);
        }
    }

    private void ensureTeiRoot(Document tei) {
        Element root = tei.getDocumentElement();
        if (root == null || !"TEI".equals(root.getLocalName())) {
            throw new GrobidDocumentParseException("根节点不是可识别的 TEI 文档");
        }
    }

    // ==================== Metadata ====================

    private PaperMetadata parseMetadata(Document tei) {
        Element biblStruct = evaluateElement(tei,
                "/tei:TEI/tei:teiHeader/tei:fileDesc/tei:sourceDesc/tei:biblStruct");

        String title = null;
        if (biblStruct != null) {
            // 优先 analytic 主标题，fallback titleStmt 主标题；绝不全局找第一个 title（references 中也有大量 title）
            title = textOrNull(evaluateElement(biblStruct, "tei:analytic/tei:title[@level='a'][@type='main']"));
        }
        if (title == null) {
            title = textOrNull(evaluateElement(tei,
                    "/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt/tei:title[@type='main']"));
        }

        List<Author> authors = biblStruct != null ? parseAuthors(biblStruct) : new ArrayList<>();
        Map<String, Affiliation> affiliationMap =
                biblStruct != null ? collectAffiliations(biblStruct) : new LinkedHashMap<>();

        List<String> keywords = parseKeywords(tei);
        String doi = textOrNull(evaluateElement(biblStruct, "tei:idno[@type='DOI']"));
        if (doi == null && biblStruct != null) {
            doi = textOrNull(evaluateElement(biblStruct, "tei:monogr/tei:idno[@type='DOI']"));
        }
        String arxivId = textOrNull(evaluateElement(biblStruct, "tei:idno[@type='arXiv']"));
        if (arxivId == null && biblStruct != null) {
            arxivId = textOrNull(evaluateElement(biblStruct, "tei:monogr/tei:idno[@type='arXiv']"));
        }
        String journal = textOrNull(evaluateElement(biblStruct, "tei:monogr/tei:title[@level='j']"));
        String publisher = textOrNull(evaluateElement(tei,
                "/tei:TEI/tei:teiHeader/tei:fileDesc/tei:publicationStmt/tei:publisher"));
        PartialDate publicationDate = parsePublicationDate(biblStruct);

        return PaperMetadata.builder()
                .title(title)
                .authors(authors)
                .affiliations(new ArrayList<>(affiliationMap.values()))
                .keywords(keywords)
                .doi(doi)
                .arxivId(arxivId)
                .journal(journal)
                .publisher(publisher)
                .publicationDate(publicationDate)
                .build();
    }

    // ==================== Authors ====================

    private List<Author> parseAuthors(Element biblStruct) {
        List<Author> authors = new ArrayList<>();
        NodeList authorNodes = evaluateNodes(biblStruct, "tei:analytic/tei:author");
        for (int i = 0; i < authorNodes.getLength(); i++) {
            Element authorEl = (Element) authorNodes.item(i);
            Element persName = firstChild(authorEl, "persName");
            if (persName == null) {
                // 没有结构化姓名时仅保留规范化的整体文本，不把 affiliation 文本混入
                String raw = normalizeWhitespace(authorEl.getTextContent());
                if (!raw.isBlank()) {
                    authors.add(Author.builder().fullName(raw).build());
                }
                continue;
            }

            String fullName = buildFullName(persName);
            String firstName = null;
            String middleName = null;
            String lastName = null;
            List<String> forenames = new ArrayList<>();
            for (Element child : directChildElements(persName)) {
                String tag = child.getTagName();
                if ("forename".equals(tag)) {
                    String text = textOrNull(child);
                    if (text != null) {
                        forenames.add(text);
                    }
                } else if ("surname".equals(tag)) {
                    lastName = textOrNull(child);
                }
            }
            if (!forenames.isEmpty()) {
                firstName = forenames.get(0);
                if (forenames.size() > 1) {
                    middleName = String.join(" ", forenames.subList(1, forenames.size()));
                }
            }

            String email = textOrNull(firstChild(authorEl, "email"));
            String orcid = null;
            for (Element idno : directChildElements(authorEl)) {
                if ("idno".equals(idno.getTagName()) && "ORCID".equals(idno.getAttribute("type"))) {
                    orcid = normalizeWhitespace(idno.getTextContent());
                }
            }

            authors.add(Author.builder()
                    .fullName(fullName)
                    .firstName(firstName)
                    .middleName(middleName)
                    .lastName(lastName)
                    .email(email)
                    .orcid(orcid)
                    .affiliationIds(collectAffiliationRefs(authorEl))
                    .build());
        }
        return authors;
    }

    private String buildFullName(Element persName) {
        List<String> parts = new ArrayList<>();
        for (Element child : directChildElements(persName)) {
            String tag = child.getTagName();
            if ("forename".equals(tag) || "surname".equals(tag) || "genName".equals(tag)
                    || "nameLink".equals(tag) || "addName".equals(tag)) {
                String text = textOrNull(child);
                if (text != null) {
                    parts.add(text);
                }
            }
        }
        return String.join(" ", parts);
    }

    private List<String> collectAffiliationRefs(Element authorEl) {
        List<String> refs = new ArrayList<>();
        for (Element child : directChildElements(authorEl)) {
            if ("affiliation".equals(child.getTagName())) {
                String key = child.getAttribute("key");
                if (key != null && !key.isBlank() && !refs.contains(key)) {
                    refs.add(key);
                }
            }
        }
        return refs;
    }

    /**
     * 收集并去重机构（同一 key 可能被多个作者引用）。
     */
    private Map<String, Affiliation> collectAffiliations(Element biblStruct) {
        Map<String, Affiliation> map = new LinkedHashMap<>();
        NodeList affiliationNodes = evaluateNodes(biblStruct, "tei:analytic/tei:affiliation[@key]");
        for (int i = 0; i < affiliationNodes.getLength(); i++) {
            Element affEl = (Element) affiliationNodes.item(i);
            String key = affEl.getAttribute("key");
            if (key == null || key.isBlank() || map.containsKey(key)) {
                continue;
            }
            List<String> institutions = new ArrayList<>();
            List<String> departments = new ArrayList<>();
            for (Element orgName : directChildElements(affEl)) {
                if (!"orgName".equals(orgName.getTagName())) {
                    continue;
                }
                String text = textOrNull(orgName);
                if (text == null) {
                    continue;
                }
                String type = orgName.getAttribute("type");
                if ("department".equals(type)) {
                    departments.add(text);
                } else if ("institution".equals(type)) {
                    institutions.add(text);
                }
            }
            Element addressEl = firstChild(affEl, "address");
            String address = addressEl != null ? normalizeWhitespace(addressEl.getTextContent()) : null;
            String country = textOrNull(firstChild(addressEl, "country"));

            // 多个 institution/department 用 "; " 统一拼接，不静默覆盖
            map.put(key, Affiliation.builder()
                    .id(key)
                    .institution(institutions.isEmpty() ? null : String.join("; ", institutions))
                    .department(departments.isEmpty() ? null : String.join("; ", departments))
                    .address(address)
                    .country(country)
                    .build());
        }
        return map;
    }

    // ==================== Keywords / Date / Abstract ====================

    private List<String> parseKeywords(Document tei) {
        NodeList termNodes = evaluateNodes(tei,
                "/tei:TEI/tei:teiHeader/tei:profileDesc/tei:textClass/tei:keywords//tei:term");
        List<String> keywords = new ArrayList<>();
        for (int i = 0; i < termNodes.getLength(); i++) {
            String text = normalizeWhitespace(termNodes.item(i).getTextContent());
            if (!text.isBlank()) {
                keywords.add(text);
            }
        }
        if (keywords.isEmpty()) {
            NodeList keywordNodes = evaluateNodes(tei,
                    "/tei:TEI/tei:teiHeader/tei:profileDesc/tei:textClass/tei:keywords//tei:keyword");
            for (int i = 0; i < keywordNodes.getLength(); i++) {
                String text = normalizeWhitespace(keywordNodes.item(i).getTextContent());
                if (!text.isBlank()) {
                    keywords.add(text);
                }
            }
        }
        return keywords;
    }

    private PartialDate parsePublicationDate(Element biblStruct) {
        if (biblStruct == null) {
            return null;
        }
        Element dateEl = evaluateElement(biblStruct, "tei:monogr/tei:imprint/tei:date");
        if (dateEl == null) {
            dateEl = evaluateElement(biblStruct, "tei:date");
        }
        return parsePartialDate(dateEl);
    }

    private PartialDate parsePartialDate(Element dateElement) {
        if (dateElement == null) {
            return null;
        }
        String rawText = normalizeWhitespace(dateElement.getTextContent());
        String when = dateElement.getAttribute("when");

        Matcher whenMatcher = WHEN_PATTERN.matcher(when);
        if (whenMatcher.find()) {
            Integer year = whenMatcher.group(1) != null ? Integer.valueOf(whenMatcher.group(1)) : null;
            Integer month = whenMatcher.group(2) != null ? Integer.valueOf(whenMatcher.group(2)) : null;
            Integer day = whenMatcher.group(3) != null ? Integer.valueOf(whenMatcher.group(3)) : null;
            return PartialDate.builder().year(year).month(month).day(day).rawText(rawText).build();
        }

        // 无 @when 时从原始文本提取年份（如 "March 2024" → 2024）
        Matcher yearMatcher = YEAR_PATTERN.matcher(rawText);
        Integer year = yearMatcher.find() ? Integer.valueOf(yearMatcher.group(1)) : null;
        return PartialDate.builder().year(year).rawText(rawText).build();
    }

    private String parseAbstract(Document tei) {
        Element abstractEl = evaluateElement(tei,
                "/tei:TEI/tei:teiHeader/tei:profileDesc/tei:abstract");
        if (abstractEl == null) {
            return null;
        }
        List<String> paragraphs = new ArrayList<>();
        for (Element child : directChildElements(abstractEl)) {
            if ("p".equals(child.getTagName())) {
                String text = normalizeWhitespace(child.getTextContent());
                if (!text.isBlank()) {
                    paragraphs.add(text);
                }
            }
        }
        if (paragraphs.isEmpty()) {
            String text = normalizeWhitespace(abstractEl.getTextContent());
            return text.isBlank() ? null : text;
        }
        return String.join("\n\n", paragraphs);
    }

    // ==================== Body Sections ====================

    private List<Section> parseBodySections(Document tei) {
        Element body = evaluateElement(tei, "/tei:TEI/tei:text/tei:body");
        if (body == null) {
            return new ArrayList<>();
        }
        List<Section> sections = new ArrayList<>();
        Section unlabeled = null; // 收集 body 直接子级中非 div 的内容
        for (Element child : directChildElements(body)) {
            if ("div".equals(child.getTagName())) {
                unlabeled = null;
                sections.add(parseSection(child, new ArrayList<>(), sections.size()));
            } else {
                // 无 div 包裹的正文内容：放入无标题 section，禁止自动命名 Introduction
                if (unlabeled == null) {
                    unlabeled = Section.builder()
                            .id(null)
                            .title(null)
                            .level(1)
                            .path(new ArrayList<>())
                            .order(sections.size())
                            .build();
                    sections.add(unlabeled);
                }
                addBlockToSection(unlabeled, child, unlabeled.getBlocks().size());
            }
        }
        return sections;
    }

    private Section parseSection(Element div, List<String> parentPath, int order) {
        Element head = firstChild(div, "head");
        String title = textOrNull(head);

        List<String> path = new ArrayList<>(parentPath);
        if (title != null) {
            path.add(title);
        }

        Section section = Section.builder()
                .id(getXmlId(div))
                .title(title)
                .level(path.size())
                .path(path)
                .order(order)
                .build();

        for (Element child : directChildElements(div)) {
            String tag = child.getTagName();
            if ("head".equals(tag)) {
                continue; // 已处理
            }
            if ("div".equals(tag)) {
                // 只有真正嵌套的 div 才成为子 section，平铺 sibling 不强猜层级
                section.getChildren().add(parseSection(child, path, section.getChildren().size()));
                continue;
            }
            addBlockToSection(section, child, section.getBlocks().size());
        }
        return section;
    }

    private void addBlockToSection(Section section, Element element, int order) {
        switch (element.getTagName()) {
            case "p" -> section.getBlocks().add(parseParagraph(element, order));
            case "figure" -> section.getBlocks().add(parseFigure(element, order));
            case "formula" -> section.getBlocks().add(parseFormula(element, order));
            case "list" -> section.getBlocks().add(parseList(element, order));
            case "table" -> section.getBlocks().add(parseTableElement(element, order));
            default -> log.debug("忽略未知 body 元素: tag={}", element.getTagName());
        }
    }

    // ==================== Paragraph + mixed content ====================

    private ParagraphBlock parseParagraph(Element p, int order) {
        StringBuilder text = new StringBuilder();
        List<CitationMarker> citations = new ArrayList<>();

        NodeList children = p.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                text.append(node.getTextContent());
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if ("ref".equals(child.getTagName()) && "bibr".equals(child.getAttribute("type"))) {
                    // 仅 bibr 生成 CitationMarker；可见文本同时保留
                    String refText = normalizeWhitespace(child.getTextContent());
                    text.append(refText);
                    List<String> targets = normalizeTargetIds(child.getAttribute("target"));
                    if (!targets.isEmpty()) {
                        citations.add(CitationMarker.builder()
                                .text(refText)
                                .targetReferenceIds(targets)
                                .build());
                    }
                } else {
                    // 其他元素（inline 样式、figure/table/formula/url ref）只保留可见文本，不映射为 citation
                    text.append(parseVisibleMixedText(child));
                }
            }
        }
        return ParagraphBlock.builder()
                .id(getXmlId(p))
                .text(normalizeWhitespace(text.toString()))
                .order(order)
                .pageRange(parseCoordsPageRange(p))
                .citations(citations)
                .build();
    }

    /**
     * 递归收集元素的可见文本（跳过 note 脚注），轻度 whitespace normalize。
     */
    private String parseVisibleMixedText(Element element) {
        StringBuilder sb = new StringBuilder();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                sb.append(node.getTextContent());
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if ("note".equals(child.getTagName())) {
                    continue; // 脚注不进入正文
                }
                sb.append(parseVisibleMixedText(child));
            }
        }
        return sb.toString();
    }

    // ==================== Figure / Table / Formula / List ====================

    private DocumentBlock parseFigure(Element figure, int order) {
        // <figure type="table"> 必须映射为 TableBlock，不是 FigureBlock
        if ("table".equals(figure.getAttribute("type"))) {
            return parseTableElement(figure, order);
        }
        String caption = textOrNull(firstChild(figure, "figDesc"));
        if (caption == null) {
            caption = textOrNull(firstChild(figure, "head"));
        }
        return FigureBlock.builder()
                .id(getXmlId(figure))
                .label(textOrNull(firstChild(figure, "label")))
                .caption(caption)
                .order(order)
                .pageRange(parseCoordsPageRange(figure))
                .build();
    }

    private TableBlock parseTableElement(Element figure, int order) {
        String caption = textOrNull(firstChild(figure, "figDesc"));
        if (caption == null) {
            caption = textOrNull(firstChild(figure, "head"));
        }
        return TableBlock.builder()
                .id(getXmlId(figure))
                .label(textOrNull(firstChild(figure, "label")))
                .caption(caption)
                .content(parseTableContent(figure))
                .order(order)
                .pageRange(parseCoordsPageRange(figure))
                .build();
    }

    /**
     * 表格文本表达：row 之间 \n、cell 之间 \t，保持顺序，不把 cell 黏成一团。
     */
    private String parseTableContent(Element container) {
        Element table = null;
        for (Element child : directChildElements(container)) {
            if ("table".equals(child.getTagName())) {
                table = child;
                break;
            }
        }
        if (table == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Element row : directChildElements(table)) {
            if (!"row".equals(row.getTagName())) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (Element cell : directChildElements(row)) {
                if ("cell".equals(cell.getTagName())) {
                    cells.add(normalizeWhitespace(cell.getTextContent()));
                }
            }
            if (!cells.isEmpty()) {
                sb.append(String.join("\t", cells)).append('\n');
            }
        }
        return sb.toString().isEmpty() ? null : sb.toString().trim();
    }

    private FormulaBlock parseFormula(Element formula, int order) {
        // 公式正文 = 除 label 外的可见文本，避免 label 与 formula 重复保存编号
        StringBuilder body = new StringBuilder();
        NodeList children = formula.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                body.append(node.getTextContent());
            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) node;
                if ("label".equals(child.getTagName())) {
                    continue;
                }
                body.append(parseVisibleMixedText(child));
            }
        }
        return FormulaBlock.builder()
                .id(getXmlId(formula))
                .label(textOrNull(firstChild(formula, "label")))
                .formula(normalizeWhitespace(body.toString()))
                .order(order)
                .pageRange(parseCoordsPageRange(formula))
                .build();
    }

    private ListBlock parseList(Element list, int order) {
        List<String> items = new ArrayList<>();
        for (Element item : directChildElements(list)) {
            if ("item".equals(item.getTagName())) {
                String text = normalizeWhitespace(item.getTextContent());
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
        }
        return ListBlock.builder()
                .id(getXmlId(list))
                .items(items)
                .order(order)
                .pageRange(parseCoordsPageRange(list))
                .build();
    }

    // ==================== References ====================

    private List<Reference> parseReferences(Document tei) {
        List<Reference> references = new ArrayList<>();
        // 只从 text/back 内解析，不全局搜索 biblStruct（header 自己也有当前论文的 biblStruct）
        NodeList biblStructNodes = evaluateNodes(tei, "/tei:TEI/tei:text/tei:back//tei:biblStruct");
        for (int i = 0; i < biblStructNodes.getLength(); i++) {
            Element biblStruct = (Element) biblStructNodes.item(i);
            references.add(parseReference(biblStruct));
        }
        return references;
    }

    private Reference parseReference(Element biblStruct) {
        String rawText = null;
        Element rawNote = evaluateElement(biblStruct, "tei:note[@type='raw_reference']");
        if (rawNote != null) {
            rawText = normalizeWhitespace(rawNote.getTextContent());
        }
        if (rawText == null || rawText.isBlank()) {
            // includeRawCitations=false 时 TEI 无逐字 citation，用 biblStruct 规范化可见文本兜底
            rawText = normalizeWhitespace(biblStruct.getTextContent());
        }

        String title = textOrNull(evaluateElement(biblStruct, "tei:analytic/tei:title[@level='a']"));
        if (title == null) {
            // fallback 到"合适的" monogr title：排除期刊 title（level='j'），避免把期刊名当论文名
            title = textOrNull(evaluateElement(biblStruct, "tei:monogr/tei:title[not(@level='j')]"));
        }

        List<String> authors = new ArrayList<>();
        NodeList authorNodes = evaluateNodes(biblStruct, "tei:analytic/tei:author");
        for (int i = 0; i < authorNodes.getLength(); i++) {
            String name = parseReferenceAuthorName((Element) authorNodes.item(i));
            if (name != null) {
                authors.add(name);
            }
        }

        String journal = textOrNull(evaluateElement(biblStruct, "tei:monogr/tei:title[@level='j']"));
        PartialDate publicationDate = parsePartialDate(
                evaluateElement(biblStruct, "tei:monogr/tei:imprint/tei:date"));
        String volume = textOrNull(evaluateElement(biblStruct,
                "tei:monogr/tei:imprint/tei:biblScope[@unit='volume']"));
        String issue = textOrNull(evaluateElement(biblStruct,
                "tei:monogr/tei:imprint/tei:biblScope[@unit='issue']"));
        String pages = textOrNull(evaluateElement(biblStruct,
                "tei:monogr/tei:imprint/tei:biblScope[@unit='page']"));

        String doi = textOrNull(evaluateElement(biblStruct, ".//tei:idno[@type='DOI']"));
        String pmid = textOrNull(evaluateElement(biblStruct, ".//tei:idno[@type='PMID']"));
        String pmcId = textOrNull(evaluateElement(biblStruct, ".//tei:idno[@type='PMCID']"));
        String arxivId = textOrNull(evaluateElement(biblStruct, ".//tei:idno[@type='arXiv']"));
        Element ptrEl = evaluateElement(biblStruct, ".//tei:ptr[@target]");
        String url = ptrEl != null ? ptrEl.getAttribute("target") : null;

        return Reference.builder()
                .id(getXmlId(biblStruct))
                .rawText(rawText)
                .title(title)
                .authors(authors)
                .journal(journal)
                .publicationDate(publicationDate)
                .volume(volume)
                .issue(issue)
                .pages(pages)
                .doi(doi)
                .pmid(pmid)
                .pmcId(pmcId)
                .arxivId(arxivId)
                .url(url)
                .build();
    }

    private String parseReferenceAuthorName(Element author) {
        Element persName = firstChild(author, "persName");
        if (persName == null) {
            return normalizeWhitespace(author.getTextContent());
        }
        return buildFullName(persName);
    }

    // ==================== ParserInfo ====================

    private ParserInfo parseParserInfo(Document tei) {
        Element application = evaluateElement(tei,
                "/tei:TEI/tei:teiHeader/tei:encodingDesc/tei:appInfo/tei:application");
        if (application == null) {
            return null;
        }
        Instant parsedAt = null;
        String when = application.getAttribute("when");
        if (when != null && !when.isBlank()) {
            try {
                parsedAt = Instant.parse(when);
            } catch (Exception e) {
                log.debug("无法解析 application@when 为 Instant: {}", when);
            }
        }
        return ParserInfo.builder()
                .parserType(application.getAttribute("ident"))
                .parserVersion(application.getAttribute("version"))
                .structureVersion(GrobidTeiConstants.STRUCTURE_VERSION)
                .parsedAt(parsedAt)
                .build();
    }

    // ==================== 通用 helpers ====================

    private Element evaluateElement(Object context, String xpathExpr) {
        if (context == null) {
            return null;
        }
        try {
            XPath xpath = newXPath();
            Node node = (Node) xpath.evaluate(xpathExpr, context, XPathConstants.NODE);
            return node instanceof Element ? (Element) node : null;
        } catch (XPathExpressionException e) {
            throw new GrobidDocumentParseException("XPath 求值失败: " + xpathExpr, e);
        }
    }

    private NodeList evaluateNodes(Object context, String xpathExpr) {
        if (context == null) {
            return EMPTY_NODE_LIST;
        }
        try {
            XPath xpath = newXPath();
            return (NodeList) xpath.evaluate(xpathExpr, context, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new GrobidDocumentParseException("XPath 求值失败: " + xpathExpr, e);
        }
    }

    private static final NodeList EMPTY_NODE_LIST = new NodeList() {
        @Override
        public Node item(int index) {
            return null;
        }

        @Override
        public int getLength() {
            return 0;
        }
    };

    private XPath newXPath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new TeiNamespaceContext());
        return xpath;
    }

    private List<Element> directChildElements(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private Element firstChild(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        for (Element child : directChildElements(parent)) {
            if (tagName.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private String textOrNull(Element element) {
        if (element == null) {
            return null;
        }
        String text = normalizeWhitespace(element.getTextContent());
        return text.isBlank() ? null : text;
    }

    private String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String getXmlId(Element element) {
        if (element == null) {
            return null;
        }
        String id = element.getAttributeNS(XMLConstants.XML_NS_URI, "id");
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * 规范化 citation target：#b8 → b8；支持多个 target（空格分隔）。
     */
    private List<String> normalizeTargetIds(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        return Arrays.stream(target.trim().split("\\s+"))
                .map(id -> id.startsWith("#") ? id.substring(1) : id)
                .filter(id -> !id.isBlank())
                .toList();
    }

    /**
     * 从 coords 属性解析页码范围（GROBID 格式：page,x,y,w,h，多组用 ; 分隔）。
     * 无 coords → null，不伪造页码。多组时 startPage=min(page)、endPage=max(page)。
     */
    private PageRange parseCoordsPageRange(Element element) {
        if (element == null) {
            return null;
        }
        String coords = element.getAttribute("coords");
        if (coords == null || coords.isBlank()) {
            return null;
        }
        List<Integer> pages = new ArrayList<>();
        for (String group : coords.split(";")) {
            String[] parts = group.trim().split("[,\\s]+");
            if (parts.length > 0 && parts[0].matches("\\d+")) {
                pages.add(Integer.parseInt(parts[0]));
            }
        }
        if (pages.isEmpty()) {
            return null;
        }
        return PageRange.builder()
                .startPage(Collections.min(pages))
                .endPage(Collections.max(pages))
                .build();
    }
}
