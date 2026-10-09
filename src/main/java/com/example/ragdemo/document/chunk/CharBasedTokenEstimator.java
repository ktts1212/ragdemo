package com.example.ragdemo.document.chunk;

import org.springframework.stereotype.Component;

/**
 * 基于字符数的经验 token 估算器（v1 默认实现）。
 * <p>
 * 面向英文学术文本，采用 {@code ceil(chars / 4)}：英文平均约 4 字符 1 token，
 * 该比值对学术正文较准，对公式/数字/符号会略有低估 —— 因此宁可"偏小"，
 * 使 chunk 实际 token 更保守，后续接入真实分词器（如 bge-m3 / OpenAI）时再标定。
 * <p>
 * 刻意保持<b>确定性、零依赖、零网络</b>：单元测试与真实论文 QA 必须可重复。
 */
@Component
public class CharBasedTokenEstimator implements ChunkTokenEstimator {

    /** 英文学术文本经验比值：字符 / token */
    private static final double CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
