package com.example.ragdemo.util;

import java.util.Arrays;
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
}
