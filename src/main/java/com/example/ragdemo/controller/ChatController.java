// package com.example.ragdemo.controller;

// import com.example.ragdemo.common.Result;
// import com.example.ragdemo.model.dto.ChatRequest;
// import com.example.ragdemo.model.dto.ChatResponse;
// import com.example.ragdemo.service.serviceImpl.ChatService;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RestController;

// @RestController
// public class ChatController {

// private final ChatService chatService;

// public ChatController(ChatService chatService) {
// this.chatService = chatService;
// }

// @PostMapping("/chat")
// public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
// ChatResponse response = chatService.chat(request.getQuestion(),
// request.getTopK());
// return Result.success(response);
// }
// }