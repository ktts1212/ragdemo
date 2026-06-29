package com.example.ragdemo.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SourceReference {
    
    //文档名
    private String docName;
    private Integer chunkIndex;
    private Double score;
    private String preview;
    private String content;
    private Integer page;
}
