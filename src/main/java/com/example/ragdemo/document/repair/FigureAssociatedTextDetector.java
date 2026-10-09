package com.example.ragdemo.document.repair;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Figure-associated text 检测器（skill §19）。
 * <p>
 * 用于识别 Figure 周边的说明性文字（"From left to right..."、"Top row..."、"Panel A..."），
 * 这类文字**不应**被错误地并入跨页续接的正文。
 * <p>
 * 只匹配明确短语，不匹配 "From" / "Figure" 等单个关键词，避免误伤正常正文。
 */
@Component
public class FigureAssociatedTextDetector {

    private static final List<String> ASSOCIATED_PHRASES = List.of(
            // 方向性描述
            "from left to right",
            "from right to left",
            "from top to bottom",
            "from bottom to top",
            // 行/列
            "top row",
            "bottom row",
            "middle row",
            "first row",
            "second row",
            "third row",
            "left column",
            "right column",
            // 面板
            "top panel",
            "bottom panel",
            "left panel",
            "right panel",
            "middle panel",
            "upper panel",
            "lower panel",
            "top and bottom panels",
            "panel a",
            "panel b",
            "panel c",
            "panel d",
            // 分图描述
            "in the left panel",
            "in the right panel",
            "in the top panel",
            "in the bottom panel",
            "as shown in the left",
            "as shown in the right"
    );

    /**
     * 检测文本是否属于 figure-associated text。
     */
    public FigureAssociationResult detect(String text) {
        if (text == null || text.isBlank()) {
            return FigureAssociationResult.none();
        }
        String lower = text.toLowerCase();
        for (String phrase : ASSOCIATED_PHRASES) {
            if (lower.contains(phrase)) {
                return new FigureAssociationResult(true, phrase);
            }
        }
        return FigureAssociationResult.none();
    }
}
