package com.example.ragdemo.document.parser.marker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.example.ragdemo.document.domain.Section;
import com.example.ragdemo.document.image.FigureImageStorage;

import lombok.Getter;
import lombok.Setter;

/**
 * 单次 Marker 解析的上下文（private ParseContext）。
 * <p>
 * skill §35：解析器为 Spring singleton，所有单次解析状态必须放局部/上下文对象，
 * 不得存为实例字段（否则并发解析会互相污染）。
 */
@Getter
public class MarkerParseContext {

    private final String documentId;
    private final FigureImageStorage imageStorage;
    private final MarkerPageTracker pageTracker = new MarkerPageTracker();

    /** 全局块顺序计数器（跨 section 单调递增，保留 XML/文档顺序） */
    private int orderCounter = 0;

    /** 当前 Section（Section skeleton 的归属） */
    @Setter
    private Section currentSection;

    /** 顶层 Section 列表 */
    private final List<Section> sections = new ArrayList<>();

    /** 解析期 warnings（未知 block 等），不中断解析 */
    private final List<String> warnings = new ArrayList<>();

    /**
     * Section 栈（index 0 = 最外层），用于按 section_hierarchy 深度构建父子嵌套。
     */
    private final Deque<Section> sectionStack = new ArrayDeque<>();

    public MarkerParseContext(String documentId, FigureImageStorage imageStorage) {
        this.documentId = documentId;
        this.imageStorage = imageStorage;
    }

    public int nextOrder() {
        return orderCounter++;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }
}
