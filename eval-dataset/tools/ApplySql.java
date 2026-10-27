// Phase 2 tooling: apply a .sql migration / run a read-only verification query, reading datasource
// settings from application.yml so no credentials ever appear on the command line.
//
// Usage:
//   java -cp "target/classes;<cp.txt>" eval-dataset/tools/ApplySql.java apply sql/migration/V7__long_term_memory.sql
//   java -cp "target/classes;<cp.txt>" eval-dataset/tools/ApplySql.java query "SELECT ..."
//   optional 4th arg: maxRows (default 50)

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApplySql {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("[FATAL] usage: <apply|query> <sql-file|sql-text> [maxRows]");
            System.exit(2);
        }
        String mode = args[0];
        String payload = args[1];
        int maxRows = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        Map<String, String> ds = readDatasource(Path.of("src/main/resources/application.yml"));
        Class.forName("org.postgresql.Driver");
        try (Connection c = DriverManager.getConnection(ds.get("url"), ds.get("username"),
                ds.getOrDefault("password", ""))) {
            if ("apply".equals(mode)) {
                String sql = Files.readString(Path.of(payload), StandardCharsets.UTF_8);
                List<String> statements = splitStatements(sql);
                int ok = 0;
                int failed = 0;
                try (Statement st = c.createStatement()) {
                    for (String stmt : statements) {
                        try {
                            st.execute(stmt);
                            ok++;
                            System.out.println("[ok] " + firstLine(stmt));
                        } catch (Exception e) {
                            failed++;
                            System.out.println("[fail] " + firstLine(stmt) + " -> " + e.getMessage());
                        }
                    }
                }
                System.out.println("[apply] ok=" + ok + " failed=" + failed);
                if (failed > 0) {
                    System.exit(1);
                }
            } else if ("query".equals(mode)) {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(payload)) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> out = new ArrayList<>();
                    int n = 0;
                    while (rs.next() && n < maxRows) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            if (i > 1) {
                                sb.append(" | ");
                            }
                            sb.append(md.getColumnLabel(i)).append('=').append(rs.getString(i));
                        }
                        out.add(sb.toString());
                        n++;
                    }
                    out.forEach(line -> System.out.println("[row] " + line));
                    System.out.println("[query] rows=" + n);
                }
            } else {
                System.out.println("[FATAL] unknown mode: " + mode);
                System.exit(2);
            }
        }
    }

    /** Very small splitter: ';' at end of statement, ignoring '--' comment lines. */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : sql.split("\n")) {
            String line = rawLine;
            if (line.trim().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            String trimmed = current.toString().trim();
            if (trimmed.endsWith(";")) {
                out.add(trimmed.substring(0, trimmed.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static String firstLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
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
}
