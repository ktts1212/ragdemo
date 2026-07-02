package com.example.ragdemo.controller;

import com.example.ragdemo.common.Result;
import com.example.ragdemo.model.dto.ChatRequestDto;
import com.example.ragdemo.model.dto.ChatResponseDto;
import com.example.ragdemo.service.ChatService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/api/chat")
    public Result<ChatResponseDto> chat(@RequestBody ChatRequestDto request) {
        return Result.success(chatService.chat(request));
    }
}