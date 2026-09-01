# Stage 2 Design — Form Mapping and the Insured's Filling Screen

## Context

This is stage 2 of 9 from `specification_document_EN.md` §15: **"Mapping the
first form and building the filling screen for the insured"** — nothing more.
§4's seven-step process is deliberately split across three stages, and stage 2
covers only steps 1–3 (agent picks company/type and sends a link; insured opens
the link and fills the on-screen form). Step 4–5 (the exclusion rules engine)
is stage 4. Steps 6–7 (signature capture, filling the real PDF, sending the
email) is stage 5. This document does not design either of those.

Stage 1 (`2026-08-14-stage1-infra-registration-isolation-design.md`) delivered
agent/admin registration, login, and tenant isolation. That design doc already
flagged three items as explicitly deferred to this stage — see "Deferred items
landing here" below — and this document is responsible for closing (or
deliberately not closing) each of them.

Two forms are already in the repo. Both PDF field profiles were verified
directly against the actual files with a fresh PDFBox-based extraction script
(independent of the existing mapping-tool prototype, to cross-check its own
extraction logic against ground truth) during this design's brainstorming:

| | migdal_health.pdf | migdal_life.pdf |
|---|---|---|
| Pages | 3 | 3 |
| Page size | 595.2 × 842 pt (A4) | 595.2 × 842 pt (A4) |
| Fields | 251 | 176 |
| Widgets | 421 | 333 |
| Fields with >1 widget | 164 | 152 |
| On-value conventions seen | `1`/`2` (144 fields), `True`/`False` (12), `Yes` (12) | `1`/`2` (137), `True`/`False` (6), `Yes` (2), `1`/`2`/`3`/`4` (2) |

**`migdal_health.pdf` is the first form** mapped in this stage — it is the
health declaration, the system's central flow, and it is already in the repo.
`migdal_life.pdf` is explicitly deferred to be the *second* form, once the
Phase A pipeline built here has been proven on the first one.

## Scope

In scope:
- Phase A: an offline mapping tool that turns a blank PDF into the JSON
  schema + WebP page images CLAUDE.md's "Phase A" section defines, run against
  `migdal_health.pdf`.
- Phase B: the insured's filling screen — token-based access, schema-driven
  overlay rendering, structural submission validation.
- The agent-side "send" action that creates a request and its one-time token.
- The three items stage 1 explicitly deferred here (tenant isolation by URL
  id, admin's aggregate-only usage projection, the "form wasn't sent" gap).

Explicitly out of scope (see "Deferred" at the end): the exclusion rules
engine, signature capture, filling the real PDF, sending any email, the
standalone forms repository, billing, and mobile support.

## The two answers this stage does *not* store

CLAUDE.md and `specification_document_EN.md` §10 both require that insured
answers are never stored. But §10 also permits "operational metadata of
requests (to whom it was sent and when)". These are two different things, and
conflating them was the sharpest scope risk identified while brainstorming
this stage:

- **The request record** (agent side): who a link was sent to, when, for
  which company/type, and its token. This is allowed to persist — it is
  exactly the "operational metadata" §10 names, and it is also what stage 2
  needs anyway to run the tenant-isolation-by-id test stage 1 deferred here.
- **The answers** (insured side): held only for the duration of one HTTP
  request, never written to a table, never logged, never queued anywhere.

Because the rules engine, PDF fill, and email don't exist until stages 4 and
5, a stage-2 submission is a **deliberate dead end**: the insured sees a
Hebrew completion screen and nothing further happens. This stage is not meant
to be usable end-to-end by a real insured — it is a demonstrable, testable
slice of the eventual flow, not a shippable product surface. Concretely, on a
successful submit:

1. The server validates the answers **structurally** against the schema —
   not "every required field was answered" (the schema doesn't have a
   `required` flag; that belongs to whichever later stage first needs it,
   most likely stage 4's rules engine). Two checks, and the rule is
   **type-aware** — it does not apply the same shape of check to every
   field:
   (a) **every field name in the payload exists in the schema.** Applies
   uniformly regardless of field type.
   (b) **for checkbox/radio-shaped fields only, the value is a member of
   that field's valid on-values.** A genuine single-widget checkbox's valid
   values are its one `on` value plus `Off` (`Off` is a legitimate "no", not
   a missing answer); a multi-widget yes/no field's valid values are the
   union of its widgets' `on` values (e.g. `1`/`2`) with no `Off` member at
   all, since none of its widgets represents an "off" state — see "Yes/no
   fields" under Phase B below for why. **Text fields are exempt from check
   (b) entirely** — `migdal_health.pdf` has 80 of them (verified: 251 fields
   total, 168 checkbox-convention fields, 3 signature, leaving 80 with
   neither), and they have no `on` values in the schema at all, so applying
   (b) to them as originally worded would reject every text answer
   unconditionally. Any string is structurally valid for a text field in
   this stage — no length/format/content validation, no `required` check;
   both belong to a later stage. A field simply absent from the payload is
   not an error for any field type, for the same reason there's no
   `required` flag yet: it's an unanswered question, not a violation.
2. **On a validation failure (either check), the server returns 400 and
   stops — the submission is not touched.** Mirrors CLAUDE.md's own rule for
   the stage 5 fill step ("validate that every incoming field name exists in
   the document first; return 400 listing the missing ones"), so the two
   stages' failure contracts stay consistent. Two distinct failure classes,
   both 400:
   - Unknown field name(s): body lists every offending field-id.
   - Invalid value(s) for a checkbox/radio field: body lists the field-id
     and that field's allowed on-values set — **never the value the insured
     actually submitted**. The allowed-values set is schema data, not answer
     content, so listing it doesn't violate the no-echo rule; the submitted
     value itself is answer content and is never included, in either
     failure class.
   Validation runs *before* the conditional status update, and only a
   payload that passes both checks reaches it. A 400 response means
   `submission.status` is still `PENDING` and the token is still unconsumed
   — the insured can correct the payload and submit again on the same link.
   This is deliberate: a validation failure is not a "used" attempt, and
   must not burn the one-time token over what is, from the insured's
   perspective, a form error to fix and resubmit.
3. The request record's status transitions from `PENDING` to `FILLED` — only
   for a payload that passed both checks above.
4. The answers are discarded. There is no echo of answer content anywhere in
   any response — the proof that the screen captured the right values lives
   in a test that inspects the payload at the validation point, before
   deletion, not in anything a human or a client can observe afterward. This
   is a deliberate design decision, not an oversight: showing the answers
   back, even for confirmation, would be a second place they exist outside
   memory.
5. The insured sees a Hebrew "thank you" screen. There is nothing to poll or
   check afterward.

## Phase A — mapping tool

### What already exists, and what's wrong with it

A prototype (`C:\Users\User\eclipse-workspace\pdf-mapping-tool`, a standalone
Maven project depending only on `pdfbox:3.0.1`) already extracts field
geometry into a CSV. **This prototype currently lives entirely outside this
repository (`C:\dev\insurance-forms`) and is not version-controlled with the
project at all** — a continuity risk on its own, independent of the
correctness issues below. It is confirmed to be working from the exact same
source files as the repo, not a divergent copy: a SHA-256 hash of
`pdf-mapping-tool\input\הצהרת בריאות ביטוח בריאות.pdf` matches
`forms/migdal_health.pdf` byte-for-byte, and likewise for the life form
against `forms/migdal_life.pdf`. **This stage moves the tool into the repo**
(`/tools/pdf-mapping-tool`, alongside `/backend` and `/frontend`) as part of
rebuilding it — Phase A tooling belongs under version control with
everything else it feeds, per the same reasoning that already puts `forms/`
at the repo root.

Read directly from `PdfMapperApp.java` before writing this section: it walks
`acroForm.getFieldTree()`, and for each field returns
on the **first widget it finds** for both page number and position. Verified
against the real files above, this silently drops 170 of `migdal_health`'s
421 widgets (164 multi-widget fields × ~1 extra widget each) — every yes/no
question in the form is a single field with two widgets (`on=1` at one
position, `on=2` at another), and the tool keeps one and throws the other
away with no warning. It also never reads a checkbox's actual on-value from
the PDF, never captures width/height (only `getLowerLeftX/Y`), never reads
page dimensions, and does not render page images. `acroForm == null` and "no
widget found → default to page 1" both currently pass through silently.

This prototype is the starting point for the real tool, not a working version
of it. It gets rebuilt, not lightly patched, because nearly every mechanical
requirement below is either missing or wrong in a way that fails silently —
exactly the failure mode CLAUDE.md warns about for PDFBox generally.

**One existing artifact from this prototype is worth preserving, not
rebuilding: 77 hand-written Hebrew labels already exist for
`migdal_life.pdf`.** The prototype's own output folder contains
`הצהרת בריאות ביטוח חיים_mapped.csv` (migdal_life's CSV), and a proper
quote-aware parse of it (a naive line-splitter undercounts here — several of
its labels are multi-line quoted Hebrew text that breaks a line-oriented
reader) shows exactly 176 data rows — matching this document's independently
verified field count for `migdal_life.pdf` exactly — of which **77 already
carry a non-empty `Hebrew_Question_Label`**, from `AgentName` through the
`HealthDecMainQ9`/variant group; everything from `HealthDecMainQ10` onward,
plus the page-2 checkboxes and the page-3 signature/date fields, is still
blank. `migdal_health.pdf`'s equivalent output file has zero labeled rows,
confirming this stage's own worksheet for `migdal_health` genuinely starts
from scratch — the 77 labels are specific to the *deferred* second form, not
a resource this stage draws on directly.

Because the new worksheet format keys `label` strictly by field-id and never
trusts the old file's geometry columns (per "build-schema re-derives every
mechanical value fresh from the PDF" below), these 77 labels remain valid
regardless of the old tool's widget-selection bug — a label is text tied to
a question, not a position. **Decision: when the tool moves into the repo,
this file moves with it** (as a seed, e.g.
`/tools/pdf-mapping-tool/seeds/migdal_life_labels.csv`), rather than being
left behind in an untracked folder or silently discarded. It is not run
through this stage's `build-schema` — `migdal_life` is still stage 8's
concern, not this stage's — but preserving it now means that whenever
`migdal_life` is actually mapped, the freshly generated per-field skeleton
for that form is pre-filled from this seed by field-id (a field-id present
in both gets its label copied across, no retyping), rather than a person
redoing roughly 44% (77 of 176) of that form's label-writing from a blank
sheet.

### Corrected extractor requirements

- **One schema entry per widget, not per field.** The JSON schema's `fields`
  array items each carry exactly one `rect` and one `page` (per CLAUDE.md's
  format). A field with multiple widgets — every yes/no question, and any
  true PDF radio button — cannot be represented as one entry. `name` is
  allowed to repeat across entries; that's required for radio-style fields,
  where writing one value in stage 5 needs to affect every widget that shares
  the field name.
- **`rect` is the full `[x, y, w, h]` per widget**, not just lower-left x/y.
  Width and height are load-bearing for Phase B's input sizing and font-size
  derivation (CLAUDE.md's rendering rules).
- **`on` is read per widget from that widget's own `/AP /N` appearance
  dictionary**, never assumed. Verified directly against `migdal_health.pdf`:
  three distinct conventions coexist in the same form — `1`/`2` (144 fields),
  `True`/`False` (12), `Yes` (12) — and `migdal_life.pdf` adds a fourth:
  2 fields expose the on-value set `1`/`2`/`3`/`4`. That set is confirmed
  directly from those widgets' `/AP /N` dictionaries; **what it represents
  semantically is not verified and is not guessed at here** — it could be a
  single field standing in for more than a binary choice, or something else
  entirely. `migdal_life` is deferred to stage 8 ("expansion to the
  remaining insurance companies and forms," per `specification_document_EN.md`
  §15) — interpreting this convention is explicitly left to that stage, not
  assumed here. Assuming any one convention leaves the others `Off`
  with no error, per CLAUDE.md's PDFBox warning. This value is load-bearing
  twice: Phase B's structural validation checks a submitted value is a member
  of the field's actual on-values, and stage 5's fill step needs the same
  value to write a checked box correctly.
- **Page dimensions (`w`, `h`) are captured per page.** This is what lets
  Phase B convert a PDF-space rect (origin bottom-left, points) into a
  CSS-space position (origin top-left, pixels) — see "Coordinate conversion"
  under Phase B below.
- **Non-semantic field names are real fields, not extraction artifacts, and
  there is no reliable automated way to enumerate all of them.** A full
  manual read of all 251 field names in `migdal_health.pdf` (not a
  pattern-match — patterns miss entries like `Tedfdxt2`, a gibberish name no
  regex would flag) finds 27 with no semantic content at all: `1`, `2`,
  `Check Box1`, `Check Box2`, `Check Box8`, `Check Box10`, `Check Box11`,
  `Check Box12`, `Check Box13`, `Check Box14`, `Check Box55`, `Check Box56`,
  `Tedfdxt2`, `Text1`, `Text2`, `Text3`, `Text4`, `Text5`, `Text21`, `Text28`,
  `Text35`, `Text42`, `Text73`, `Text75`, `Text103`, `Text317`,
  `Textadsad2`. (An earlier pass through this design's brainstorming reported
  "16" — that number came from a narrower regex that happened to catch only
  some of the `Text*`/`Check Box*` fields and missed `Tedfdxt2` entirely; it
  was an artifact of an incomplete filter, not a verified count, and is
  superseded by this list.) `Textadsad2` and `Tedfdxt2` are confirmed by
  opening the rendered page image next to the field list to be real header
  cells; the numbered checkboxes are real yes/no boxes for the citizenship
  questions in section A. None are excluded, and none of them are special —
  every field's `label` comes from its position against the rendered image
  during hand-tagging regardless of whether its technical name looks
  descriptive, so this list is informational, not a separate code path.
  Because two of these field names are the bare strings `"1"` and `"2"`,
  **every code path that touches a field-id must treat it as a string**,
  never coerce it to a number.
- **Page rendering to WebP** (`PDFRenderer.renderImageWithDPI` per CLAUDE.md)
  is the other half of Phase A and does not exist in the prototype at all.
  CLAUDE.md already flags that JDK `ImageIO` has no WebP writer and that
  write support across third-party ImageIO plugins is less mature than read
  support. **This has not yet been empirically verified in this design's
  brainstorming** — no library has actually been used to write a WebP file
  from this codebase yet. It is the first concrete implementation task in
  Phase A, with a pass criterion that is a human opening the written file in
  an image viewer, not a successful method return.
- **`type` is normalized to a canonical string, not the raw PDFBox class
  name.** The prototype currently emits `field.getClass().getSimpleName()
  .replace("PD", "")` — `CheckBox`, `TextField`, `SignatureField`,
  `RadioButton` — not CLAUDE.md's schema vocabulary (`"checkbox"`, `"text"`,
  `"signature"`). This stopped being cosmetic the moment Phase B's submit
  validation became type-aware (see "The two answers this stage does not
  store" above): that logic branches on whether a field is
  checkbox/radio-shaped or text-shaped, and has no other source for that
  distinction. `CheckBox` and `RadioButton` both normalize to `checkbox`
  (both are validated against an on-values set); `TextField` normalizes to
  `text` (exempt from that check); `SignatureField` normalizes to
  `signature` (positioned in this stage, not part of answer validation at
  all — see "What's visible but not functional" below).
- **Fail loudly, not silently**, on the two conditions the prototype
  currently swallows: `acroForm == null` (the document has no form at all —
  this should abort with an error, not silently write an empty CSV), and "no
  widget found for a field" (should abort, not default to page 1).

### The worksheet: CSV, hand-edited, per field — not per widget

The CSV survives, but only as a hand-tagging intermediate — the actual
contract other code depends on is the JSON schema `build-schema` produces
from it, never the CSV itself.

**Only `label` is hand-tagged in this stage.** `rule` and `exclusion-form-id`
depend on the Client's exclusion-rules table (`specification_document_EN.md`
§5: "the exclusion rules will not be written inside the code... stored in a
separate data table"). Checked directly against the repo: no such table or
data file exists anywhere yet — §16 ("What the Client Provides") lists
"definition of the exclusion rules for each company" as something still
owed, not something in hand. The worksheet skeleton still carries `rule` and
`exclusion-form-id` as columns (so the file format doesn't need to change
shape again once that table exists), but both are left blank for every field
in this stage, and `build-schema` treats a blank `rule` as `false` and a
blank `exclusion-form-id` as absent — CLAUDE.md's own schema example already
shows `rule` as an optional, sparingly-set flag, not a value every field
needs. Populating these two columns for real is stage 4's work, once the
Client's rules table exists to populate them from.

- **One worksheet row per unique field-id**, not per widget. A human writes
  one Hebrew `label` for one question; a question with two widgets (yes and
  no) doesn't need the label written twice. The `build-schema` step looks up
  a field-id's worksheet row once and applies its `label`/`rule`/
  `exclusion-form-id` to every widget-level schema entry sharing that
  field-id.
- **The worksheet's `field-id` column is machine-generated, never
  hand-typed.** The extractor emits the worksheet skeleton — one row per
  unique field-id already filled in, sorted by page then position, with
  `label`/`rule`/`exclusion-form-id` left blank — and a person edits only
  those three columns. Field-ids are non-semantic technical strings (see
  above); hand-typing one is exactly where a transcription error would enter
  and silently break the join to the schema.
- **`build-schema` re-derives every mechanical value fresh from the PDF** —
  type, on-value, rect, page, page dimensions — and joins it to the
  worksheet's `label`/`rule`/`exclusion-form-id` by field-id. None of the
  mechanical data round-trips through the CSV. This is a safety property, not
  just a convenience: Excel silently corrupts values on open/save even
  without deliberate editing (`"01"` becomes `1`, `"03/2024"`-shaped strings
  become dates), and CLAUDE.md already establishes that an invalid on-value
  fails silently downstream. Making the PDF the sole source of mechanical
  truth removes an entire class of "the worksheet looked fine but the schema
  is wrong" bugs.
- **A SHA-256 hash of the source PDF is recorded in a metadata row at the top
  of the worksheet** when the skeleton is generated, and `build-schema`
  recomputes and compares it against the PDF it's about to read. A mismatch
  aborts with an error, rather than silently attaching a person's careful
  hand-tagging to geometry that shifted after a corrected PDF replaced the
  original mid-mapping. The metadata row is recognized and skipped by the
  field-id matching rule below, not treated as a stray field-id.
- **Fail loudly on any field-id mismatch between worksheet and PDF**,
  compared as a **set of unique field-ids**, not a widget or row count (a
  field-id with four widgets in the PDF correctly matches one worksheet row).
  A field-id present in one side and missing from the other — typo, or a
  field added to the PDF after the worksheet was generated — aborts
  `build-schema` with the specific field-ids listed on both sides, rather
  than emitting a schema silently missing a question.
- **Label propagation**, once the worksheet is joined: a label written once
  applies to every widget sharing that field-id, and where the same logical
  question repeats across variants (the six `Q{n}` variants observed in this
  form's structure — main/spouse and four child slots), the same label
  propagates across those variants too, so the label is written once per
  question rather than once per variant × widget. `aria-label` for each
  rendered input then composes the question's label with a per-variant
  section identifier (main / spouse / child 1–4) and, for yes/no widgets, a
  channel/state identifier — so a screen reader distinguishes "child 3, does
  the child smoke — yes" from "child 3, does the child smoke — no", not two
  inputs with an identical label.

### Two independent task tracks

Per CLAUDE.md's Phase A/B separation, this stage's work splits into two
tracks that must not share dependencies:

- **Phase A** (`/tools/pdf-mapping-tool`, its own Maven project inside the
  repo — the existing prototype moved in, per "What already exists" above.
  Its own-Maven-project structure is confirmed as the right structural
  choice: CLAUDE.md is explicit that this tool is "not part of the running
  service", and there is no shared Java code between it and the backend —
  the schema is a JSON data contract, not a shared class). PDFBox and, once
  verified, a WebP-writing ImageIO plugin live only here.
- **Phase B** (the backend service and frontend, in the existing
  `backend`/`frontend` layout) reads the JSON schema and serves WebP images
  as static content. It has no PDFBox dependency in this stage — PDFBox only
  enters the running service in stage 5, when it starts filling the real PDF
  at request time.

### Mapping time as a measured, reportable quantity

`specification_document_EN.md` §12: mapping duration is measured in practice
and used as the pricing basis for every future company. This stage is the
first real measurement, not a formality — the hand-tagging time spent on
`migdal_health.pdf`'s worksheet is timed and recorded as part of this
stage's definition of done, in whatever form is convenient (a note in the
plan's execution log is sufficient — no tooling is being built to automate
this). **The timed work is the `label` column only.** `rule` and
`exclusion-form-id` are left blank in this stage (see above — the Client's
exclusion-rules table doesn't exist yet), so no time is spent on them here;
including their eventual hand-tagging time in this stage's number would
inflate the §12 pricing basis with work that didn't happen yet. When stage 4
populates those two columns, that is a separate, later timing entry, not a
correction to this one.

## Phase B — the insured's filling screen

### Company/type selection is data, not a hardcoded pair

Stage 1's deferred item and `specification_document_EN.md` §12 both require
that insurance types are per-company data (Migdal alone has four: life,
health, mortgage life, abbreviated health — not the two a hardcoded
assumption would guess). This stage has exactly one form to offer
(`migdal_health`), but the agent's company/type picker is built against a
small reference table (`insurance_form`: company, insurance type label, and
which schema file backs it) with a single row, rather than a hardcoded
dropdown — so adding the second form later (`migdal_life`, already scoped
above as this pipeline's next target) is a data insert, not a code change.
Full catalog-management UI for this table is stage 8's "expansion to the
remaining companies" concern, not this stage's — a single seeded row is
enough to prove the picker is data-driven.

### Data model

```
submission
  id                  uuid pk
  agent_id            uuid fk -> agent.id     -- owner; the tenant-isolation column
  insurance_form_id   fk -> insurance_form.id
  insured_name        text
  insured_email       text
  token_hash          text unique             -- see "Token handling" below
  status              text                    -- PENDING | FILLED
  sent_at             timestamptz
  expires_at          timestamptz
  filled_at           timestamptz null
  created_at          timestamptz
```

"Expired" is a derived condition (`status = 'PENDING' AND expires_at <
now()`), not a third stored status — nothing in this stage runs a background
job to flip expired rows to some other state, and adding one would be
speculative infrastructure this stage doesn't need. Both the submit endpoint
and the token filter check `expires_at` directly.

One table, not a separate attempt-history table. A history of failed
open/submit attempts would help the "form wasn't sent" investigation gap
(below), but that gap is explicitly recorded as a later stage's deliberate
design problem, not something to speculatively half-solve here by shaping
this stage's schema around it — CLAUDE.md's YAGNI stance applies as much to
data modeling as to code.

**Token handling:** the token itself is never stored — only its hash, the
same pattern already used for passwords, and for the same reason: a database
compromise should not directly hand over usable insured links. The raw token
exists only in the generated URL and, per the decision below, in the
send-response shown to the agent.

**Concurrency:** this update only runs after structural validation passes
(see "The two answers this stage does not store" above) — a validation
failure returns 400 before reaching this statement at all, leaving the row
untouched. The three token-lifecycle failures identified while brainstorming
this stage — a used link opened again, a double-submit, and an expired
token — are guarded by one conditional update:
`UPDATE submission SET status='FILLED', filled_at=now() WHERE id=? AND
status='PENDING' AND expires_at > now()`, checking the affected-row count.
Two concurrent submits (e.g. a double-click, or two tabs) race on this single
`UPDATE`; exactly one succeeds, the other observes zero rows affected and is
rejected — no separate locking needed.

### The third filter chain

Per stage 1's design doc, the security configuration already anticipated
this: "adding \[the insured's access\] later means adding a third,
independent filter chain, not modifying either login path." This stage
builds that chain:

- The insured never logs in and has no session cookie. The one-time token,
  carried in the URL path (`/api/insured/{token}/...`), is the entire
  credential for every request in this flow.
- A dedicated filter validates the token against `submission.token_hash` on
  every request: unknown hash → 404, `status != PENDING` → 410 (already used
  or otherwise closed), `expires_at` in the past → 410. On success it
  establishes a request-scoped principal scoped to exactly that one
  submission id — no broader agent/admin authority leaks into this path, and
  this path grants no authority over any other submission.
- Agent/admin authentication is untouched by this addition, per stage 1's
  design intent — this is a new, parallel `SecurityFilterChain`, not a
  modification of either existing one.
- **CSRF is explicitly disabled on this chain.** Spring Security's CSRF
  filter is on by default per chain (confirmed in
  `backend/src/main/java/com/insuranceforms/security/SecurityConfig.java` —
  `agentChain` and `adminChain` both wire
  `CookieCsrfTokenRepository.withHttpOnlyFalse()` explicitly); left at that
  default here, `POST /api/insured/{token}/submit` would 403 with no session
  and no CSRF cookie ever issued to this flow — stage 1's CSRF problem,
  mirrored. The justification is not "skip CSRF for convenience": CSRF
  defends against a browser silently attaching a victim's *ambient*
  credential (a cookie) to a forged cross-site request. This chain has no
  ambient credential at all — the token is a value the insured's own browser
  must already know and place explicitly in the URL path, which a forging
  site cannot do blind. There is no cookie for a forged request to ride, so
  there is nothing for CSRF protection to defend. This exact pattern already
  exists in the same file for the same reason: `logoChain` (`@Order(3)`,
  `/logos/**`) disables CSRF with `.csrf(AbstractHttpConfigurer::disable)`
  because it too is a public, cookie-free route. The insured chain follows
  that precedent, not a new one.

### Endpoints

- `POST /api/agent/submissions` (agent session required) — body:
  `{ insuranceFormId, insuredName, insuredEmail }`. Creates the `submission`
  row and token, sets `agent_id` from the session (never from the body).
  Returns `{ id, status, insuredLink }`.
  **`insuredLink` is an explicit, temporary dev/demo affordance** — the real
  delivery channel is the email stage 5 adds. It is called out in the
  response schema's documentation as such, is expected to be removed or
  gated once stage 5's email exists, and does not relax any token rule
  above: displaying the link is not the same as extending its life, and it
  is still single-use and still expires.
- `GET /api/agent/submissions/{id}` (agent session required) — returns the
  caller's own submission; **403 if the id belongs to another agent**. This
  is the exact test stage 1 deferred: no id-addressable resource existed
  until this endpoint does.
- `GET /api/insured/{token}` (token filter chain) — returns which
  `insurance_form` this submission is for (company/type/schema+image paths),
  so the frontend knows what to load. Returns 404/410 per the token-validity
  rules above.
- `POST /api/insured/{token}/submit` (token filter chain) — body: the answers
  map. Performs the type-aware structural validation described above first;
  on failure, returns 400 per the two failure classes there (unknown
  field-ids, or invalid values on a checkbox/radio field — schema data only,
  never the submitted value) and leaves the submission `PENDING`. Only on
  success does it run the conditional status transition, then discard the
  answers. Returns a bare success/failure — never the submitted content,
  in either outcome.
- The JSON schema file and WebP page images themselves are served as
  **public static content** (e.g. `/forms/migdal_health/schema.json`,
  `/forms/migdal_health/p1.webp`), not behind the token filter — the blank
  form's layout is identical for every insured and carries no insured-
  identifying data, the same reasoning stage 1 already applied to the
  agent's logo. The token-gated endpoint only ever returns which form to
  load and whether this particular link is still valid.

### Yes/no fields: two widgets, one value

Phase A emits one schema entry per widget (above), so a yes/no question —
164 of `migdal_health`'s 251 fields — arrives at Phase B as two separate
`fields` entries sharing one `name`: one entry at the "yes" position with its
own `on` value, one at the "no" position with its own `on` value. Rendered
naively as two independent checkboxes, nothing stops both being checked at
once, and the answers map would need two keys for what the PDF and the rules
engine both treat as a single value.

The frontend collapses them back into one control: both widgets render as
native `<input type="radio">` elements sharing the same `name` attribute
(the field's `name` from the schema), each with its own `value` set to that
widget's `on`. The browser's native radio-group behavior — not custom
selection-tracking logic — enforces mutual exclusivity, because that's
exactly the semantics a PDF radio-style field already has at the AcroForm
level (one field, one current value, expressed across widgets). Each input
still gets its own `aria-label` per the propagation rule above (distinct
"yes" and "no" wording), so the pairing is a rendering/state detail, not an
accessibility regression. The answers map this produces has exactly one key
per such field — `{ [name]: selectedOnValue }` — never two independent
booleans, which is what makes the structural-validation rule above ("on
values, no `Off` member") the correct one for this shape rather than a
special case bolted on afterward.

### Rendering and the coordinate conversion

The screen is a fixed-size container holding the page image as a backdrop,
with inputs absolutely positioned from the schema's `rect` and scaled by one
CSS `transform: scale()` on the container, per CLAUDE.md's rendering rules.
Two conversions happen once per field, in the frontend:

- **Y-axis flip:** PDF space has its origin at the bottom-left; CSS has its
  origin at the top-left. A field's PDF `y` becomes `pageHeightInPoints - y -
  height` before it's usable as a CSS `top`.
- **Points → render pixels:** the schema's `rect` is in PDF points (1/72
  inch); the page image was rendered at a specific DPI. A coordinate is
  scaled by `renderDpi / 72` to land on the correct pixel in that image.

Both conversions are simple arithmetic and not new risk in themselves — the
risk CLAUDE.md and this design's brainstorming both flagged is a **silent**
off-by-a-constant that produces a plausible-looking but wrong overlay, which
a values-only test cannot catch (an input can be bound to the right field
and still be drawn in the wrong place). The acceptance criterion for this
part of Phase B is therefore explicitly visual: rendering `migdal_health`'s
page 1 in a real browser and confirming, by eye, that a known field's input
box sits inside its printed box on the image — not a passing unit test on
the arithmetic alone.

### What's visible but not functional in this stage

- **The three signature fields** (`migdal_health.pdf` has widgets for
  client, spouse, and agent signatures) are positioned on screen from the
  schema like any other field, so the layout is complete and matches the
  real form — but nothing is drawn into them. Signature *capture* (the
  drawing canvas) is stage 5. This is a deliberate scope line, not a gap
  that surfaces unannounced: the boxes are visibly present and visibly
  inert.
- **No PDF is generated at submit time.** The real fill-and-verify pipeline
  (Bidi reordering, `PDType0Font`, read-back verification, and above all
  CLAUDE.md's requirement that a human open and look at the result) belongs
  entirely to stage 5, where it can be checked against a complete artifact —
  signatures included, exclusions included. Building a partial version here
  would exercise the Hebrew-fill machinery against something that is not yet
  "the form sent to the company," and would mean a filled PDF sitting on
  disk with synthetic data as its only lawful content. What this stage needs
  to prove — that the screen captured the right value at the right field —
  is proven by the structural-validation test on the payload, with no PDF
  involved.

## Deferred items landing in this stage

Verified directly against `2026-08-14-stage1-infra-registration-isolation-design.md`
and `specification_document_EN.md` §11, not assumed from memory:

- **Tenant isolation by URL-path id** (design doc, "Tenant isolation
  pattern"): "agent A requests agent B's submission by id in the URL path and
  gets a 403... Stage 2's definition of done must include that second test."
  Delivered by `GET /api/agent/submissions/{id}` above.
- **Admin's aggregate-only usage projection** (design doc, "Admin visibility
  boundary"): "admin's per-agent usage count must be a dedicated aggregate
  projection... admin-facing code must never be given a dependency on the
  row-returning submissions repository." Once `submission` exists, admin's
  usage count is `SELECT agent_id, COUNT(*) FROM submission GROUP BY
  agent_id` behind its own repository — never `SubmissionRepository` reused
  from agent-facing code.
- **The "form wasn't sent" gap** (`specification_document_EN.md` §11 and the
  design doc): admin has no submission-level visibility, so cannot
  investigate a complaint that a form never arrived. This stage does not
  solve it — it is recorded again here, deliberately, so it is designed on
  purpose in a later stage rather than discovered during a support incident.
  Nothing in this stage's data model (e.g. an attempt-history table) is being
  speculatively added against this gap now.

## Testing plan

- **Phase A extractor**, run against `migdal_health.pdf`: widget count
  matches ground truth (421); every multi-widget field's every widget
  appears as a separate schema entry; every checkbox's `on` value matches
  what's actually in that widget's `/AP /N` (spot-checked against the three
  known conventions); page dimensions present and correct; `acroForm==null`
  and "no widget for field" both abort with an error rather than producing
  partial output.
- **`build-schema`**: a worksheet field-id absent from the PDF, and a PDF
  field-id absent from the worksheet, both abort with the specific field-ids
  named; a modified PDF (different hash) aborts before producing a schema; a
  label written once for a field-id appears on every widget-level entry
  sharing that field-id.
- **Tenant isolation**: agent A creates a submission; agent B requests it by
  id and gets 403 (the deferred test above). **Positive control in the same
  test:** agent B then requests their *own* submission by id and gets 200
  with the correct data — proving the 403 comes from an actual ownership
  check and not from an endpoint that blanket-rejects every `GET` by id
  regardless of whose it is, which would pass the 403 assertion alone just
  as well while checking nothing.
- **Admin boundary**: admin's usage-count endpoint returns aggregates only —
  a test asserting the endpoint's admin-facing code has no dependency on the
  submission-detail repository, not just that the response happens to omit
  fields.
- **Token lifecycle**: a used token opened again is rejected (410); a second
  concurrent submit against the same token has exactly one winner; an
  expired token is rejected (410); an unknown token is rejected (404); a
  request with an agent session cookie but no token cannot reach
  `/api/insured/**`, and vice versa.
- **Structural validation**, with teeth in both directions:
  - A field-id outside the schema → 400, and the response body names that
    exact field-id.
  - An invalid value on a checkbox field → 400, the response body names that
    field-id and its allowed on-values, and — the no-echo control — the
    response body does **not** contain the value that was actually
    submitted. A test that only checked the status code would pass even if
    the endpoint echoed the answer back; this test inspects the body
    specifically to rule that out.
  - A validation failure leaves the submission `PENDING`, not `FILLED`, and
    the token still unconsumed. **Positive control:** re-submit the same
    token with a valid payload immediately afterward and confirm it
    succeeds — proving the failed attempt didn't silently burn the
    single-use token or otherwise leave the submission in a state a
    legitimate retry can't recover from.
  - A text field submitted with an arbitrary free-form string is accepted —
    proving check (b) is actually skipped for text fields, not merely
    documented as skipped while the code still rejects them.
  - The `Off` pair, tested together so one can't silently regress the
    other: a multi-widget yes/no field submitted with `Off` → 400 (not a
    member of that field's on-values); a genuine single-widget checkbox
    submitted with `Off` → accepted.
  - A successful submit's response contains no answer content, for any
    field type.
- **Rendering**: manual — open `migdal_health` page 1 in a real browser at
  the DPI Phase A rendered at, and visually confirm a known field's input
  lands inside its printed box on the image. Read-back or unit-tested
  arithmetic alone does not satisfy this criterion, per CLAUDE.md. **Negative
  control, run once before trusting the positive result:** deliberately
  offset one known field's `rect` by a fixed, visible amount (e.g. 50px) and
  confirm a human doing the same by-eye check actually notices and flags it
  as wrong. This proves the manual check is capable of catching the class of
  bug it exists to catch, before relying on it to confirm the un-offset
  version is correct — a visual check that would pass a visibly-wrong
  overlay isn't a check at all.
- **Mapping time**: recorded, not asserted by a test — see "Mapping time as a
  measured quantity" above.

## Definition of done

- `/tools/pdf-mapping-tool` exists inside this repo, version-controlled, and
  the previously untracked prototype at
  `C:\Users\User\eclipse-workspace\pdf-mapping-tool` is superseded by it.
  The `migdal_life` 77-label seed file has been carried over, not left
  behind.
- Phase A's corrected extractor runs against `migdal_health.pdf` and produces
  a schema matching every fact verified in this document (421 widgets, three
  on-value conventions, page dimensions, no silent failures).
- A WebP writer has been used to actually produce a page image, and a human
  has opened the resulting file and confirmed it renders correctly — not
  assumed from a library's read-support claim.
- The worksheet → `build-schema` pipeline produces a schema for
  `migdal_health` that a human has reviewed for label correctness on at
  least the non-semantic fields (`Textadsad2`, `Tedfdxt2`, the numbered
  `Check Box*` fields, bare `"1"`/`"2"`), with `rule`/`exclusion-form-id`
  blank throughout, per the scope decision above.
- The insured's screen, run in a real browser against a real submission
  token, visually matches the source form's layout for at least page 1 —
  and the negative-control check (a deliberately offset field is correctly
  flagged as wrong) has actually been run once, not assumed to work.
- The tenant-isolation test includes both the 403 (agent A on agent B's
  submission) and the positive-control 200 (agent B on their own).
- A validation-failure 400 (unknown field-id, or invalid checkbox value)
  never echoes the submitted value, leaves the submission `PENDING`, and a
  same-token retry with a corrected payload has been proven to still
  succeed afterward.
- All three deferred items above are addressed as specified (two delivered,
  one deliberately re-recorded, not solved).
- The mapping time for `migdal_health.pdf`'s `label` column has been
  recorded.
- All tests in the plan above pass.

## Deferred (explicitly out of scope for this stage)

- The exclusion rules engine and all `rule`/`exclusion-form-id` *values* —
  the Client's exclusion-rules table doesn't exist yet (verified: no such
  file anywhere in the repo), so stage 2 reserves the columns in the
  worksheet/schema format but leaves them blank for every field. Populating
  them is stage 4's work, once that table exists to populate them from.
- Signature capture (the drawing canvas) and everything about producing or
  verifying an actual filled PDF — stage 5.
- Sending any email — stage 5. The agent-visible `insuredLink` in the send
  response is this stage's stand-in, explicitly temporary.
- `migdal_life.pdf` and every company/form beyond `migdal_health` — stage 8,
  though this stage's `insurance_form` table is built so that adding
  `migdal_life` next is a data insert.
- Mobile/responsive layout for the filling screen (`specification_document_EN.md`
  §13 ties this to a separate agreement, not this stage).
- Any resolution of the "form wasn't sent" admin visibility gap beyond
  re-recording it.
- An attempt-history table or any other speculative support for the above
  gap.
