import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BuildSchema {

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
}
