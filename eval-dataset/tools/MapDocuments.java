// Step 5 Stage 5.1 (read-only tool): map registered PDFs to ingested documents in PostgreSQL,
// and obtain the TRUE page count via PDFBox when available (reflection => works with PDFBox 2.x/3.x).
//
// Safety: never prints credentials; reads datasource settings from src/main/resources/application.yml;
// never writes to any database table; only reads. Output: eval-dataset/doc_mapping.json (ASCII JSON).
//
// Usage: java -cp "target/classes;<cp.txt>" eval-dataset/tools/MapDocuments.java

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MapDocuments {

    private static final String KB_ID = "1";

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(".");
        Path pdfDir = repo.resolve("src/test/resources/pdfs");
        Path yml = repo.resolve("src/main/resources/application.yml");

        Map<String, String> ds = readDatasource(yml);
        String url = ds.get("url");
        String user = ds.get("username");
        String pass = ds.getOrDefault("password", "");
        if (url == null || user == null) {
            System.out.println("[FATAL] datasource url/username not found in application.yml");
            System.exit(2);
        }

        Class.forName("org.postgresql.Driver");
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, user, pass);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT id, knowledge_base_id, file_name, stored_file_name, file_size, checksum, "
                                + "status, version FROM documents WHERE knowledge_base_id = " + KB_ID)) {
            while (rs.next()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", rs.getLong("id"));
                d.put("kb", rs.getLong("knowledge_base_id"));
                d.put("file_name", rs.getString("file_name"));
                d.put("file_size", rs.getObject("file_size") == null ? null : rs.getLong("file_size"));
                d.put("checksum", rs.getString("checksum"));
                d.put("status", rs.getString("status"));
                d.put("version", rs.getObject("version") == null ? null : rs.getInt("version"));
                docs.add(d);
            }
        }
        System.out.println("[info] ingested documents in kb=" + KB_ID + ": " + docs.size());

        List<Path> pdfs = new ArrayList<>();
        try (var stream = Files.list(pdfDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().forEach(pdfs::add);
        }

        StringBuilder out = new StringBuilder();
        out.append("{\n  \"schema_version\": \"1.0\",\n  \"stage\": \"5.1-map\",\n");
        out.append("  \"knowledge_base_id\": ").append(KB_ID).append(",\n");
        out.append("  \"ingested_document_count\": ").append(docs.size()).append(",\n");
        out.append("  \"notes\": [\"read-only mapping; unmatched stays null (no guessing)\",");
        out.append(" \"match order: sha256 checksum, then file_size+file_name (case/space-insensitive)\"],\n");
        out.append("  \"papers\": [\n");

        int matched = 0;
        int unmatched = 0;
        int verifiedPages = 0;
        for (int i = 0; i < pdfs.size(); i++) {
            Path pdf = pdfs.get(i);
            String name = pdf.getFileName().toString();
            String sha = sha256(pdf);
            long size = Files.size(pdf);
            Integer truePages = truePageCount(pdf);

            Map<String, Object> hit = null;
            String method = null;
            for (Map<String, Object> d : docs) {
                String checksum = (String) d.get("checksum");
                if (checksum != null && checksum.equalsIgnoreCase(sha)) {
                    hit = d;
                    method = "sha256";
                    break;
                }
            }
            if (hit == null) {
                for (Map<String, Object> d : docs) {
                    Object fs = d.get("file_size");
                    String fn = (String) d.get("file_name");
                    if (fs != null && ((Number) fs).longValue() == size && fn != null
                            && normalize(fn).equals(normalize(name))) {
                        hit = d;
                        method = "size+name";
                        break;
                    }
                }
            }
            if (hit != null) {
                matched++;
            } else {
                unmatched++;
            }
            if (truePages != null) {
                verifiedPages++;
            }

            out.append("    {\"paper_id\": \"sha256:").append(sha, 0, 16).append("\",");
            out.append(" \"file_name\": ").append(json(name)).append(",");
            out.append(" \"pdf_sha256\": \"").append(sha).append("\",");
            out.append(" \"bytes\": ").append(size).append(",");
            out.append(" \"match_method\": ").append(method == null ? "null" : json(method)).append(",");
            out.append(" \"document_id\": ").append(hit == null ? "null" : String.valueOf(hit.get("id"))).append(",");
            out.append(" \"knowledge_base_id\": ").append(hit == null ? "null" : String.valueOf(hit.get("kb")))
                    .append(",");
            out.append(" \"document_status\": ")
                    .append(hit == null ? "null" : json((String) hit.get("status"))).append(",");
            out.append(" \"document_version\": ")
                    .append(hit == null || hit.get("version") == null ? "null" : String.valueOf(hit.get("version")))
                    .append(",");
            out.append(" \"page_count_true\": ").append(truePages == null ? "null" : truePages).append(",");
            out.append(" \"page_count_verified\": ").append(truePages != null);
            out.append("}");
            out.append(i == pdfs.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]\n}\n");

        Files.writeString(repo.resolve("eval-dataset/doc_mapping.json"), out, StandardCharsets.UTF_8);
        System.out.println("[done] papers=" + pdfs.size() + " matched=" + matched + " unmatched=" + unmatched
                + " page_count_verified=" + verifiedPages);
        System.out.println("[done] wrote eval-dataset/doc_mapping.json (UTF-8)");
    }

    private static Integer truePageCount(Path pdf) {
        try {
            Class<?> loader = Class.forName("org.apache.pdfbox.Loader");
            Object doc = loader.getMethod("loadPDF", File.class).invoke(null, pdf.toFile());
            try {
                return (int) doc.getClass().getMethod("getNumberOfPages").invoke(doc);
            } finally {
                doc.getClass().getMethod("close").invoke(doc);
            }
        } catch (Throwable ignored) {
            // fall through to PDFBox 2.x API, then give up (page_count stays null => unverified)
        }
        try {
            Class<?> pdDocument = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Object doc = pdDocument.getMethod("load", File.class).invoke(null, pdf.toFile());
            try {
                return (int) doc.getClass().getMethod("getNumberOfPages").invoke(doc);
            } finally {
                doc.getClass().getMethod("close").invoke(doc);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Minimal extraction of constructor-arg style datasource settings; values are never printed. */
    private static Map<String, String> readDatasource(Path yml) throws Exception {
        Map<String, String> map = new HashMap<>();
        boolean inDatasource = false;
        for (String raw : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("datasource:")) {
                inDatasource = true;
                continue;
            }
            if (inDatasource) {
                if (!raw.startsWith(" ") && !raw.startsWith("\t") && !line.isEmpty()) {
                    inDatasource = false; // left the datasource block
                } else {
                    if (line.startsWith("url:")) {
                        map.put("url", line.substring(4).strip());
                    } else if (line.startsWith("username:")) {
                        map.put("username", line.substring(9).strip());
                    } else if (line.startsWith("password:")) {
                        map.put("password", line.substring(9).strip());
                    }
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
