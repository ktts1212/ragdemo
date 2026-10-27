// Step 5 Stage 5.2 prep (read-only): extract per-page text from the registered PDFs into a
// git-ignored working area (eval-dataset/work/page_text/<paper_id>.jsonl).
//
// Rationale: the step5 spec forbids committing large verbatim paper text into the repository,
// so full page text lives only under eval-dataset/work/ (git-ignored); repository artifacts keep
// only short quotes inside the JSONL evidence fields.
//
// PDFBox is used via reflection (works with 2.x "PDDocument.load" and 3.x "Loader.loadPDF").
// Usage: java -cp "target/classes;<cp.txt>" eval-dataset/tools/ExtractPages.java

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExtractPages {

    public static void main(String[] args) throws Exception {
        Path pdfDir = Path.of("src/test/resources/pdfs");
        Path outRoot = Path.of("eval-dataset/work/page_text");
        Files.createDirectories(outRoot);

        List<Path> pdfs = new ArrayList<>();
        try (var stream = Files.list(pdfDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted().forEach(pdfs::add);
        }

        int ok = 0;
        int failed = 0;
        long totalChars = 0L;
        for (Path pdf : pdfs) {
            String sha = sha256(pdf);
            String paperId = "sha256:" + sha.substring(0, 16);
            String safeName = paperId.replace(':', '_');   // ':' is illegal in Windows file names
            Path outFile = outRoot.resolve(safeName + ".jsonl");
            try {
                Object doc = open(pdf);
                int pages = (int) doc.getClass().getMethod("getNumberOfPages").invoke(doc);
                StringBuilder sb = new StringBuilder();
                int chars = 0;
                for (int page = 1; page <= pages; page++) {
                    String text = pageText(doc, page);
                    chars += text.length();
                    sb.append("{\"pdf_page\":").append(page)
                            .append(",\"chars\":").append(text.length())
                            .append(",\"text\":").append(json(text)).append("}\n");
                }
                doc.getClass().getMethod("close").invoke(doc);
                Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
                totalChars += chars;
                ok++;
                System.out.println("[ok] " + paperId + " pages=" + pages + " chars=" + chars);
            } catch (Throwable t) {
                failed++;
                System.out.println("[fail] " + paperId + " reason=" + t.getClass().getSimpleName());
            }
        }
        System.out.println("[done] papers_ok=" + ok + " failed=" + failed + " total_chars=" + totalChars);
        System.out.println("[done] output=" + outRoot.toString().replace('\\', '/') + " (git-ignored)");
    }

    private static Object open(Path pdf) throws Exception {
        try {
            Class<?> loader = Class.forName("org.apache.pdfbox.Loader");
            return loader.getMethod("loadPDF", File.class).invoke(null, pdf.toFile());
        } catch (Throwable ignored) {
            Class<?> pdDocument = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            return pdDocument.getMethod("load", File.class).invoke(null, pdf.toFile());
        }
    }

    private static String pageText(Object doc, int page) throws Exception {
        Class<?> stripperClass = Class.forName("org.apache.pdfbox.text.PDFTextStripper");
        Object stripper = stripperClass.getDeclaredConstructor().newInstance();
        stripperClass.getMethod("setStartPage", int.class).invoke(stripper, page);
        stripperClass.getMethod("setEndPage", int.class).invoke(stripper, page);
        Object text = stripperClass.getMethod("getText", Class.forName("org.apache.pdfbox.pdmodel.PDDocument"))
                .invoke(stripper, doc);
        return text == null ? "" : text.toString();
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

    private static String json(String s) {
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
