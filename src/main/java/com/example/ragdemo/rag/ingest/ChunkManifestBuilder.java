package com.example.ragdemo.rag.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.ragdemo.document.chunk.AcademicChunk;

/**
 * {@code chunks.json} manifest 的唯一构建器。
 * <p>
 * Step 4 从 {@code AcademicIngestionService} 提取出来的<b>最小接口</b>：
 * 旧链路与新入库图共用同一份实现，保证 manifest 字段与顺序<b>逐字一致</b>（直接支撑行为等价）。
 * 放在 {@code rag.ingest} 而非 workflow 包下，是为保持依赖方向正确
 * （workflow → 业务服务，而不是业务服务 → workflow）。
 * <p>
 * manifest 只记关键字段，<b>不含</b> {@code content} / {@code embeddingText} ——
 * 因此它<b>不能</b>当作可单独恢复分块内容的完整快照；节点恢复时必须从 canonical.json 重建
 * （step4 §1 关键事实 / §6.2）。
 */
@Component
public class ChunkManifestBuilder {

    public List<Map<String, Object>> build(List<AcademicChunk> chunks) {
        List<Map<String, Object>> manifest = new ArrayList<>(chunks.size());
        for (AcademicChunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", chunk.getChunkId());
            item.put("chunkType", chunk.getChunkType());
            item.put("order", chunk.getOrder());
            item.put("tokenCount", chunk.getTokenCount());
            item.put("sectionPath", chunk.getSectionPath());
            item.put("startPage", chunk.getStartPage());
            item.put("endPage", chunk.getEndPage());
            item.put("sourceBlockIds", chunk.getSourceBlockIds());
            item.put("contentHash", chunk.getContentHash());
            manifest.add(item);
        }
        return manifest;
    }
}
