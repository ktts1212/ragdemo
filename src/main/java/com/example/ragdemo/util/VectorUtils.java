package com.example.ragdemo.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class VectorUtils {

    private VectorUtils() {
    }

    public static String toPgVector(float[] vector) {
        return "[" + arrayToString(vector) + "]";
    }

    private static String arrayToString(float[] vector) {
        return Arrays.stream(toDoubleArray(vector))
                .mapToObj(Double::toString)
                .collect(Collectors.joining(","));
    }

    private static double[] toDoubleArray(float[] vector) {
        double[] values = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            values[i] = vector[i];
        }
        return values;
    }

    public static String preview(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * {@code List<String>} → PostgreSQL {@code text[]} 字面量，如 {@code {"4. Methods","4.1 Datasets"}}。
     * <p>
     * 元素加双引号并转义 {@code \} 与 {@code "}，null / 空白元素跳过；空列表 → {@code {}}。
     * SQL 侧配合 {@code CAST(#{sectionPath} AS text[])} 使用。
     */
    public static String toPgTextArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        String joined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(VectorUtils::escapePgArrayElement)
                .collect(Collectors.joining(","));
        return "{" + joined + "}";
    }

    private static String escapePgArrayElement(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
