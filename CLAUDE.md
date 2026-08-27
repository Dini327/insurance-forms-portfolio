# Insurance Forms System — Project Instructions

## What this is

A subscription web app for Israeli insurance agents. An agent picks an insurance
company, sends a personal link to an insured person, the insured fills the
official form on screen, and the system writes the answers into the company's
real PDF and emails it. The system is sold to many independent agents.

## Language

- Code, variable names, comments, commit messages: **English**
- Anything shown to a user: **Hebrew**, full RTL
- The spec document is in Hebrew. Do not translate it — read it as is.

---

## Architecture — read this before writing any PDF code

There are **two separate phases**. Never mix them.

### Phase A — preparation (offline, once per form)

A build-time tool. **Not part of the running service.** Input: one blank PDF.
Output, committed into the repo:

1. A rendered image of each page (`PDFRenderer.renderImageWithDPI`)
2. A JSON schema listing every field

The schema is the single source of truth. Format:

```json
{
  "form": "harel_ent",
  "company": "harel",
  "pages": [{ "page": 1, "image": "harel_ent_p1.webp", "w": 1191, "h": 1684 }],
  "fields": [
    {
      "name": "H2Q13",
      "type": "checkbox",
      "on": "1",
      "page": 1,
      "rect": [x, y, w, h],
      "label": "שקדים",
      "rule": true
    }
  ]
}
```

- `name`, `type`, `on`, `rect`, `page` are extracted automatically from the PDF.
- `on` is the checkbox's on-state value. **Read it from the PDF per field.**
  Never assume "Yes" or "On" — in the Harel form it is `"1"`.
- `label` is added by hand, and only for fields that matter. It serves both the
  rules engine and accessibility.
- `rule: true` marks fields that drive exclusion logic. Only these need a
  hand-written label. A free-text field renders fine from its position alone.

### Rules — a separate file per company

Rules do not live in the field schema and are never hardcoded. They live in
their own data file, so adding a company or changing a rule is a data edit.

**There are three outcome types, and all three must be supported:**

```json
[
  { "when": { "field": "H2Q13", "equals": "1" },
    "then": { "type": "form", "form": "harel_tonsils" } },

  { "when": { "field": "H2Q16", "equals": "1" },
    "then": { "type": "document", "document": "דוח בדיקת שינה" } },

  { "when": { "field": "H3Q7", "equals": "1" },
    "then": { "type": "question",
              "id": "H3Q7_which",
              "text": "באיזו מהאבחנות מדובר?",
              "select": "multi",
              "options": [
                { "value": "epilepsy", "label": "אפילפסיה" },
                { "value": "stroke",   "label": "אירוע מוחי" },
                { "value": "tumor",    "label": "גידול מוחי" },
                { "value": "ms",       "label": "טרשת נפוצה" }
              ] } },

  { "when": { "field": "H3Q7_which", "contains": "epilepsy" },
    "then": { "type": "form", "form": "harel_exclusion_04" } }
]
```

- `type: "form"` — the insured must fill an additional form. It is shown to them
  immediately, before the submission completes.
- `type: "document"` — no extra form. The insured must supply a medical document
  afterwards, on their own. Nothing is shown mid-flow; it becomes a notice in
  the final email.
- `type: "question"` — a clarifying question, shown immediately. Its answer is
  what decides whether anything further is triggered.

**Why clarifying questions exist.** Declaration questions are often broad — one
checkbox covers several conditions at once ("nervous system disorders, including
epilepsy, stroke, brain tumour, MS"). A yes does not tell you which one, and the
insurance company only wants an exclusion form for some of them. So a yes opens
a question, and only a specific answer opens a form.

**These questions are not PDF fields.** They are invented by the rules file, they
have no widget in the document, and their answers are **never written into the
PDF**. They exist solely to drive the rules. Writing one into the PDF will fail
the field-existence check, and it should.

**The engine runs in rounds.** Evaluate all rules against everything known so
far; if a round produces a new clarifying question, show it, take the answer,
and evaluate again. Stop when a round adds nothing new. Cap the number of rounds
and log if the cap is hit — that means a rules file with a cycle in it.

A single submission can trigger several of each type. Collect forms and
documents into two lists.
Two empty lists and no pending question means the flow ends normally.

The document list and the clarifying answers are held in memory and are **never
stored** — same rule as the answers themselves.

### Phase B — runtime

1. Browser shows the page image with input boxes positioned on top of it,
   read from the schema.
2. Insured fills in, signs by drawing on a canvas.
3. Answers travel to the server as plain data: `{ "H2Q13": "1", ... }` —
   never as screen coordinates.
4. Rules engine reads the answers, asks clarifying questions where a broad
   declaration needs narrowing, and produces two lists: additional forms to
   fill now, and medical documents the insured must supply later.
5. PDFBox opens the original blank PDF and writes the answers in.
6. The filled PDF is emailed, then discarded.

---

## PDF library rules — these were established by testing, do not revisit

**Use Apache PDFBox (3.x) and nothing else.** It does all three jobs:
rendering pages to images, reading field geometry, and filling forms.

**Never use `pypdf`, `pdf-lib`, or any JavaScript PDF library to fill forms.**
Both were tested against the real Harel form and produced garbage for Hebrew —
they write Hebrew codepoints into Helvetica, which has no Hebrew glyphs.

**Never use PyMuPDF or iText.** Both work, but are AGPL. This is a commercial
product sold to third parties, so AGPL would require publishing the source.

**No Python service.** An earlier plan had one. PDFBox replaced it entirely.

### Filling Hebrew correctly

Three things are required together, and the result is wrong if any is missing:

1. Load a Hebrew TTF as `PDType0Font` and add it to the AcroForm's
   `DefaultResources`.
2. Set the field's `DefaultAppearance` to that font.
3. **Reorder the string with `java.text.Bidi` before writing it.**
   PDFBox does not do bidirectional reordering itself. Without this,
   "ישראל ישראלי" is written as "ילארשי לארשי".

**Never reverse the string manually.** A naive reverse passes a pure-Hebrew test
and then corrupts anything mixed: `03/2024` becomes `4202/30`. The form is full
of dates.

PDFBox 3.x uses `Loader.loadPDF(...)`, not the 2.x `PDDocument.load(...)`.

### Verify every fill

PDFBox fails **silently** on bad input:

- An unknown field name is ignored, no exception. Validate that every incoming
  field name exists in the document first; return 400 listing the missing ones.
- An invalid checkbox value leaves the box `Off` with no error. After filling,
  reopen the result, read the values back, and compare. Mismatch → 500 with
  detail.

**Reading a value back proves the value was stored. It does not prove the PDF
looks right.** Value and appearance are separate structures in PDF. Never report
that a fill "works" based on a read-back alone.

---

## Rendering rules

- Positions live in the schema, never hardcoded in screen components. Stage 2 is
  a second renderer for mobile reading the same schema — this only works if
  nothing about layout leaks into the logic.
- Scale the whole overlay with one CSS `transform: scale()` on a fixed-size
  container. This keeps positions, font sizes and borders in proportion with a
  single number. Do not scale positions and fonts separately.
- Derive input font size from the field's height in the schema (roughly 70%).
  Field heights vary by a factor of about 1.5 within a single form.
- Every input gets an `aria-label` from the schema's `label`. Israeli law
  requires website accessibility, and an input positioned over an image with no
  label is unusable with a screen reader. This is impossible to retrofit across
  ~190 forms.
- The signature field is a `Signature` type in the PDF, not a text input. Render
  a drawing canvas at its rect.
- Render page images as **WebP**. Measured on the real Harel form at 150 DPI:
  WebP 98KB, PNG 164KB, JPEG 195KB. JPEG is *worse* than PNG here — these pages
  are sharp black text on white, which is exactly what JPEG handles badly.
  150 DPI is enough; the image is a backdrop, not the input surface.
  **Known gap, found in stage 1 (Task 13) and not yet resolved:** the JDK's
  built-in `ImageIO` has no WebP support at all — no reader, and (relevant
  here) no writer either. Stage 1 hit this from the reader side (logo
  uploads) and dropped WebP from the upload allowlist rather than add a
  dependency for it. This bullet needs a WebP *writer* for the offline
  Phase A tool — the same gap, the opposite direction. Don't rediscover this
  as a surprise when Phase A tooling is built: it will need a WebP-capable
  ImageIO module added deliberately, not the full TwelveMonkeys suite
  (minimize decoder attack surface, same reasoning as Task 13 — this offline
  tool isn't network-facing, but the runtime service that later reads these
  images as a backdrop for user input has no reason to also carry extra
  image *decoders* it never needs). Verify **write** support specifically
  before picking a library — WebP write support across ImageIO plugins
  (TwelveMonkeys included) tends to be newer and more limited than read
  support, so "it can read WebP" does not imply "it can write WebP well."
  This needs its own decision when stage 2 gets there, not an assumption
  carried over from Task 13's read-side finding.

---

## Email rules

**One email per event, never two.** The completed form goes out as a single
email to both the agent and the insured, with the filled PDF attached. The same
principle applies to the monthly subscription charge: one email carrying both
the charge notice and the receipt.

**When the rules engine produced a document list**, that same email carries a
prominent, visually distinct notice listing the medical documents the insured
must supply. It is not a second email and not a footnote — it must be
impossible to miss.

The notice wording is supplied by the client and is not to be invented. Keep it
in a template file with the document list injected into it, so the text can be
changed without touching code.

The email is sent from the agency's verified domain with the individual agent's
address in `Reply-To`, and carries that agent's logo.

---

## Tenant isolation

Agents are independent businesses, some of them competitors. An agent must never
see another agent's data.

- Every table carries an owner column.
- Enforce the filter in **one central place** — a single query helper every read
  goes through. Do not scatter permission checks across handlers.
- The agent id always comes from the server-side session. **Never from a request
  parameter, query string, or request body.** This is the most common way
  multi-tenant systems leak.
- The insured's link token grants access to one submission only, never to the
  agent's account.
- Include a test that logs in as agent A, requests agent B's resource by id, and
  asserts a 403.

---

## Privacy

**No medical data is ever stored.** Answers are processed in memory, the PDF is
generated and emailed, and everything is discarded. There is no medical
database, no medical audit log, no medical backup.

The database holds only: agent accounts, operational metadata about
submissions, usage counters, and payment status.

**Card numbers are never stored.** The card is entered on the payment provider's
own page. Store only their token plus the last four digits for display.

---

## Working style

- Implement in small pieces. Stop after each and explain what it does before
  moving on.
- Deploy to the cloud early, right after the first working slice — not at the
  end. Finding a deployment problem with 100 lines of code is cheap; finding it
  with 3000 is not.
- **Never claim something works until it has been verified the way it will
  actually be used.** For anything that produces a PDF, that means a human
  opening the file and looking at it. Say what you verified and what you did
  not.
- When something fails, characterise the failure before proposing a fix. State
  what you expected, what happened, and two or three hypotheses.

---

## Environment notes

- Development runs against a local PostgreSQL. The cloud database is configured
  only at deploy time, via a connection string in env.
- The developer's network filters HTTPS and has broken package installs before.
  If a download or install fails, suspect the network before the tool.
- Windows, Hebrew locale. Console output may show `?????` for Hebrew — that is a
  terminal encoding artifact, not a data problem. Never "fix" data because of it.
- All secrets in env files. `.env` never enters git. Maintain `.env.example`.
