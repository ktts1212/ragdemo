package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.PageRange;
import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.domain.block.ListBlock;
import com.example.ragdemo.document.domain.block.ParagraphBlock;

import lombok.extern.slf4j.Slf4j;

/**
 * Figure/Table 浮动元素归属修复（skill §6 Phase 3）。
 * <p>
 * <b>问题本质</b>：学术 PDF 中 Figure/Table 是浮动排版 —— 正文在 Section A 引用 Table 3，
 * 而表格被排版到下一页、落在 Section B 的标题之后。若按"最近 SectionHeader / 物理位置"归属就会错。
 * <p>
 * <b>做法</b>：优先利用<b>正文显式引用</b>（确定性、可解释），而不是视觉距离：
 * <ol>
 *   <li>从 {@code label}/{@code caption} 解析浮动块编号（{@code Fig. 1} / {@code Figure 4} / {@code Fig. S2} / {@code Table 3}）</li>
 *   <li>扫描 {@code PARAGRAPH}/{@code LIST} 的正文，建立 {@code TYPE:N → 引用位置} 索引</li>
 *   <li>仅当「类型+编号完全匹配」且「引用 Section ≠ 当前 Section」且「引用与浮动块距离在阈值内」时移动</li>
 * </ol>
 * 定义性引用（{@code shown in Fig. N} / {@code see Table N} …）优先；否则取浮动块之前<b>最近</b>的匹配引用。
 * 无引用 → 不动；距离超限 → 只告警；多次引用且不确定 → 不动（禁止为归零计数而强行选一个 Section）。
 * <p>
 * 只移动 block 的所属 Section，<b>不改</b> {@code caption}/{@code content}/{@code sourceBlockIds}/{@code pageRange}
 * 以及 {@code order} 本身（重排按原 {@code order} 稳定排序），因此 provenance 完全保留。
 */
@Slf4j
@Component
public class FloatBlockSectionReassigner {

    /**
     * 定义性引用措辞（出现在编号前一小段文本里即视为"强定义性引用"）。
     * 仅用于<b>优先级排序</b>，不改变匹配条件。
     */
    private static final List<String> DEFINITIONAL_MARKERS = List.of(
            "shown in", "illustrated in", "presented in", "reported in", "listed in",
            "summarized in", "summarised in", "described in", "depicted in", "displayed in",
            "available in", "given in", "see table", "see fig", "results in", "results are in",
            "is provided in", "are provided in", "can be found in");

    /** 引用与浮动块的最大页距（超过只告警不移动） */
    private static final int MAX_PAGE_DISTANCE = 3;

    /** 无页码信息时的最大 order 距离兜底 */
    private static final int MAX_ORDER_DISTANCE = 120;

    /** 判定定义性引用时回看的字符数 */
    private static final int DEFINITIONAL_LOOKBACK = 40;

    /**
     * 修复浮动块归属（原地修改 document）。
     *
     * @return 审计记录 + warnings（不含致命错误）
     */
    public Result reassign(AcademicDocument document) {
        List<Audit> audits = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (document == null || document.getSections() == null) {
            return new Result(audits, warnings);
        }

        List<Section> sections = new ArrayList<>();
        collectSections(document.getSections(), sections);

        // 1) 浮动块索引：TYPE:N → [浮动块]
        Map<String, List<FloatRef>> floatIndex = new LinkedHashMap<>();
        for (Section section : sections) {
            for (DocumentBlock block : new ArrayList<>(section.getBlocks())) {
                String key = floatKey(block);
                if (key != null) {
                    floatIndex.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new FloatRef(section, block, key));
                }
            }
        }
        if (floatIndex.isEmpty()) {
            return new Result(audits, warnings);
        }

        // 2) 正文引用索引
        Map<String, List<TextReference>> referenceIndex = new LinkedHashMap<>();
        for (Section section : sections) {
            for (DocumentBlock block : section.getBlocks()) {
                String text = paragraphText(block);
                if (text != null && !text.isBlank()) {
                    scanReferences(section, block, text, referenceIndex);
                }
            }
        }

        // 3) 高置信重挂
        int unresolved = 0;
        int skippedByDistance = 0;
        for (Map.Entry<String, List<FloatRef>> entry : floatIndex.entrySet()) {
            List<TextReference> references = referenceIndex.getOrDefault(entry.getKey(), List.of());
            for (FloatRef floatRef : entry.getValue()) {
                if (references.isEmpty()) {
                    unresolved++;
                    continue;
                }
                TextReference chosen = chooseReference(floatRef, references);
                if (chosen == null) {
                    unresolved++;
                    continue;
                }
                if (Objects.equals(chosen.sectionId(), floatRef.section().getId())) {
                    continue; // 已一致，无需移动
                }
                Integer pageDistance = pageDistance(floatRef, chosen);
                Integer orderDistance = orderDistance(floatRef, chosen);
                if (!withinDistance(pageDistance, orderDistance)) {
                    skippedByDistance++;
                    warnings.add("FLOAT_REASSIGN_SKIPPED_BY_DISTANCE: " + floatRef.key()
                            + " block=" + floatRef.block().getId()
                            + " 当前Section=" + floatRef.section().getId()
                            + " 引用Section=" + chosen.sectionId()
                            + " pageDistance=" + pageDistance + " orderDistance=" + orderDistance);
                    continue;
                }
                Section target = findSectionById(sections, chosen.sectionId());
                if (target == null || target == floatRef.section()) {
                    continue;
                }
                move(floatRef, target);
                audits.add(new Audit(floatRef.block().getId(), floatRef.key(),
                        floatRef.section().getId(), target.getId(),
                        chosen.blockId(), chosen.snippet(), pageDistance, orderDistance,
                        chosen.definitional() ? "definitional-reference" : "nearest-preceding-reference"));
            }
        }

        if (!audits.isEmpty()) {
            warnings.add("FLOAT_BLOCK_REASSIGNED: " + audits.size());
            // skill §8：所有自动修复都必须可追踪
            for (Audit audit : audits) {
                log.info("Float 归属修复: blockId={}, id={}, {} → {}, refBlock={}, pageDistance={}, "
                                + "orderDistance={}, reason={}",
                        audit.blockId(), audit.identifier(), audit.oldSectionId(), audit.newSectionId(),
                        audit.referenceBlockId(), audit.pageDistance(), audit.orderDistance(),
                        audit.reason());
            }
        }

        log.info("FloatBlockSectionReassigner 完成: floatBlocks={}, referenceKeys={}, moved={}, "
                        + "unresolved={}, skippedByDistance={}",
                floatIndex.values().stream().mapToInt(List::size).sum(), referenceIndex.size(),
                audits.size(), unresolved, skippedByDistance);

        return new Result(audits, warnings);
    }

    // ==================== 编号解析 ====================

    /** 从 label 优先、caption 兜底解析浮动块编号（实现见 {@link FloatIdentifiers}）。 */
    private String floatKey(DocumentBlock block) {
        return FloatIdentifiers.keyOf(block);
    }

    // ==================== 引用扫描 ====================

    private void scanReferences(Section section, DocumentBlock block, String text,
            Map<String, List<TextReference>> index) {
        scanOne(section, block, text, FloatIdentifiers.FIGURE_PATTERN,
                FloatIdentifiers.FIGURE_PREFIX, index);
        scanOne(section, block, text, FloatIdentifiers.TABLE_PATTERN,
                FloatIdentifiers.TABLE_PREFIX, index);
    }

    private void scanOne(Section section, DocumentBlock block, String text, Pattern pattern,
            String prefix, Map<String, List<TextReference>> index) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String key = prefix + matcher.group(1).toUpperCase(Locale.ROOT);
            int lookbackStart = Math.max(0, matcher.start() - DEFINITIONAL_LOOKBACK);
            String before = text.substring(lookbackStart, matcher.start())
                    .toLowerCase(Locale.ROOT);
            boolean definitional = DEFINITIONAL_MARKERS.stream().anyMatch(before::contains);
            String snippet = text.substring(Math.max(0, matcher.start() - 20),
                    Math.min(text.length(), matcher.end() + 40)).replaceAll("\\s+", " ");
            index.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TextReference(section.getId(), block.getId(), block.getOrder(),
                            block.getPageRange(), definitional, snippet));
        }
    }

    private String paragraphText(DocumentBlock block) {
        if (block instanceof ParagraphBlock paragraph) {
            return paragraph.getText();
        }
        if (block instanceof ListBlock list) {
            return list.getItems() == null ? null : String.join("\n", list.getItems());
        }
        return null;
    }

    // ==================== 选择与距离 ====================

    /**
     * 定义性引用优先 → 其次浮动块之前最近的引用 → 再其次紧随其后的最近引用。
     */
    private TextReference chooseReference(FloatRef floatRef, List<TextReference> references) {
        TextReference definitional = references.stream().filter(TextReference::definitional)
                .findFirst().orElse(null);
        if (definitional != null) {
            return definitional;
        }
        int floatOrder = floatRef.block().getOrder() == null ? Integer.MAX_VALUE
                : floatRef.block().getOrder();
        return references.stream()
                .filter(ref -> ref.order() != null && ref.order() <= floatOrder)
                .max(Comparator.comparingInt(TextReference::order))
                .orElseGet(() -> references.stream()
                        .filter(ref -> ref.order() != null)
                        .min(Comparator.comparingInt(TextReference::order))
                        .orElse(null));
    }

    private Integer pageDistance(FloatRef floatRef, TextReference reference) {
        Integer floatPage = startPage(floatRef.block().getPageRange());
        Integer refPage = startPage(reference.pageRange());
        if (floatPage == null || refPage == null) {
            return null;
        }
        return Math.abs(floatPage - refPage);
    }

    private Integer orderDistance(FloatRef floatRef, TextReference reference) {
        if (floatRef.block().getOrder() == null || reference.order() == null) {
            return null;
        }
        return Math.abs(floatRef.block().getOrder() - reference.order());
    }

    /** 有页距则用页距（≤3）；无页距回退 order 距（≤120）；两者都不可得 → 视为超限（保守不移动）。 */
    private boolean withinDistance(Integer pageDistance, Integer orderDistance) {
        if (pageDistance != null) {
            return pageDistance <= MAX_PAGE_DISTANCE;
        }
        if (orderDistance != null) {
            return orderDistance <= MAX_ORDER_DISTANCE;
        }
        return false;
    }

    private Integer startPage(PageRange range) {
        return range == null ? null : range.getStartPage();
    }

    // ==================== 移动 ====================

    private void move(FloatRef floatRef, Section target) {
        floatRef.section().getBlocks().remove(floatRef.block());
        target.getBlocks().add(floatRef.block());
        sortByOrder(floatRef.section());
        sortByOrder(target);
    }

    /** 按原始全局 order 稳定排序（order 为 null 的保持在最后），保证文档顺序不变。 */
    private void sortByOrder(Section section) {
        if (section.getBlocks() == null) {
            return;
        }
        section.getBlocks().sort(Comparator.comparingInt(
                block -> block.getOrder() == null ? Integer.MAX_VALUE : block.getOrder()));
    }

    private void collectSections(List<Section> sections, List<Section> accumulator) {
        if (sections == null) {
            return;
        }
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            accumulator.add(section);
            collectSections(section.getChildren(), accumulator);
        }
    }

    private Section findSectionById(List<Section> sections, String id) {
        if (id == null) {
            return null;
        }
        for (Section section : sections) {
            if (id.equals(section.getId())) {
                return section;
            }
        }
        return null;
    }

    // ==================== 内部数据 ====================

    private record FloatRef(Section section, DocumentBlock block, String key) {
    }

    private record TextReference(String sectionId, String blockId, Integer order,
            PageRange pageRange, boolean definitional, String snippet) {
    }

    /**
     * 一次重挂的审计记录（skill §8）。
     */
    public record Audit(String blockId, String identifier, String oldSectionId, String newSectionId,
            String referenceBlockId, String referenceText, Integer pageDistance,
            Integer orderDistance, String reason) {
    }

    /**
     * @param audits   全部重挂记录
     * @param warnings 跳过/超距等告警
     */
    public record Result(List<Audit> audits, List<String> warnings) {

        public int movedCount() {
            return audits == null ? 0 : audits.size();
        }
    }
}
