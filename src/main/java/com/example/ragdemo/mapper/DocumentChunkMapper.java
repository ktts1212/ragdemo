package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.dto.RetrievedChunkDto;
import com.example.ragdemo.model.entity.DocumentChunk;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 插入单条 chunk + embedding。
     * <p>
     * 学术链路字段（chunk_key / chunk_type / section_* / embedding_text / content_hash / end_page）
     * 对旧通用链路为 NULL，因此同一条 SQL 可服务两条链路（skill §6 混合方案）。
     */
    @Insert("""
            INSERT INTO document_chunks (
                document_id, knowledge_base_id, chunk_index, content, page_number, token_count,
                start_offset, end_offset, metadata, embedding,
                chunk_key, chunk_type, section_id, section_title, section_path, section_level,
                embedding_text, content_hash, end_page
            ) VALUES (
                #{documentId}, #{knowledgeBaseId}, #{chunkIndex}, #{content}, #{pageNumber}, #{tokenCount},
                #{startOffset}, #{endOffset}, CAST(#{metadata} AS jsonb), CAST(#{embedding} AS vector),
                #{chunkKey}, #{chunkType}, #{sectionId}, #{sectionTitle}, CAST(#{sectionPath} AS text[]),
                #{sectionLevel}, #{embeddingText}, #{contentHash}, #{endPage}
            )
            """)
    int insertChunkWithEmbedding(DocumentChunkInsertParam param);

    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * pgvector 向量相似度检索。
     * <p>
     * 使用余弦距离 <=> 算子对问题向量与所有 chunk embedding 做相似度计算，
     * 返回最相关的 topK 条记录（按相似度降序）。
     * <p>
     * SQL 定义在 resources/mapper/DocumentChunkMapper.xml
     *
     * @param knowledgeBaseId   知识库 ID，限定检索范围
     * @param questionEmbedding 问题向量，pgvector 字符串格式 "[0.12, -0.34, ...]"
     * @param topK              返回数量
     * @return 相似度从高到低的 chunk 列表，score 字段为余弦相似度 (0~1)
     */
    List<RetrievedChunkDto> searchSimilarChunks(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("questionEmbedding") String questionEmbedding,
            @Param("topK") Integer topK
    );
}
