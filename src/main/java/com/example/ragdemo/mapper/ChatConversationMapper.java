package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.ChatConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    @Update("UPDATE chat_conversations SET updated_at = NOW() WHERE id = #{id}")
    int touch(@Param("id") String conversationId);

    @Select("SELECT id, title, created_at, updated_at FROM chat_conversations ORDER BY updated_at DESC LIMIT #{limit}")
    List<ChatConversation> selectRecentConversations(@Param("limit") int limit);

    @Update("UPDATE chat_conversations SET summary = #{summary}, updated_at = NOW() WHERE id = #{id}")
    int updateSummary(@Param("id") String conversationId, @Param("summary") String summary);

    @Update("UPDATE chat_conversations SET session_state = #{stateJson}::jsonb, updated_at = NOW() WHERE id = #{id}")
    int updateSessionState(@Param("id") String conversationId, @Param("stateJson") String stateJson);
}
