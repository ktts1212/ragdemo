package com.example.ragdemo.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.example.ragdemo.agent.tools.model.SourceType;
import com.example.ragdemo.agent.tools.model.ToolResult;
import com.example.ragdemo.retrieval.DocumentRetrievalService;
import com.example.ragdemo.retrieval.model.RetrievalRequest;
import com.example.ragdemo.retrieval.model.RetrievedChunk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool 1/6：语义检索（Step 2 §5.1）。
 * <p>
 * <b>职责硬边界</b>：只调用 Step 1 的 {@link DocumentRetrievalService} 取回 chunk，
 * <b>不</b>调用任何 LLM（不做答案生成、不做 query 改写），<b>不</b>修改检索 SQL/评分语义，
 * <b>不</b>接受 knowledgeBaseId 参数（来自受信上下文）。
 */
public class SearchPaperTool extends AbstractPaperTool {

    private static final String TOOL_NAME = "SearchPaperTool";

    private final DocumentRetrievalService retrievalService;

    public SearchPaperTool(PaperToolExecutionContext context, DocumentRetrievalService retrievalService) {
        super(context);
        this.retrievalService = retrievalService;
    }

    /** 单条检索命中（sourceType 恒为 CHUNK）。 */
    public record SearchHit(Long chunkId, Long documentId, String documentName, Integer chunkIndex,
            Integer page, Double score, String contentPreview, boolean truncated, SourceType sourceType) {
    }

    /** 检索结果视图。 */
    public record SearchView(String query, int topK, int returned, boolean truncated, List<SearchHit> hits,
            List<String> warnings) {
        public SearchView {
            hits = hits == null ? List.of() : List.copyOf(hits);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @Tool("在授权知识库内做向量语义检索，返回相关片段、相似度与出处。只检索，不做总结或回答。")
    public ToolResult<SearchView> search(@P("检索查询语句") String query,
            @P("返回条数，1-10，缺省为上限") Integer topK) {
        return guarded(TOOL_NAME, () -> {
            String normalizedQuery = requireQuery(query);
            int k = resolveTopK(topK);

            List<RetrievedChunk> chunks = retrievalService
                    .retrieve(new RetrievalRequest(normalizedQuery, knowledgeBaseId(), k, null));

            int budget = limits().searchContentMaxChars();
            List<SearchHit> hits = new ArrayList<>();
            boolean truncated = false;
            for (RetrievedChunk chunk : chunks) {
                String content = chunk.content() == null ? "" : chunk.content();
                boolean itemTruncated = content.length() > budget;
                String preview = itemTruncated ? content.substring(0, Math.max(budget, 0)) : content;
                budget -= preview.length();
                truncated = truncated || itemTruncated;
                hits.add(new SearchHit(chunk.chunkId(), chunk.documentId(), chunk.documentName(),
                        chunk.chunkIndex(), chunk.page(), chunk.score(), preview, itemTruncated, SourceType.CHUNK));
                if (budget <= 0) {
                    break;
                }
            }

            List<String> warnings = new ArrayList<>();
            warnings.add("MIN_SCORE_FILTERED: 已按配置 minScore 过滤，评分语义与 /api/chat 一致");
            if (truncated) {
                warnings.add("TEXT_TRUNCATED: 命中内容达到单次字符上限 " + limits().searchContentMaxChars());
            }
            if (hits.isEmpty()) {
                warnings.add("NO_HIT: 当前知识库没有高于 minScore 的片段");
            }
            return ToolResult.ok(new SearchView(normalizedQuery, k, hits.size(), truncated, hits, warnings));
        });
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw PaperToolException.invalidArgument("query 不能为空");
        }
        String trimmed = query.trim();
        int max = limits().queryMaxChars();
        if (trimmed.length() > max) {
            throw PaperToolException.limitExceeded("query 长度", trimmed.length(), max);
        }
        return trimmed;
    }

    private int resolveTopK(Integer topK) {
        int max = limits().searchTopKMax();
        if (topK == null) {
            return max;
        }
        if (topK <= 0) {
            throw PaperToolException.invalidArgument("topK 必须为正整数");
        }
        if (topK > max) {
            throw PaperToolException.limitExceeded("topK", topK, max);
        }
        return topK;
    }
}
