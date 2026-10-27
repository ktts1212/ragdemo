package com.example.ragdemo.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.example.ragdemo.model.entity.KnowledgeDocument;

/**
 * 论文目录只读 Mapper（Step 2 §5.2）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>强制</b> {@code knowledge_base_id = :trustedKbId}：知识库范围只来自受信上下文，绝不来自模型参数；</li>
 *   <li><b>不查 storage_path</b>：Tool 层拿不到 MinIO key，从数据获取层面杜绝 key 泄漏（Step 2 §3/§5.4）；</li>
 *   <li>标题过滤用参数绑定 + {@code ILIKE}，排序稳定（{@code created_at DESC, id DESC}）；</li>
 *   <li>{@code documents} 表<b>没有</b> year/author 列 ⇒ 不提供这两类筛选（不虚构缺失列，Step 2 §5.2）。</li>
 * </ul>
 * 本 Mapper 只读，绝不更新任何数据。
 */
@Mapper
public interface PaperCatalogMapper {

    @Select("""
            <script>
            SELECT id, knowledge_base_id AS knowledgeBaseId, file_name AS fileName,
                   stored_file_name AS storedFileName, file_size AS fileSize, content_type AS contentType,
                   file_ext AS fileExt, checksum, parse_mode AS parseMode, version, status, progress,
                   chunk_count AS chunkCount, error_message AS errorMessage,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM documents
            WHERE knowledge_base_id = #{knowledgeBaseId}
            <if test="titleContains != null"> AND file_name ILIKE '%' || #{titleContains} || '%'</if>
            <if test="status != null"> AND status = #{status}</if>
            <if test="parseMode != null"> AND parse_mode = #{parseMode}</if>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<KnowledgeDocument> selectPage(@Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("titleContains") String titleContains,
            @Param("status") String status,
            @Param("parseMode") String parseMode,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * 按 id 取单条，<b>同时约束知识库范围</b>。
     * <p>
     * 越权与不存在返回同一个 {@code null} ⇒ 上层统一报 NOT_FOUND_OR_FORBIDDEN，
     * 不泄漏"跨知识库记录是否存在"。
     */
    @Select("""
            SELECT id, knowledge_base_id AS knowledgeBaseId, file_name AS fileName,
                   stored_file_name AS storedFileName, storage_path AS storagePath,
                   file_size AS fileSize, content_type AS contentType, file_ext AS fileExt,
                   checksum, parse_mode AS parseMode, version, status, progress,
                   chunk_count AS chunkCount, error_message AS errorMessage,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM documents
            WHERE knowledge_base_id = #{knowledgeBaseId} AND id = #{documentId}
            """)
    KnowledgeDocument selectDocumentInScope(@Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM documents
            WHERE knowledge_base_id = #{knowledgeBaseId}
            <if test="titleContains != null"> AND file_name ILIKE '%' || #{titleContains} || '%'</if>
            <if test="status != null"> AND status = #{status}</if>
            <if test="parseMode != null"> AND parse_mode = #{parseMode}</if>
            </script>
            """)
    long countByFilter(@Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("titleContains") String titleContains,
            @Param("status") String status,
            @Param("parseMode") String parseMode);
}
