package com.example.ragdemo.document.marker.raw;

import java.util.List;

/**
 * Marker 包围盒 [x0, y0, x1, y1]。
 */
public record MarkerBoundingBox(
        double x0,
        double y0,
        double x1,
        double y1
) {

    /**
     * 从 Marker 的 bbox List 安全转换；长度不足或 null 时返回 null。
     */
    public static MarkerBoundingBox from(List<Double> bbox) {
        if (bbox == null || bbox.size() < 4) {
            return null;
        }
        Double x0 = bbox.get(0);
        Double y0 = bbox.get(1);
        Double x1 = bbox.get(2);
        Double y1 = bbox.get(3);
        if (x0 == null || y0 == null || x1 == null || y1 == null) {
            return null;
        }
        return new MarkerBoundingBox(x0, y0, x1, y1);
    }
}
