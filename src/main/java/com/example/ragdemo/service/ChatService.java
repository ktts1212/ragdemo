package com.example.ragdemo.service;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.model.dto.ChatResponse;
import com.example.ragdemo.model.dto.SourceReference;
import com.example.ragdemo.model.entity.DocumentChunk;
import com.example.ragdemo.repository.DocumentChunkRepository;
import com.example.ragdemo.util.VectorUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {

    private final OpenAiClient openAiClient;
    private final DocumentChunkRepository documentChunkRepository;
    private final RagProperties ragProperties;

    public ChatService(OpenAiClient openAiClient,
                       DocumentChunkRepository documentChunkRepository,
                       RagProperties ragProperties) {
        this.openAiClient = openAiClient;
        this.documentChunkRepository = documentChunkRepository;
        this.ragProperties = ragProperties;
    }

    public ChatResponse chat(String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        int limit = topK == null ? ragProperties.getRetrieval().getTopK() : topK;
        if (limit <= 0 || limit > 20) {
            throw new IllegalArgumentException("topK 必须在 1 到 20 之间");
        }

        // 1. 先把用户问题向量化。
        float[] queryEmbedding = openAiClient.embed(question);

        // 2. 使用问题向量去 pgvector 检索最相似的文档片段。
        List<DocumentChunk> chunks = documentChunkRepository.searchSimilar(queryEmbedding, limit);

        if (chunks.isEmpty()) {
            return new ChatResponse("当前知识库没有可用于回答的文档，请先上传 txt 或 md 文档。", List.of());
        }

        double minScore = 0.5;

        double bestScore = chunks.get(0).getScore();
        if (bestScore < minScore) {
            return new ChatResponse("根据已上传文档无法确定。", chunks.stream()
                    .map(chunk -> new SourceReference(
                            chunk.getDocName(),
                            chunk.getChunkIndex(),
                            chunk.getScore(),
                            VectorUtils.preview(chunk.getContent(), 160)))
                    .toList());
        }

        // 3. 把检索到的 chunk 拼进 prompt，再交给大模型回答。
        String prompt = buildPrompt(question, chunks);
        String answer = openAiClient.chat(prompt);

        List<SourceReference> sources = chunks.stream()
                .map(chunk -> new SourceReference(
                        chunk.getDocName(),
                        chunk.getChunkIndex(),
                        chunk.getScore(),
                        VectorUtils.preview(chunk.getContent(), 160)))
                .toList();

        return new ChatResponse(answer, sources);
    }

    private String buildPrompt(String question, List<DocumentChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            context.append("[来源: ")
                    .append(chunk.getDocName())
                    .append("#")
                    .append(chunk.getChunkIndex())
                    .append("]\n")
                    .append(chunk.getContent())
                    .append("\n\n");
        }

        return """
                请根据下面的上下文回答问题。
                回答要求：
                1. 优先使用上下文中的信息。
                2. 如果上下文没有答案，请回答“根据已上传文档无法确定”。
                3. 回答末尾用“引用：doc_name#chunk_index”列出依据。

                上下文：
                %s

                问题：
                %s
                """.formatted(context, question);
    }
}
