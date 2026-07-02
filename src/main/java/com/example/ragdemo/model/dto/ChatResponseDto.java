package com.example.ragdemo.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponseDto {

    private String answer;
    private List<SourceReference> sources;
    private String conversationId;
}
