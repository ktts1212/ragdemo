package com.example.ragdemo.document.marker.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * Marker Document Tree 的原始节点（通用树节点）。
 * <p>
 * 设计原则（skill §8）：Raw 层应宽容、忠实、少做假设。
 * 不为每个 block_type 建独立 Java class，未知字段/未知类型不导致解析失败。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarkerBlock {

    /** Marker 原始 block id，如 "/page/3/Text/12" */
    private String id;

    /** 原始 block 类型字符串（不直接映射 enum，保持宽容） */
    private String blockType;

    /** block 的 HTML 内容 */
    private String html;

    /** 多边形坐标 */
    private List<List<Double>> polygon;

    /** 包围盒 [x0, y0, x1, y1] */
    private List<Double> bbox;

    /** 子节点 */
    private List<MarkerBlock> children = new ArrayList<>();

    /** section 层级信息（原始 Map，不做假设） */
    private Map<String, Object> sectionHierarchy;

    /** 图片资源：文件名 → base64 / dataURI（禁止进入长期领域模型） */
    private Map<String, String> images;
}
