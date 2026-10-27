// Step 5 task 1 (read-only): knowledge-base alignment for the 15 registered PDFs.
//
// Match order (never guesses; weak matches are AMBIGUOUS, not VERIFIED):
//   1) full-file sha256 == documents.checksum          -> VERIFIED (content identity)
//   2) normalized file_name/stored_file_name equal      -> AMBIGUOUS (needs body/hash confirmation)
//   3) file_size equal only (any knowledge base)        -> AMBIGUOUS (weak)
//   4) otherwise                                        -> NOT_FOUND
// One-to-many / many-to-one collisions are reported explicitly. NEVER writes to the database.
//
// Usage: java -cp "target/classes;<cp.txt>" eval-dataset/tools/AlignKb.java

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AlignKb {

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(".");
        Path yml = repo.resolve("src/main/resources/application.yml");
        Path pdfDir = repo.resolve("src/test/resources/pdfs");
        Map<String, String> ds = readDatasource(yml);
        Class.forName("org.postgresql.Driver");

        // optional --kb N : scope the document scan to one knowledge base (used to poll ingestion progress)
        Long kbFilter = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--kb".equals(args[i])) {
                kbFilter = Long.parseLong(args[i + 1]);
            }
        }

        List<Map<String, Object>> docs = new ArrayList<>();
        try (var c = java.sql.DriverManager.getConnection(ds.get("url"), ds.get("username"),
                ds.getOrDefault("password", ""));
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT id, knowledge_base_id, file_name, stored_file_name, file_size, "
                        + "checksum, status, version FROM documents"
                        + (kbFilter == null ? "" : " WHERE knowledge_base_id = " + kbFilter))) {
            while (rs.next()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", rs.getLong("id"));
                d.put("kb", rs.getLong("knowledge_base_id"));
                d.put("file_name", rs.getString("file_name"));
                d.put("stored_file_name", rs.getString("stored_file_name"));
                d.put("file_size", rs.getObject("file_size") == null ? null : rs.getLong("file_size"));
                d.put("checksum", rs.getString("checksum"));
                d.put("status", rs.getString("status"));
                d.put("version", rs.getObject("version") == null ? null : rs.getInt("version"));
                docs.add(d);
            }
        }
        System.out.println("[align] scanned documents (all knowledge bases)=" + docs.size());

        // chunk counts per document (used for "chunk>0" verification of ingested papers)
        java.util.Map<Long, Integer> chunksByDoc = new java.util.LinkedHashMap<>();
        try (var cc = java.sql.DriverManager.getConnection(ds.get("url"), ds.get("username"),
                ds.getOrDefault("password", ""));
                var st2 = cc.createStatement();
                var rs2 = st2.executeQuery(
                        "SELECT document_id, count(*) AS n FROM document_chunks GROUP BY document_id")) {
            while (rs2.next()) {
                chunksByDoc.put(rs2.getLong("document_id"), rs2.getInt("n"));
            }
        }

        List<Path> pdfs = new ArrayList<>();
        try (var s = Files.list(pdfDir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted()
                    .forEach(pdfs::add);
        }

        StringBuilder out = new StringBuilder();
        out.append("{\n  \"schema_version\": \"1.0\",\n  \"stage\": \"kb-align\",\n");
        out.append("  \"scanned_documents\": ").append(docs.size()).append(",\n");
        out.append("  \"match_order\": [\"sha256==checksum -> VERIFIED\", "
                + "\"normalized name equal -> AMBIGUOUS (needs body/hash check)\", "
                + "\"size equal (any kb) -> AMBIGUOUS (weak)\", \"else NOT_FOUND\"],\n");
        out.append("  \"import_plan\": \"NOT_FOUND papers must be imported through the existing ingestion path "
                + "(upload API / ingestion graph); this tool NEVER creates or mutates documents\",\n");
        out.append("  \"papers\": [\n");

        int verified = 0;
        int ambiguous = 0;
        int notFound = 0;
        for (int i = 0; i < pdfs.size(); i++) {
            Path pdf = pdfs.get(i);
            String name = pdf.getFileName().toString();
            String normName = normalize(name);
            String sha = sha256(pdf);
            long size = Files.size(pdf);

            List<Map<String, Object>> hitsSha = new ArrayList<>();
            List<Map<String, Object>> hitsName = new ArrayList<>();
            List<Map<String, Object>> hitsSize = new ArrayList<>();
            for (Map<String, Object> d : docs) {
                String checksum = (String) d.get("checksum");
                if (checksum != null && checksum.equalsIgnoreCase(sha)) {
                    hitsSha.add(d);
                } else {
                    String fn = normalize((String) d.get("file_name"));
                    String sfn = normalize((String) d.get("stored_file_name"));
                    if (normName.equals(fn) || normName.equals(sfn)) {
                        hitsName.add(d);
                    } else {
                        Object fs = d.get("file_size");
                        if (fs != null && ((Number) fs).longValue() == size) {
                            hitsSize.add(d);
                        }
                    }
                }
            }

            String status;
            String method;
            String confidence;
            List<Map<String, Object>> candidates;
            if (!hitsSha.isEmpty()) {
                status = hitsSha.size() == 1 ? "VERIFIED" : "AMBIGUOUS";
                method = "sha256==checksum";
                confidence = hitsSha.size() == 1 ? "high" : "medium";
                candidates = hitsSha;
                if (hitsSha.size() == 1) {
                    verified++;
                } else {
                    ambiguous++;
                }
            } else if (!hitsName.isEmpty()) {
                status = "AMBIGUOUS";
                method = "normalized_name_equal";
                confidence = "medium";
                candidates = hitsName;
                ambiguous++;
            } else if (!hitsSize.isEmpty()) {
                status = "AMBIGUOUS";
                method = "file_size_equal_only";
                confidence = "low";
                candidates = hitsSize;
                ambiguous++;
            } else {
                status = "NOT_FOUND";
                method = "none";
                confidence = "low";
                candidates = List.of();
                notFound++;
            }

            out.append("    {\"paper_id\": \"sha256:").append(sha, 0, 16).append("\",");
            out.append(" \"file_name\": ").append(json(name)).append(",");
            out.append(" \"pdf_sha256\": \"").append(sha).append("\",");
            out.append(" \"bytes\": ").append(size).append(",");
            out.append(" \"status\": \"").append(status).append("\",");
            out.append(" \"match_method\": \"").append(method).append("\",");
            out.append(" \"confidence\": \"").append(confidence).append("\",");
            out.append(" \"candidates\": [");
            for (int k = 0; k < candidates.size(); k++) {
                Map<String, Object> d = candidates.get(k);
                out.append("{\"document_id\": ").append(d.get("id"))
                        .append(", \"knowledge_base_id\": ").append(d.get("kb"))
                        .append(", \"file_name\": ").append(json((String) d.get("file_name")))
                        .append(", \"file_size\": ").append(d.get("file_size"))
                        .append(", \"document_status\": ").append(json((String) d.get("status")))
                        .append(", \"document_version\": ").append(d.get("version"))
                        .append(", \"chunks\": ").append(chunksByDoc.getOrDefault((Long) d.get("id"), 0))
                        .append("}");
                if (k < candidates.size() - 1) {
                    out.append(", ");
                }
            }
            out.append("]}");
            out.append(i == pdfs.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ],\n");
        out.append("  \"summary\": {\"VERIFIED\": ").append(verified).append(", \"AMBIGUOUS\": ").append(ambiguous)
                .append(", \"NOT_FOUND\": ").append(notFound).append("}\n}\n");

        Files.writeString(repo.resolve("eval-dataset/kb_alignment.json"), out, StandardCharsets.UTF_8);
        System.out.println("[align] papers=" + pdfs.size() + " VERIFIED=" + verified + " AMBIGUOUS=" + ambiguous
                + " NOT_FOUND=" + notFound);
        System.out.println("[align] wrote eval-dataset/kb_alignment.json (read-only; no DB writes)");
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replace(".pdf", "").replaceAll("[\\s_,]+", " ").trim();
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Map<String, String> readDatasource(Path yml) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        boolean in = false;
        for (String raw : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("datasource:")) {
                in = true;
                continue;
            }
            if (in) {
                if (!raw.startsWith(" ") && !raw.startsWith("\t") && !line.isEmpty()) {
                    in = false;
                } else if (line.startsWith("url:")) {
                    map.put("url", line.substring(4).strip());
                } else if (line.startsWith("username:")) {
                    map.put("username", line.substring(9).strip());
                } else if (line.startsWith("password:")) {
                    map.put("password", line.substring(9).strip());
                }
            }
        }
        return map;
    }

    private static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
