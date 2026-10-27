// Step 5 task 1: audit knowledge_bases and create ONE dedicated evaluation KB (additive only).
//  * reads information_schema for columns / types / nullability / defaults / key strategy;
//  * lists existing KB rows (never modified or deleted);
//  * reuses an existing clean eval KB if one is found, otherwise INSERTs exactly one new row;
//  * prints and persists the resulting knowledgeBaseId to eval-dataset/eval_kb.json.
// Usage: java -cp "target/classes;<cp.txt>" eval-dataset/tools/EvalKbSetup.java [--name <name>]

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EvalKbSetup {

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(".");
        Map<String, String> ds = readDatasource(repo.resolve("src/main/resources/application.yml"));
        Class.forName("org.postgresql.Driver");

        String requestedName = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--name".equals(args[i])) {
                requestedName = args[i + 1];
            }
        }

        StringBuilder log = new StringBuilder();
        List<Map<String, Object>> columns = new ArrayList<>();
        List<Map<String, Object>> existing = new ArrayList<>();
        Long createdId = null;
        String action;

        try (Connection c = DriverManager.getConnection(ds.get("url"), ds.get("username"),
                ds.getOrDefault("password", ""))) {

            // 1) structure audit
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("""
                            SELECT column_name, data_type, is_nullable, column_default
                            FROM information_schema.columns
                            WHERE table_name = 'knowledge_bases' ORDER BY ordinal_position
                            """)) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", rs.getString(1));
                    col.put("type", rs.getString(2));
                    col.put("nullable", rs.getString(3));
                    col.put("default", rs.getString(4));
                    columns.add(col);
                }
            }
            // 2) key strategy (serial/identity?)
            boolean identity = false;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("""
                            SELECT column_name, is_identity, identity_generation
                            FROM information_schema.columns
                            WHERE table_name = 'knowledge_bases' AND is_identity = 'YES'
                            """)) {
                while (rs.next()) {
                    identity = true;
                    log.append("identity_column=").append(rs.getString(1)).append(" gen=")
                            .append(rs.getString(3)).append(' ');
                }
            }
            String serial = null;
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT pg_get_serial_sequence('knowledge_bases', 'id')")) {
                if (rs.next()) {
                    serial = rs.getString(1);
                }
            }
            log.append("identity=").append(identity).append(" serialSeq=").append(serial).append(' ');

            // 3) existing rows (read-only)
            try (Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT id, name, description, created_at FROM knowledge_bases ORDER BY id")) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("description", rs.getString("description"));
                    row.put("created_at", String.valueOf(rs.getObject("created_at")));
                    existing.add(row);
                }
            }
            log.append("existing_kbs=").append(existing.size()).append(' ');

            // 4) reuse a clean eval KB when present (name contains eval/benchmark), else create one
            for (Map<String, Object> row : existing) {
                String name = String.valueOf(row.get("name")).toLowerCase();
                if (name.contains("eval") || name.contains("benchmark")) {
                    createdId = ((Number) row.get("id")).longValue();
                    action = "REUSED_EXISTING_EVAL_KB";
                    writeResult(repo, createdId, String.valueOf(row.get("name")), action, columns, existing, log.toString());
                    System.out.println("[kb] " + action + " id=" + createdId);
                    return;
                }
            }

            String name = requestedName != null ? requestedName
                    : "eval-literature-rag-agent-" + java.time.LocalDate.now();
            String description = "Step 5 独立评测知识库（15 篇 ECG 自监督学习论文；只用于 RAG vs Agent 对照，"
                    + "不存放生产数据）";
            if (identity || serial != null || hasIdDefault(columns)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_bases (name, description, created_at, updated_at) "
                                + "VALUES (?, ?, NOW(), NOW()) RETURNING id")) {
                    ps.setString(1, name);
                    ps.setString(2, description);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        createdId = rs.getLong(1);
                    }
                }
                action = "CREATED_NEW_EVAL_KB";
            } else {
                // no auto id: derive next id from MAX(id)+1 (single-row insert, additive only)
                try (Statement st = c.createStatement();
                        ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id),0)+1 FROM knowledge_bases")) {
                    rs.next();
                    createdId = rs.getLong(1);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO knowledge_bases (id, name, description, created_at, updated_at) "
                                + "VALUES (?, ?, ?, NOW(), NOW())")) {
                    ps.setLong(1, createdId);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.executeUpdate();
                }
                action = "CREATED_NEW_EVAL_KB_MANUAL_ID";
            }
        }

        writeResult(repo, createdId, "eval-literature-rag-agent", action, columns, existing, log.toString());
        System.out.println("[kb] " + action + " id=" + createdId);
        System.out.println("[kb] " + log);
        System.out.println("[kb] wrote eval-dataset/eval_kb.json (additive only; no KB modified or deleted)");
    }

    private static boolean hasIdDefault(List<Map<String, Object>> columns) {
        for (Map<String, Object> col : columns) {
            if ("id".equals(col.get("column")) && col.get("default") != null) {
                return true;
            }
        }
        return false;
    }

    private static void writeResult(Path repo, Long id, String name, String action,
            List<Map<String, Object>> columns, List<Map<String, Object>> existing, String log) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"schema_version\": \"1.0\",\n  \"stage\": \"kb-setup\",\n");
        sb.append("  \"action\": \"").append(action).append("\",\n");
        sb.append("  \"knowledge_base_id\": ").append(id).append(",\n");
        sb.append("  \"name\": ").append(json(name)).append(",\n");
        sb.append("  \"audit_log\": ").append(json(log)).append(",\n");
        sb.append("  \"columns\": ").append(toJson(columns)).append(",\n");
        sb.append("  \"existing_knowledge_bases_before\": ").append(toJson(existing)).append(",\n");
        sb.append("  \"constraints\": [\"additive only: never updates or deletes existing KB rows\", "
                + "\"documents are imported via the existing upload/ingestion path only\"],\n");
        sb.append("  \"generated_at\": \"").append(java.time.OffsetDateTime.now()).append("\"\n}\n");
        Files.writeString(repo.resolve("eval-dataset/eval_kb.json"), sb, StandardCharsets.UTF_8);
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
