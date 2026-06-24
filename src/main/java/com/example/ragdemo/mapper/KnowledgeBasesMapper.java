package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBasesMapper extends BaseMapper<KnowledgeBase> {

    @Select("SELECT COUNT(*) > 0 FROM knowledge_bases WHERE id = #{id}")
    boolean existsById(Long id);
}
