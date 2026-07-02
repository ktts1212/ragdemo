package com.example.ragdemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragdemo.model.entity.ConversationMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {

    @Insert("INSERT INTO chat_messages (conversation_id, role, content) VALUES (#{conversationId}, #{role}, #{content})")
    int insertMessage(@Param("conversationId") String conversationId,
                      @Param("role") String role,
                      @Param("content") String content);

    @Select("SELECT * FROM chat_messages WHERE conversation_id = #{conversationId} ORDER BY created_at DESC LIMIT #{limit}")
    List<ConversationMessage> selectRecentMessages(@Param("conversationId") String conversationId,
                                                   @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = #{conversationId}")
    long countByConversationId(@Param("conversationId") String conversationId);
}
