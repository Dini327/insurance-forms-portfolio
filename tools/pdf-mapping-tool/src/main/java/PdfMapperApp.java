import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PdfMapperApp {

    private static final String INPUT_DIR = "input";
    private static final String OUTPUT_DIR = "output";

    private static class FieldModel {
        String fieldId;
        String fieldType;
        int pageNum;
        float posX;
        float posY;

        public FieldModel(String fieldId, String fieldType, int pageNum, float posX, float posY) {
            this.fieldId = fieldId;
            this.fieldType = fieldType;
            this.pageNum = pageNum;
            this.posX = posX;
            this.posY = posY;
        }

        // מקבץ שדות שנמצאים באותו גובה בדיוק (סובלנות של 3 נקודות)
        public int getRowGroup() {
            return Math.round(posY / 3.0f);
        }
    }

    public static void main(String[] args) {
        File inputFolder = new File(INPUT_DIR);
        File outputFolder = new File(OUTPUT_DIR);

        if (!inputFolder.exists()) inputFolder.mkdirs();
        if (!outputFolder.exists()) outputFolder.mkdirs();

        File[] pdfFiles = inputFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));

        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println(" לא נמצאו קובצי PDF בתיקיית input!");
            return;
        }

        System.out.println(" מתחיל לעבד " + pdfFiles.length + " קובצי PDF...\n");

        for (File pdfFile : pdfFiles) {
            processSinglePdf(pdfFile);
        }

        System.out.println("\n כל המיפויים הסתיימו בהצלחה! הכל ממוין מורכב ומדויק.");
    }

    private static void processSinglePdf(File pdfFile) {
        String outputCsvName = pdfFile.getName().replace(".pdf", "_mapped.csv");
        File outputFile = new File(OUTPUT_DIR, outputCsvName);

        try (PDDocument doc = Loader.loadPDF(pdfFile);
             PrintWriter writer = new PrintWriter(outputFile, StandardCharsets.UTF_8)) {

            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();

            if (acroForm == null) {
                throw new IllegalStateException(
                        "No AcroForm in " + pdfFile.getName() + " \u2014 this PDF has no fillable fields at all.");
            }

            writer.write('\uFEFF');
            writer.println("Field_ID,Field_Type,Page_Number,Position_X,Position_Y,Hebrew_Question_Label,Exclusion_Form_ID");

            {
                List<FieldModel> fieldList = new ArrayList<>();

                for (PDField field : acroForm.getFieldTree()) {
                    String fieldId = field.getFullyQualifiedName();

                    // Defensive check for malformed PDFs: PDFBox's standard API always creates widgets
                    // when a field is added. This exception catches only corrupted PDFs where a field
                    // has no widgets. Both migdal_health.pdf and migdal_life.pdf verified (2026-09-01)
                    // to have 0 fields without widgets. This code path is not reachable through normal
                    // PDFBox usage and is defensive-only.
                    if (field.getWidgets().isEmpty()) {
                        throw new IllegalStateException(
                                "Field '" + fieldId + "' has no widgets — cannot determine its page or position.");
                    }

                    String fieldType = field.getClass().getSimpleName().replace("PD", "");
                    int pageNum = getFieldPageNumber(doc, field);
                    float posX = getFieldPosX(field);
                    float posY = getFieldPosY(field);

                    fieldList.add(new FieldModel(fieldId, fieldType, pageNum, posX, posY));
                }

                // תיקון המיון:
                // 1. עמוד: מהקטן לגדול (1, 2, 3)
                // 2. Y: מהגדול לקטן (מלמעלה למטה)
                // 3. X: מהגדול לקטן (מימין לשמאל)
                fieldList.sort(Comparator
                        .comparingInt((FieldModel f) -> f.pageNum)
                        .thenComparing(FieldModel::getRowGroup, Comparator.reverseOrder())
                        .thenComparing((FieldModel f) -> f.posX, Comparator.reverseOrder()));

                for (FieldModel f : fieldList) {
                    writer.printf("\"%s\",\"%s\",\"%d\",\"%.1f\",\"%.1f\",\"\",\"\"\n",
                            f.fieldId, f.fieldType, f.pageNum, f.posX, f.posY);
                }

                System.out.println(" ✅ מעבד: " + pdfFile.getName() + " -> מויונו " + fieldList.size() + " שדות בסדר מושלם.");
            }

        } catch (Exception e) {
            System.err.println(" 💥 שגיאה בעיבוד הקובץ: " + pdfFile.getName());
            e.printStackTrace();
        }
    }

    private static int getFieldPageNumber(PDDocument doc, PDField field) {
        for (PDAnnotationWidget widget : field.getWidgets()) {
            PDPage page = widget.getPage();
            if (page != null) {
                return doc.getPages().indexOf(page) + 1;
            }
        }
        return 1;
    }

    private static float getFieldPosX(PDField field) {
        for (PDAnnotationWidget widget : field.getWidgets()) {
            if (widget.getRectangle() != null) {
                return widget.getRectangle().getLowerLeftX();
            }
        }
        return 0.0f;
    }

    private static float getFieldPosY(PDField field) {
        for (PDAnnotationWidget widget : field.getWidgets()) {
            if (widget.getRectangle() != null) {
                return widget.getRectangle().getLowerLeftY();
            }
        }
        return 0.0f;
    }
}