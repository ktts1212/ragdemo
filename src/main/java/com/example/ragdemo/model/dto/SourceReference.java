package com.example.ragdemo.model.dto;

public class SourceReference {

    private String docName;
    private Integer chunkIndex;
    private Double score;
    private String preview;

    public SourceReference(String docName, Integer chunkIndex, Double score, String preview) {
        this.docName = docName;
        this.chunkIndex = chunkIndex;
        this.score = score;
        this.preview = preview;
    }

    public String getDocName() {
        return docName;
    }

    public void setDocName(String docName) {
        this.docName = docName;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }
}
