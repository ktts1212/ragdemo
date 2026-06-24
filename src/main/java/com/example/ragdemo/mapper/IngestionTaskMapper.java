package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTask> {

    @Update("UPDATE ingestion_tasks SET status = #{status}, error_message = #{errorMessage}, finished_at = #{finishedAt}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage,
                     @Param("finishedAt") LocalDateTime finishedAt);
}
