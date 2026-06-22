package com.example.ragdemo.repository;

import com.example.ragdemo.model.entity.DocumentChunk;
import com.example.ragdemo.util.VectorUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class DocumentChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void deleteByDocName(String docName) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE doc_name = ?", docName);
    }

    public void save(DocumentChunk chunk) {
        // pgvector 可以把 "[0.1,0.2,...]" 这样的字符串转换为 vector 类型。
        String sql = """
                INSERT INTO document_chunks (doc_name, chunk_index, content, embedding)
                VALUES (?, ?, ?, CAST(? AS vector))
                """;
        jdbcTemplate.update(sql,
                chunk.getDocName(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                VectorUtils.toPgVector(chunk.getEmbedding()));
    }

    public List<DocumentChunk> searchSimilar(float[] queryEmbedding, int topK) {
        // <=> 是 pgvector 的 cosine distance，数值越小越相似。
        String sql = """
                SELECT id, doc_name, chunk_index, content, created_at,
                       1 - (embedding <=> CAST(? AS vector)) AS score
                FROM document_chunks
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """;
        String vector = VectorUtils.toPgVector(queryEmbedding);
        return jdbcTemplate.query(sql, this::mapSearchResult, vector, vector, topK);
    }

    private DocumentChunk mapSearchResult(ResultSet rs, int rowNum) throws SQLException {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocName(rs.getString("doc_name"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setScore(rs.getDouble("score"));
        chunk.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        return chunk;
    }
}
