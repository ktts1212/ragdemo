package com.example.ragdemo.document.chunk;

/**
 * {@link ChunkBuildStats} 的可变累加器（Chunker 执行期间使用）。
 */
public class ChunkStatsAccumulator {

    private int noiseBlockCount;
    private int skippedFigureCount;
    private int skippedTableCount;
    private int skippedReferenceCount;
    private int splitParagraphCount;

    public void addNoiseBlock() {
        noiseBlockCount++;
    }

    public void addSkippedFigure() {
        skippedFigureCount++;
    }

    public void addSkippedTable() {
        skippedTableCount++;
    }

    public void addSkippedReference() {
        skippedReferenceCount++;
    }

    public void addSplitParagraph() {
        splitParagraphCount++;
    }

    public int getNoiseBlockCount() {
        return noiseBlockCount;
    }

    public int getSkippedFigureCount() {
        return skippedFigureCount;
    }

    public int getSkippedTableCount() {
        return skippedTableCount;
    }

    public int getSkippedReferenceCount() {
        return skippedReferenceCount;
    }

    public int getSplitParagraphCount() {
        return splitParagraphCount;
    }

    public ChunkBuildStats toStats() {
        return new ChunkBuildStats(noiseBlockCount, skippedFigureCount, skippedTableCount,
                skippedReferenceCount, splitParagraphCount);
    }
}
