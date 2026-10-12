import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class Worksheet {

    public record WorksheetRow(String label, boolean rule, String exclusionFormId, Map<String, String> onValueMeanings) {
    }

    // Package-private: the format is "on-value=meaning" pairs separated by ';',
    // e.g. "1=כן;2=לא" or "True=זכר;False=נקבה". Blank input means "no mapping
    // provided" — a valid, common state for fields with 0 or 1 distinct on-values
    // (text fields, signature fields, single-widget checkboxes) — and returns an
    // empty map, not an error. Anything else that doesn't parse throws loudly,
    // consistent with this project's "fail loud, never silently default" rule:
    // this method does not know which field it is parsing for, so callers that
    // can identify the field (see load() below) must re-throw with that context.
    static Map<String, String> parseOnValueMeanings(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split(";")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new IllegalStateException("Malformed On_Value_Meanings entry (missing '='): '" + pair + "'");
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (key.isEmpty()) {
                throw new IllegalStateException("Malformed On_Value_Meanings entry (empty on-value before '='): '" + pair + "'");
            }
            if (value.isEmpty()) {
                throw new IllegalStateException("Malformed On_Value_Meanings entry (empty meaning after '='): '" + pair + "'");
            }
            if (result.containsKey(key)) {
                throw new IllegalStateException("Duplicate on-value key in On_Value_Meanings: '" + key + "'");
            }
            result.put(key, value);
        }
        return result;
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
            String onValueMeaningsRaw = row.size() > 8 ? row.get(8).trim() : "";
            Map<String, String> onValueMeanings;
            try {
                onValueMeanings = parseOnValueMeanings(onValueMeaningsRaw);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("Field '" + fieldId + "': " + e.getMessage(), e);
            }
            rowsByFieldId.put(fieldId, new WorksheetRow(label, rule, exclusionFormId, onValueMeanings));
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
