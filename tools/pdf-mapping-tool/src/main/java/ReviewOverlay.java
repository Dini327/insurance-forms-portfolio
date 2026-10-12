import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.*;

/**
 * Dev-only review aid for Task 9's "AI draft + human correction" workflow: renders each
 * page of a mapped PDF with every widget's box drawn, numbered to match the review
 * worksheet's row numbers, and labeled with its own on-value where it has one. Not part
 * of the production pipeline (BuildSchema/writeSchema) and not shipped — purely so a
 * human can visually confirm a row number corresponds to the right box on the real form.
 */
public class ReviewOverlay {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: ReviewOverlay <pdf> <rowOrderWorksheetCsv> <outputDir> [dpi]");
            System.exit(1);
        }
        File pdf = new File(args[0]);
        File worksheetCsv = new File(args[1]);
        File outputDir = new File(args[2]);
        outputDir.mkdirs();
        int dpi = args.length > 3 ? Integer.parseInt(args[3]) : 200;

        List<String> rowOrder = readFieldIdOrder(worksheetCsv);
        Map<String, Integer> rowNumberByFieldId = new HashMap<>();
        for (int i = 0; i < rowOrder.size(); i++) {
            rowNumberByFieldId.put(rowOrder.get(i), i + 1);
        }

        List<SchemaField> widgets = BuildSchema.extractFields(pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            double scale = dpi / 72.0;

            for (int pageIndex = 0; pageIndex < doc.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi);
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setFont(new Font("Arial", Font.BOLD, 13));

                PDRectangle mediaBox = doc.getPage(pageIndex).getMediaBox();
                double pageHeightPoints = mediaBox.getHeight();

                for (SchemaField w : widgets) {
                    if (w.page() != pageNumber) continue;
                    Integer rowNum = rowNumberByFieldId.get(w.name());
                    if (rowNum == null) {
                        throw new IllegalStateException("Widget field-id not found in worksheet row order: " + w.name());
                    }

                    double xPx = w.x() * scale;
                    double yTopPx = (pageHeightPoints - w.y() - w.h()) * scale;
                    double wPx = w.w() * scale;
                    double hPx = w.h() * scale;

                    g.setColor(Color.RED);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRect((int) xPx, (int) yTopPx, (int) Math.max(wPx, 1), (int) Math.max(hPx, 1));

                    String label = String.valueOf(rowNum);
                    if (w.on() != null) {
                        label += ":" + w.on();
                    }

                    int textX = (int) xPx;
                    int textY = (int) yTopPx - 3;
                    if (textY < 12) {
                        textY = (int) (yTopPx + hPx + 13);
                    }

                    FontMetrics fm = g.getFontMetrics();
                    int textW = fm.stringWidth(label);
                    g.setColor(Color.WHITE);
                    g.fillRect(textX, textY - fm.getAscent(), textW + 2, fm.getAscent() + fm.getDescent());
                    g.setColor(Color.BLUE);
                    g.drawString(label, textX, textY);
                }

                g.dispose();
                File out = new File(outputDir, "review_p" + pageNumber + ".png");
                boolean wrote = ImageIO.write(image, "png", out);
                if (!wrote) {
                    throw new IllegalStateException("No PNG writer available for page " + pageNumber);
                }
                System.out.println("Wrote " + out + " (" + image.getWidth() + "x" + image.getHeight() + ")");
            }
        }
    }

    /** Reads the Field_ID column, in file order, skipping the #SHA256 metadata line and header. */
    private static List<String> readFieldIdOrder(File csv) throws Exception {
        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\r?\n");
        List<String> ids = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int firstComma = findUnquotedComma(line);
            String idField = firstComma >= 0 ? line.substring(0, firstComma) : line;
            idField = idField.trim();
            if (idField.startsWith("\"") && idField.endsWith("\"") && idField.length() >= 2) {
                idField = idField.substring(1, idField.length() - 1);
            }
            if (!idField.isBlank()) {
                ids.add(idField);
            }
        }
        return ids;
    }

    private static int findUnquotedComma(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) return i;
        }
        return -1;
    }
}
