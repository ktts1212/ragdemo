package com.example.ragdemo.model.entity;

public enum DocumentStatus {

    UPLOADED("uploaded"),
    PARSING("parsing"),
    CHUNKING("chunking"),
    EMBEDDING("embedding"),
    INDEXED("indexed"),
    FAILED("failed");

    private final String value;

    DocumentStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 是否处于处理中状态
     */
    public boolean isProcessing() {
        return this == PARSING || this == CHUNKING || this == EMBEDDING;
    }

    /**
     * 是否终态（入库成功或失败，不再变化）
     */
    public boolean isTerminal() {
        return this == INDEXED || this == FAILED;
    }

    /**
     * 从数据库字符串转枚举
     */
    public static DocumentStatus fromValue(String value) {
        for (DocumentStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown document status: " + value);
    }
}