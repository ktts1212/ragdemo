package com.example.ragdemo.document.marker.raw;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * Marker 顶层文档节点（Document）。
 * <p>
 * 结构：Document → Page[] → (PageHeader/SectionHeader/Text/Figure/Caption/Table/...)*
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarkerDocument {

    private String id;

    private String blockType;

    private List<MarkerBlock> children = new ArrayList<>();
}
