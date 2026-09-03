import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class Worksheet {

    public record WorksheetRow(String label, boolean rule, String exclusionFormId) {
    }

    private final String sha256;
    private final Map<String, WorksheetRow> rowsByFieldId;

    private Worksheet(String sha256, Map<String, WorksheetRow> rowsByFieldId) {
        this.sha256 = sha256;
        this.rowsByFieldId = rowsByFieldId;
    }

    public String sha256() {
        return sha256;
    }

    public Map<String, WorksheetRow> rowsByFieldId() {
        return rowsByFieldId;
    }

    public static Worksheet load(File csv) throws Exception {
        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        // Task 5's writer always emits a UTF-8 BOM (writer.write('﻿')) before the
        // #SHA256 line — Java's UTF-8 decoding does not strip it automatically. Without
        // this check, every normal, untampered worksheet has a first line that literally
        // starts with "﻿#SHA256:...", so `startsWith("#SHA256:")` below would fail
        // silently on every valid file, not just corrupted ones. Do not remove this as
        // dead code — it is what makes the common case work at all.
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }

        java.util.List<java.util.List<String>> rows = parseCsv(content);
        if (rows.isEmpty() || !rows.get(0).get(0).startsWith("#SHA256:")) {
            throw new IllegalStateException("Worksheet is missing its #SHA256 metadata line");
        }
        String sha256 = rows.get(0).get(0).substring("#SHA256:".length()).trim();

        Map<String, WorksheetRow> rowsByFieldId = new LinkedHashMap<>();
        // rows.get(1) is the header; data starts at index 2
        for (int i = 2; i < rows.size(); i++) {
            java.util.List<String> row = rows.get(i);
            if (row.size() < 6 || row.get(0).isBlank()) {
                continue;
            }
            String fieldId = row.get(0);
            String label = row.get(5).trim();
            boolean rule = row.size() > 6 && "true".equalsIgnoreCase(row.get(6).trim());
            String exclusionFormId = row.size() > 7 && !row.get(7).isBlank() ? row.get(7).trim() : null;
            rowsByFieldId.put(fieldId, new WorksheetRow(label, rule, exclusionFormId));
        }
        return new Worksheet(sha256, rowsByFieldId);
    }

    private static java.util.List<java.util.List<String>> parseCsv(String content) {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        java.util.List<String> current = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                // A hand-edited worksheet on Windows (Excel, Notepad) uses CRLF line
                // endings, including inside a multi-line quoted field. Stripped here for
                // the same reason it's stripped outside quotes below: without this, a
                // label with an internal line break comes back with a literal embedded
                // \r before every \n, which no downstream consumer expects.
                if (c == '\r') {
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r') {
                i++;
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new java.util.ArrayList<>();
                i++;
            } else {
                field.append(c);
                i++;
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }
}
