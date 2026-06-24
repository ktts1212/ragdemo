package com.example.ragdemo.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DocumentVO {
    private int id;
    private String fileName;
    private String status;
    private String uploadTime;
}
