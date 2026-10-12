import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorksheetTest {

    @Test
    void parsesNormalMultiValueMapping() {
        Map<String, String> result = Worksheet.parseOnValueMeanings("1=כן;2=לא");
        assertEquals(Map.of("1", "כן", "2", "לא"), result);
    }

    @Test
    void blankColumnParsesToEmptyMap() {
        assertTrue(Worksheet.parseOnValueMeanings("").isEmpty());
        assertTrue(Worksheet.parseOnValueMeanings(null).isEmpty());
        assertTrue(Worksheet.parseOnValueMeanings("   ").isEmpty());
    }

    @Test
    void missingEqualsSignThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Worksheet.parseOnValueMeanings("1כן"));
        assertTrue(e.getMessage().contains("missing '='"));
    }

    @Test
    void emptyKeyThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Worksheet.parseOnValueMeanings("=כן"));
        assertTrue(e.getMessage().contains("empty on-value"));
    }

    @Test
    void emptyMeaningThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Worksheet.parseOnValueMeanings("1="));
        assertTrue(e.getMessage().contains("empty meaning"));
    }

    @Test
    void duplicateKeyThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Worksheet.parseOnValueMeanings("1=כן;1=לא"));
        assertTrue(e.getMessage().contains("Duplicate on-value key"));
    }

    // --- Tests exercised through Worksheet.load(), to confirm the field-id
    // gets attached to the error message (parseOnValueMeanings itself has no
    // idea which field it's parsing for; load() must wrap the exception).

    private static final String SHA = "0".repeat(64);

    private java.io.File writeCsv(java.nio.file.Path dir, String onValueMeaningsColumn) throws Exception {
        String content = "﻿#SHA256:" + SHA + "\n"
                + "Field_ID,Field_Type,Page_Number,Position_X,Position_Y,Hebrew_Question_Label,Rule,Exclusion_Form_ID,On_Value_Meanings\n"
                + "\"MyField\",\"checkbox\",\"1\",\"0.0\",\"0.0\",\"some label\",\"false\",\"\",\"" + onValueMeaningsColumn + "\"\n";
        java.io.File csv = dir.resolve("worksheet.csv").toFile();
        java.nio.file.Files.write(csv.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return csv;
    }

    @Test
    void loadParsesValidMapping(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File csv = writeCsv(dir, "1=כן;2=לא");
        Worksheet worksheet = Worksheet.load(csv);
        Worksheet.WorksheetRow row = worksheet.rowsByFieldId().get("MyField");
        assertEquals(Map.of("1", "כן", "2", "לא"), row.onValueMeanings());
    }

    @Test
    void loadWithAbsentColumnYieldsEmptyMap(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Simulate a pre-existing 8-column worksheet: no 9th column at all.
        String content = "﻿#SHA256:" + SHA + "\n"
                + "Field_ID,Field_Type,Page_Number,Position_X,Position_Y,Hebrew_Question_Label,Rule,Exclusion_Form_ID\n"
                + "\"MyField\",\"checkbox\",\"1\",\"0.0\",\"0.0\",\"some label\",\"false\",\"\"\n";
        java.io.File csv = dir.resolve("worksheet.csv").toFile();
        java.nio.file.Files.write(csv.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Worksheet worksheet = Worksheet.load(csv);
        Worksheet.WorksheetRow row = worksheet.rowsByFieldId().get("MyField");
        assertTrue(row.onValueMeanings().isEmpty());
    }

    @Test
    void loadWithMalformedEntryThrowsWithFieldIdAttached(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File csv = writeCsv(dir, "1כן");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Worksheet.load(csv));
        assertTrue(e.getMessage().contains("MyField"));
        assertTrue(e.getMessage().contains("missing '='"));
    }

    @Test
    void loadWithEmptyKeyThrowsWithFieldIdAttached(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File csv = writeCsv(dir, "=כן");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Worksheet.load(csv));
        assertTrue(e.getMessage().contains("MyField"));
    }

    @Test
    void loadWithEmptyMeaningThrowsWithFieldIdAttached(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File csv = writeCsv(dir, "1=");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Worksheet.load(csv));
        assertTrue(e.getMessage().contains("MyField"));
    }

    @Test
    void loadWithDuplicateKeyThrowsWithFieldIdAttached(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File csv = writeCsv(dir, "1=כן;1=לא");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Worksheet.load(csv));
        assertTrue(e.getMessage().contains("MyField"));
        assertTrue(e.getMessage().contains("Duplicate on-value key"));
    }
}
