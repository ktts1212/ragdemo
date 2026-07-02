package com.example.ragdemo.service;

import com.example.ragdemo.model.dto.ChatRequestDto;
import com.example.ragdemo.model.dto.ChatResponseDto;

/**
 * RAG 智能问答服务接口（V1）。
 * <p>
 * 第一版核心职责：
 * <ol>
 *   <li>问题向量化（EmbeddingModel）</li>
 *   <li>pgvector 相似度检索（DocumentChunkMapper.searchSimilarChunks）</li>
 *   <li>minScore 阈值过滤低质量 chunk</li>
 *   <li>拼接检索结果构造上下文</li>
 *   <li>严格 Prompt 模板生成答案（ChatLanguageModel）</li>
 *   <li>返回答案 + 引用来源列表</li>
 * </ol>
 * <p>
 * 实现类：{@link com.example.ragdemo.service.serviceImpl.ChatServiceImpl}
 */
public interface ChatService {

    /**
     * RAG 问答主入口。
     *
     * @param request 包含 knowledgeBaseId、question、topK、minScore
     * @return 包含 answer 和 sources 的响应
     */
    ChatResponseDto chat(ChatRequestDto request);
}
