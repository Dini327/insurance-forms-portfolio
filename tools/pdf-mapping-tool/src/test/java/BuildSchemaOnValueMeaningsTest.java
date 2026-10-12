import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildSchemaOnValueMeaningsTest {

    private static Worksheet.WorksheetRow row(Map<String, String> onValueMeanings) {
        return new Worksheet.WorksheetRow("some label", false, null, onValueMeanings);
    }

    @Test
    void twoDistinctOnValuesWithEmptyMeaningsIsMissingMandatoryViolation() {
        Map<String, Set<String>> real = Map.of("F1", Set.of("1", "2"));
        Map<String, Worksheet.WorksheetRow> rows = Map.of("F1", row(Map.of()));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("Missing On_Value_Meanings"));
        assertTrue(violations.get(0).contains("F1"));
    }

    @Test
    void meaningsMissingAKeyIsMismatchViolation() {
        Map<String, Set<String>> real = Map.of("F1", Set.of("1", "2"));
        Map<String, Worksheet.WorksheetRow> rows = Map.of("F1", row(Map.of("1", "כן")));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("does not match the PDF's real on-values"));
        assertTrue(violations.get(0).contains("F1"));
        assertTrue(violations.get(0).contains("[1]"));
        assertTrue(violations.get(0).contains("[1, 2]"));
    }

    @Test
    void meaningsWithExtraKeyIsMismatchViolation() {
        Map<String, Set<String>> real = Map.of("F1", Set.of("1", "2"));
        Map<String, Worksheet.WorksheetRow> rows = Map.of(
                "F1", row(Map.of("1", "כן", "2", "לא", "3", "אולי")));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("does not match the PDF's real on-values"));
        assertTrue(violations.get(0).contains("F1"));
    }

    @Test
    void oneDistinctOnValueWithEmptyMeaningsIsNotAViolation() {
        Map<String, Set<String>> real = Map.of("F1", Set.of("Yes"));
        Map<String, Worksheet.WorksheetRow> rows = Map.of("F1", row(Map.of()));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertTrue(violations.isEmpty());
    }

    @Test
    void multiWidgetTextFieldWithZeroDistinctOnValuesIsNotAViolation() {
        // Models a multi-widget text field (e.g. AgentName, 4 widgets) where every
        // widget's SchemaField.on() is null, so the field never makes it into the
        // real-on-values map at all with a non-empty set — represented here as an
        // explicit empty set, exactly as the Background section describes.
        Map<String, Set<String>> real = Map.of("AgentName", Set.of());
        Map<String, Worksheet.WorksheetRow> rows = Map.of("AgentName", row(Map.of()));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertTrue(violations.isEmpty());
    }

    @Test
    void correctlyMatchingTwoValueFieldIsNotAViolation() {
        Map<String, Set<String>> real = Map.of("IsSmoking", Set.of("True", "False"));
        Map<String, Worksheet.WorksheetRow> rows = Map.of(
                "IsSmoking", row(Map.of("True", "כן", "False", "לא")));

        List<String> violations = BuildSchema.validateOnValueMeanings(real, rows);

        assertTrue(violations.isEmpty());
    }
}
