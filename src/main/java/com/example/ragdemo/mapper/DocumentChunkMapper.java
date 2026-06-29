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

    @Insert("INSERT INTO document_chunks (document_id, knowledge_base_id, chunk_index, content, page_number, token_count, start_offset, end_offset, metadata, embedding) VALUES (#{documentId}, #{knowledgeBaseId}, #{chunkIndex}, #{content}, #{pageNumber}, #{tokenCount}, #{startOffset}, #{endOffset}, CAST(#{metadata} AS jsonb), CAST(#{embedding} AS vector))")
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
