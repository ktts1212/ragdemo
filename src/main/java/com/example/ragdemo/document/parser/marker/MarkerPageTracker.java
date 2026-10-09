package com.example.ragdemo.document.parser.marker;

import com.example.ragdemo.document.domain.PageRange;

/**
 * Marker 页码追踪器（每次解析创建新实例，不共享状态）。
 * <p>
 * Marker 结构 Document → Page → blocks，进入一个 Page 即页号 +1。
 * 每个 block 的 pageRange 由当前页号得出；Repair 阶段跨页合并后才会出现 start != end。
 */
public class MarkerPageTracker {

    private int currentPage = 0;

    /** 进入一个 Page block 时调用。 */
    public void enterPage() {
        currentPage++;
    }

    /** 当前页号（尚未进入任何 Page 时按第 1 页处理）。 */
    public int currentPage() {
        return currentPage == 0 ? 1 : currentPage;
    }

    /** 当前页对应的 PageRange（单页）。 */
    public PageRange currentPageRange() {
        int page = currentPage();
        return PageRange.builder()
                .startPage(page)
                .endPage(page)
                .build();
    }
}
