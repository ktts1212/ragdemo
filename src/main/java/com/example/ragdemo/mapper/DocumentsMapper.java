package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.KnowledgeDocument;
import com.example.ragdemo.model.vo.DocumentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}