package com.example.ragdemo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequestDto {

    private String question;
    private Integer topK;
    private Long knowledgeBaseId;
    private Double minScore;
    private String model;
    private String conversationId;
}
