package com.example.ragdemo.document.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 参考文献条目拆分器（skill §5.6）。
 * <p>
 * 只对"疑似包含多条 reference 的单个 item"执行：检测连续编号（{@code 1. } / {@code [1] } / {@code (1) }），
 * <b>仅当</b>发现至少 2 个连续编号且每段达到最低长度时才拆分。
 * 禁止根据年份随意切分。
 */
@Component
public class ReferenceItemSplitter {

    /** 行内编号：[n] / (n) / n. / n) */
    private static final Pattern NUMBERING =
            Pattern.compile("(?:\\[(\\d{1,3})\\]|\\((\\d{1,3})\\)|(?<![\\d.])(\\d{1,3})[.)])\\s+");

    /** 每个拆分片段的最低长度 */
    private static final int MIN_PART_LENGTH = 30;

    /**
     * @return 拆分后的条目列表；无法高置信拆分时返回只含原串的单元素列表
     */
    public List<String> split(String item) {
        if (item == null || item.isBlank()) {
            return List.of();
        }
        List<MatchInfo> matches = findMatches(item);
        if (matches.size() < 2 || !hasConsecutive(matches)) {
            return List.of(item);
        }

        List<String> parts = new ArrayList<>();
        // 首个编号之前的残留文本（可能是上一条的尾部），长度够才保留
        int firstStart = matches.get(0).start();
        if (firstStart > 0) {
            String head = item.substring(0, firstStart).trim();
            if (head.length() >= MIN_PART_LENGTH) {
                parts.add(head);
            }
        }
        for (int i = 0; i < matches.size(); i++) {
            int start = matches.get(i).start();
            int end = (i + 1 < matches.size()) ? matches.get(i + 1).start() : item.length();
            String part = item.substring(start, end).trim();
            if (part.length() >= MIN_PART_LENGTH) {
                parts.add(part);
            }
        }
        return parts.size() >= 2 ? parts : List.of(item);
    }

    private List<MatchInfo> findMatches(String item) {
        List<MatchInfo> matches = new ArrayList<>();
        Matcher m = NUMBERING.matcher(item);
        while (m.find()) {
            Integer number = null;
            for (int g = 1; g <= 3; g++) {
                if (m.group(g) != null) {
                    number = Integer.parseInt(m.group(g));
                    break;
                }
            }
            if (number != null) {
                matches.add(new MatchInfo(m.start(), number));
            }
        }
        return matches;
    }

    private boolean hasConsecutive(List<MatchInfo> matches) {
        for (int i = 1; i < matches.size(); i++) {
            if (matches.get(i).number() == matches.get(i - 1).number() + 1) {
                return true;
            }
        }
        return false;
    }

    private record MatchInfo(int start, int number) {
    }
}
