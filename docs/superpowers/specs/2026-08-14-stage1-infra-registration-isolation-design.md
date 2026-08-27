# Stage 1 Design — Infrastructure, Agent Registration, Tenant Isolation

## Context

This is stage 1 of 9 from `specification_document_EN.md` §15: infrastructure setup,
agent registration, and separation between agents. Everything else in the system
(form mapping, the insured's filling screen, the rules engine, billing, the
standalone forms repository) builds on top of this stage, so it is being designed
and built first, in isolation.

Two forms are already in the repo for later stages (`migdal_health.pdf`: 3 pages,
251 fields — 168 checkboxes, 80 text fields, 3 signature fields; `migdal_life.pdf`:
3 pages, 176 fields — 148 checkboxes, 25 text fields, 3 signature fields). They are
not touched in this stage, but their scale (multi-page, multiple signatures) is
noted here so stage 2's schema and rendering design treats multi-page as the
default case, not an edge case, and does not assume a single repeating signature.

## Scope

In scope:
- Project scaffolding (backend + frontend)
- Agent self-registration and login
- Admin (platform owner) login and a read-only agent list
- The central tenant-isolation pattern, enforced and tested
- The admin visibility boundary, enforced and tested
- Logo upload, storage, and replacement

Explicitly out of scope for this stage (see "Deferred" below): everything related
to insured people, submissions, forms, rules, billing, and email.

## Tech stack decisions

- **Backend framework: Spring Boot**, with **Spring Data JPA** for persistence.
  Spring Data JPA repository interfaces are declared with no implementation body
  (e.g. `interface AgentRepository extends JpaRepository<Agent, UUID>`) — Spring
  generates the implementation at startup and injects it wherever it's needed via
  dependency injection, so application code never calls `new AgentRepository()`.
- **Authentication: server-side sessions** via Spring Security, not JWT. There is
  no mobile app or third-party API consumer in the spec — only the agent's own
  React frontend — so a stateless token brings no benefit here. This does mean
  CSRF protection needs to be deliberately wired for a decoupled SPA, not
  assumed free — see Security configuration below.
- **Session storage: `spring-session-jdbc`**, not the default in-memory
  `HttpSession` map. In-memory sessions are lost on every restart and don't work
  across more than one backend instance; `spring-session-jdbc` persists sessions
  to PostgreSQL so a deploy doesn't silently log out every agent.
- **Password hashing:** Spring Security's default `BCryptPasswordEncoder`.

## Repo layout

Monorepo: `/backend` (Spring Boot, Maven) and `/frontend` (React + TypeScript).
`forms/` and the specification documents stay at the repo root.

## Data model

```
agent
  id                uuid pk
  full_name         text
  email             citext unique -- case-insensitive; see Security below
  password_hash     text
  israeli_id        text          -- validated with the standard check-digit algorithm
  agency_name       text
  mobile            text
  agent_number      text unique   -- prevents re-registering for a second trial
  logo_id           uuid null     -- opaque id into LogoStorage; null until uploaded
  is_active         boolean       -- default true; disables login without deleting the row
  trial_started_at  timestamptz   -- set at registration; enforcement is stage 7
  created_at        timestamptz
  updated_at        timestamptz

admin
  id                uuid pk
  full_name         text
  email             citext unique -- case-insensitive; see Security below
  password_hash     text
  created_at        timestamptz
  updated_at        timestamptz
```

`citext` is a Postgres extension type (`CREATE EXTENSION IF NOT EXISTS citext;`)
that behaves like `text` but compares and indexes case-insensitively, so
`Ruth@x.com` and `ruth@x.com` collide as the same address instead of creating two
rows. Decided now, while these tables are new — changing a column's type after
real rows exist is a much more painful migration.

`spring_session` / `spring_session_attributes` are created automatically by
`spring-session-jdbc` — not hand-designed.

There is intentionally no foreign key or shared table between `agent` and
`admin`. They are separate identities with separate logins (see below), even
though the Client operates both (see "Admin visibility boundary").

## Security configuration

**Two separate login endpoints**, not one shared login that infers role from
the email: `POST /api/auth/agent/login` and `POST /api/auth/admin/login`, each
backed by its own `UserDetailsService` (Spring Security's interface for "how do
I load a login-capable identity" — one implementation reads from
`AgentRepository`, the other from `AdminRepository`). Which endpoint
successfully authenticated the request determines the session's role
(`ROLE_AGENT` or `ROLE_ADMIN`) — there is no code path that searches both
tables for a matching email. This is what resolves the email-collision case:
the Client needs one remembered identity to log in as both an agent and as
admin, most likely with the same email in both tables — expected, not a
conflict, since uniqueness is per-table and role comes from the endpoint.

**`is_active` is enforced through `UserDetailsService`, not custom logic, for
new logins** — `UserDetails` (the object Spring Security uses to represent a
login-capable identity) has an `isEnabled()` method designed for exactly this
case; a deactivated agent's `UserDetailsService` implementation returns
`isEnabled() = false`, and Spring Security rejects the login natively.

That alone doesn't touch a session opened *before* deactivation — the agent
would keep working on their existing cookie, which is exactly the stage-7
subscription-cancellation scenario. So deactivation must also **actively
invalidate that agent's existing sessions**, not just block new ones:
`spring-session-jdbc`'s `JdbcIndexedSessionRepository` indexes stored sessions
by principal name, so setting `is_active = false` also looks up every session
indexed under that agent's principal and deletes it immediately. No custom
session-tracking table is needed — this is a lookup against the index
Spring Session already maintains.

**CSRF protection stays on, configured for a decoupled SPA — it is not free
with sessions.** Spring Security's CSRF filter is enabled by default and, left
unconfigured for a separate-origin React app, rejects every POST — including
registration and login — with a 403, since it expects a token embedded in a
server-rendered form. The fix is `CookieCsrfTokenRepository.withHttpOnlyFalse()`:
Spring sets a JS-readable CSRF cookie (distinct from the HttpOnly session
cookie), and the shared frontend `apiClient` reads it and echoes it back as an
`X-XSRF-TOKEN` header on every mutating request — axios does this
automatically once configured with matching cookie/header names. A small
`GET /api/csrf-token` bootstrap call on app load guarantees the cookie exists
before the first POST. CSRF is not being disabled: for a cookie-session app,
turning it off would be a real session-riding vulnerability, not a
simplification.

**Password policy:** minimum 10 characters, no mandated composition rules
(no forced digit/symbol/case mix) — current guidance favors length over
composition, since composition rules tend to push people toward weaker,
pattern-based passwords.

**Session timeout is explicit, not Spring Boot's 30-minute default** — an
agent midway through filling in their profile or account details should not be
silently logged out. Set to 8 hours (a working day), sourced from
configuration (bindable from an environment variable via Spring Boot's
relaxed property binding) rather than hardcoded, so it can be tuned in
production without a code change.

**CORS and cookies are both environment-driven, not hardcoded** — hardcoding
`localhost:3000` would silently break the moment stage 3 deploys the frontend
to a real address, the same class of mistake CLAUDE.md's "cloud config only at
deploy time, via env" rule exists to prevent for the database connection:
- `CorsConfigurationSource`'s allowed origin comes from a `FRONTEND_ORIGIN`
  environment variable (`allowCredentials(true)` still requires an explicit
  origin, not a wildcard — the value is explicit, just not hardcoded), defaulting
  to `http://localhost:3000` in local dev.
- Session cookie flags: `HttpOnly` is Spring's default and needs no change.
  `SameSite=Lax` is the default for this stage — the frontend and backend are
  "same-site" under the SameSite definition (same registrable domain) even
  though they run on different ports in dev; this should keep holding in
  production as long as frontend and API stay on the same domain, which stage
  3's actual deploy will confirm. `Secure` is driven by a `COOKIE_SECURE`
  environment variable / Spring profile — `false` for local HTTP dev (there is
  no HTTPS locally), `true` in production, rather than one hardcoded value that
  would either break local dev or ship insecure.

  **This `SameSite=Lax` assumption is deployment-dependent, not a settled
  fact — it becomes an explicit stage 3 done-criterion, not just a note here.**
  It holds only if the frontend and API end up served from the same site
  (same registrable domain) in production. If stage 3's actual cloud
  deployment puts them on two different providers/domains, they become
  cross-site, and a `SameSite=Lax` cookie is simply never sent on a cross-site
  request — login would fail in the cloud with no equivalent local failure,
  since local dev's matching-domain-different-port setup is same-site and
  would keep working right up until deploy. **Stage 3's definition of done
  must include verifying, after the first real deployment, that the session
  cookie is actually being sent on a real cross-origin request** — and if it
  isn't, deciding then between `SameSite=None` with `Secure=true`, or serving
  the frontend from the same origin as the API. Recorded here so this isn't
  discovered as a mysterious cloud-only login failure with no clear cause.

The insured's future access is a **one-time link token**, valid for one request
only and for a limited time — architecturally unrelated to agent/admin login.
It is not built in this stage (there is nothing yet to send a link to), but the
security config is structured so that adding it later means adding a third,
independent filter chain, not modifying either login path above. Agent/admin
authentication must never leak into the insured's flow.

## Tenant isolation pattern

`AgentRepository` (Spring Data JPA) is the **only** code path that touches the
`agent` table. Every read is filtered by the agent id read from Spring
Security's authentication context (the request-scoped record of who is logged
in) — never from a request parameter, query string, or request body field.

**Test required in this stage:** agent A logs in and calls the account-update
endpoint with a payload that includes agent B's id. The test asserts that only
agent A's row changes and agent B's row is untouched — proving the id-from-session
rule holds even when a client tries to override it.

This only covers the request-body-id attack vector. The URL-path-id vector
(e.g. `GET /api/requests/{id}` where the id belongs to another agent) can't be
tested yet, because no id-addressable resource exists until stage 2 introduces
submissions. **Stage 2's definition of done must include that second test** —
agent A requests agent B's submission by id in the URL path and gets a 403. This
is being written down explicitly so it isn't assumed already covered once this
stage's narrower test passes.

## Admin visibility boundary

The Client is herself a practicing insurance agent — a competitor to the agents
who buy this system. An admin role with full visibility into agents' data would
let her (as platform owner) see a competing agent's client list, which is a
product-adoption blocker, not just a privacy concern: no agent would buy a
system where a competitor-vendor can see their insureds. This requirement is now
recorded in `specification_document_EN.md` §11, since it existed nowhere before
this stage's design work.

Admin is permitted, as a **general, system-wide scope** (not all of it available
yet — see below): the agent list, each agent's subscription/billing status,
per-agent submission **counts** (for billing), and management of insurance
companies, forms, and rules.

Admin is forbidden, for the system's entire lifetime: insured identity (name,
email) and which form was sent to whom.

**Enforcement principle:** the restriction lives in the shape of the query, not
in a runtime role check that strips fields from a shared result. A shared
repository with an `if (role == ADMIN) omit these fields` branch is exactly the
"scattered checks" pattern CLAUDE.md prohibits for agent isolation — it's easy
to forget in a later addition. Instead, admin-facing code depends only on
narrowly-typed, admin-specific queries whose return shape cannot contain
insured data. In this stage, that means:

- `GET /api/admin/agents` returns `full_name`, `agency_name`, `email`,
  `mobile`, `agent_number`, `is_active`, and `trial_started_at` — safe to build
  directly against `agent`, since that table holds no insured data at all.
  **It does not return a subscription status in this stage** — that's the gap
  between the general permitted scope above and what actually exists right
  now: no subscription-status field exists yet, only `trial_started_at`. Stage
  7 (trial, subscription, billing) adds the field(s) needed to represent real
  subscription state and extends this endpoint then.
- The stricter separate-repository rule becomes load-bearing in stage 2, once
  the `submission` table exists and mixes agent-owned and insured-identifying
  columns. At that point, admin's per-agent usage count must be a dedicated
  aggregate projection (e.g. `SELECT agent_id, COUNT(*) FROM submission GROUP
  BY agent_id`, returned as a DTO with no insured columns), and admin-facing
  code must never be given a dependency on the row-returning submissions
  repository. This is recorded as a stage 2 done-criterion.

**Admin account provisioning:** no seeded password in a migration file — a
migration is permanent, and a password committed to it is a secret permanently
in git history, which CLAUDE.md's "all secrets in env, never in code" rule
prohibits regardless of where in the codebase it lives. Instead, on startup, if
no `admin` row exists yet, the application creates one from
`ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` environment variables,
hashing the password immediately. Both variables are documented with no value in
`.env.example`, matching every other secret in this project.

**Test required in this stage:** admin calling an agent-only endpoint gets a
403; an agent calling an admin-only endpoint gets a 403.

**Known open question, not solved in this stage:** if an agent reports that a
form "was never sent," admin currently has no way to investigate — not even
non-identifying metadata like a timestamp or delivery status — because admin
has no submission-level visibility at all. This is recorded in
`specification_document_EN.md` §11 and must be designed deliberately in a later
stage, before real customers are onboarded, rather than discovered during a live
support incident.

## Logo storage and upload

**Delivery mechanism:** a logo is shown both in the on-screen filling interface
and inside emails sent to insureds. Email clients cannot authenticate, so an
auth-gated file endpoint doesn't work for the email case — and using two
different mechanisms (a URL for the screen, a MIME inline attachment for email)
would mean two code paths that can drift apart. Both cases are served by one
mechanism instead: a **public, unauthenticated URL**. This is not a privacy
exception — a logo is branding the agent explicitly wants insureds to see; it
was never confidential.

The URL path is an **opaque random id**, not the agent's own id, so that
agent ids can't be enumerated to scrape every agent's logo — the same "long
random identifier" pattern the spec already uses for the insured's link token.

`LogoStorage` interface:
```
store(bytes, contentType) -> LogoRef { id, url }
resolve(id) -> InputStream
delete(id)
```
Stage 1 implementation: `LocalDiskLogoStorage`, writing to local disk, served by
a public Spring route `GET /logos/{opaqueId}`. The cloud storage provider isn't
chosen yet (per `specification_document_EN.md` §16, the Client opens cloud
accounts separately); swapping in a cloud-backed implementation later — most
likely with public-read object ACLs — requires no change to callers, matching
how the database connection is only configured at deploy time.

**Upload validation and reprocessing** (this is the first file upload in the
system):
- Accepted formats: PNG, JPEG only — no SVG (SVG can carry embedded
  scripts, not worth the risk for a logo). WebP was originally in scope but
  dropped during Task 13: the JDK's built-in `ImageIO` has no WebP decoder,
  and adding one would mean a new third-party image-decoding dependency
  purely for a logo upload — not worth the added attack surface (image
  decoders are a classic CVE source).
- Validate by **decoding** the bytes with `ImageIO`, not by trusting the file
  extension or the client-supplied `Content-Type`. Anything that fails to
  decode as a genuine raster image is rejected, regardless of what it claims to
  be.
- **Re-encode** the decoded image to a fresh file before storing — this is what
  strips EXIF/XMP metadata and neutralizes anything else embedded in the
  original bytes. The originally uploaded bytes are never persisted as-is.
- Resize to at most 512px on the long edge, preserving aspect ratio, during the
  re-encode — a logo doesn't need to be large.
- Cap upload size at 2MB before decoding.

**Replacement:** uploading a new logo must not leave the old one reachable
forever or accumulate orphaned files. Order of operations: store the new file
(new opaque id) → update `agent.logo_id` to point at it → delete the old file.
If the final delete fails, it's logged for later cleanup, not treated as a
request failure — a failure at that point must never leave `agent.logo_id`
pointing at a file that no longer exists.

## Frontend

Two separate login pages (agent, admin), a registration form, and a minimal "my
account" page (view/edit own profile, upload/replace logo) — enough surface to
exercise the isolation test end-to-end, not a full account-management UI. A
shared `apiClient` wrapper (axios) with `withCredentials: true` and CSRF
cookie/header handling (`xsrfCookieName`/`xsrfHeaderName` matching the backend's
names) configured once, used by all calls.

## Testing plan

- Registration validation: unique email (per table, case-insensitively — a
  second registration differing only in email case is rejected), unique
  `agent_number` (duplicate rejected — this is the trial-abuse prevention), ID
  number check-digit format, password shorter than 10 characters rejected.
- CSRF: a POST from a properly-configured client (CSRF cookie fetched, then
  echoed in the `X-XSRF-TOKEN` header) succeeds — proving the mechanism is
  actually wired end-to-end, not just believed to be configured.
- Login/session: valid agent credentials succeed on the agent endpoint; valid
  admin credentials succeed on the admin endpoint; agent credentials are
  rejected on the admin login endpoint and vice versa, even where the same
  email exists in both tables; a deactivated (`is_active = false`) agent
  cannot log in; session survives a simulated backend restart (proving
  `spring-session-jdbc` is actually wired in, not just configured).
- Deactivation revokes existing access: an agent with an already-open session
  is deactivated, and the next request on that same session's cookie is
  rejected as unauthenticated — not just new login attempts.
- Tenant isolation: the body-id-spoof test described above.
- Admin/agent role boundary: admin → agent-only endpoint → 403; agent →
  admin-only endpoint → 403.
- Admin agent list: returns exactly the defined field set, no subscription
  status claim, no insured-related fields (trivially true in this stage since
  no insured data exists yet, but the test documents the contract for stage 2).
- Logo upload: rejects a non-image file with an image extension (magic-byte
  check via `ImageIO`, not extension trust); rejects an oversized file; a
  successfully stored logo's re-encoded output carries no EXIF metadata from
  the original; replacing a logo invalidates the old opaque URL (404) and
  serves the new one.
- Public logo route stays public: `GET /logos/{opaqueId}` with **no session at
  all** (not merely an unauthenticated agent — a request with no cookie
  whatsoever, matching what an email client actually sends) returns the image
  with a 200, not a 401/403. The entire point of the public URL is that it
  sits outside the security filter chain; if it were accidentally pulled
  inside it, every other test would still pass (a developer's own authenticated
  browser session would keep working, uploads and on-screen display would look
  fine), and the mistake would only surface in stage 5 when the first real
  email goes out to an insured and shows a broken image.

## Deferred (explicitly out of scope for this stage)

- Cloud storage provider selection (stage 1 uses local disk behind
  `LogoStorage`)
- Trial-expiry enforcement (stage 7 — this stage only records
  `trial_started_at`)
- Password reset flow
- Email verification at signup
- Login rate limiting / brute-force lockout protection — not built now, but
  recorded here so it isn't silently forgotten
- The insured's one-time-token auth flow and its filter chain (stage 2)
- The `submission` table and admin's aggregate-only usage projection (stage 2)
- Admin's ability to investigate "form wasn't sent" complaints (open question,
  to be designed before customer onboarding — not this stage)
