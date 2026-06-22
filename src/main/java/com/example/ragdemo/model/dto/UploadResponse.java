package com.example.ragdemo.model.dto;

public class UploadResponse {

    private String docName;
    private int chunks;
    private String message;

    public UploadResponse(String docName, int chunks, String message) {
        this.docName = docName;
        this.chunks = chunks;
        this.message = message;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public int getChunks() {
        return chunks;
    }

    public void setChunks(int chunks) {
        this.chunks = chunks;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
