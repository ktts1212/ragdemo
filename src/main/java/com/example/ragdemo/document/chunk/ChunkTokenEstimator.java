package com.example.ragdemo.document.chunk;

/**
 * chunk 阶段的 token 估算抽象。
 * <p>
 * 设计意图：Chunker 的所有预算（target / max / min / overlap）都以 token 为单位，
 * 但 token 的具体口径必须可替换，便于后续接入与 embedding 模型一致的真实分词器。
 * <p>
 * 约束：实现必须<b>确定性且离线</b>（不得发起网络请求），否则单元测试不可重复。
 */
public interface ChunkTokenEstimator {

    /**
     * 估算文本 token 数。
     *
     * @param text 待估算文本，允许 null
     * @return token 数；null / 空白文本返回 0
     */
    int estimate(String text);
}
