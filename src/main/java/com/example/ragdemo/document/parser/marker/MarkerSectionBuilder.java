package com.example.ragdemo.document.parser.marker;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.domain.block.DocumentBlock;
import com.example.ragdemo.document.marker.raw.MarkerBlock;

/**
 * Section skeleton 构建器（skill §16）。
 * <p>
 * 依据 SectionHeader 顺序 + {@code section_hierarchy} 构建骨架。真实 Marker 输出中
 * {@code section_hierarchy} 形如 {@code {"1":"/page/0/SectionHeader/15","2":"/page/2/SectionHeader/6"}}，
 * 即「层级 → SectionHeader blockId」：本类把 blockId 解析回标题得到 {@code path}，
 * 并按层级数构建真实的父子嵌套（这是 Marker 明确给出的层级信息，属忠实映射而非猜测）。
 * <p>
 * 无状态：所有单次解析状态（标题索引、section 栈）都放在 {@link MarkerParseContext}。
 */
@Component
public class MarkerSectionBuilder {

    /** 罗马数字编号标题：[IVXLC]+ 后跟 "." 与标题正文 */
    private static final Pattern ROMAN_PATTERN =
            Pattern.compile("^([IVXLC]+)\\.\\s+(.*)$");

    private final MarkerTextExtractor textExtractor;

    public MarkerSectionBuilder(MarkerTextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    /**
     * 遇到 SectionHeader：开启新 Section，并按层级挂到父节点下。
     */
    public void openSection(MarkerBlock headerBlock, MarkerParseContext ctx) {
        String title = textExtractor.extractText(headerBlock.getHtml());
        String normalizedTitle = title == null || title.isBlank() ? null : title.trim();

        int depth = resolveDepth(normalizedTitle, headerBlock.getSectionHierarchy());

        // 先按深度调整 section 栈（弹出所有"同级或更深"的 section）
        Deque<Section> stack = ctx.getSectionStack();
        while (stack.size() >= depth) {
            stack.removeLast();
        }

        // path = 栈中祖先标题链 + 自身标题（与实际嵌套一致，而非 Marker 的 blockId 链）
        List<String> path = new ArrayList<>();
        for (Section ancestor : stack) {
            if (ancestor.getTitle() != null && !ancestor.getTitle().isBlank()) {
                path.add(ancestor.getTitle());
            }
        }
        if (normalizedTitle != null) {
            path.add(normalizedTitle);
        }

        Section section = Section.builder()
                .id(headerBlock.getId())
                .sourceBlockId(headerBlock.getId())
                .title(normalizedTitle)
                .level(depth)
                // 保留 Marker 原始 heading depth 作为独立佐证（不被编号推断覆盖）
                .rawLevel(hierarchyDepth(headerBlock.getSectionHierarchy()))
                .path(path)
                .order(ctx.nextOrder())
                .build();

        if (stack.isEmpty()) {
            ctx.getSections().add(section);
        } else {
            stack.peekLast().getChildren().add(section);
        }
        stack.addLast(section);
        ctx.setCurrentSection(section);
    }

    /**
     * 解析 Section 层级深度。
     * <p>
     * 优先使用标题中的<b>编号</b>（学术论文惯例，比 Marker 的 section_hierarchy 更可靠）：
     * <ul>
     *   <li>罗马数字 "I." / "III." → 一级</li>
     *   <li>字母 "A." / "B." → 二级</li>
     *   <li>字母.数字 "A.1" → 三级</li>
     *   <li>阿拉伯 "1" / "1.1" → 按点数</li>
     * </ul>
     * 编号不可识别时才回退到 section_hierarchy 的条目数。
     * <p>
     * 例：Marker 会把 "III. RESULTS" 误标为 &lt;h4&gt; 并挂到 "C. Dataset" 下；
     * 编号 "III." 明确表明它是一级章节，据此纠正。
     */
    /**
     * Marker 原始 heading depth = {@code section_hierarchy} 的条目数。
     *
     * @return 缺失时为 {@code null}（表示"无 Marker 佐证"，纠偏时不得据此推断）
     */
    private Integer hierarchyDepth(Map<String, Object> hierarchy) {
        if (hierarchy == null || hierarchy.isEmpty()) {
            return null;
        }
        return hierarchy.size();
    }

    private int resolveDepth(String title, Map<String, Object> hierarchy) {        Integer numbered = levelFromNumbering(title);
        if (numbered != null) {
            return numbered;
        }
        if (hierarchy != null && !hierarchy.isEmpty()) {
            return hierarchy.size();
        }
        return 1;
    }

    private Integer levelFromNumbering(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = title.trim();
        // 字母.数字：A.1 / B.2 → 三级
        if (t.matches("^[A-Z]\\.\\d+.*")) {
            return 3;
        }
        // 罗马数字：多字符（II./III./IV.）→ 一级；单字符（I./V./C.）仅当后续标题全大写才判为罗马数字
        // （避免把字母子节 "C. Dataset" / "D. Experimental setup" 误判为一级）
        Matcher roman = ROMAN_PATTERN.matcher(t);
        if (roman.matches()
                && (roman.group(1).length() > 1 || isAllCaps(roman.group(2)))) {
            return 1;
        }
        // 阿拉伯多级：2.1 / 3.2.1 → 点数
        if (t.matches("^\\d+(\\.\\d+)+\\s+.*")) {
            return t.split("\\s+")[0].split("\\.").length;
        }
        // 单个字母：A. / C. / D. → 二级
        if (t.matches("^[A-Z]\\.\\s+.*")) {
            return 2;
        }
        // 阿拉伯数字：1 / 1. → 一级
        if (t.matches("^\\d+\\.?\\s+.*")) {
            return 1;
        }
        return null;
    }

    private boolean isAllCaps(String text) {
        boolean hasLetter = false;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) {
                    return false;
                }
            }
        }
        return hasLetter;
    }

    /**
     * 把 block 加入当前 Section；若尚未出现 SectionHeader，则创建无标题 Section 兜底
     * （不允许正文丢失）。
     */
    public void addBlock(DocumentBlock block, MarkerParseContext ctx) {
        Section target = ctx.getCurrentSection();
        if (target == null) {
            target = Section.builder()
                    .title(null)
                    .level(1)
                    .path(new ArrayList<>())
                    .order(ctx.nextOrder())
                    .build();
            ctx.getSections().add(target);
            ctx.getSectionStack().addLast(target);
            ctx.setCurrentSection(target);
        }
        target.getBlocks().add(block);
    }
}
