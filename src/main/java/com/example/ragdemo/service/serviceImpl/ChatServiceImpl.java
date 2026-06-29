package com.example.ragdemo.service.serviceImpl;

import com.example.ragdemo.mapper.DocumentChunkMapper;
import com.example.ragdemo.model.dto.ChatRequest;
import com.example.ragdemo.model.dto.ChatResponse;
import com.example.ragdemo.model.dto.RetrievedChunkDto;
import com.example.ragdemo.model.dto.SourceReference;
import com.example.ragdemo.service.ChatService;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RAG 智能问答服务实现（V1）。
 * <p>
 * 完整链路：参数校验 → 问题向量化 → pgvector 检索 → minScore 过滤
 * → 上下文拼接 → 严格 Prompt → LLM 生成 → 返回答案 + 来源引用。
 * <p>
 * 依赖：
 * <ul>
 *   <li>{@link EmbeddingModel}       — 问题向量化（替代手写 OpenAiClient.embed）</li>
 *   <li>{@link ChatLanguageModel}    — 根据 Prompt 生成答案（替代手写 OpenAiClient.chat）</li>
 *   <li>{@link DocumentChunkMapper}  — pgvector 相似度检索</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    // ==================== 默认值常量 ====================
    private static final Long DEFAULT_KNOWLEDGE_BASE_ID = 1L;
    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_MIN_SCORE = 0.60;

    // ==================== 依赖注入 ====================
    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private final DocumentChunkMapper documentChunkMapper;

    /**
     * Prompt 模板，从 resources/prompt/system-prompt.txt 加载。
     * 包含 {context} 和 {question} 占位符，运行时由 buildPrompt() 替换。
     */
    private String promptTemplate;

    @Value("classpath:prompt/system-prompt.txt")
    private Resource promptResource;

    public ChatServiceImpl(EmbeddingModel embeddingModel,
                           ChatLanguageModel chatLanguageModel,
                           DocumentChunkMapper documentChunkMapper) {
        this.embeddingModel = embeddingModel;
        this.chatLanguageModel = chatLanguageModel;
        this.documentChunkMapper = documentChunkMapper;
    }

    @PostConstruct
    private void loadPromptTemplate() throws IOException {
        this.promptTemplate = promptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    // ==================== 主流程 ====================

    @Override
    public ChatResponse chat(ChatRequest request) {
        validateRequest(request);                    // 1. 参数校验

        // TODO 2. 提取并填充默认值                    — kbId/topK/minScore 为 null 时用默认值
        Long knowledgeBaseId = request.getKnowledgeBaseId() != null
                ? request.getKnowledgeBaseId() : DEFAULT_KNOWLEDGE_BASE_ID;
        int topK = request.getTopK() != null ? request.getTopK() : DEFAULT_TOP_K;
        double minScore = request.getMinScore() != null ? request.getMinScore() : DEFAULT_MIN_SCORE;

        // TODO 3. embeddingModel.embed(question)     — 问题向量化
        float[] questionVector = embeddingModel.embed(request.getQuestion())
                .content().vector();

        // TODO 4. toPgVectorString(vector)           — float[] → "[0.12, -0.34, ...]"
        String questionEmbedding = toPgVectorString(questionVector);

        // TODO 5. documentChunkMapper.searchSimilarChunks() — pgvector 检索 topK
        List<RetrievedChunkDto> retrievedChunks = documentChunkMapper
                .searchSimilarChunks(knowledgeBaseId, questionEmbedding, topK);

        // TODO 6. filter(score >= minScore)          — 过滤低相似度 chunk
        List<RetrievedChunkDto> validChunks = retrievedChunks.stream()
                .filter(chunk -> chunk.getScore() != null && chunk.getScore() >= minScore)
                .toList();

        // TODO 7. isEmpty? → return "无法确定"        — 无有效资料时拒绝编造
        if (validChunks.isEmpty()) {
            return new ChatResponse("根据当前知识库资料无法确定。", List.of());
        }

        // TODO 8. buildContext(chunks)               — 拼接【资料1】【资料2】...
        String context = buildContext(validChunks);

        // TODO 9. buildPrompt(question, context)     — 严格 Prompt 模板
        String prompt = buildPrompt(request.getQuestion(), context);

        // TODO 10. chatLanguageModel.chat(prompt)    — 调用 LLM 生成答案
        String answer = chatLanguageModel.chat(prompt);

        // TODO 11. 组装 SourceReference 列表
        List<SourceReference> sources = validChunks.stream()
                .map(chunk -> new SourceReference(
                        chunk.getDocumentName(),
                        chunk.getChunkIndex(),
                        chunk.getScore(),
                        truncatePreview(chunk.getContent(), 160),
                        chunk.getContent(),
                        chunk.getPage()))
                .toList();

        // TODO 12. return ChatResponse(answer, sources)
        return new ChatResponse(answer, sources);
    }

    // ==================== 私有方法（待实现） ====================

    /**
     * 校验请求参数。
     * <ul>
     *   <li>request 不能为 null</li>
     *   <li>question 不能为空</li>
     *   <li>topK 必须 > 0</li>
     *   <li>minScore 必须在 [0, 1] 区间</li>
     * </ul>
     */
    private void validateRequest(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        if (request.getTopK() != null && request.getTopK() <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }
        if (request.getMinScore() != null
                && (request.getMinScore() < 0 || request.getMinScore() > 1)) {
            throw new IllegalArgumentException("minScore 必须在 0 到 1 之间");
        }
    }

    /**
     * 将 float[] 向量转为 pgvector 可识别的字符串格式。
     * <p>
     * 示例：float[]{0.12, -0.34} → "[0.12, -0.34]"
     */
    private String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 将检索到的 chunk 拼接成上下文文本。
     * <p>
     * 格式：
     * <pre>
     * 【资料1】
     * 文档名称：xxx.pdf
     * 页码：3
     * 相似度分数：0.86
     * 内容：
     * ...chunk 原文...
     * </pre>
     */
    private String buildContext(List<RetrievedChunkDto> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunkDto chunk = chunks.get(i);
            sb.append("【资料").append(i + 1).append("】\n");
            sb.append("文档名称：").append(chunk.getDocumentName()).append("\n");
            if (chunk.getPage() != null) {
                sb.append("页码：").append(chunk.getPage()).append("\n");
            }
            sb.append("相似度分数：").append(chunk.getScore()).append("\n");
            sb.append("内容：\n");
            sb.append(chunk.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构造严格 Prompt 模板。
     * <p>
     * 规则：
     * <ol>
     *   <li>只能根据【知识库资料】回答</li>
     *   <li>不使用资料之外的知识补充</li>
     *   <li>资料不足时回答"根据当前知识库资料无法确定"</li>
     *   <li>不编造文献、页码、数据、结论</li>
     *   <li>回答中标注依据来源（如"根据资料1和资料2"）</li>
     *   <li>资料冲突时明确说明</li>
     *   <li>使用中文，表达清晰简洁</li>
     * </ol>
     *
     * @param question 用户原始问题
     * @param context  buildContext() 拼接好的上下文
     * @return 完整的 Prompt 字符串
     */
    private String buildPrompt(String question, String context) {
        return promptTemplate
                .replace("{context}", context)
                .replace("{question}", question);
    }

    /**
     * 截取预览文本，用于 SourceReference.preview。
     */
    private String truncatePreview(String content, int maxLen) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLen ? content : content.substring(0, maxLen) + "...";
    }
}
