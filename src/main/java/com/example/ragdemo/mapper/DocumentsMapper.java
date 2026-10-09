package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.model.vo.DocumentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * documents 表 Mapper
 * <p>
 * 继承 BaseMapper 获得内置 CRUD 方法，再加自定义查询。
 */
@Mapper
public interface DocumentsMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 获取所有已上传文档列表，按上传时间倒序
     */
    @Select("SELECT id, file_name AS fileName, status, TO_CHAR(created_at, 'YYYY-MM-DD HH24:MI:SS') AS uploadTime FROM documents ORDER BY created_at DESC")
    List<DocumentVO> selectDocumentList();

    /**
     * 更新文档状态和进度
     */
    @Update("UPDATE documents SET status = #{status}, progress = #{progress}, error_message = #{errorMessage}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("progress") int progress,
                     @Param("errorMessage") String errorMessage);

    /**
     * 更新文档的 chunk 数量
     */
    @Update("UPDATE documents SET chunk_count = #{chunkCount}, updated_at = NOW() WHERE id = #{id}")
    int updateChunkCount(@Param("id") Long id, @Param("chunkCount") int chunkCount);

    /**
     * 回填存储路径。
     * <p>
     * 上传流程为「先插 documents 行取 id → 再算 artifact key → 再上传 MinIO」，
     * 因为 ACADEMIC 链路的 key 含 documentId（决策 D2），必须先有 id。
     */
    @Update("UPDATE documents SET storage_path = #{storagePath}, updated_at = NOW() WHERE id = #{id}")
    int updateStoragePath(@Param("id") Long id, @Param("storagePath") String storagePath);
}