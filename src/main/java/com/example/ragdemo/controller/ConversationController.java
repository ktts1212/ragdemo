package com.example.ragdemo.controller;

import com.example.ragdemo.common.Result;
import com.example.ragdemo.memory.ConversationMemoryService;
import com.example.ragdemo.model.entity.ChatConversation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话历史记录接口
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationMemoryService memoryService;

    public ConversationController(ConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public Result<List<ChatConversation>> listConversations(
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(memoryService.listConversations(limit));
    }
}
