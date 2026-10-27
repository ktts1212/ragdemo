// Step 5 task 1 (READ-ONLY): audit duplicate document rows that share one checksum in the KB.
// Discovers foreign keys referencing "documents" from information_schema, then counts real references
// per duplicate row, plus chunk counts. Produces eval-dataset/duplicate_audit.json and a keep/cleanup
// recommendation. It NEVER deletes or modifies anything.
//
// Usage: java -cp "target/classes;<cp.txt>" eval-dataset/tools/AuditDuplicates.java

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuditDuplicates {

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(".");
        Map<String, String> ds = readDatasource(repo.resolve("src/main/resources/application.yml"));
        Class.forName("org.postgresql.Driver");

        StringBuilder out = new StringBuilder();
        List<String> log = new ArrayList<>();
        List<Map<String, Object>> groups = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(ds.get("url"), ds.get("username"),
                ds.getOrDefault("password", ""))) {

            // 1) which tables/columns reference documents(id)?
            List<String[]> refs = new ArrayList<>();
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("""
                            SELECT tc.table_name, kcu.column_name
                            FROM information_schema.table_constraints tc
                            JOIN information_schema.key_column_usage kcu
                              ON tc.constraint_name = kcu.constraint_name
                            JOIN information_schema.constraint_column_usage ccu
                              ON tc.constraint_name = ccu.constraint_name
                            WHERE tc.constraint_type = 'FOREIGN KEY' AND ccu.table_name = 'documents'
                            """)) {
                while (rs.next()) {
                    refs.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            }
            log.add("fk_refs_to_documents=" + refs);

            // 2) duplicate groups by checksum (file_size shown for context)
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("""
                            SELECT checksum, count(*) AS n, max(file_size) AS size
                            FROM documents
                            WHERE checksum IS NOT NULL
                            GROUP BY checksum HAVING count(*) > 1
                            ORDER BY n DESC
                            """)) {
                while (rs.next()) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("checksum", rs.getString("checksum"));
                    g.put("count", rs.getInt("n"));
                    g.put("file_size", rs.getObject("size"));
                    g.put("rows", new ArrayList<Map<String, Object>>());
                    g.put("ref_counts", new LinkedHashMap<String, Integer>());
                    groups.add(g);
                }
            }
            log.add("duplicate_groups=" + groups.size());

            // 3) per row: metadata + chunk count + reference counts
            for (Map<String, Object> g : groups) {
                String checksum = (String) g.get("checksum");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) g.get("rows");
                @SuppressWarnings("unchecked")
                Map<String, Integer> refCounts = (Map<String, Integer>) g.get("ref_counts");
                try (var ps = c.prepareStatement("""
                        SELECT id, knowledge_base_id, file_name, status, version FROM documents
                        WHERE checksum = ? ORDER BY id
                        """)) {
                    ps.setString(1, checksum);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            long id = rs.getLong("id");
                            row.put("document_id", id);
                            row.put("knowledge_base_id", rs.getLong("knowledge_base_id"));
                            row.put("file_name", rs.getString("file_name"));
                            row.put("status", rs.getString("status"));
                            row.put("version", rs.getObject("version"));
                            int chunks = count(c, "SELECT count(*) FROM document_chunks WHERE document_id = " + id);
                            row.put("chunks", chunks);
                            for (String[] ref : refs) {
                                if (!"document_chunks".equals(ref[0])) {
                                    int n = count(c, "SELECT count(*) FROM " + ref[0] + " WHERE " + ref[1]
                                            + " = " + id);
                                    if (n > 0) {
                                        String key = ref[0] + "." + ref[1];
                                        refCounts.merge(key, n, Integer::sum);
                                        row.put("ref:" + key, n);
                                    }
                                }
                            }
                            rows.add(row);
                        }
                    }
                }
            }
        }

        int totalRows = 0;
        for (Map<String, Object> g : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) g.get("rows");
            totalRows += rows.size();
            List<Map<String, Object>> keep = new ArrayList<>();
            List<Map<String, Object>> cleanup = new ArrayList<>();
            // keep rule: prefer indexed + real (non test-prefixed) name + referenced rows;
            // recommend cleanup only for test-name rows that are unreferenced, and never auto-apply.
            for (Map<String, Object> row : rows) {
                String name = String.valueOf(row.get("file_name"));
                boolean testName = name.startsWith("step4-") || name.startsWith("step5-");
                boolean referenced = row.keySet().stream()
                        .anyMatch(k -> k.startsWith("ref:") && ((Number) row.get(k)).intValue() > 0);
                if (!testName || referenced) {
                    keep.add(row);
                } else {
                    cleanup.add(row);
                }
            }
            g.put("recommended_keep", keep);
            g.put("cleanup_candidates", cleanup);
            g.put("keep_rule", "keep = (file_name not test-prefixed) OR (referenced by other business rows); "
                    + "cleanup candidates are test-named AND unreferenced (never deleted in this step)");
        }

        out.append("{\n  \"schema_version\": \"1.0\",\n  \"stage\": \"duplicate-audit\",\n");
        out.append("  \"mode\": \"READ_ONLY (no deletes, no updates, no MinIO operations)\",\n");
        out.append("  \"fk_refs_to_documents\": ").append(jsonListOfPairs(log.get(0).substring(11))).append(",\n");
        out.append("  \"duplicate_groups\": ").append(groups.size()).append(",\n");
        out.append("  \"duplicate_rows_total\": ").append(totalRows).append(",\n");
        out.append("  \"groups\": [\n");
        for (int i = 0; i < groups.size(); i++) {
            out.append(renderGroup(groups.get(i)));
            out.append(i == groups.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]\n}\n");
        Files.writeString(repo.resolve("eval-dataset/duplicate_audit.json"), out, StandardCharsets.UTF_8);

        System.out.println("[audit] " + String.join(" | ", log));
        System.out.println("[audit] duplicate_rows_total=" + totalRows);
        for (Map<String, Object> g : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) g.get("rows");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> keep = (List<Map<String, Object>>) g.get("recommended_keep");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cleanup = (List<Map<String, Object>>) g.get("cleanup_candidates");
            System.out.println("[audit] checksum=" + ((String) g.get("checksum")).substring(0, 12) + "... count="
                    + g.get("count") + " keep=" + keep.size() + " cleanup_candidates=" + cleanup.size()
                    + " ref_counts=" + g.get("ref_counts"));
        }
        System.out.println("[audit] wrote eval-dataset/duplicate_audit.json (read-only)");
    }

    private static String renderGroup(Map<String, Object> g) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\"checksum\": \"").append(g.get("checksum")).append("\", \"count\": ")
                .append(g.get("count")).append(", \"file_size\": ").append(g.get("file_size"));
        sb.append(", \"ref_counts\": ").append(toJson(g.get("ref_counts")));
        sb.append(", \"recommended_keep\": ").append(toJson(g.get("recommended_keep")));
        sb.append(", \"cleanup_candidates\": ").append(toJson(g.get("cleanup_candidates")));
        sb.append(", \"keep_rule\": ").append(json(String.valueOf(g.get("keep_rule")))).append("}");
        return sb.toString();
    }

    private static String toJson(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return json(s);
        }
        if (o instanceof Number || o instanceof Boolean) {
            return o.toString();
        }
        if (o instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(json(String.valueOf(e.getKey()))).append(": ").append(toJson(e.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (o instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(toJson(l.get(i)));
                if (i < l.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.append("]").toString();
        }
        return json(String.valueOf(o));
    }

    private static String jsonListOfPairs(String raw) {
        String body = raw.trim();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1).trim();
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String part : body.split("\\],\\s*")) {
            String p = part.replace("[", "").replace("]", "").trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] kv = p.split(",\\s*");
            if (kv.length == 2) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append("{\"table\": ").append(json(kv[0])).append(", \"column\": ").append(json(kv[1])).append("}");
                first = false;
            }
        }
        return sb.append("]").toString();
    }

    private static int count(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return -1; // table/column not applicable; surfaced as -1 rather than guessed
        }
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
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return sb.append('"').toString();
    }
}
