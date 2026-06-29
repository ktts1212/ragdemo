package com.example.ragdemo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {

    private String question;
    private Integer topK;
    private Long knowledgeBaseId;
    private Double minScore;
}
