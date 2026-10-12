import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class BuildSchema {

    public record SchemaFieldWithLabel(SchemaField field, String label, boolean rule, String exclusionFormId, Map<String, String> onValueMeanings) {
    }

    public static List<SchemaFieldWithLabel> buildSchema(File pdfFile, File worksheetCsv) throws Exception {
        Worksheet worksheet = Worksheet.load(worksheetCsv);

        String actualHash = sha256Hex(pdfFile);
        if (!actualHash.equals(worksheet.sha256())) {
            throw new IllegalStateException(
                    "PDF has changed since the worksheet was generated. Worksheet hash: "
                            + worksheet.sha256() + ", actual PDF hash: " + actualHash
                            + ". Re-generate the worksheet or confirm the change is intentional.");
        }

        List<SchemaField> extracted = extractFields(pdfFile);
        Set<String> extractedFieldIds = extracted.stream().map(SchemaField::name).collect(Collectors.toSet());
        Set<String> worksheetFieldIds = worksheet.rowsByFieldId().keySet();

        Set<String> missingFromWorksheet = new TreeSet<>(extractedFieldIds);
        missingFromWorksheet.removeAll(worksheetFieldIds);
        Set<String> missingFromPdf = new TreeSet<>(worksheetFieldIds);
        missingFromPdf.removeAll(extractedFieldIds);

        if (!missingFromWorksheet.isEmpty() || !missingFromPdf.isEmpty()) {
            throw new IllegalStateException(
                    "Field-id mismatch between PDF and worksheet. In PDF but not worksheet: "
                            + missingFromWorksheet + ". In worksheet but not PDF: " + missingFromPdf);
        }

        Map<String, Set<String>> realOnValuesByFieldId = new java.util.HashMap<>();
        for (SchemaField f : extracted) {
            if (f.on() != null) {
                realOnValuesByFieldId.computeIfAbsent(f.name(), k -> new TreeSet<>()).add(f.on());
            }
        }

        List<String> onValueViolations = validateOnValueMeanings(realOnValuesByFieldId, worksheet.rowsByFieldId());
        if (!onValueViolations.isEmpty()) {
            throw new IllegalStateException("On_Value_Meanings validation failed. " + String.join(" ", onValueViolations));
        }

        List<SchemaFieldWithLabel> result = new ArrayList<>();
        for (SchemaField f : extracted) {
            Worksheet.WorksheetRow row = worksheet.rowsByFieldId().get(f.name());
            result.add(new SchemaFieldWithLabel(f, row.label(), row.rule(), row.exclusionFormId(), row.onValueMeanings()));
        }
        return result;
    }

    // Gate on *distinct on-values*, not widget count. migdal_health.pdf has real
    // multi-widget text fields (e.g. AgentName appears 4 times, SupervisorName 3
    // times, FirstNameChild1-4 twice each — confirmed during Task 6) whose widgets
    // never carry an on-value at all (SchemaField.on() is null for every text
    // widget). Gating on widget count would demand an On_Value_Meanings mapping
    // for those fields that could never satisfy the mismatch rule, since a
    // non-empty mapping can never equal an empty real-value set. The only
    // correct gate is realOnValuesByFieldId.get(fieldId).size() — the number of
    // distinct on-values — which a Set naturally captures.
    static List<String> validateOnValueMeanings(
            Map<String, Set<String>> realOnValuesByFieldId,
            Map<String, Worksheet.WorksheetRow> rowsByFieldId) {

        List<String> missingMandatory = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();

        for (String fieldId : rowsByFieldId.keySet()) {
            Set<String> realValues = realOnValuesByFieldId.getOrDefault(fieldId, Set.of());
            Map<String, String> meanings = rowsByFieldId.get(fieldId).onValueMeanings();

            if (realValues.size() >= 2 && meanings.isEmpty()) {
                missingMandatory.add(fieldId);
                continue;
            }
            if (!meanings.isEmpty()) {
                Set<String> worksheetKeys = new TreeSet<>(meanings.keySet());
                Set<String> pdfValues = new TreeSet<>(realValues);
                if (!worksheetKeys.equals(pdfValues)) {
                    mismatched.add(fieldId + " (worksheet keys: " + worksheetKeys + ", PDF on-values: " + pdfValues + ")");
                }
            }
        }

        List<String> violations = new ArrayList<>();
        if (!missingMandatory.isEmpty()) {
            java.util.Collections.sort(missingMandatory);
            violations.add("Missing On_Value_Meanings (mandatory — these fields have 2 or more distinct on-values): " + missingMandatory);
        }
        if (!mismatched.isEmpty()) {
            java.util.Collections.sort(mismatched);
            violations.add("On_Value_Meanings does not match the PDF's real on-values: " + mismatched);
        }
        return violations;
    }

    private static String sha256Hex(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(java.nio.file.Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static List<SchemaField> extractFields(File pdfFile) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            if (acroForm == null) {
                throw new IllegalStateException("No AcroForm in " + pdfFile.getName());
            }

            List<SchemaField> result = new ArrayList<>();
            for (PDField field : acroForm.getFieldTree()) {
                String name = field.getFullyQualifiedName();
                String type = canonicalType(field);
                List<PDAnnotationWidget> widgets = field.getWidgets();
                if (widgets.isEmpty()) {
                    throw new IllegalStateException("Field '" + name + "' has no widgets");
                }
                for (PDAnnotationWidget widget : widgets) {
                    result.add(extractWidget(doc, name, type, widget));
                }
            }
            return result;
        }
    }

    private static SchemaField extractWidget(PDDocument doc, String name, String type, PDAnnotationWidget widget) {
        PDPage page = widget.getPage();
        if (page == null) {
            throw new IllegalStateException("Widget of field '" + name + "' has no page");
        }
        int pageNumber = doc.getPages().indexOf(page) + 1;

        PDRectangle rect = widget.getRectangle();
        if (rect == null) {
            throw new IllegalStateException("Widget of field '" + name + "' has no rectangle");
        }

        String on = "signature".equals(type) ? null : readOnValue(widget);
        return new SchemaField(name, type, on, pageNumber,
                rect.getLowerLeftX(), rect.getLowerLeftY(), rect.getWidth(), rect.getHeight());
    }

    private static String readOnValue(PDAnnotationWidget widget) {
        COSDictionary ap = widget.getCOSObject().getCOSDictionary(COSName.AP);
        if (ap == null) {
            return null;
        }
        COSDictionary normalAppearance = ap.getCOSDictionary(COSName.N);
        if (normalAppearance == null) {
            return null;
        }
        for (COSName key : normalAppearance.keySet()) {
            if (!key.getName().equals("Off")) {
                return key.getName();
            }
        }
        return null;
    }

    private static String canonicalType(PDField field) {
        String raw = field.getClass().getSimpleName();
        if (raw.equals("PDCheckBox") || raw.equals("PDRadioButton")) {
            return "checkbox";
        }
        if (raw.equals("PDTextField")) {
            return "text";
        }
        if (raw.equals("PDSignatureField")) {
            return "signature";
        }
        throw new IllegalStateException("Unrecognized field type '" + raw + "' for field '" + field.getFullyQualifiedName() + "'");
    }

    public static void renderPages(File pdfFile, File outputDir, String formName, int dpi) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi);
                File out = new File(outputDir, formName + "_p" + (i + 1) + ".webp");
                boolean wrote = ImageIO.write(image, "webp", out);
                if (!wrote) {
                    throw new IllegalStateException("No WebP writer available for page " + (i + 1));
                }
            }
        }
    }

    public static void writeSchema(File pdfFile, File worksheetCsv, File outputDir,
                                    String formName, String company, int dpi) throws Exception {
        List<SchemaFieldWithLabel> fields = buildSchema(pdfFile, worksheetCsv);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("form", formName);
        root.put("company", company);

        ArrayNode pages = root.putArray("pages");
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDRectangle mediaBox = doc.getPage(i).getMediaBox();
                double scale = dpi / 72.0;
                ObjectNode page = pages.addObject();
                page.put("page", i + 1);
                page.put("image", formName + "_p" + (i + 1) + ".webp");
                page.put("w", (int) Math.round(mediaBox.getWidth() * scale));
                page.put("h", (int) Math.round(mediaBox.getHeight() * scale));
            }
        }

        ArrayNode fieldsNode = root.putArray("fields");
        for (SchemaFieldWithLabel swl : fields) {
            SchemaField f = swl.field();
            ObjectNode node = fieldsNode.addObject();
            node.put("name", f.name());
            node.put("type", f.type());
            if (f.on() != null) {
                node.put("on", f.on());
                String meaning = swl.onValueMeanings().get(f.on());
                if (meaning != null) {
                    node.put("onMeaning", meaning);
                }
            }
            node.put("page", f.page());
            ArrayNode rect = node.putArray("rect");
            rect.add(f.x()).add(f.y()).add(f.w()).add(f.h());
            if (!swl.label().isBlank()) {
                node.put("label", swl.label());
            }
            if (swl.rule()) {
                node.put("rule", true);
            }
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outputDir, formName + ".json"), root);
        renderPages(pdfFile, outputDir, formName, dpi);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("Usage: BuildSchema <pdf> <worksheet.csv> <outputDir> <formName> <company> [dpi]");
            System.exit(1);
        }
        File pdf = new File(args[0]);
        File worksheet = new File(args[1]);
        File outputDir = new File(args[2]);
        outputDir.mkdirs();
        String formName = args[3];
        String company = args[4];
        int dpi = args.length > 5 ? Integer.parseInt(args[5]) : 150;
        writeSchema(pdf, worksheet, outputDir, formName, company, dpi);
        System.out.println("Wrote schema and " + args[3] + "'s page images to " + outputDir);
    }
}
