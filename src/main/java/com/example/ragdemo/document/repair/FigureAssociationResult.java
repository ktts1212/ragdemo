package com.example.ragdemo.document.repair;

/**
 * Figure-associated text 检测结果（skill §19）。
 *
 * @param associated    是否判定为图相关说明文字
 * @param matchedPhrase 命中的明确短语（未命中时为 null）
 */
public record FigureAssociationResult(
        boolean associated,
        String matchedPhrase
) {

    public static FigureAssociationResult none() {
        return new FigureAssociationResult(false, null);
    }
}
