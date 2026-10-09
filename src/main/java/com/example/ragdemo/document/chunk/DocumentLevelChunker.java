package com.example.ragdemo.document.chunk;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.ragdemo.config.RagProperties;
import com.example.ragdemo.document.domain.AcademicDocument;
import com.example.ragdemo.document.domain.Reference;

/**
 * 文档级 chunk：ABSTRACT 与 REFERENCE（skill §19/§20）。
 * <p>
 * 这两类内容不属于任何正文 Section，因此 sectionId / sectionPath / sectionLevel 均为空。
 * <p>
 * references 策略：1 Reference → 1 REFERENCE chunk（不给几十条文献合成大 chunk）；
 * 内容以 {@code rawText} 为唯一必选（真实 Marker 数据中 title 常为空），doi/year 有值才附加；
 * 仅当 rawText 自身超长时才句子级切分。
 */
@Component
public class DocumentLevelChunker {

    private final ChunkTokenEstimator estimator;
    private final ChunkTextSplitter textSplitter;
    private final RagProperties properties;

    public DocumentLevelChunker(ChunkTokenEstimator estimator, ChunkTextSplitter textSplitter,
            RagProperties properties) {
        this.estimator = estimator;
        this.textSplitter = textSplitter;
        this.properties = properties;
    }

    /** abstractText 为空时不产出 chunk（不产生空 chunk）。 */
    public List<ChunkDraft> chunkAbstract(AcademicDocument document) {
        if (document.getAbstractText() == null || document.getAbstractText().isBlank()) {
            return List.of();
        }
        return List.of(ChunkDraft.of(ChunkType.ABSTRACT, document.getAbstractText().trim(),
                List.of(), List.of()));
    }

    public List<ChunkDraft> chunkReferences(AcademicDocument document, ChunkStatsAccumulator stats) {
        if (document.getReferences() == null || document.getReferences().isEmpty()) {
            return List.of();
        }
        int target = properties.getChunker().getTargetTokens();
        int max = properties.getChunker().getMaxTokens();

        List<ChunkDraft> chunks = new ArrayList<>();
        for (Reference reference : document.getReferences()) {
            if (reference == null || reference.getRawText() == null || reference.getRawText().isBlank()) {
                stats.addSkippedReference();
                continue;
            }
            String content = renderReference(reference);
            List<String> referenceIds = reference.getId() == null || reference.getId().isBlank()
                    ? List.of()
                    : List.of(reference.getId());
            if (estimator.estimate(content) <= max) {
                chunks.add(new ChunkDraft(ChunkType.REFERENCE, content, List.of(), List.of(), referenceIds));
                continue;
            }
            for (String part : textSplitter.splitAndPack(content, target, max)) {
                chunks.add(new ChunkDraft(ChunkType.REFERENCE, part, List.of(), List.of(), referenceIds));
            }
        }
        return chunks;
    }

    private String renderReference(Reference reference) {
        StringBuilder builder = new StringBuilder(reference.getRawText().trim());
        if (reference.getDoi() != null && !reference.getDoi().isBlank()) {
            builder.append("\nDOI: ").append(reference.getDoi().trim());
        }
        if (reference.getPublicationDate() != null && reference.getPublicationDate().getYear() != null) {
            builder.append("\nYear: ").append(reference.getPublicationDate().getYear());
        }
        return builder.toString();
    }
}
