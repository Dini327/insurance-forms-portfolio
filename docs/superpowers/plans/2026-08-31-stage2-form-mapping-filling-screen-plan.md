# Stage 2 Implementation Plan — Form Mapping and the Insured's Filling Screen

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Map `migdal_health.pdf` into the schema format CLAUDE.md defines (Phase A), and build the insured's token-based filling screen that renders that schema and accepts a structurally-validated, never-stored submission (Phase B).

**Architecture:** Two independent tracks with no shared dependencies. Phase A is a standalone Maven tool (`/tools/pdf-mapping-tool`) that reads a blank PDF with PDFBox and emits a hand-taggable CSV worksheet, then a JSON schema + WebP page images. Phase B is the existing Spring Boot backend (new `submission`/`insurance_form` tables, a third token-based `SecurityFilterChain`, type-aware structural validation) and the existing React frontend (a new company/type picker and a new schema-driven overlay screen). Phase A produces static files Phase B only reads — the backend does not touch PDFBox in this stage.

**Tech Stack:** Apache PDFBox 3.0.1 (Phase A only), Java 17, Maven, Spring Boot 3.3.5 / Spring Data JPA / Spring Security, PostgreSQL + Flyway, React + TypeScript.

**Spec:** `docs/superpowers/specs/2026-08-31-stage2-form-mapping-filling-screen-design.md` — this plan implements that design exactly; read it in full before starting. This plan is the "how", the design doc is the "why" — when a step here seems to skip a justification, the reasoning is in the design doc's matching section.

## Global Constraints

- Apache PDFBox 3.x only for any PDF work, never pypdf/pdf-lib/PyMuPDF/iText. PDFBox 3.x loads via `Loader.loadPDF(...)`, not `PDDocument.load(...)`.
- A field-id is always a string in code, never coerced to a number (two real field-ids in `migdal_health.pdf` are the bare strings `"1"` and `"2"`).
- Insured answers are never persisted, logged, or echoed back in any response, at any point, including validation failures.
- The agent id used by any read/write always comes from the server-side session, never a request body/param/path value.
- The insured's token is stored only as a hash (SHA-256 hex), never in plaintext, in the database.
- CSRF stays enabled on every session/cookie-based `SecurityFilterChain`; it is disabled only on the token-based insured chain, which has no ambient cookie to protect.
- `rule` and `exclusion-form-id` are reserved columns in the worksheet/schema in this stage but stay blank for every field — no exclusion-rules table exists yet (verified absent from the repo during design).
- Every `Off` classification is derived from the field's actual on-value set read from the PDF — never assumed to be `"Off"`, `"No"`, or any other literal.
- Rendering: page images are WebP; the whole overlay scales via one CSS `transform: scale()`; every rendered input carries an `aria-label` from the schema's `label`.

---

## Phase A — the mapping tool (`/tools/pdf-mapping-tool`)

### Task 1: Relocate the existing prototype into the repo, unchanged

**Files:**
- Create: `tools/pdf-mapping-tool/pom.xml` (copied verbatim)
- Create: `tools/pdf-mapping-tool/src/main/java/PdfMapperApp.java` (copied verbatim)
- Create: `tools/pdf-mapping-tool/seeds/migdal_life_labels.csv` (copied and renamed from the prototype's Hebrew-named output file)
- Create: `tools/pdf-mapping-tool/.gitignore`

**Interfaces:**
- Consumes: nothing (this task moves code, it doesn't change it).
- Produces: a version-controlled baseline of `PdfMapperApp.java` that Task 2 onward edits in place. No later task creates a new mapper class — every correction is a diff on this file.

- [ ] **Step 1: Copy the prototype's source and build file into the repo**

```bash
mkdir -p tools/pdf-mapping-tool/src/main/java
cp "/c/Users/User/eclipse-workspace/pdf-mapping-tool/pom.xml" tools/pdf-mapping-tool/pom.xml
cp "/c/Users/User/eclipse-workspace/pdf-mapping-tool/src/main/java/PdfMapperApp.java" tools/pdf-mapping-tool/src/main/java/PdfMapperApp.java
```

Do not copy `.classpath`, `.project`, `.settings/`, or `target/` from the source — those are Eclipse/build artifacts, not source, and are excluded by Step 3's `.gitignore` the same way `backend/target` is already excluded from this repo.

- [ ] **Step 2: Copy and rename the migdal_life label seed**

```bash
mkdir -p tools/pdf-mapping-tool/seeds
cp "/c/Users/User/eclipse-workspace/pdf-mapping-tool/output/הצהרת בריאות ביטוח חיים_mapped.csv" "tools/pdf-mapping-tool/seeds/migdal_life_labels.csv"
```

Verify the copy landed correctly and still has 176 data rows with 77 labeled:

```bash
grep -c "^" tools/pdf-mapping-tool/seeds/migdal_life_labels.csv
```

Expected: `213` (1 header + 212 physical lines — several labels are multi-line quoted Hebrew text, so this is a *physical* line count, not a data-row count; Task 9's worksheet-parsing code is what correctly counts 176 logical rows / 77 labeled).

- [ ] **Step 3: Add `.gitignore` for build output**

```
target/
*.class
```

Write this to `tools/pdf-mapping-tool/.gitignore`.

- [ ] **Step 4: Verify the moved project still builds**

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit — this is the unmodified baseline**

```bash
git add tools/pdf-mapping-tool/pom.xml tools/pdf-mapping-tool/src tools/pdf-mapping-tool/seeds tools/pdf-mapping-tool/.gitignore
git commit -m "chore: import existing pdf-mapping-tool prototype as baseline, unmodified"
```

No logic changes in this commit — Task 2 onward correct the tool, each as its own diff on top of this baseline.

---

### Task 2: Fail loudly on `acroForm == null` and on a field with no widget

**Files:**
- Modify: `tools/pdf-mapping-tool/src/main/java/PdfMapperApp.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `processSinglePdf` now throws `IllegalStateException` instead of silently degrading, for the two conditions CLAUDE.md's "PDFBox fails silently" warning calls out.

- [ ] **Step 1: Add a manual reproduction of both failure modes before touching code**

This tool has no unit tests today (it's a `main()`-driven CLI). Rather than retrofit a test harness as a separate concern, verify each fix by direct execution against a deliberately bad input, per the steps below — consistent with how this tool is actually used (run once per form, output inspected by a human).

- [ ] **Step 2: Fix `acroForm == null` to abort instead of writing an empty CSV**

In `processSinglePdf`, replace:

```java
if (acroForm != null) {
```

with an early, loud failure:

```java
if (acroForm == null) {
    throw new IllegalStateException(
            "No AcroForm in " + pdfFile.getName() + " — this PDF has no fillable fields at all.");
}
```

and un-indent the existing `if` block's body to follow it directly (same logic, no longer conditional).

- [ ] **Step 3: Fix "no widget found" to abort instead of defaulting to page 1 / position (0,0)**

Replace `getFieldPageNumber`, `getFieldPosX`, and `getFieldPosY`'s silent fallback returns with a single loud check done once per field, before calling any of them. In the loop inside `processSinglePdf`, right after computing `fieldId`, add:

```java
if (field.getWidgets().isEmpty()) {
    throw new IllegalStateException(
            "Field '" + fieldId + "' has no widgets — cannot determine its page or position.");
}
```

`getFieldPageNumber`/`getFieldPosX`/`getFieldPosY` keep their existing bodies (they still return their `1`/`0.0f` fallback if somehow reached), but that path is now provably unreachable for any field that passed the check above — the fallback becomes dead code kept only as a defensive default, not a silent success path.

- [ ] **Step 4: Verify against the real files — this must still succeed cleanly**

```bash
mkdir -p tools/pdf-mapping-tool/input
cp forms/migdal_health.pdf "tools/pdf-mapping-tool/input/migdal_health.pdf"
cp forms/migdal_life.pdf "tools/pdf-mapping-tool/input/migdal_life.pdf"
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=PdfMapperApp
```

Expected: both files process without throwing, producing the same two CSVs as before (this only changes behavior on documents that would already have produced broken output).

**Deviation note:** the prototype's `input`/`output` folders duplicated the PDFs from `forms/`. From this task onward, `tools/pdf-mapping-tool/input/` holds working copies used only for manual runs during development — Task 9 is where the pipeline is pointed at `forms/` directly as its real input, removing this duplication. Do not commit the copies added in this step; add `tools/pdf-mapping-tool/input/` and `tools/pdf-mapping-tool/output/` to the `.gitignore` from Task 1 now:

```bash
printf "input/\noutput/\n" >> tools/pdf-mapping-tool/.gitignore
git add tools/pdf-mapping-tool/.gitignore
```

- [ ] **Step 5: Commit**

```bash
git add tools/pdf-mapping-tool/src tools/pdf-mapping-tool/.gitignore
git commit -m "fix: fail loudly on missing AcroForm or widgetless field instead of silent defaults"
```

---

### Task 3: Normalize `type` to a canonical string

**Files:**
- Modify: `tools/pdf-mapping-tool/src/main/java/PdfMapperApp.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `FieldModel.fieldType` now holds one of `"checkbox"`, `"text"`, `"signature"` — the vocabulary Task 6's `build-schema` and Phase B's structural validation both key off of. No other task reads the *old* raw values (`CheckBox`, `TextField`, `SignatureField`, `RadioButton`), so this is safe to change now.

- [ ] **Step 1: Replace the raw class-name extraction**

Current code:

```java
String fieldType = field.getClass().getSimpleName().replace("PD", "");
```

Replace with:

```java
String fieldType = canonicalType(field);
```

- [ ] **Step 2: Add the normalization method**

```java
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
    throw new IllegalStateException(
            "Unrecognized field type '" + raw + "' for field '" + field.getFullyQualifiedName() + "'");
}
```

Throwing on an unrecognized type (rather than passing the raw name through) is deliberate: a silently-passed-through raw type would reach Phase B's validation logic, which only recognizes `"checkbox"` and `"text"` — an unrecognized type must be caught here, at the one place that knows about PDFBox's class hierarchy, not discovered later as a mysterious validation bug.

- [ ] **Step 3: Verify against the real files**

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=PdfMapperApp
grep -o '"[A-Za-z]*",' tools/pdf-mapping-tool/output/*mapped.csv | sort -u
```

Expected: only `"checkbox",` and `"text",` appear (both PDFs' fields are checkboxes, radio-style checkboxes, or text fields — the 3 signature fields per form are also present but as `SignatureField` type; confirm `"signature",` also appears exactly 3 times per file: `grep -c '"signature",' tools/pdf-mapping-tool/output/*mapped.csv` should print `3` for each).

- [ ] **Step 4: Commit**

```bash
git add tools/pdf-mapping-tool/src
git commit -m "fix: normalize field type to canonical checkbox/text/signature vocabulary"
```

---

### Task 4: Verify a WebP writer actually works — empirically, before building on it

**Files:**
- Create: `tools/pdf-mapping-tool/pom.xml` (dependency addition)
- Create: `tools/pdf-mapping-tool/src/main/java/WebpWriterSpike.java` (throwaway verification class, deleted at the end of this task)

**Interfaces:**
- Consumes: nothing.
- Produces: a confirmed, working WebP-writing dependency that Task 8 adds to the real pipeline. If this task's first candidate fails, it tries the documented fallback before this task is considered done — Task 8 must not start until this task has a verified-working answer.

- [ ] **Step 1: Add the first candidate — TwelveMonkeys' WebP plugin**

Add to `tools/pdf-mapping-tool/pom.xml`:

```xml
<dependency>
    <groupId>com.twelvemonkeys.imageio</groupId>
    <artifactId>imageio-webp</artifactId>
    <version>3.11.0</version>
</dependency>
```

- [ ] **Step 2: Write a spike that writes a real file, not just checks for a writer object**

```java
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;

public class WebpWriterSpike {
    public static void main(String[] args) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("webp");
        if (!writers.hasNext()) {
            System.out.println("NO WEBP WRITER REGISTERED");
            return;
        }
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.setColor(java.awt.Color.BLACK);
        g.drawString("webp write spike", 10, 50);
        g.dispose();

        File out = new File("webp-spike-output.webp");
        boolean wrote = ImageIO.write(image, "webp", out);
        System.out.println("ImageIO.write returned: " + wrote);
        System.out.println("File size: " + out.length() + " bytes");
    }
}
```

- [ ] **Step 2: Run it**

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=WebpWriterSpike
```

- [ ] **Step 3: The pass criterion is a human opening the file, not the program's exit code**

Open `webp-spike-output.webp` in a real image viewer (or an OS file-preview pane). Confirm it opens and shows the white rectangle with visible text, not a corrupt-file error.

**If this fails** (no writer registered, `ImageIO.write` returns `false`, or the file doesn't open): do not proceed to a workaround inside this task. Try the documented fallback — `org.sejda.imageio:webp-imageio` — by repeating Steps 1–3 with that dependency instead. If *that* also fails, stop and escalate back to a design conversation before Task 8: CLAUDE.md already anticipated this ("needs its own decision when stage 2 gets there, not an assumption"), and a genuinely absent write path changes Phase A's image format decision, which is bigger than this task.

- [ ] **Step 4: Record which dependency actually worked, and remove the spike**

```bash
rm tools/pdf-mapping-tool/src/main/java/WebpWriterSpike.java
rm webp-spike-output.webp
```

Leave the working dependency in `pom.xml` — Task 8 uses it.

- [ ] **Step 5: Commit**

```bash
git add tools/pdf-mapping-tool/pom.xml tools/pdf-mapping-tool/src
git commit -m "spike: verify a WebP writer works empirically before building on it"
```

---

### Task 5: Turn the extractor into a worksheet-skeleton generator

**Files:**
- Modify: `tools/pdf-mapping-tool/src/main/java/PdfMapperApp.java`

**Interfaces:**
- Consumes: the corrected `canonicalType` from Task 3.
- Produces: a worksheet CSV whose first row is a `#SHA256:<hex>` metadata line, followed by the existing header, followed by one row per unique field-id (already true today via `getFieldTree()` — this task adds the hash line and a duplicate-field-id regression test, it does not change the per-field grouping). Consumed by Task 7's `build-schema`, which matches worksheet field-ids against a fresh PDF read using this same hash to detect drift.

- [ ] **Step 1: Add a SHA-256 helper**

```java
private static String sha256Hex(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(java.nio.file.Files.readAllBytes(file.toPath()));
    StringBuilder hex = new StringBuilder();
    for (byte b : hash) {
        hex.append(String.format("%02x", b));
    }
    return hex.toString();
}
```

- [ ] **Step 2: Write the hash as the worksheet's first line**

In `processSinglePdf`, immediately after opening `writer` and before writing the CSV header, add:

```java
writer.println("#SHA256:" + sha256Hex(pdfFile));
```

- [ ] **Step 3: Verify no duplicate field-ids are ever emitted**

This is a genuine regression risk worth checking explicitly, since Task 7 will trust the worksheet's field-id set as ground truth for matching: after a run, confirm the field-id column has no duplicates.

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=PdfMapperApp
tail -n +3 "tools/pdf-mapping-tool/output/migdal_health_mapped.csv" | cut -d',' -f1 | sort | uniq -d
```

(`tail -n +3` skips the new hash line and the header.) Expected: empty output — no duplicate field-ids.

- [ ] **Step 4: Commit**

```bash
git add tools/pdf-mapping-tool/src
git commit -m "feat: record source PDF hash in worksheet for later drift detection"
```

---

### Task 6: `build-schema` — per-widget extraction (rect, on-value, page dimensions)

**Files:**
- Create: `tools/pdf-mapping-tool/src/main/java/BuildSchema.java`
- Create: `tools/pdf-mapping-tool/src/main/java/SchemaField.java`

**Interfaces:**
- Consumes: nothing from `PdfMapperApp` — reads the PDF directly and independently, per the design doc's "re-derives every mechanical value fresh from the PDF" rule.
- Produces: `SchemaField(String name, String type, String on, int page, double x, double y, double w, double h)` — one instance per **widget**, not per field. `extractFields(File pdf): List<SchemaField>`, consumed by Task 7's worksheet-join and Task 8's final JSON writer.

- [ ] **Step 1: Define `SchemaField`**

```java
public record SchemaField(
        String name,
        String type,
        String on,
        int page,
        double x,
        double y,
        double w,
        double h) {
}
```

- [ ] **Step 2: Write `BuildSchema.extractFields`, one entry per widget**

```java
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
```

- [ ] **Step 3: Verify the widget count against ground truth**

```java
// temporary check, run via exec:java with -Dexec.args, or a quick main() —
// remove after confirming, this is a verification step, not shipped code
public static void main(String[] args) throws Exception {
    List<SchemaField> fields = extractFields(new File(args[0]));
    System.out.println("Total widgets: " + fields.size());
}
```

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=BuildSchema -Dexec.args="../../forms/migdal_health.pdf"
```

Expected: `Total widgets: 421` — the ground truth verified during design. Remove the temporary `main` method once this passes (Task 7 adds the real entry point).

- [ ] **Step 4: Verify on-value conventions against ground truth**

```java
Map<String, Long> onCounts = fields.stream()
        .filter(f -> f.on() != null)
        .collect(Collectors.groupingBy(SchemaField::on, Collectors.counting()));
onCounts.forEach((k, v) -> System.out.println(k + ": " + v));
```

Expected to see `1: <n>`, `2: <n>`, `True`/`False`, and `Yes` appear, consistent with the design doc's verified breakdown (144 fields on the `1`/`2` convention, 12 on `True`/`False`, 12 on `Yes` — note this counts *widgets*, not fields, so raw numbers will be higher than the per-field counts in the design doc; the point of this check is that no *unexpected* on-value string appears, not matching an exact count).

- [ ] **Step 5: Commit**

```bash
git add tools/pdf-mapping-tool/src/main/java/BuildSchema.java tools/pdf-mapping-tool/src/main/java/SchemaField.java
git commit -m "feat: add per-widget field extraction with real on-values, rect, and page"
```

---

### Task 7: `build-schema` — worksheet join, SHA-256 guard, fail-loud field-id matching

**Files:**
- Create: `tools/pdf-mapping-tool/src/main/java/Worksheet.java`
- Modify: `tools/pdf-mapping-tool/src/main/java/BuildSchema.java`

**Interfaces:**
- Consumes: `SchemaField` from Task 6; the worksheet CSV format from Task 5 (`#SHA256:<hex>` line, header, one row per unique field-id with `label`/`rule`/`exclusion-form-id`).
- Produces: `Worksheet.load(File csv): Worksheet` with `String sha256()` and `Map<String,WorksheetRow> rowsByFieldId()`; `WorksheetRow(String label, boolean rule, String exclusionFormId)`. `BuildSchema.buildSchema(File pdf, File worksheet): List<SchemaFieldWithLabel>` — consumed by Task 8, which only adds page-image rendering and JSON serialization on top.

- [ ] **Step 1: Write `Worksheet` — a quote-aware CSV reader**

A naive line-splitter is wrong here — Task 9's real worksheet will contain multi-line quoted Hebrew labels (already proven true of the `migdal_life` seed CSV during design). This parser must handle quoted fields spanning multiple physical lines.

```java
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
```

- [ ] **Step 2: Add the join, hash guard, and field-id set matching to `BuildSchema`**

```java
public record SchemaFieldWithLabel(SchemaField field, String label, boolean rule, String exclusionFormId) {
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

    List<SchemaFieldWithLabel> result = new ArrayList<>();
    for (SchemaField f : extracted) {
        Worksheet.WorksheetRow row = worksheet.rowsByFieldId().get(f.name());
        result.add(new SchemaFieldWithLabel(f, row.label(), row.rule(), row.exclusionFormId()));
    }
    return result;
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
```

(Add `import java.util.Set;`, `import java.util.TreeSet;`, `import java.util.stream.Collectors;` to the top of the file.)

- [ ] **Step 2: Verify the positive case — a normal, BOM-prefixed worksheet still hash-matches**

This is the counterpart to Step 4's negative control below: Step 4 proves a *tampered* file is correctly rejected; this step proves the *ordinary, untampered* case is correctly accepted despite the leading BOM every worksheet actually has (Task 5's writer always emits one). Without this check, a BOM-stripping bug could silently reject every real worksheet and nothing in the test suite would catch it — Step 4 alone would still pass, since a broken strip and a genuinely-mismatched hash both throw, just for different reasons.

Run `buildSchema` against the real, unmodified `migdal_health.pdf` and its real worksheet from Task 5 (`tools/pdf-mapping-tool/output/migdal_health_mapped.csv`, which — like every worksheet this tool produces — starts with a UTF-8 BOM before `#SHA256:`).

`buildSchema(File pdf, File worksheetCsv)` takes exactly two arguments — this task does not add page rendering, an output directory, or a form/company name (that's Task 8's job on top of this one). Add a temporary `main(String[] args)` that calls `buildSchema(new File(args[0]), new File(args[1]))` and prints the result size, same throwaway-verification pattern as Task 6, removed again before commit:

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=BuildSchema \
  -Dexec.args="forms/migdal_health.pdf tools/pdf-mapping-tool/output/migdal_health_mapped.csv"
```

Expected: exits cleanly, no hash-mismatch exception — proving the BOM is stripped correctly and the hash comparison succeeds on the normal case, not just correctly fails on the tampered one.

- [ ] **Step 3: Verify the mismatch case fails loudly**

Manually edit one field-id in a copy of `migdal_health`'s worksheet CSV (e.g. rename one row's field-id to `TYPO_FIELD`), then run `buildSchema` against it via a temporary `main`:

```bash
cp tools/pdf-mapping-tool/output/migdal_health_mapped.csv /tmp/broken_worksheet.csv
# manually edit one field-id in /tmp/broken_worksheet.csv, e.g. change "AptNumber" to "AptNumberTypo"
```

Expected: `IllegalStateException` naming both `AptNumber` (missing from worksheet) and `AptNumberTypo` (missing from PDF).

- [ ] **Step 4: Verify the hash-mismatch case fails loudly**

Copy `migdal_health.pdf`, flip one byte (e.g. append a byte with `printf '\0' >> copy.pdf`), and confirm `buildSchema` throws citing the hash mismatch before attempting any field extraction on the modified file.

- [ ] **Step 5: Commit**

```bash
git add tools/pdf-mapping-tool/src/main/java/Worksheet.java tools/pdf-mapping-tool/src/main/java/BuildSchema.java
git commit -m "feat: join worksheet labels by field-id with SHA-256 and field-id-set guards"
```

---

### Task 8: `build-schema` — render WebP page images and write the final JSON schema

**Files:**
- Modify: `tools/pdf-mapping-tool/src/main/java/BuildSchema.java`
- Modify: `tools/pdf-mapping-tool/pom.xml` (Jackson dependency for JSON writing)

**Interfaces:**
- Consumes: `SchemaFieldWithLabel` from Task 7; the verified WebP dependency from Task 4.
- Produces: `<output>/<form>.json` matching CLAUDE.md's schema format exactly, and `<output>/<form>_p<n>.webp` per page. Consumed directly by Phase B (Task 12 copies these into the backend's static resources — no code-level dependency, just files).

- [ ] **Step 1: Add Jackson for JSON writing**

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.2</version>
</dependency>
```

- [ ] **Step 2: Render each page to WebP**

```java
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

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
```

- [ ] **Step 3: Write the JSON schema matching CLAUDE.md's format exactly**

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
```

Note: `rect` here is written in PDF points (the extracted `x/y/w/h`), *not* pre-scaled to the render DPI — Phase B's frontend (Task 23) does the points→pixels conversion at render time using `pages[].w`/`h` (which *are* in render pixels) to derive the scale factor. This keeps `rect` and page dimensions in two different, individually-consistent unit systems, each documented at the point of use, rather than silently mixing them in one field.

- [ ] **Step 4: Add a real `main` entry point**

```java
public static void main(String[] args) throws Exception {
    if (args.length < 4) {
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
```

- [ ] **Step 5: Commit**

```bash
git add tools/pdf-mapping-tool/pom.xml tools/pdf-mapping-tool/src/main/java/BuildSchema.java
git commit -m "feat: render WebP page images and write the final JSON schema"
```

---

### Task 9: Run the real pipeline against `migdal_health.pdf`, hand-tag labels, record mapping time

This task is mostly manual work, not code — the pass criteria are a human looking at output, per CLAUDE.md's "never claim something works until verified the way it will actually be used."

**Files:**
- Create: `tools/pdf-mapping-tool/worksheets/migdal_health_worksheet.csv` (generated, then hand-edited)
- Create: `tools/pdf-mapping-tool/output/migdal_health.json` and `tools/pdf-mapping-tool/output/migdal_health_p{1,2,3}.webp` (generated)

- [ ] **Step 1: Start the clock**

Note the wall-clock start time before beginning Step 3's hand-tagging — this is what gets recorded in Step 6.

- [ ] **Step 2: Generate the worksheet skeleton against the real form**

```bash
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=PdfMapperApp
cp "tools/pdf-mapping-tool/output/migdal_health_mapped.csv" "tools/pdf-mapping-tool/worksheets/migdal_health_worksheet.csv"
```

- [ ] **Step 3: Hand-tag `label` for every field, cross-checking the 27 non-semantic field-ids by position**

Open the worksheet and `tools/pdf-mapping-tool/output/` alongside a viewer for `forms/migdal_health.pdf` itself (the real page images don't exist yet at this point — use the source PDF directly, opened in any PDF viewer, for this pass). Fill in the `Hebrew_Question_Label` column for every row. Pay particular attention to the 27 field-ids the design doc identifies as carrying no semantic hint in their name — confirm each by its position on the page, not its name: `1`, `2`, `Check Box1`, `Check Box2`, `Check Box8`, `Check Box10`, `Check Box11`, `Check Box12`, `Check Box13`, `Check Box14`, `Check Box55`, `Check Box56`, `Tedfdxt2`, `Text1`, `Text2`, `Text3`, `Text4`, `Text5`, `Text21`, `Text28`, `Text35`, `Text42`, `Text73`, `Text75`, `Text103`, `Text317`, `Textadsad2`.

Leave `rule` and `exclusion-form-id` blank for every row — per the design doc, no exclusion-rules table exists yet, and populating those columns is stage 4's work.

For a question repeated across the six main/spouse/child variants (e.g. `HealthDecMainQ7`/`HealthDecBzugQ7`/`HealthDecC1Q7`..`C4Q7`), write the same label text into all six rows — `build-schema` does not auto-propagate a label across different field-ids (only across widgets sharing one field-id, which is a different thing). Copy-pasting six times is a hand-tagging convenience, not a code feature; Task 23's `variantSuffix` appends the main/spouse/child identifier to each rendered input's `aria-label` at render time, on top of whatever label text is here.

- [ ] **Step 4: Stop the clock and record the mapping time**

Record elapsed time from Step 1 to the end of Step 3 (label-tagging only — not the automated extraction, not this task's remaining steps) in this plan file, as a line directly below this task's heading:

> **Mapping time recorded:** `label`-column hand-tagging of `migdal_health.pdf`'s 251 fields took **[fill in actual elapsed time]**.

- [ ] **Step 5: Run `build-schema` against the real form and the hand-tagged worksheet**

```bash
mkdir -p tools/pdf-mapping-tool/output
mvn -f tools/pdf-mapping-tool/pom.xml compile exec:java -Dexec.mainClass=BuildSchema \
  -Dexec.args="forms/migdal_health.pdf tools/pdf-mapping-tool/worksheets/migdal_health_worksheet.csv tools/pdf-mapping-tool/output migdal_health migdal 150"
```

Expected: exits cleanly, producing `migdal_health.json`, `migdal_health_p1.webp`, `migdal_health_p2.webp`, `migdal_health_p3.webp` in `tools/pdf-mapping-tool/output/`.

- [ ] **Step 6: Human review of the output — required, not optional**

1. Open all three `.webp` files in an image viewer. Confirm each shows the correct page of the real form, not a blank or corrupted image.
2. Open `migdal_health.json` and spot-check: total `fields` array length is 421 (one per widget); a known yes/no field (e.g. `IsSmoking`) appears as two separate entries sharing `"name": "IsSmoking"`, one with `"on": "1"` and one with `"on": "2"`; the three signature fields (`ClientMust1`, `SpouseMust1`, `AgentMust1`) appear with `"type": "signature"` and no `on` key; `Textadsad2` and `Tedfdxt2` carry the labels written in Step 3, not blank ones.
3. Report explicitly which of these checks were actually performed and what was observed — per CLAUDE.md's "never claim something works until verified the way it will actually be used."

- [ ] **Step 7: Commit**

```bash
git add tools/pdf-mapping-tool/worksheets tools/pdf-mapping-tool/output
git commit -m "feat: map migdal_health.pdf — worksheet, schema, and page images"
```

---

## Phase B — the insured's filling screen

### Task 10: Migrations for `insurance_form` and `submission`

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__create_insurance_form_table.sql`
- Create: `backend/src/main/resources/db/migration/V5__create_submission_table.sql`
- Test: `backend/src/test/java/com/insuranceforms/MigrationTest.java` (existing — extend if it asserts table counts)

**Interfaces:**
- Consumes: nothing.
- Produces: the `insurance_form` and `submission` tables. `InsuranceForm`/`Submission` JPA entities (Task 11/14) map onto these exactly.

- [ ] **Step 1: Write the `insurance_form` migration**

```sql
CREATE TABLE insurance_form (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company           text NOT NULL,
    insurance_type    text NOT NULL,
    schema_path       text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Write the `submission` migration**

```sql
CREATE TABLE submission (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id            uuid NOT NULL REFERENCES agent(id),
    insurance_form_id   uuid NOT NULL REFERENCES insurance_form(id),
    insured_name        text NOT NULL,
    insured_email       text NOT NULL,
    token_hash          text NOT NULL UNIQUE,
    status              text NOT NULL DEFAULT 'PENDING',
    sent_at             timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    filled_at           timestamptz NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_submission_agent_id ON submission(agent_id);
```

The `idx_submission_agent_id` index is added now, not as an afterthought — every agent-facing query in this stage filters by `agent_id` (the tenant-isolation column), and this table will be the one most frequently queried by it.

- [ ] **Step 3: Run and verify migrations apply cleanly**

```bash
mvn -f backend/pom.xml test -Dtest=MigrationTest
```

Expected: PASS (Flyway applies V4 and V5 against the Testcontainers Postgres instance with no errors).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration
git commit -m "feat: add insurance_form and submission tables"
```

---

### Task 11: `InsuranceForm` entity, repository, and the seeded `migdal_health` row

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/form/InsuranceForm.java`
- Create: `backend/src/main/java/com/insuranceforms/form/InsuranceFormRepository.java`
- Create: `backend/src/main/resources/db/migration/V6__seed_migdal_health_insurance_form.sql`
- Test: `backend/src/test/java/com/insuranceforms/form/InsuranceFormRepositoryTest.java`

**Interfaces:**
- Consumes: the `insurance_form` table from Task 10.
- Produces: `InsuranceForm(company, insuranceType, schemaPath)`, `InsuranceFormRepository extends JpaRepository<InsuranceForm, UUID>` with `List<InsuranceForm> findAll()` (used by Task 22's picker) — consumed by Task 15 (create submission) and Task 19 (insured GET).

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.form;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class InsuranceFormRepositoryTest {

    @Autowired
    private InsuranceFormRepository insuranceFormRepository;

    @Test
    void migdalHealthIsSeeded() {
        var forms = insuranceFormRepository.findAll();
        assertThat(forms).anySatisfy(f -> {
            assertThat(f.getCompany()).isEqualTo("migdal");
            assertThat(f.getInsuranceType()).isEqualTo("health");
            assertThat(f.getSchemaPath()).isEqualTo("/forms/migdal_health/migdal_health.json");
        });
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=InsuranceFormRepositoryTest
```

Expected: FAIL — compile error, `InsuranceForm`/`InsuranceFormRepository` don't exist yet.

- [ ] **Step 3: Write the entity**

```java
package com.insuranceforms.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "insurance_form")
public class InsuranceForm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company", nullable = false)
    private String company;

    @Column(name = "insurance_type", nullable = false)
    private String insuranceType;

    @Column(name = "schema_path", nullable = false)
    private String schemaPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected InsuranceForm() {
    }

    public UUID getId() { return id; }
    public String getCompany() { return company; }
    public String getInsuranceType() { return insuranceType; }
    public String getSchemaPath() { return schemaPath; }
}
```

- [ ] **Step 4: Write the repository**

```java
package com.insuranceforms.form;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InsuranceFormRepository extends JpaRepository<InsuranceForm, UUID> {
}
```

- [ ] **Step 5: Write the seed migration**

```sql
INSERT INTO insurance_form (company, insurance_type, schema_path)
VALUES ('migdal', 'health', '/forms/migdal_health/migdal_health.json');
```

- [ ] **Step 6: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=InsuranceFormRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/form backend/src/main/resources/db/migration/V6__seed_migdal_health_insurance_form.sql backend/src/test/java/com/insuranceforms/form
git commit -m "feat: add InsuranceForm entity and seed the migdal_health row"
```

---

### Task 12: Copy Phase A's output into backend static resources, serve publicly

**Files:**
- Create: `backend/src/main/resources/static/forms/migdal_health/migdal_health.json` (copied from Phase A output)
- Create: `backend/src/main/resources/static/forms/migdal_health/migdal_health_p1.webp`, `_p2.webp`, `_p3.webp` (copied)
- Test: `backend/src/test/java/com/insuranceforms/form/PublicFormAssetsTest.java`

**Interfaces:**
- Consumes: Task 9's Phase A output files (a file copy, not a code dependency — Phase A and Phase B share no code, per the design doc's "two independent task tracks").
- Produces: `GET /forms/migdal_health/migdal_health.json` and `.../migdal_health_p{1,2,3}.webp` reachable with no session at all, matching the pattern already established for logos in stage 1.

- [ ] **Step 1: Copy the files**

```bash
mkdir -p backend/src/main/resources/static/forms/migdal_health
cp tools/pdf-mapping-tool/output/migdal_health.json backend/src/main/resources/static/forms/migdal_health/
cp tools/pdf-mapping-tool/output/migdal_health_p*.webp backend/src/main/resources/static/forms/migdal_health/
```

- [ ] **Step 2: Write the failing test**

Spring Boot serves `src/main/resources/static/**` automatically with no controller needed — but this must be proven with a bare, cookie-free client, matching exactly what an insured's browser (with no agent/admin session) will do.

```java
package com.insuranceforms.form;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class PublicFormAssetsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void schemaJsonIsPubliclyReachableWithNoSessionAtAll() {
        RestTemplate bare = new RestTemplate();
        ResponseEntity<String> response = bare.getForEntity(
                restTemplate.getRootUri() + "/forms/migdal_health/migdal_health.json", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"form\"");
    }

    @Test
    void pageImageIsPubliclyReachableWithNoSessionAtAll() {
        RestTemplate bare = new RestTemplate();
        ResponseEntity<byte[]> response = bare.getForEntity(
                restTemplate.getRootUri() + "/forms/migdal_health/migdal_health_p1.webp", byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=PublicFormAssetsTest
```

Expected: FAIL — `SecurityConfig`'s existing chains don't yet `permitAll` on `/forms/**` (every unmatched path currently falls through to `anyRequest().authenticated()` on whichever chain's `securityMatcher` happens to catch it, or 401s if none does).

- [ ] **Step 4: Add a public chain for `/forms/**`, following the `logoChain` pattern exactly**

In `SecurityConfig.java`, add:

```java
@Bean
@Order(5)
public SecurityFilterChain formAssetsChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/forms/**")
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
}
```

(`@Order(5)` — the next free slot after the existing four chains at 1–4.)

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=PublicFormAssetsTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/static backend/src/main/java/com/insuranceforms/security/SecurityConfig.java backend/src/test/java/com/insuranceforms/form
git commit -m "feat: serve mapped form schema and page images as public static content"
```

---

### Task 13: `FormSchema` model and loader — server-side schema access for validation

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/form/FormSchema.java`
- Create: `backend/src/main/java/com/insuranceforms/form/FormSchemaLoader.java`
- Test: `backend/src/test/java/com/insuranceforms/form/FormSchemaLoaderTest.java`

**Interfaces:**
- Consumes: the JSON file copied in Task 12.
- Produces: `FormSchema(String form, String company, List<FieldDef> fields)`, `FieldDef(String name, String type, String on)` (only the fields validation needs — `page`/`rect`/`label` are frontend-only concerns, loaded separately by the browser from the same public JSON, not duplicated into this backend model). `FormSchemaLoader.load(String schemaPath): FormSchema`, consumed by Task 20's validation service and Task 19's insured GET endpoint.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.form;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormSchemaLoaderTest {

    @Test
    void loadsRealMigdalHealthSchema() {
        FormSchema schema = FormSchemaLoader.load("/forms/migdal_health/migdal_health.json");

        assertThat(schema.form()).isEqualTo("migdal_health");
        assertThat(schema.fields()).hasSize(421);

        long isSmokingWidgets = schema.fields().stream()
                .filter(f -> f.name().equals("IsSmoking"))
                .count();
        assertThat(isSmokingWidgets).isEqualTo(2);

        var onValues = schema.fields().stream()
                .filter(f -> f.name().equals("IsSmoking"))
                .map(FormSchema.FieldDef::on)
                .toList();
        assertThat(onValues).containsExactlyInAnyOrder("1", "2");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=FormSchemaLoaderTest
```

Expected: FAIL — `FormSchema`/`FormSchemaLoader` don't exist yet.

- [ ] **Step 3: Write `FormSchema`**

```java
package com.insuranceforms.form;

import java.util.List;

public record FormSchema(String form, String company, List<FieldDef> fields) {

    public record FieldDef(String name, String type, String on) {
    }
}
```

- [ ] **Step 4: Write `FormSchemaLoader`, reading from the classpath (the same static resources Task 12 made public)**

```java
package com.insuranceforms.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class FormSchemaLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static FormSchema load(String classpathStaticPath) {
        // classpathStaticPath is a public URL path like "/forms/migdal_health/migdal_health.json",
        // which corresponds to src/main/resources/static + that path on the classpath.
        String resourcePath = "static" + classpathStaticPath;
        try (InputStream in = FormSchemaLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Schema not found on classpath: " + resourcePath);
            }
            JsonNode root = MAPPER.readTree(in);
            List<FormSchema.FieldDef> fields = new ArrayList<>();
            for (JsonNode fieldNode : root.get("fields")) {
                String on = fieldNode.has("on") ? fieldNode.get("on").asText() : null;
                fields.add(new FormSchema.FieldDef(
                        fieldNode.get("name").asText(),
                        fieldNode.get("type").asText(),
                        on));
            }
            return new FormSchema(root.get("form").asText(), root.get("company").asText(), fields);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

(Add `import java.io.UncheckedIOException;`.)

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=FormSchemaLoaderTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/form/FormSchema.java backend/src/main/java/com/insuranceforms/form/FormSchemaLoader.java backend/src/test/java/com/insuranceforms/form/FormSchemaLoaderTest.java
git commit -m "feat: load the mapped form schema server-side for submission validation"
```

---

### Task 14: `Submission` entity, `SubmissionRepository`, and token hashing

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/Submission.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/SubmissionStatus.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/SubmissionRepository.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/SubmissionTokenService.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/SubmissionTokenServiceTest.java`

**Interfaces:**
- Consumes: the `submission` table from Task 10.
- Produces: `Submission` entity with `getStatus()/setStatus()` etc.; `SubmissionRepository extends JpaRepository<Submission, UUID>` plus `Optional<Submission> findByTokenHash(String hash)` and `int fillIfPending(UUID id, String hash)` (the conditional-update method, added in Task 21 once its exact semantics are needed — declared here as a method signature other tasks can rely on). `SubmissionTokenService.generate(): TokenAndHash` with `record TokenAndHash(String rawToken, String hash)`, and `SubmissionTokenService.hash(String rawToken): String` — consumed by Task 15 (create) and Task 18 (filter validates a presented token by hashing and looking up).

- [ ] **Step 1: Write the failing test for token hashing**

```java
package com.insuranceforms.submission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionTokenServiceTest {

    private final SubmissionTokenService service = new SubmissionTokenService();

    @Test
    void generatesAUniqueUrlSafeTokenAndItsHash() {
        SubmissionTokenService.TokenAndHash first = service.generate();
        SubmissionTokenService.TokenAndHash second = service.generate();

        assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
        assertThat(first.rawToken()).matches("[A-Za-z0-9_-]{40,}");
        assertThat(first.hash()).isNotEqualTo(first.rawToken());
    }

    @Test
    void hashingTheSameTokenTwiceProducesTheSameHash() {
        SubmissionTokenService.TokenAndHash generated = service.generate();
        assertThat(service.hash(generated.rawToken())).isEqualTo(generated.hash());
    }

    @Test
    void differentTokensHashDifferently() {
        String hashA = service.hash("token-a");
        String hashB = service.hash("token-b");
        assertThat(hashA).isNotEqualTo(hashB);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=SubmissionTokenServiceTest
```

Expected: FAIL — `SubmissionTokenService` doesn't exist yet.

- [ ] **Step 3: Write `SubmissionTokenService`**

```java
package com.insuranceforms.submission;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SubmissionTokenService {

    private final SecureRandom random = new SecureRandom();

    public record TokenAndHash(String rawToken, String hash) {
    }

    public TokenAndHash generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new TokenAndHash(rawToken, hash(rawToken));
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=SubmissionTokenServiceTest
```

Expected: PASS.

- [ ] **Step 5: Write `SubmissionStatus`, `Submission`, and `SubmissionRepository`**

```java
package com.insuranceforms.submission;

public enum SubmissionStatus {
    PENDING,
    FILLED
}
```

```java
package com.insuranceforms.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submission")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "insurance_form_id", nullable = false)
    private UUID insuranceFormId;

    @Column(name = "insured_name", nullable = false)
    private String insuredName;

    @Column(name = "insured_email", nullable = false)
    private String insuredEmail;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "filled_at")
    private Instant filledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Submission() {
    }

    public Submission(UUID agentId, UUID insuranceFormId, String insuredName, String insuredEmail,
                       String tokenHash, Instant expiresAt) {
        this.agentId = agentId;
        this.insuranceFormId = insuranceFormId;
        this.insuredName = insuredName;
        this.insuredEmail = insuredEmail;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getAgentId() { return agentId; }
    public UUID getInsuranceFormId() { return insuranceFormId; }
    public String getInsuredName() { return insuredName; }
    public String getInsuredEmail() { return insuredEmail; }
    public String getTokenHash() { return tokenHash; }
    public SubmissionStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getFilledAt() { return filledAt; }
}
```

```java
package com.insuranceforms.submission;

import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {

    Optional<Submission> findByTokenHash(String tokenHash);

    // Task 21 wires this in: the single conditional UPDATE that guards against
    // replay, double-submit, and expiry all at once. Declared now so Task 15/18/19
    // can be written and tested against a stable repository interface.
    @Modifying
    @Query("UPDATE Submission s SET s.status = com.insuranceforms.submission.SubmissionStatus.FILLED, "
            + "s.filledAt = :now WHERE s.id = :id AND s.status = com.insuranceforms.submission.SubmissionStatus.PENDING "
            + "AND s.expiresAt > :now")
    int fillIfPending(@Param("id") UUID id, @Param("now") Instant now);
}
```

- [ ] **Step 6: Verify the whole module compiles**

```bash
mvn -f backend/pom.xml compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission backend/src/test/java/com/insuranceforms/submission
git commit -m "feat: add Submission entity, repository, and token hashing service"
```

---

### Task 15: `POST /api/agent/submissions` — create a submission and its token

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/dto/CreateSubmissionRequest.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/dto/SubmissionResponse.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/AgentSubmissionController.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/AgentSubmissionControllerTest.java`

**Interfaces:**
- Consumes: `SubmissionTokenService`, `SubmissionRepository`, `InsuranceFormRepository`, `AgentPrincipal.getAgentId()`.
- Produces: `POST /api/agent/submissions` — 201 with `SubmissionResponse(UUID id, String status, String insuredLink)`. `insuredLink` is built from a configurable base URL + the raw token, never persisted.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.submission;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AgentSubmissionControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    void createsASubmissionOwnedByTheSessionAgentAndReturnsALink() {
        Agent agent = agentRepository.save(new Agent("Agent A", "sub-a@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-6001"));
        InsuranceForm form = insuranceFormRepository.findAll().stream()
                .filter(f -> f.getCompany().equals("migdal") && f.getInsuranceType().equals("health"))
                .findFirst().orElseThrow();

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "sub-a@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);
        Map<String, Object> body = Map.of(
                "insuranceFormId", form.getId().toString(),
                "insuredName", "Ploni Almoni",
                "insuredEmail", "insured@example.com");

        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/submissions", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING");
        assertThat((String) response.getBody().get("insuredLink")).contains("/fill/");

        Submission saved = submissionRepository.findById(
                java.util.UUID.fromString((String) response.getBody().get("id"))).orElseThrow();
        assertThat(saved.getAgentId()).isEqualTo(agent.getId());
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String extractCookie(ResponseEntity<?> response, String name) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> c.split(name + "=")[1].split(";")[0])
                .orElseThrow();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=AgentSubmissionControllerTest
```

Expected: FAIL — no `/api/agent/submissions` endpoint exists yet.

- [ ] **Step 3: Write the request/response DTOs**

```java
package com.insuranceforms.submission.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateSubmissionRequest(
        @NotNull UUID insuranceFormId,
        @NotBlank String insuredName,
        @NotBlank @Email String insuredEmail) {
}
```

```java
package com.insuranceforms.submission.dto;

import java.util.UUID;

public record SubmissionResponse(UUID id, String status, String insuredLink) {
}
```

- [ ] **Step 4: Add `/api/agent/**` to the existing `agentChain` authorization rules**

`SecurityConfig.agentChain` already matches `/api/agent/**` broadly and requires `ROLE_AGENT` — no change needed there; the new controller is picked up automatically.

- [ ] **Step 5: Write the controller**

```java
package com.insuranceforms.submission;

import com.insuranceforms.agent.AgentPrincipal;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.submission.dto.CreateSubmissionRequest;
import com.insuranceforms.submission.dto.SubmissionResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/agent/submissions")
public class AgentSubmissionController {

    private final SubmissionRepository submissionRepository;
    private final InsuranceFormRepository insuranceFormRepository;
    private final SubmissionTokenService tokenService;
    private final String frontendOrigin;

    public AgentSubmissionController(SubmissionRepository submissionRepository,
                                      InsuranceFormRepository insuranceFormRepository,
                                      SubmissionTokenService tokenService,
                                      @Value("${app.frontend-origin}") String frontendOrigin) {
        this.submissionRepository = submissionRepository;
        this.insuranceFormRepository = insuranceFormRepository;
        this.tokenService = tokenService;
        this.frontendOrigin = frontendOrigin;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionResponse create(@AuthenticationPrincipal AgentPrincipal principal,
                                      @Valid @RequestBody CreateSubmissionRequest request) {
        insuranceFormRepository.findById(request.insuranceFormId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown insuranceFormId"));

        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        Submission submission = new Submission(
                principal.getAgentId(),
                request.insuranceFormId(),
                request.insuredName(),
                request.insuredEmail(),
                tokenAndHash.hash(),
                Instant.now().plus(Duration.ofDays(7)));
        submissionRepository.save(submission);

        // Dev/demo affordance only, per the design doc: the real delivery channel is the
        // email stage 5 adds. Displaying this link does not extend the token's life or
        // relax the single-use rule below.
        String insuredLink = frontendOrigin + "/fill/" + tokenAndHash.rawToken();
        return new SubmissionResponse(submission.getId(), submission.getStatus().name(), insuredLink);
    }
}
```

- [ ] **Step 6: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=AgentSubmissionControllerTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission
git commit -m "feat: add agent-facing submission creation with a temporary insuredLink"
```

---

### Task 16: `GET /api/agent/submissions/{id}` — tenant isolation, with a positive control

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/dto/SubmissionDetailResponse.java`
- Modify: `backend/src/main/java/com/insuranceforms/submission/AgentSubmissionController.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/AgentSubmissionIsolationTest.java`

**Interfaces:**
- Consumes: `SubmissionRepository`, `AgentPrincipal`.
- Produces: `GET /api/agent/submissions/{id}` — 200 with `SubmissionDetailResponse` for the caller's own submission, 403 for anyone else's. This is the URL-path-id tenant-isolation test stage 1 deferred.

- [ ] **Step 1: Write the failing test — both the 403 and its positive control**

```java
package com.insuranceforms.submission;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AgentSubmissionIsolationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTokenService tokenService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    void agentBCannotReadAgentAsSubmissionByIdButCanReadTheirOwn() {
        Agent agentA = agentRepository.save(new Agent("Agent A", "iso-sub-a@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-7001"));
        Agent agentB = agentRepository.save(new Agent("Agent B", "iso-sub-b@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency B", "0502222222", "AG-7002"));
        InsuranceForm form = insuranceFormRepository.findAll().get(0);

        Submission submissionA = submissionRepository.save(new Submission(
                agentA.getId(), form.getId(), "Insured A", "insured-a@example.com",
                tokenService.generate().hash(), Instant.now().plus(7, ChronoUnit.DAYS)));
        Submission submissionB = submissionRepository.save(new Submission(
                agentB.getId(), form.getId(), "Insured B", "insured-b@example.com",
                tokenService.generate().hash(), Instant.now().plus(7, ChronoUnit.DAYS)));

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "iso-sub-b@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        // The 403: agent B requests agent A's submission by id.
        ResponseEntity<Map> forbidden = restTemplate.exchange(
                "/api/agent/submissions/" + submissionA.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Positive control: the same agent B, same session, requesting THEIR OWN
        // submission, succeeds — proving the 403 above is a real ownership check,
        // not an endpoint that blanket-rejects every GET by id.
        ResponseEntity<Map> ok = restTemplate.exchange(
                "/api/agent/submissions/" + submissionB.getId(), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("insuredName")).isEqualTo("Insured B");
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String extractCookie(ResponseEntity<?> response, String name) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> c.split(name + "=")[1].split(";")[0])
                .orElseThrow();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=AgentSubmissionIsolationTest
```

Expected: FAIL — no `GET /api/agent/submissions/{id}` exists yet.

- [ ] **Step 3: Write `SubmissionDetailResponse`**

```java
package com.insuranceforms.submission.dto;

import java.time.Instant;
import java.util.UUID;

public record SubmissionDetailResponse(
        UUID id, String status, String insuredName, String insuredEmail, Instant sentAt) {
}
```

- [ ] **Step 4: Add the endpoint, throwing 403 (not 404) on an ownership mismatch**

Add to `AgentSubmissionController`:

```java
@GetMapping("/{id}")
public SubmissionDetailResponse get(@AuthenticationPrincipal AgentPrincipal principal,
                                     @PathVariable java.util.UUID id) {
    Submission submission = submissionRepository.findById(id)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND));
    if (!submission.getAgentId().equals(principal.getAgentId())) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN);
    }
    return new SubmissionDetailResponse(submission.getId(), submission.getStatus().name(),
            submission.getInsuredName(), submission.getInsuredEmail(), submission.getSentAt());
}
```

The id is read from the URL path *only* to look the row up — the ownership check that follows is what actually enforces isolation. This is the central-query-helper pattern already established: `AgentSubmissionController` is the one place that touches `SubmissionRepository` on the agent side, and every read goes through this same ownership check.

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=AgentSubmissionIsolationTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission backend/src/test/java/com/insuranceforms/submission/AgentSubmissionIsolationTest.java
git commit -m "feat: add ownership-scoped submission read, closing stage 1's deferred URL-id isolation test"
```

---

### Task 17: Admin's aggregate-only usage projection

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/AgentUsageProjection.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/AgentUsageRepository.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/dto/AgentUsageSummary.java`
- Modify: `backend/src/main/java/com/insuranceforms/admin/AdminAgentController.java` (or create `AdminUsageController` if that file's shape doesn't fit — check its current contents before choosing)
- Test: `backend/src/test/java/com/insuranceforms/admin/AdminUsageControllerTest.java`

**Interfaces:**
- Consumes: the `submission` table (via a projection query only — never `SubmissionRepository`, which returns full rows including insured identity).
- Produces: `GET /api/admin/usage` — 200 with `List<AgentUsageSummary>`, each `{agentId, submissionCount}`, no insured-identifying field anywhere in the type.

- [ ] **Step 1: Write the failing test — asserting the aggregate shape, not just status code**

```java
package com.insuranceforms.admin;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.submission.Submission;
import com.insuranceforms.submission.SubmissionRepository;
import com.insuranceforms.submission.SubmissionTokenService;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AdminUsageControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTokenService tokenService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    void returnsPerAgentCountsWithNoInsuredIdentity() {
        Agent agent = agentRepository.save(new Agent("Agent A", "usage-a@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-8001"));
        InsuranceForm form = insuranceFormRepository.findAll().get(0);
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured 1", "i1@example.com",
                tokenService.generate().hash(), Instant.now().plus(7, ChronoUnit.DAYS)));
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured 2", "i2@example.com",
                tokenService.generate().hash(), Instant.now().plus(7, ChronoUnit.DAYS)));

        HttpHeaders headers = adminSessionHeaders();

        ResponseEntity<List> response = restTemplate.exchange("/api/admin/usage", HttpMethod.GET,
                new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> entry = (Map<String, Object>) response.getBody().stream()
                .filter(e -> ((Map<String, Object>) e).get("agentId").equals(agent.getId().toString()))
                .findFirst().orElseThrow();
        assertThat(entry.get("submissionCount")).isEqualTo(2);
        // No key in the response shape carries insured identity at all — the type itself
        // (AgentUsageSummary) has no such field, so there is nothing to assert "absent";
        // this is verified structurally in Step 3's repository test instead.
    }

    private HttpHeaders adminSessionHeaders() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String csrfToken = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split("XSRF-TOKEN=")[1].split(";")[0];
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", System.getenv("ADMIN_BOOTSTRAP_EMAIL"),
                        "password", System.getenv("ADMIN_BOOTSTRAP_PASSWORD")), loginHeaders), Map.class);
        String sessionCookie = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("SESSION=")).findFirst()
                .map(c -> c.split("SESSION=")[1].split(";")[0]).orElseThrow();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);
        return headers;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=AdminUsageControllerTest
```

Expected: FAIL — no `/api/admin/usage` endpoint exists.

- [ ] **Step 3: Write the aggregate-only projection — a separate repository, never `SubmissionRepository`**

```java
package com.insuranceforms.submission;

import java.util.UUID;

public interface AgentUsageProjection {
    UUID getAgentId();
    long getSubmissionCount();
}
```

```java
package com.insuranceforms.submission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

// Deliberately a distinct interface from SubmissionRepository, per the design doc:
// "admin-facing code must never be given a dependency on the row-returning submissions
// repository." AdminUsageController below only ever sees this interface, whose return
// type (AgentUsageProjection) structurally cannot carry insured_name/insured_email —
// there is no runtime branch to forget, because the type itself has no such field.
public interface AgentUsageRepository extends JpaRepository<Submission, UUID> {

    @Query("SELECT s.agentId AS agentId, COUNT(s) AS submissionCount FROM Submission s GROUP BY s.agentId")
    List<AgentUsageProjection> usageByAgent();
}
```

- [ ] **Step 4: Write the DTO and controller**

```java
package com.insuranceforms.admin.dto;

import java.util.UUID;

public record AgentUsageSummary(UUID agentId, long submissionCount) {
}
```

```java
package com.insuranceforms.admin;

import com.insuranceforms.admin.dto.AgentUsageSummary;
import com.insuranceforms.submission.AgentUsageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/usage")
public class AdminUsageController {

    private final AgentUsageRepository agentUsageRepository;

    public AdminUsageController(AgentUsageRepository agentUsageRepository) {
        this.agentUsageRepository = agentUsageRepository;
    }

    @GetMapping
    public List<AgentUsageSummary> usage() {
        return agentUsageRepository.usageByAgent().stream()
                .map(p -> new AgentUsageSummary(p.getAgentId(), p.getSubmissionCount()))
                .toList();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=AdminUsageControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission/AgentUsageProjection.java backend/src/main/java/com/insuranceforms/submission/AgentUsageRepository.java backend/src/main/java/com/insuranceforms/admin
git commit -m "feat: add admin's aggregate-only usage projection, closing stage 1's deferred item"
```

---

### Task 18: The third `SecurityFilterChain` — token-based insured access

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/security/InsuredPrincipal.java`
- Create: `backend/src/main/java/com/insuranceforms/security/InsuredTokenAuthenticationFilter.java`
- Modify: `backend/src/main/java/com/insuranceforms/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/insuranceforms/security/InsuredTokenAuthenticationTest.java`

**Interfaces:**
- Consumes: `SubmissionRepository.findByTokenHash`, `SubmissionTokenService.hash`.
- Produces: any `/api/insured/{token}/**` request is authenticated as `InsuredPrincipal(UUID submissionId)` if the token is valid, unused, and unexpired; otherwise 404 (unknown) or 410 (used/expired). No CSRF filter on this chain.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.security;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.submission.Submission;
import com.insuranceforms.submission.SubmissionRepository;
import com.insuranceforms.submission.SubmissionStatus;
import com.insuranceforms.submission.SubmissionTokenService;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class InsuredTokenAuthenticationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTokenService tokenService;

    private Agent agent;
    private InsuranceForm form;

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
        agent = agentRepository.save(new Agent("Agent A", "token-a@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("x"),
                "123456782", "Agency A", "0501111111", "AG-9001"));
        form = insuranceFormRepository.findAll().get(0);
    }

    @Test
    void validUnusedUnexpiredTokenReaches200() {
        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured", "i@example.com",
                tokenAndHash.hash(), Instant.now().plus(7, ChronoUnit.DAYS)));

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/insured/" + tokenAndHash.rawToken(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownTokenIs404() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/insured/not-a-real-token", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredTokenIs410() {
        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured", "i@example.com",
                tokenAndHash.hash(), Instant.now().minus(1, ChronoUnit.DAYS)));

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/insured/" + tokenAndHash.rawToken(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void alreadyFilledTokenIs410() {
        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        Submission submission = new Submission(agent.getId(), form.getId(), "Insured", "i@example.com",
                tokenAndHash.hash(), Instant.now().plus(7, ChronoUnit.DAYS));
        submission = submissionRepository.save(submission);
        submissionRepository.fillIfPending(submission.getId(), Instant.now());

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/insured/" + tokenAndHash.rawToken(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void anAgentSessionCookieAloneCannotReachInsuredEndpoints() {
        // No token in the path at all — this hits the insured chain's own matcher with
        // nothing to authenticate against, proving agent auth doesn't leak into this path.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/insured/", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredTokenAuthenticationTest
```

Expected: FAIL — no `/api/insured/**` chain exists.

- [ ] **Step 3: Write `InsuredPrincipal`**

```java
package com.insuranceforms.security;

import java.util.UUID;

public record InsuredPrincipal(UUID submissionId) {
}
```

- [ ] **Step 4: Write the token authentication filter**

```java
package com.insuranceforms.security;

import com.insuranceforms.submission.Submission;
import com.insuranceforms.submission.SubmissionRepository;
import com.insuranceforms.submission.SubmissionStatus;
import com.insuranceforms.submission.SubmissionTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InsuredTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern TOKEN_PATH = Pattern.compile("^/api/insured/([^/]+)");

    private final SubmissionRepository submissionRepository;
    private final SubmissionTokenService tokenService;

    public InsuredTokenAuthenticationFilter(SubmissionRepository submissionRepository,
                                             SubmissionTokenService tokenService) {
        this.submissionRepository = submissionRepository;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher matcher = TOKEN_PATH.matcher(request.getRequestURI());
        if (!matcher.find()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String rawToken = matcher.group(1);
        String hash = tokenService.hash(rawToken);

        Optional<Submission> found = submissionRepository.findByTokenHash(hash);
        if (found.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Submission submission = found.get();
        if (submission.getStatus() != SubmissionStatus.PENDING || submission.getExpiresAt().isBefore(Instant.now())) {
            response.sendError(HttpServletResponse.SC_GONE);
            return;
        }

        InsuredPrincipal principal = new InsuredPrincipal(submission.getId());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INSURED")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 5: Wire the sixth `SecurityFilterChain` — no CSRF, per the `logoChain` precedent**

Task 12 already claimed `@Order(5)` for `formAssetsChain`. Add this one at `@Order(6)` — the two chains' relative order doesn't matter, only that both precede the catch-all default and neither collides with the other's number:

```java
@Bean
@Order(6)
public SecurityFilterChain insuredChain(HttpSecurity http,
                                         com.insuranceforms.submission.SubmissionRepository submissionRepository,
                                         com.insuranceforms.submission.SubmissionTokenService tokenService)
        throws Exception {
    http.securityMatcher("/api/insured/**")
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .addFilterBefore(new InsuredTokenAuthenticationFilter(submissionRepository, tokenService),
                    UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

CSRF is disabled here deliberately, not by oversight: this chain has no ambient cookie for a forged cross-site request to ride — the token itself, placed explicitly in the URL by whoever holds the link, is the only credential. This mirrors `logoChain`'s existing `.csrf(AbstractHttpConfigurer::disable)` for the same reason.

- [ ] **Step 6: Add a placeholder `GET /api/insured/{token}` returning 200, so this task's tests can pass — Task 19 replaces the body**

```java
package com.insuranceforms.submission;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsuredSubmissionController {

    @GetMapping("/api/insured/{token}")
    public String placeholder(@PathVariable String token) {
        return "ok";
    }
}
```

- [ ] **Step 7: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredTokenAuthenticationTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/security backend/src/main/java/com/insuranceforms/submission/InsuredSubmissionController.java backend/src/test/java/com/insuranceforms/security
git commit -m "feat: add the third, token-based SecurityFilterChain for insured access"
```

---

### Task 19: `GET /api/insured/{token}` — real form-pointer response

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/dto/InsuredFormResponse.java`
- Modify: `backend/src/main/java/com/insuranceforms/submission/InsuredSubmissionController.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/InsuredSubmissionControllerTest.java`

**Interfaces:**
- Consumes: `SubmissionRepository`, `InsuranceFormRepository`, `InsuredPrincipal` (from `@AuthenticationPrincipal`).
- Produces: `InsuredFormResponse(String company, String insuranceType, String schemaUrl)`, consumed by the frontend (Task 23) to know which schema/images to fetch next.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.submission;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class InsuredSubmissionControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTokenService tokenService;

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    void returnsWhichFormThisTokenIsFor() {
        Agent agent = agentRepository.save(new Agent("Agent A", "form-ptr-a@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("x"),
                "123456782", "Agency A", "0501111111", "AG-9101"));
        InsuranceForm form = insuranceFormRepository.findAll().get(0);
        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured", "i@example.com",
                tokenAndHash.hash(), Instant.now().plus(7, ChronoUnit.DAYS)));

        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/insured/" + tokenAndHash.rawToken(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("company")).isEqualTo("migdal");
        assertThat(response.getBody().get("insuranceType")).isEqualTo("health");
        assertThat(response.getBody().get("schemaUrl")).isEqualTo("/forms/migdal_health/migdal_health.json");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredSubmissionControllerTest
```

Expected: FAIL — the Task 18 placeholder returns `"ok"`, not this shape.

- [ ] **Step 3: Write `InsuredFormResponse`**

```java
package com.insuranceforms.submission.dto;

public record InsuredFormResponse(String company, String insuranceType, String schemaUrl) {
}
```

- [ ] **Step 4: Replace the placeholder controller method**

```java
package com.insuranceforms.submission;

import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.security.InsuredPrincipal;
import com.insuranceforms.submission.dto.InsuredFormResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InsuredSubmissionController {

    private final SubmissionRepository submissionRepository;
    private final InsuranceFormRepository insuranceFormRepository;

    public InsuredSubmissionController(SubmissionRepository submissionRepository,
                                        InsuranceFormRepository insuranceFormRepository) {
        this.submissionRepository = submissionRepository;
        this.insuranceFormRepository = insuranceFormRepository;
    }

    @GetMapping("/api/insured/{token}")
    public InsuredFormResponse get(@AuthenticationPrincipal InsuredPrincipal principal, @PathVariable String token) {
        // token itself is unused here — the filter already validated it and resolved
        // the submission id onto the principal; using the path variable directly
        // instead of the principal would reopen exactly the "id from the request, not
        // the session" mistake CLAUDE.md's tenant-isolation rule forbids.
        Submission submission = submissionRepository.findById(principal.submissionId()).orElseThrow();
        InsuranceForm form = insuranceFormRepository.findById(submission.getInsuranceFormId()).orElseThrow();
        return new InsuredFormResponse(form.getCompany(), form.getInsuranceType(), form.getSchemaPath());
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredSubmissionControllerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission backend/src/test/java/com/insuranceforms/submission/InsuredSubmissionControllerTest.java
git commit -m "feat: return the real form pointer for a valid insured token"
```

---

### Task 20: Type-aware structural validation service

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/submission/UnknownFieldException.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/InvalidFieldValueException.java`
- Create: `backend/src/main/java/com/insuranceforms/submission/SubmissionValidationService.java`
- Modify: `backend/src/main/java/com/insuranceforms/web/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/SubmissionValidationServiceTest.java`

**Interfaces:**
- Consumes: `FormSchema`/`FormSchemaLoader` from Task 13.
- Produces: `SubmissionValidationService.validate(FormSchema schema, Map<String,String> answers)`, throwing `UnknownFieldException(List<String> fieldIds)` or `InvalidFieldValueException(String fieldId, Set<String> allowedValues)` on failure; consumed by Task 21's submit endpoint. Both exceptions map to 400 via `GlobalExceptionHandler`, in the same pattern as `RegistrationException`.

- [ ] **Step 1: Write the failing tests — covering both failure classes, the type-aware exemption, and both `Off` cases**

```java
package com.insuranceforms.submission;

import com.insuranceforms.form.FormSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmissionValidationServiceTest {

    private final SubmissionValidationService service = new SubmissionValidationService();

    private FormSchema schemaWith(FormSchema.FieldDef... fields) {
        return new FormSchema("test_form", "test_company", List.of(fields));
    }

    @Test
    void unknownFieldNameIsRejectedNamingIt() {
        FormSchema schema = schemaWith(new FormSchema.FieldDef("KnownField", "text", null));

        assertThatThrownBy(() -> service.validate(schema, Map.of("UnknownField", "anything")))
                .isInstanceOf(UnknownFieldException.class)
                .satisfies(e -> assertThat(((UnknownFieldException) e).getUnknownFieldIds())
                        .containsExactly("UnknownField"));
    }

    @Test
    void multiWidgetCheckboxAcceptsOnlyItsOwnOnValues() {
        FormSchema schema = schemaWith(
                new FormSchema.FieldDef("IsSmoking", "checkbox", "1"),
                new FormSchema.FieldDef("IsSmoking", "checkbox", "2"));

        service.validate(schema, Map.of("IsSmoking", "1")); // does not throw
        service.validate(schema, Map.of("IsSmoking", "2")); // does not throw

        assertThatThrownBy(() -> service.validate(schema, Map.of("IsSmoking", "3")))
                .isInstanceOf(InvalidFieldValueException.class)
                .satisfies(e -> {
                    InvalidFieldValueException ex = (InvalidFieldValueException) e;
                    assertThat(ex.getFieldId()).isEqualTo("IsSmoking");
                    assertThat(ex.getAllowedValues()).containsExactlyInAnyOrder("1", "2");
                });
    }

    @Test
    void multiWidgetCheckboxRejectsOff() {
        FormSchema schema = schemaWith(
                new FormSchema.FieldDef("IsSmoking", "checkbox", "1"),
                new FormSchema.FieldDef("IsSmoking", "checkbox", "2"));

        assertThatThrownBy(() -> service.validate(schema, Map.of("IsSmoking", "Off")))
                .isInstanceOf(InvalidFieldValueException.class);
    }

    @Test
    void singleWidgetCheckboxAcceptsOff() {
        FormSchema schema = schemaWith(new FormSchema.FieldDef("SoloCheckbox", "checkbox", "Yes"));

        service.validate(schema, Map.of("SoloCheckbox", "Off")); // does not throw
        service.validate(schema, Map.of("SoloCheckbox", "Yes")); // does not throw
    }

    @Test
    void textFieldAcceptsAnyString() {
        FormSchema schema = schemaWith(new FormSchema.FieldDef("FreeText", "text", null));

        service.validate(schema, Map.of("FreeText", "anything the insured typed, any shape"));
        // does not throw — this is the whole point of the exemption
    }

    @Test
    void fieldAbsentFromPayloadIsNotAnError() {
        FormSchema schema = schemaWith(
                new FormSchema.FieldDef("Optional1", "text", null),
                new FormSchema.FieldDef("Optional2", "checkbox", "Yes"));

        service.validate(schema, Map.of()); // does not throw
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=SubmissionValidationServiceTest
```

Expected: FAIL — none of these classes exist yet.

- [ ] **Step 3: Write the exceptions**

```java
package com.insuranceforms.submission;

import java.util.List;

public class UnknownFieldException extends RuntimeException {
    private final List<String> unknownFieldIds;

    public UnknownFieldException(List<String> unknownFieldIds) {
        super("Unknown field-ids: " + unknownFieldIds);
        this.unknownFieldIds = unknownFieldIds;
    }

    public List<String> getUnknownFieldIds() {
        return unknownFieldIds;
    }
}
```

```java
package com.insuranceforms.submission;

import java.util.Set;

public class InvalidFieldValueException extends RuntimeException {
    private final String fieldId;
    private final Set<String> allowedValues;

    public InvalidFieldValueException(String fieldId, Set<String> allowedValues) {
        super("Invalid value for field '" + fieldId + "'. Allowed: " + allowedValues);
        this.fieldId = fieldId;
        this.allowedValues = allowedValues;
    }

    public String getFieldId() {
        return fieldId;
    }

    public Set<String> getAllowedValues() {
        return allowedValues;
    }
}
```

- [ ] **Step 4: Write `SubmissionValidationService`**

```java
package com.insuranceforms.submission;

import com.insuranceforms.form.FormSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SubmissionValidationService {

    public void validate(FormSchema schema, Map<String, String> answers) {
        Set<String> knownFieldIds = schema.fields().stream()
                .map(FormSchema.FieldDef::name)
                .collect(Collectors.toSet());

        List<String> unknown = answers.keySet().stream()
                .filter(fieldId -> !knownFieldIds.contains(fieldId))
                .toList();
        if (!unknown.isEmpty()) {
            throw new UnknownFieldException(unknown);
        }

        Map<String, List<FormSchema.FieldDef>> byFieldId = schema.fields().stream()
                .collect(Collectors.groupingBy(FormSchema.FieldDef::name));

        for (Map.Entry<String, String> answer : answers.entrySet()) {
            List<FormSchema.FieldDef> defs = byFieldId.get(answer.getKey());
            boolean isCheckbox = defs.stream().anyMatch(d -> d.type().equals("checkbox"));
            if (!isCheckbox) {
                continue; // text (and signature) fields: any string is structurally valid in this stage
            }

            Set<String> onValues = defs.stream().map(FormSchema.FieldDef::on).collect(Collectors.toSet());
            Set<String> allowed = new HashSet<>(onValues);
            if (onValues.size() == 1) {
                allowed.add("Off"); // single-widget checkbox: unchecked is a legitimate "no"
            }
            // multi-widget (radio-style) field: no "Off" member at all — none of its
            // widgets represents an "off" state.

            if (!allowed.contains(answer.getValue())) {
                throw new InvalidFieldValueException(answer.getKey(), allowed);
            }
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=SubmissionValidationServiceTest
```

Expected: PASS.

- [ ] **Step 6: Wire both exceptions into `GlobalExceptionHandler` — schema data only, never the submitted value**

Add to `GlobalExceptionHandler.java`:

```java
@ExceptionHandler(UnknownFieldException.class)
public ResponseEntity<Map<String, Object>> handleUnknownField(UnknownFieldException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "unknown_field", "fieldIds", ex.getUnknownFieldIds()));
}

@ExceptionHandler(InvalidFieldValueException.class)
public ResponseEntity<Map<String, Object>> handleInvalidFieldValue(InvalidFieldValueException ex) {
    // Deliberately omits the submitted value — allowedValues is schema data, not answer
    // content, so it's safe to return; the value the insured actually typed never appears
    // in this response, per the no-echo rule.
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "invalid_value", "fieldId", ex.getFieldId(), "allowedValues", ex.getAllowedValues()));
}
```

Add the two new imports (`com.insuranceforms.submission.UnknownFieldException`, `com.insuranceforms.submission.InvalidFieldValueException`) to the top of the file.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission/UnknownFieldException.java backend/src/main/java/com/insuranceforms/submission/InvalidFieldValueException.java backend/src/main/java/com/insuranceforms/submission/SubmissionValidationService.java backend/src/main/java/com/insuranceforms/web/GlobalExceptionHandler.java backend/src/test/java/com/insuranceforms/submission/SubmissionValidationServiceTest.java
git commit -m "feat: add type-aware structural validation for submitted answers"
```

---

### Task 21: `POST /api/insured/{token}/submit` — wiring validation, the conditional update, and no-echo

**Files:**
- Modify: `backend/src/main/java/com/insuranceforms/submission/InsuredSubmissionController.java`
- Test: `backend/src/test/java/com/insuranceforms/submission/InsuredSubmitControllerTest.java`

**Interfaces:**
- Consumes: `SubmissionValidationService`, `FormSchemaLoader`, `SubmissionRepository.fillIfPending`.
- Produces: `POST /api/insured/{token}/submit` — 200 on success (submission moves to `FILLED`), 400 on validation failure (submission stays `PENDING`), per the design doc's full contract. This is the task the design doc's entire testing-plan "Structural validation" section targets — write every one of those cases here.

- [ ] **Step 1: Write the failing tests — one per case in the design doc's testing plan**

```java
package com.insuranceforms.submission;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.form.InsuranceForm;
import com.insuranceforms.form.InsuranceFormRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class InsuredSubmitControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;
    @Autowired private InsuranceFormRepository insuranceFormRepository;
    @Autowired private SubmissionRepository submissionRepository;
    @Autowired private SubmissionTokenService tokenService;

    private Agent agent;
    private InsuranceForm form;

    @BeforeEach
    void cleanSlate() {
        submissionRepository.deleteAll();
        agentRepository.deleteAll();
        agent = agentRepository.save(new Agent("Agent A", "submit-a@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("x"),
                "123456782", "Agency A", "0501111111", "AG-9201"));
        form = insuranceFormRepository.findAll().get(0);
    }

    private String createPendingSubmissionAndReturnToken() {
        SubmissionTokenService.TokenAndHash tokenAndHash = tokenService.generate();
        submissionRepository.save(new Submission(agent.getId(), form.getId(), "Insured", "i@example.com",
                tokenAndHash.hash(), Instant.now().plus(7, ChronoUnit.DAYS)));
        return tokenAndHash.rawToken();
    }

    @Test
    void unknownFieldIdIs400NamingIt() {
        String token = createPendingSubmissionAndReturnToken();

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/insured/" + token + "/submit", Map.of("NotARealField", "x"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((java.util.List<String>) response.getBody().get("fieldIds")).contains("NotARealField");
    }

    @Test
    void invalidCheckboxValueIs400WithoutEchoingTheSubmittedValue() {
        String token = createPendingSubmissionAndReturnToken();

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/insured/" + token + "/submit", Map.of("IsSmoking", "not-a-real-on-value"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("fieldId")).isEqualTo("IsSmoking");
        assertThat(response.getBody().toString()).doesNotContain("not-a-real-on-value");
    }

    @Test
    void validationFailureLeavesTheSubmissionPendingAndTheTokenStillUsable() {
        String token = createPendingSubmissionAndReturnToken();

        restTemplate.postForEntity("/api/insured/" + token + "/submit",
                Map.of("NotARealField", "x"), Map.class);

        // Positive control: the same token, now with a valid free-text answer, still works.
        ResponseEntity<Map> retry = restTemplate.postForEntity(
                "/api/insured/" + token + "/submit", Map.of("FirstName", "Ploni"), Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void textFieldAcceptsAFreeFormString() {
        String token = createPendingSubmissionAndReturnToken();

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/insured/" + token + "/submit", Map.of("FirstName", "Anything At All"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void offOnAMultiWidgetFieldIs400ButOffOnASingleWidgetCheckboxIsAccepted() {
        String tokenForRejection = createPendingSubmissionAndReturnToken();
        ResponseEntity<Map> rejected = restTemplate.postForEntity(
                "/api/insured/" + tokenForRejection + "/submit", Map.of("IsSmoking", "Off"), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String tokenForAcceptance = createPendingSubmissionAndReturnToken();
        // "Check Box1" is a real single-widget checkbox in migdal_health.pdf (on=Yes,
        // one of the 27 non-semantic field-ids, verified during design) — not a stand-in
        // name. "HMORadio" was considered here and rejected: it's a real field, but it
        // belongs to migdal_life.pdf, not migdal_health.pdf, and would fail as an
        // unknown-field 400 against this form's schema rather than testing what this
        // case needs to test.
        ResponseEntity<Map> accepted = restTemplate.postForEntity(
                "/api/insured/" + tokenForAcceptance + "/submit", Map.of("Check Box1", "Off"), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void successfulSubmitResponseContainsNoAnswerContent() {
        String token = createPendingSubmissionAndReturnToken();

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/insured/" + token + "/submit", Map.of("FirstName", "A Very Distinctive Name Xyzzy"), Map.class);

        assertThat(response.getBody().toString()).doesNotContain("A Very Distinctive Name Xyzzy");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredSubmitControllerTest
```

Expected: FAIL — no `/submit` endpoint exists yet.

- [ ] **Step 3: Wire the endpoint**

Add to `InsuredSubmissionController`:

```java
private final SubmissionValidationService validationService;

// add to constructor:
public InsuredSubmissionController(SubmissionRepository submissionRepository,
                                    InsuranceFormRepository insuranceFormRepository,
                                    SubmissionValidationService validationService) {
    this.submissionRepository = submissionRepository;
    this.insuranceFormRepository = insuranceFormRepository;
    this.validationService = validationService;
}

@org.springframework.web.bind.annotation.PostMapping("/api/insured/{token}/submit")
public java.util.Map<String, String> submit(@AuthenticationPrincipal InsuredPrincipal principal,
                                             @org.springframework.web.bind.annotation.PathVariable String token,
                                             @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> answers) {
    Submission submission = submissionRepository.findById(principal.submissionId()).orElseThrow();
    InsuranceForm form = insuranceFormRepository.findById(submission.getInsuranceFormId()).orElseThrow();
    com.insuranceforms.form.FormSchema schema = com.insuranceforms.form.FormSchemaLoader.load(form.getSchemaPath());

    validationService.validate(schema, answers); // throws before the answers ever reach the update below

    int updated = submissionRepository.fillIfPending(submission.getId(), java.time.Instant.now());
    if (updated == 0) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.GONE, "Submission is no longer pending");
    }

    // answers goes out of scope here — never logged, never persisted, never returned.
    return java.util.Map.of("status", "FILLED");
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=InsuredSubmitControllerTest
```

Expected: PASS.

- [ ] **Step 5: Run the full backend suite to confirm nothing else regressed**

```bash
mvn -f backend/pom.xml test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/submission/InsuredSubmissionController.java backend/src/test/java/com/insuranceforms/submission/InsuredSubmitControllerTest.java
git commit -m "feat: wire structural validation and the conditional status update into submit"
```

---

### Task 22: Frontend — data-driven company/type picker and the send flow

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/form/dto/InsuranceFormSummary.java`
- Create: `backend/src/main/java/com/insuranceforms/form/AgentInsuranceFormController.java`
- Create: `frontend/src/pages/AgentSendFormPage.tsx`
- Modify: `frontend/src/App.tsx` (add route)
- Test: `backend/src/test/java/com/insuranceforms/form/AgentInsuranceFormControllerTest.java`

**Interfaces:**
- Consumes: `InsuranceFormRepository` (backend); `apiClient` (frontend, existing shared axios wrapper).
- Produces: `GET /api/agent/insurance-forms` — 200 with `List<InsuranceFormSummary>`. `AgentSendFormPage` renders a picker built from that list (not a hardcoded dropdown) and posts to Task 15's `/api/agent/submissions`, displaying the returned `insuredLink`.

- [ ] **Step 1: Backend — write the failing test**

```java
package com.insuranceforms.form;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.JdkClientTestConfig;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestContainerConfig.class, JdkClientTestConfig.class})
class AgentInsuranceFormControllerTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AgentRepository agentRepository;

    @Test
    void listsAvailableFormsForAnAuthenticatedAgent() {
        agentRepository.save(new Agent("Agent A", "picker-a@example.com",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("correct-horse-battery"),
                "123456782", "Agency A", "0501111111", "AG-9301"));

        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String csrfToken = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split("XSRF-TOKEN=")[1].split(";")[0];
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "picker-a@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = loginResponse.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("SESSION=")).findFirst()
                .map(c -> c.split("SESSION=")[1].split(";")[0]).orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        ResponseEntity<List> response = restTemplate.exchange("/api/agent/insurance-forms", HttpMethod.GET,
                new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> entry = (Map<String, Object>) response.getBody().get(0);
        assertThat(entry.get("company")).isEqualTo("migdal");
        assertThat(entry.get("insuranceType")).isEqualTo("health");
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

```bash
mvn -f backend/pom.xml test -Dtest=AgentInsuranceFormControllerTest
```

```java
package com.insuranceforms.form.dto;

import java.util.UUID;

public record InsuranceFormSummary(UUID id, String company, String insuranceType) {
}
```

```java
package com.insuranceforms.form;

import com.insuranceforms.form.dto.InsuranceFormSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/insurance-forms")
public class AgentInsuranceFormController {

    private final InsuranceFormRepository insuranceFormRepository;

    public AgentInsuranceFormController(InsuranceFormRepository insuranceFormRepository) {
        this.insuranceFormRepository = insuranceFormRepository;
    }

    @GetMapping
    public List<InsuranceFormSummary> list() {
        return insuranceFormRepository.findAll().stream()
                .map(f -> new InsuranceFormSummary(f.getId(), f.getCompany(), f.getInsuranceType()))
                .toList();
    }
}
```

- [ ] **Step 3: Run to verify it passes**

```bash
mvn -f backend/pom.xml test -Dtest=AgentInsuranceFormControllerTest
```

- [ ] **Step 4: Frontend — the send page**

```tsx
import { useEffect, useState } from "react";
import apiClient from "../api/apiClient";

interface InsuranceFormSummary {
  id: string;
  company: string;
  insuranceType: string;
}

export default function AgentSendFormPage() {
  const [forms, setForms] = useState<InsuranceFormSummary[]>([]);
  const [insuranceFormId, setInsuranceFormId] = useState("");
  const [insuredName, setInsuredName] = useState("");
  const [insuredEmail, setInsuredEmail] = useState("");
  const [insuredLink, setInsuredLink] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient.get<InsuranceFormSummary[]>("/api/agent/insurance-forms").then((res) => {
      setForms(res.data);
      if (res.data.length > 0) setInsuranceFormId(res.data[0].id);
    });
  }, []);

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setInsuredLink(null);
    try {
      const res = await apiClient.post("/api/agent/submissions", {
        insuranceFormId,
        insuredName,
        insuredEmail,
      });
      setInsuredLink(res.data.insuredLink);
    } catch {
      setError("שליחת הטופס נכשלה");
    }
  }

  return (
    <div dir="rtl">
      <h1>שליחת טופס למבוטח</h1>
      <form onSubmit={handleSend}>
        <label>
          חברת ביטוח וסוג
          <select value={insuranceFormId} onChange={(e) => setInsuranceFormId(e.target.value)} aria-label="חברת ביטוח וסוג">
            {forms.map((f) => (
              <option key={f.id} value={f.id}>
                {f.company} — {f.insuranceType}
              </option>
            ))}
          </select>
        </label>
        <label>
          שם המבוטח
          <input value={insuredName} onChange={(e) => setInsuredName(e.target.value)} aria-label="שם המבוטח" required />
        </label>
        <label>
          דוא"ל המבוטח
          <input type="email" value={insuredEmail} onChange={(e) => setInsuredEmail(e.target.value)} aria-label="דוא&quot;ל המבוטח" required />
        </label>
        <button type="submit">שלח</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {insuredLink && (
        <p>
          קישור זמני להדגמה (הערוץ הרשמי הוא מייל, יתווסף בשלב 5): <a href={insuredLink}>{insuredLink}</a>
        </p>
      )}
    </div>
  );
}
```

- [ ] **Step 5: Add the route**

In `frontend/src/App.tsx`, add a route for `/send` (or wherever the agent's other authenticated pages live) rendering `AgentSendFormPage`, following the existing route pattern for `AgentAccountPage`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/form frontend/src/pages/AgentSendFormPage.tsx frontend/src/App.tsx backend/src/test/java/com/insuranceforms/form/AgentInsuranceFormControllerTest.java
git commit -m "feat: add data-driven company/type picker and the agent send flow"
```

---

### Task 23: Frontend — the insured's filling screen

**Files:**
- Create: `frontend/src/pages/InsuredFillPage.tsx`
- Create: `frontend/src/components/FormOverlay.tsx`
- Modify: `frontend/src/App.tsx` (add `/fill/:token` route)
- Test: `frontend/src/components/FormOverlay.test.tsx`

**Interfaces:**
- Consumes: `GET /api/insured/{token}` (Task 19), the public schema JSON (Task 12), `POST /api/insured/{token}/submit` (Task 21).
- Produces: the rendered filling screen — this is the task the design doc's visual acceptance criterion applies to (verified in Task 24, not here).

- [ ] **Step 1: Write the coordinate-conversion unit test first — the part that's silently wrong if it's wrong**

```tsx
import { describe, expect, it } from "vitest";
import { toScreenRect } from "./FormOverlay";

describe("toScreenRect", () => {
  it("flips the Y axis and scales points to render pixels", () => {
    // A field at PDF y=100 with height=20, on a page 842pt tall, rendered at 150 DPI.
    // Points -> pixels scale = 150/72 = 2.0833...
    // PDF-space top-left y (before scaling) = 842 - 100 - 20 = 722
    const result = toScreenRect(
      { x: 50, y: 100, w: 30, h: 20 },
      /* pageHeightPoints */ 842,
      /* renderDpi */ 150
    );

    expect(result.top).toBeCloseTo(722 * (150 / 72), 1);
    expect(result.left).toBeCloseTo(50 * (150 / 72), 1);
    expect(result.width).toBeCloseTo(30 * (150 / 72), 1);
    expect(result.height).toBeCloseTo(20 * (150 / 72), 1);
  });
});
```

- [ ] **Step 2: Run to verify it fails**

```bash
npm --prefix frontend test -- FormOverlay
```

Expected: FAIL — `toScreenRect` doesn't exist yet.

- [ ] **Step 3: Write `FormOverlay.tsx`**

```tsx
interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

interface ScreenRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export function toScreenRect(rect: Rect, pageHeightPoints: number, renderDpi: number): ScreenRect {
  const scale = renderDpi / 72;
  const topLeftYPoints = pageHeightPoints - rect.y - rect.h;
  return {
    top: topLeftYPoints * scale,
    left: rect.x * scale,
    width: rect.w * scale,
    height: rect.h * scale,
  };
}

// migdal_health.pdf repeats several questions across six sections — main insured,
// spouse ("Bzug"), and four children — as distinct field-ids (e.g. HealthDecMainQ7 /
// HealthDecBzugQ7 / HealthDecC1Q7..C4Q7). The worksheet still gets one hand-written
// label per field-id (Task 9 — the same text copied into each variant's row, not
// auto-propagated by build-schema, since the form uses at least two different naming
// conventions for "the same question repeated per person" — HealthDec*Q{n} and
// IsSmoking/IsSmokingBzug/IsSmokingChild{n} — and a single regex confident enough to
// merge both without ever merging two unrelated fields was judged too risky to build
// for this stage). This function only adds a spoken/read section identifier on top of
// whatever label the worksheet already carries, so two inputs with an identical label
// remain distinguishable to a screen reader.
export function variantSuffix(fieldName: string): string | null {
  if (fieldName.includes("Bzug")) return "בן/בת זוג";
  const childMatch = fieldName.match(/C([1-4])/);
  if (childMatch) return `ילד ${childMatch[1]}`;
  if (fieldName.includes("Child")) {
    const numMatch = fieldName.match(/Child(\d)/);
    return numMatch ? `ילד ${numMatch[1]}` : "ילד";
  }
  return null; // the "main" insured variant carries no suffix at all
}

interface FieldDef {
  name: string;
  type: "checkbox" | "text" | "signature";
  on?: string;
  page: number;
  rect: [number, number, number, number];
  label?: string;
}

interface PageDef {
  page: number;
  image: string;
  w: number;
  h: number;
}

interface FormSchema {
  form: string;
  company: string;
  pages: PageDef[];
  fields: FieldDef[];
}

interface FormOverlayProps {
  schema: FormSchema;
  imageBaseUrl: string;
  answers: Record<string, string>;
  onAnswerChange: (fieldName: string, value: string) => void;
}

export default function FormOverlay({ schema, imageBaseUrl, answers, onAnswerChange }: FormOverlayProps) {
  return (
    <div dir="rtl">
      {schema.pages.map((page) => {
        const fieldsOnPage = schema.fields.filter((f) => f.page === page.page);
        return (
          <div
            key={page.page}
            style={{ position: "relative", width: page.w, height: page.h, marginBottom: 16 }}
          >
            <img src={`${imageBaseUrl}/${page.image}`} alt={`עמוד ${page.page}`} width={page.w} height={page.h} />
            {fieldsOnPage.map((field, idx) => {
              // Page dimensions (page.w/page.h) are already in render pixels (Task 8 wrote
              // them that way); rect is in PDF points, so the height passed here must be
              // the PDF-space page height, not page.w/h. Derive it back from the same DPI
              // this page was rendered at: renderDpi = page.h / (mediaBoxHeightPoints/72).
              // Simpler in practice: the schema also carries page.h in pixels at a known
              // DPI (150, fixed by Task 8's writeSchema call) — pageHeightPoints follows
              // from page.h / (150/72).
              const renderDpi = 150;
              const pageHeightPoints = page.h / (renderDpi / 72);
              const screenRect = toScreenRect(
                { x: field.rect[0], y: field.rect[1], w: field.rect[2], h: field.rect[3] },
                pageHeightPoints,
                renderDpi
              );
              const fontSize = screenRect.height * 0.7;
              const style: React.CSSProperties = {
                position: "absolute",
                top: screenRect.top,
                left: screenRect.left,
                width: screenRect.width,
                height: screenRect.height,
                fontSize,
              };

              if (field.type === "signature") {
                // Positioned but inert in this stage — signature capture is stage 5.
                return <div key={idx} style={{ ...style, border: "1px dashed #999" }} aria-hidden="true" />;
              }

              const variantLabel = variantSuffix(field.name);
              const baseLabel = field.label ?? field.name;
              const fullLabel = variantLabel ? `${baseLabel} — ${variantLabel}` : baseLabel;

              if (field.type === "text") {
                return (
                  <input
                    key={idx}
                    type="text"
                    style={style}
                    aria-label={fullLabel}
                    value={answers[field.name] ?? ""}
                    onChange={(e) => onAnswerChange(field.name, e.target.value)}
                  />
                );
              }

              // checkbox/radio-style: multiple widgets sharing one `name` become one
              // native radio group, so the browser enforces mutual exclusivity natively.
              // The on-value is appended raw ("— 1" / "— 2") rather than translated to
              // "yes"/"no" wording — the schema carries no semantic yes/no flag, only the
              // literal on-value, and inventing one here would be exactly the kind of
              // unverified assumption this project avoids elsewhere. A screen reader user
              // still hears two distinguishable options, which is the accessibility
              // floor this stage needs to clear; friendlier wording is a later polish.
              return (
                <input
                  key={idx}
                  type="radio"
                  name={field.name}
                  value={field.on}
                  style={style}
                  aria-label={`${fullLabel} — ${field.on}`}
                  checked={answers[field.name] === field.on}
                  onChange={() => onAnswerChange(field.name, field.on ?? "")}
                />
              );
            })}
          </div>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: Run to verify the unit test passes**

```bash
npm --prefix frontend test -- FormOverlay
```

Expected: PASS.

- [ ] **Step 5: Write `InsuredFillPage.tsx`**

```tsx
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import axios from "axios";
import FormOverlay from "../components/FormOverlay";

export default function InsuredFillPage() {
  const { token } = useParams<{ token: string }>();
  const [schema, setSchema] = useState<any | null>(null);
  // schemaUrl (e.g. "/forms/migdal_health/migdal_health.json") only exists on the FIRST
  // response, from /api/insured/{token} — the schema.json content itself (what `schema`
  // holds) has no such field. Kept in its own state, not derived from `schema`, so
  // imageBaseUrl below doesn't silently read undefined off the wrong object.
  const [imageBaseUrl, setImageBaseUrl] = useState<string>("");
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [status, setStatus] = useState<"loading" | "ready" | "submitted" | "expired" | "error">("loading");
  const [validationError, setValidationError] = useState<string | null>(null);

  useEffect(() => {
    axios
      .get(`/api/insured/${token}`)
      .then((res) => {
        const schemaUrl: string = res.data.schemaUrl;
        setImageBaseUrl(schemaUrl.replace(/\/[^/]+$/, ""));
        return axios.get(schemaUrl);
      })
      .then((res) => {
        setSchema(res.data);
        setStatus("ready");
      })
      .catch((err) => {
        setStatus(err.response?.status === 410 ? "expired" : "error");
      });
  }, [token]);

  async function handleSubmit() {
    setValidationError(null);
    try {
      await axios.post(`/api/insured/${token}/submit`, answers);
      setStatus("submitted");
    } catch (err: any) {
      if (err.response?.status === 400) {
        setValidationError("נא לבדוק את התשובות ולנסות שוב");
      } else if (err.response?.status === 410) {
        setStatus("expired");
      } else {
        setStatus("error");
      }
    }
  }

  if (status === "loading") return <p dir="rtl">טוען...</p>;
  if (status === "expired") return <p dir="rtl">הקישור אינו תקף עוד</p>;
  if (status === "error") return <p dir="rtl">אירעה שגיאה</p>;
  if (status === "submitted") return <p dir="rtl">תודה, הטופס נשלח בהצלחה</p>;

  return (
    <div dir="rtl">
      <FormOverlay
        schema={schema}
        imageBaseUrl={imageBaseUrl}
        answers={answers}
        onAnswerChange={(name, value) => setAnswers((prev) => ({ ...prev, [name]: value }))}
      />
      {validationError && <p role="alert">{validationError}</p>}
      <button onClick={handleSubmit}>שלח</button>
    </div>
  );
}
```

- [ ] **Step 6: Add the route**

In `frontend/src/App.tsx`, add `<Route path="/fill/:token" element={<InsuredFillPage />} />` — outside any authenticated-route wrapper, since this page has no session at all, only the token in the URL.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/pages/InsuredFillPage.tsx frontend/src/components/FormOverlay.tsx frontend/src/components/FormOverlay.test.tsx frontend/src/App.tsx
git commit -m "feat: add the insured's schema-driven filling screen"
```

---

### Task 24: Manual end-to-end verification — visual acceptance and the negative control

This task is the design doc's explicitly visual acceptance criterion. It cannot be satisfied by any automated test above — it requires a human looking at a real browser.

**Files:** none (verification only).

- [ ] **Step 1: Start the backend and frontend**

```bash
mvn -f backend/pom.xml spring-boot:run
```

```bash
npm --prefix frontend run dev
```

- [ ] **Step 2: Full flow — agent side**

1. Log in as an agent at `http://localhost:3000/login`.
2. Navigate to the send page, confirm the company/type dropdown shows "migdal — health" (from the seeded row, not hardcoded).
3. Send a submission to a test insured; confirm the temporary `insuredLink` appears on screen.

- [ ] **Step 3: Full flow — insured side, visual acceptance**

1. Open the `insuredLink` in a new incognito window (no agent session).
2. Confirm page 1 of the real `migdal_health` form renders as the backdrop image.
3. Pick at least 3 known fields spread across the page (e.g. `FirstName`, `IsSmoking`'s two radio widgets, one of the numbered `Check Box*` fields) and visually confirm each rendered input sits inside its printed box on the image — not offset, not on the wrong page.
4. Fill in several text fields and one yes/no question, then submit. Confirm the "thank you" screen appears.

- [ ] **Step 4: Negative control — prove the visual check can actually fail**

1. In `backend/src/main/resources/static/forms/migdal_health/migdal_health.json`, temporarily add 50 to one field's `rect[1]` (the `y` value) — pick a field used in Step 3.
2. Restart the backend, reload the insured page (a fresh token, since the previous one is now `FILLED`), and confirm a human doing the same visual check in Step 3 actually notices the field is now visibly offset from its printed box.
3. Revert the temporary edit and confirm the field is back in its correct position.

- [ ] **Step 5: Confirm the token lifecycle by hand once**

1. Reload the same `insuredLink` from Step 2 after it was already submitted — confirm it now shows the "link no longer valid" (expired/gone) state, not the form again.

- [ ] **Step 6: Report explicitly which of these steps were actually run and what was observed**

Per CLAUDE.md's "never claim something works until verified the way it will actually be used" — state plainly which of Steps 2–5 were performed and their actual outcome, not an assumption that they would pass.

---

## Definition of done

- All 9 Phase A tasks complete: the tool lives at `/tools/pdf-mapping-tool` in git, its baseline-then-diff commit history is intact, the WebP writer was empirically verified (Task 4), and `migdal_health.json` plus its 3 page images exist, human-reviewed (Task 9).
- All 15 Phase B tasks complete: `mvn -f backend/pom.xml test` and `npm --prefix frontend test` both pass.
- Task 16 and Task 17 close stage 1's two deferred tenant-isolation/admin-boundary items.
- Task 24's manual visual verification, including its negative control, has actually been run and its results reported.
- The mapping time recorded in Task 9, Step 4, is filled in with a real elapsed time, not left blank.

## Deferred (explicitly out of scope for this stage — see the design doc for the full list and reasoning)

- The exclusion rules engine and all `rule`/`exclusion-form-id` values — stage 4.
- Signature capture and any real PDF fill/verify — stage 5.
- Sending any email — stage 5.
- `migdal_life.pdf` and any company/form beyond `migdal_health` — stage 8.
- Mobile/responsive layout.
- Resolving the "form wasn't sent" admin visibility gap beyond re-recording it.
