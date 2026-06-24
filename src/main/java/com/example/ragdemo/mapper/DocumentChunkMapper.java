package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.DocumentChunk;
import com.example.ragdemo.model.param.DocumentChunkInsertParam;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Insert("INSERT INTO document_chunks (document_id, knowledge_base_id, chunk_index, content, page_number, token_count, start_offset, end_offset, metadata, embedding) VALUES (#{documentId}, #{knowledgeBaseId}, #{chunkIndex}, #{content}, #{pageNumber}, #{tokenCount}, #{startOffset}, #{endOffset}, CAST(#{metadata} AS jsonb), CAST(#{embedding} AS vector))")
    int insertChunkWithEmbedding(DocumentChunkInsertParam param);

    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
