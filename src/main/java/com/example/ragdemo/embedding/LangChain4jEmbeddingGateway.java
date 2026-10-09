package com.example.ragdemo.embedding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link EmbeddingGateway} 的 LangChain4j 实现（skill §4.2 / §13）。
 * <p>
 * 复用 Spring 容器里既有的 {@link EmbeddingModel} bean（见 {@code EmbeddingConfig}），
 * 因此与 {@code ChatServiceImpl}（查询侧）<b>共享同一份 baseUrl/model/dimension 配置</b>。
 * <p>
 * 批处理（skill §13）：按 {@code rag.embedding.batch-size} 切批，逐批调用；
 * 仅对<b>可重试</b>错误（超时 / 5xx / 网络）做指数退避有限重试；
 * 维度不一致、4xx 参数错误<b>不</b>重试（盲目重试只会浪费时间）。
 * 任一批最终失败 → 抛出，调用方必须让整篇 ingestion 不得标记 indexed。
 */
@Slf4j
@Component
public class LangChain4jEmbeddingGateway implements EmbeddingGateway {

    /** 退避基数（毫秒）：attempt 1 → 200ms，attempt 2 → 400ms … */
    private static final long BACKOFF_BASE_MS = 200L;

    private final EmbeddingModel embeddingModel;
    private final RagProperties ragProperties;

    public LangChain4jEmbeddingGateway(EmbeddingModel embeddingModel, RagProperties ragProperties) {
        this.embeddingModel = embeddingModel;
        this.ragProperties = ragProperties;
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embedding 输入不能为空");
        }
        return embeddingModel.embed(text).content().vector();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(1, ragProperties.getEmbedding().getBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            vectors.addAll(embedBatch(texts.subList(start, end)));
        }
        log.debug("embedding 完成: 文本数={}, batchSize={}", texts.size(), batchSize);
        return vectors;
    }

    /** 单批 embedding + 有限重试；返回顺序与入参一致。 */
    private List<float[]> embedBatch(List<String> batch) {
        int maxAttempts = Math.max(1, ragProperties.getEmbedding().getMaxRetries() + 1);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<TextSegment> segments = batch.stream().map(TextSegment::from).toList();
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
                if (embeddings == null || embeddings.size() != batch.size()) {
                    // 数量不一致是契约错误，不重试
                    throw new IllegalStateException("embedding 返回数量不一致: 期望 "
                            + batch.size() + " 实际 " + (embeddings == null ? 0 : embeddings.size()));
                }
                List<float[]> vectors = new ArrayList<>(embeddings.size());
                for (Embedding embedding : embeddings) {
                    vectors.add(embedding.vector());
                }
                return vectors;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (!isRetryable(e) || attempt == maxAttempts) {
                    break;
                }
                long backoff = BACKOFF_BASE_MS * (1L << (attempt - 1));
                log.warn("embedding 批次失败，{}/{} 次尝试，{}ms 后重试: {}",
                        attempt, maxAttempts, backoff, e.toString());
                sleep(backoff);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("embedding 批次失败（无异常信息）")
                : lastFailure;
    }

    /** 只重试超时 / 5xx / 网络类；维度不一致与 4xx 参数错误不重试。 */
    private boolean isRetryable(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (message.contains("dimension") || message.contains("维度")) {
            return false;
        }
        for (String clientError : List.of("400", "401", "403", "404", "422", "参数")) {
            if (message.contains(clientError)) {
                return false;
            }
        }
        return true;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 重试等待被中断", interrupted);
        }
    }
}
