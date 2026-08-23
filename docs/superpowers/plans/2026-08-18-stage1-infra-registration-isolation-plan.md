# Stage 1 — Infrastructure, Agent Registration, Tenant Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the backend and frontend projects, agent self-registration and login, admin login and a read-only agent list, the central tenant-isolation pattern, the admin visibility boundary, and logo upload/storage — all enforced and tested.

**Architecture:** Spring Boot (Maven) backend with Spring Data JPA, Spring Security session auth (two independent login endpoints, one `UserDetailsService` per role), `spring-session-jdbc` for durable sessions, Flyway migrations against PostgreSQL. React + TypeScript (Vite) frontend behind a dev proxy to the backend. Tenant isolation is enforced by always sourcing the agent id from the Spring Security principal, never from request payloads; the admin visibility boundary is enforced by giving admin-facing code only narrowly-typed queries that cannot return insured data.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Maven, Spring Data JPA, Spring Security 6, `spring-session-jdbc`, Flyway 10 (`flyway-database-postgresql`), PostgreSQL 16 (`citext` extension), Testcontainers for integration tests, React 18 + TypeScript 5 + Vite 5, axios, react-router-dom, Vitest + React Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-14-stage1-infra-registration-isolation-design.md`

## Global Constraints

- Code, identifiers, comments, commit messages: English. Everything shown to a user: Hebrew, full RTL (`<html lang="he" dir="rtl">`).
- Two separate login endpoints (`POST /api/auth/agent/login`, `POST /api/auth/admin/login`), each backed by its own `UserDetailsService` — no code path searches both tables for a matching email.
- The agent id used by any read/write always comes from the server-side session (Spring Security principal). Never from a request parameter, query string, or request body field.
- Admin-facing code must never depend on a query or repository capable of returning insured data. In this stage that means admin's agent list is built directly against the `agent` table (which holds no insured data) — this direct dependency is a stage-1-only allowance the spec calls out explicitly, and it must become a narrow aggregate projection in stage 2 once the `submission` table exists.
- No seeded admin password in a migration file. Admin bootstraps from `ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` env vars on startup, documented with no value in `.env.example`.
- CSRF stays on for session auth, configured for a decoupled SPA (`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `X-XSRF-TOKEN` header echo). Never disabled globally.
- CORS origin, database URL, cookie `Secure`/`SameSite` flags, and session timeout are environment-driven (`FRONTEND_ORIGIN`, `DATABASE_URL`, `COOKIE_SECURE`, `COOKIE_SAME_SITE`, `SESSION_TIMEOUT`), never hardcoded. Session timeout defaults to 8 hours. `COOKIE_SAME_SITE` defaults to `lax` locally but stage 3's done-criteria (per the spec) may require `none` once frontend and API are confirmed cross-site in production — this variable is what makes that a config change, not a code change.
- Password policy: minimum 10 characters, no composition rules.
- `email` columns are `citext` (case-insensitive unique) on both `agent` and `admin`; `agent_number` is unique (trial-abuse prevention).
- Logo delivery is a public, unauthenticated URL under an opaque random id (`GET /logos/{opaqueId}`) — this is intentional, not a bug, because email clients can't authenticate.
- Logo uploads: PNG/JPEG/WebP only, validated by decoding with `ImageIO` (never by extension or client-supplied `Content-Type`), capped at 2MB before decoding, re-encoded to strip embedded metadata, resized to at most 512px on the long edge.
- `spring.jpa.hibernate.ddl-auto=none` — Flyway owns the schema. (Hibernate's schema validator does not recognize `citext` as a variant of `varchar` and will fail `ddl-auto=validate` at startup; `none` sidesteps that false mismatch entirely.)
- Every backend integration test that touches PostgreSQL uses Testcontainers via the shared `PostgresTestContainerConfig` (Task 1), not a hand-run local database.

---

## File Structure

```
backend/
  pom.xml
  src/main/java/com/insuranceforms/
    InsuranceFormsApplication.java
    agent/
      Agent.java
      AgentRepository.java
      AgentPrincipal.java
      AgentUserDetailsService.java
      AgentRegistrationService.java
      AgentRegistrationController.java
      AgentAccountController.java
      RegistrationException.java
      dto/
        RegisterAgentRequest.java
        AgentProfileResponse.java
        UpdateAgentProfileRequest.java
    admin/
      Admin.java
      AdminRepository.java
      AdminPrincipal.java
      AdminUserDetailsService.java
      AdminBootstrapRunner.java
      AdminAgentController.java
      dto/
        AdminAgentSummary.java
    security/
      SecurityConfig.java
      JsonAuthenticationFilter.java
      JsonLoginSuccessHandler.java
      JsonLoginFailureHandler.java
      CsrfController.java
      AgentDeactivationService.java
    validation/
      IsraeliIdValidator.java
    logo/
      LogoRef.java
      LogoStorage.java
      LocalDiskLogoStorage.java
      LogoNotFoundException.java
      LogoImageProcessor.java
      InvalidLogoException.java
      LogoUploadService.java
      LogoController.java
    web/
      GlobalExceptionHandler.java
  src/main/resources/
    application.yml
    db/migration/
      V1__enable_citext.sql
      V2__create_agent_table.sql
      V3__create_admin_table.sql
  src/test/java/com/insuranceforms/
    support/PostgresTestContainerConfig.java
    InsuranceFormsApplicationTests.java
    agent/AgentRepositoryTest.java
    admin/AdminRepositoryTest.java
    validation/IsraeliIdValidatorTest.java
    agent/AgentRegistrationTest.java
    security/LoginTest.java
    security/CsrfTest.java
    security/AgentDeactivationServiceTest.java
    agent/AgentAccountIsolationTest.java
    admin/AdminBootstrapRunnerTest.java
    admin/AdminAgentControllerTest.java
    logo/LocalDiskLogoStorageTest.java
    logo/LogoImageProcessorTest.java
    logo/LogoControllerTest.java

frontend/
  package.json
  vite.config.ts
  tsconfig.json
  index.html
  src/
    main.tsx
    App.tsx
    App.test.tsx
    api/apiClient.ts
    pages/
      AgentLoginPage.tsx
      AdminLoginPage.tsx
      AgentRegisterPage.tsx
      AgentAccountPage.tsx
```

---

## Task 1: Backend project scaffolding

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/insuranceforms/InsuranceFormsApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/insuranceforms/support/PostgresTestContainerConfig.java`
- Test: `backend/src/test/java/com/insuranceforms/InsuranceFormsApplicationTests.java`
- Modify: `.gitignore` (add `backend/target/`)

**Interfaces:**
- Produces: `PostgresTestContainerConfig` — a `@TestConfiguration` importable by any later `@SpringBootTest`, providing a `PostgreSQLContainer` wired via `@ServiceConnection` so the datasource is configured automatically.

- [x] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>com.insuranceforms</groupId>
  <artifactId>backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>backend</name>

  <properties>
    <java.version>17</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.session</groupId>
      <artifactId>spring-session-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [x] **Step 2: Write `application.yml`**

```yaml
spring:
  application:
    name: insurance-forms-backend
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/insurance_forms}
    username: ${DB_USERNAME:insurance_forms}
    password: ${DB_PASSWORD:insurance_forms}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  flyway:
    enabled: true
  session:
    jdbc:
      initialize-schema: always
    timeout: ${SESSION_TIMEOUT:8h}

server:
  port: ${SERVER_PORT:8080}
  servlet:
    session:
      cookie:
        secure: ${COOKIE_SECURE:false}
        same-site: ${COOKIE_SAME_SITE:lax}

app:
  frontend-origin: ${FRONTEND_ORIGIN:http://localhost:3000}
  admin-bootstrap-email: ${ADMIN_BOOTSTRAP_EMAIL:}
  admin-bootstrap-password: ${ADMIN_BOOTSTRAP_PASSWORD:}

logo:
  storage-dir: ${LOGO_STORAGE_DIR:./storage/logos}
```

- [x] **Step 3: Write the application class**

```java
package com.insuranceforms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InsuranceFormsApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsuranceFormsApplication.class, args);
    }
}
```

- [x] **Step 4: Write the shared Testcontainers config**

```java
package com.insuranceforms.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }
}
```

- [x] **Step 5: Write the failing smoke test**

```java
package com.insuranceforms;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class InsuranceFormsApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

There is nothing to implement yet — this step exists to confirm the project skeleton fails to *compile/run* before `pom.xml`/app class exist, per the TDD cycle. Since Steps 1–4 already created those files, this test should compile immediately; the "red" state you're verifying in Step 6 is "does the context actually start against a real Postgres container," not a missing class.

- [x] **Step 6: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=InsuranceFormsApplicationTests`
Expected: PASS. Docker must be running locally for Testcontainers to start the `postgres:16` image — pinned to that exact tag (not `latest`, not `-alpine`) so it reuses the image already pulled locally instead of triggering a new network pull.

- [x] **Step 7: Add backend build output to `.gitignore` and commit**

```bash
git add backend/pom.xml backend/src .gitignore
git commit -m "feat: scaffold Spring Boot backend with Testcontainers smoke test"
```

---

## Task 2: Frontend project scaffolding

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`
- Test: `frontend/src/App.test.tsx`

**Interfaces:**
- Produces: `App` component rendering the Hebrew app title, to be extended with routing in Task 15.

- [x] **Step 1: Write `package.json`**

```json
{
  "name": "insurance-forms-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run"
  },
  "dependencies": {
    "axios": "^1.7.7",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.26.2"
  },
  "devDependencies": {
    "@testing-library/react": "^16.0.1",
    "@types/react": "^18.3.11",
    "@types/react-dom": "^18.3.0",
    "@vitejs/plugin-react": "^4.3.2",
    "jsdom": "^25.0.1",
    "typescript": "^5.6.2",
    "vite": "^5.4.8",
    "vitest": "^2.1.2"
  }
}
```

- [x] **Step 2: Write `vite.config.ts`, with a dev proxy to the backend**

The proxy keeps the browser talking only to `localhost:3000` in dev, so the session cookie and CSRF cookie stay same-site with no CORS involved — matching `FRONTEND_ORIGIN=http://localhost:3000`'s default in `application.yml`.

```ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
      '/logos': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

- [x] **Step 3: Write `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true
  },
  "include": ["src"]
}
```

- [x] **Step 4: Write `index.html` (Hebrew, RTL)**

```html
<!doctype html>
<html lang="he" dir="rtl">
  <head>
    <meta charset="UTF-8" />
    <title>מערכת טפסי ביטוח</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [x] **Step 5: Write `src/main.tsx`**

```tsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
```

- [x] **Step 6: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('renders the app title', () => {
    render(<App />)
    expect(screen.getByText('מערכת טפסי ביטוח')).toBeInTheDocument()
  })
})
```

- [x] **Step 7: Install dependencies and run the test to verify it fails**

Run: `npm --prefix frontend install`
Run: `npm --prefix frontend test`
Expected: FAIL — `App.tsx` does not exist yet.

- [x] **Step 8: Write the minimal `App.tsx`**

```tsx
export default function App() {
  return <h1>מערכת טפסי ביטוח</h1>
}
```

- [x] **Step 9: Run the test to verify it passes**

Run: `npm --prefix frontend test`
Expected: PASS

- [x] **Step 10: Verify the dev server boots**

Run: `npm --prefix frontend run build`
Expected: exits 0 with no TypeScript errors.

- [x] **Step 11: Commit**

```bash
git add frontend/package.json frontend/vite.config.ts frontend/tsconfig.json frontend/index.html frontend/src
git commit -m "feat: scaffold Vite React TypeScript frontend"
```

---

## Task 3: Database migrations — `citext`, `agent`, `admin`

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__enable_citext.sql`
- Create: `backend/src/main/resources/db/migration/V2__create_agent_table.sql`
- Create: `backend/src/main/resources/db/migration/V3__create_admin_table.sql`
- Test: `backend/src/test/java/com/insuranceforms/MigrationTest.java`

**Interfaces:**
- Produces: `agent` and `admin` tables exactly matching the spec's data model (Task 4's entities map onto these columns 1:1).

- [x] **Step 1: Write the failing test**

```java
package com.insuranceforms;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void agentTableHasExpectedColumns() {
        var columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'agent'",
                String.class);
        assertThat(columns).contains(
                "id", "full_name", "email", "password_hash", "israeli_id", "agency_name",
                "mobile", "agent_number", "logo_id", "is_active", "trial_started_at",
                "created_at", "updated_at");
    }

    @Test
    void adminTableHasExpectedColumns() {
        var columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'admin'",
                String.class);
        assertThat(columns).contains(
                "id", "full_name", "email", "password_hash", "created_at", "updated_at");
    }

    @Test
    void emailColumnsAreCitext() {
        var agentEmailType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_name = 'agent' and column_name = 'email'",
                String.class);
        var adminEmailType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns where table_name = 'admin' and column_name = 'email'",
                String.class);
        assertThat(agentEmailType).isEqualTo("USER-DEFINED");
        assertThat(adminEmailType).isEqualTo("USER-DEFINED");
    }
}
```

- [x] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=MigrationTest`
Expected: FAIL — no `agent`/`admin` tables exist, Flyway has nothing to run.

- [x] **Step 3: Write the migrations**

```sql
-- V1__enable_citext.sql
CREATE EXTENSION IF NOT EXISTS citext;
```

```sql
-- V2__create_agent_table.sql
CREATE TABLE agent (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name         text NOT NULL,
    email             citext NOT NULL UNIQUE,
    password_hash     text NOT NULL,
    israeli_id        text NOT NULL,
    agency_name       text NOT NULL,
    mobile            text NOT NULL,
    agent_number      text NOT NULL UNIQUE,
    logo_id           uuid NULL,
    is_active         boolean NOT NULL DEFAULT true,
    trial_started_at  timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
```

```sql
-- V3__create_admin_table.sql
CREATE TABLE admin (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name         text NOT NULL,
    email             citext NOT NULL UNIQUE,
    password_hash     text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
```

- [x] **Step 4: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=MigrationTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration backend/src/test/java/com/insuranceforms/MigrationTest.java
git commit -m "feat: add agent and admin table migrations with citext email"
```

---

## Task 4: `Agent`/`Admin` JPA entities and repositories

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/agent/Agent.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentRepository.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/Admin.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/AdminRepository.java`
- Test: `backend/src/test/java/com/insuranceforms/agent/AgentRepositoryTest.java`
- Test: `backend/src/test/java/com/insuranceforms/admin/AdminRepositoryTest.java`

**Interfaces:**
- Consumes: `agent`/`admin` tables from Task 3.
- Produces: `Agent(fullName, email, passwordHash, israeliId, agencyName, mobile, agentNumber)` constructor; `AgentRepository.findByEmail/existsByEmail/existsByAgentNumber`; `Admin(fullName, email, passwordHash)` constructor; `AdminRepository.findByEmail`. Every later task that touches these tables goes through these repositories only.

- [x] **Step 1: Write the failing repository tests**

> **Deviation note:** the `AutoConfigureTestDatabase` import shown below (`org.springframework.boot.test.autoconfigure.orm.jpa`) is wrong — the class actually lives in `org.springframework.boot.test.autoconfigure.jdbc` (verified against the jar contents). Fixed in the actual test files.
>
> **Deviation note 2:** the derived `findByEmail`/`existsByEmail` queries shown below do not actually achieve case-insensitive matching in practice. PostgreSQL's `citext` case-insensitivity applies only to untyped literals; Hibernate binds `String` parameters as typed `varchar`, which resolves the `=` operator to plain case-sensitive text equality. Verified against a real Postgres container. Fixed by rewriting both methods as `@Query`s using `LOWER(...) = LOWER(...)` instead of relying on derived-query citext behavior.

```java
package com.insuranceforms.agent;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(PostgresTestContainerConfig.class)
class AgentRepositoryTest {

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void savesAndFindsByEmailCaseInsensitively() {
        Agent agent = new Agent("Ruth Cohen", "Ruth@Example.com", "hash", "123456782",
                "Cohen Insurance", "0501234567", "AG-1001");
        agentRepository.save(agent);

        assertThat(agentRepository.findByEmail("ruth@example.com")).isPresent();
        assertThat(agentRepository.existsByEmail("RUTH@EXAMPLE.COM")).isTrue();
    }

    @Test
    void existsByAgentNumberDetectsDuplicates() {
        agentRepository.save(new Agent("Ruth Cohen", "ruth@example.com", "hash", "123456782",
                "Cohen Insurance", "0501234567", "AG-1002"));

        assertThat(agentRepository.existsByAgentNumber("AG-1002")).isTrue();
        assertThat(agentRepository.existsByAgentNumber("AG-9999")).isFalse();
    }
}
```

```java
package com.insuranceforms.admin;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(PostgresTestContainerConfig.class)
class AdminRepositoryTest {

    @Autowired
    private AdminRepository adminRepository;

    @Test
    void savesAndFindsByEmailCaseInsensitively() {
        adminRepository.save(new Admin("Client Admin", "Owner@Example.com", "hash"));

        assertThat(adminRepository.findByEmail("owner@example.com")).isPresent();
    }
}
```

- [x] **Step 2: Run the tests and verify they fail**

Run: `mvn -f backend/pom.xml test -Dtest=AgentRepositoryTest,AdminRepositoryTest`
Expected: FAIL — compile error, `Agent`/`Admin`/repositories don't exist yet.

- [x] **Step 3: Write `Agent.java`**

```java
package com.insuranceforms.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "israeli_id", nullable = false)
    private String israeliId;

    @Column(name = "agency_name", nullable = false)
    private String agencyName;

    @Column(name = "mobile", nullable = false)
    private String mobile;

    @Column(name = "agent_number", nullable = false, unique = true)
    private String agentNumber;

    @Column(name = "logo_id")
    private UUID logoId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "trial_started_at", nullable = false)
    private Instant trialStartedAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Agent() {
    }

    public Agent(String fullName, String email, String passwordHash, String israeliId,
                 String agencyName, String mobile, String agentNumber) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.israeliId = israeliId;
        this.agencyName = agencyName;
        this.mobile = mobile;
        this.agentNumber = agentNumber;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getIsraeliId() { return israeliId; }
    public String getAgencyName() { return agencyName; }
    public void setAgencyName(String agencyName) { this.agencyName = agencyName; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getAgentNumber() { return agentNumber; }
    public UUID getLogoId() { return logoId; }
    public void setLogoId(UUID logoId) { this.logoId = logoId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getTrialStartedAt() { return trialStartedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [x] **Step 4: Write `AgentRepository.java`** (implemented with `@Query`/`LOWER()`, see deviation note above)

```java
package com.insuranceforms.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
    Optional<Agent> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByAgentNumber(String agentNumber);
}
```

- [x] **Step 5: Write `Admin.java`**

```java
package com.insuranceforms.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Admin() {
    }

    public Admin(String fullName, String email, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [x] **Step 6: Write `AdminRepository.java`** (implemented with `@Query`/`LOWER()`, see deviation note above)

```java
package com.insuranceforms.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminRepository extends JpaRepository<Admin, UUID> {
    Optional<Admin> findByEmail(String email);
}
```

- [x] **Step 7: Run the tests and verify they pass**

Run: `mvn -f backend/pom.xml test -Dtest=AgentRepositoryTest,AdminRepositoryTest`
Expected: PASS

- [x] **Step 8: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/agent backend/src/main/java/com/insuranceforms/admin backend/src/test/java/com/insuranceforms/agent backend/src/test/java/com/insuranceforms/admin
git commit -m "feat: add Agent and Admin JPA entities and repositories"
```

---

## Task 5: Israeli ID check-digit validator

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/validation/IsraeliIdValidator.java`
- Test: `backend/src/test/java/com/insuranceforms/validation/IsraeliIdValidatorTest.java`

**Interfaces:**
- Produces: `IsraeliIdValidator.isValid(String rawId): boolean`, used by Task 6's registration validation.

This is a pure unit test — no Spring context, no database, so it runs instantly and is the fastest possible feedback loop in this stage.

- [x] **Step 1: Write the failing test**

`"123456782"` is derived, not guessed: applying the standard alternating-weight (1,2,1,2,…) algorithm to the 8-digit prefix `12345678` requires a check digit of `2` to make the weighted sum a multiple of 10 — see the algorithm in Step 3.

```java
package com.insuranceforms.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsraeliIdValidatorTest {

    @Test
    void acceptsValidCheckDigit() {
        assertThat(IsraeliIdValidator.isValid("123456782")).isTrue();
    }

    @Test
    void rejectsInvalidCheckDigit() {
        assertThat(IsraeliIdValidator.isValid("123456783")).isFalse();
    }

    @Test
    void rejectsNonDigitInput() {
        assertThat(IsraeliIdValidator.isValid("12345678X")).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(IsraeliIdValidator.isValid(null)).isFalse();
    }
}
```

- [x] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=IsraeliIdValidatorTest`
Expected: FAIL — `IsraeliIdValidator` does not exist yet.

- [x] **Step 3: Write the validator**

```java
package com.insuranceforms.validation;

public final class IsraeliIdValidator {

    private IsraeliIdValidator() {
    }

    public static boolean isValid(String rawId) {
        if (rawId == null || !rawId.matches("\\d{1,9}")) {
            return false;
        }
        String padded = String.format("%09d", Long.parseLong(rawId));
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = padded.charAt(i) - '0';
            int weight = (i % 2 == 0) ? 1 : 2;
            int product = digit * weight;
            sum += (product > 9) ? product - 9 : product;
        }
        return sum % 10 == 0;
    }
}
```

- [x] **Step 4: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=IsraeliIdValidatorTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/validation backend/src/test/java/com/insuranceforms/validation
git commit -m "feat: add Israeli ID check-digit validator"
```

---

## Task 6: Agent registration endpoint

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/agent/dto/RegisterAgentRequest.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/dto/AgentProfileResponse.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/RegistrationException.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentRegistrationService.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentRegistrationController.java`
- Create: `backend/src/main/java/com/insuranceforms/web/GlobalExceptionHandler.java`
- Create (pulled forward from Task 7, temporary): `backend/src/main/java/com/insuranceforms/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/insuranceforms/agent/AgentRegistrationTest.java`

**Interfaces:**
- Consumes: `AgentRepository` (Task 4), `IsraeliIdValidator` (Task 5).
- Produces: `POST /api/auth/agent/register` — 201 with `AgentProfileResponse` on success, 400 `{"errors": {field: message}}` on validation failure. `AgentProfileResponse.from(Agent)` static factory, reused by Task 8's account endpoints.
- Note: this test runs `@SpringBootTest` with the full context but **without** the security filter chain (Task 7 doesn't exist yet) — Spring Security's autoconfiguration defaults to permit-all when no `SecurityFilterChain` bean is defined, so registration is reachable unauthenticated here as expected for this endpoint.

> **Deviation note:** the assumption above is wrong. Verified against the real behavior: once `spring-boot-starter-security` is on the classpath (added in Task 1) and no `SecurityFilterChain` bean exists, Spring Boot's `SecurityAutoConfiguration` secures **every** endpoint by default (generated in-memory user, all requests need authentication) — it does not default to permit-all. Running Step 2 confirmed this: all 5 requests returned 401 Unauthorized instead of 404/400. Fixed by pulling a minimal `SecurityConfig.java` forward from Task 7 (see Step 3.5 below) that permits `/api/auth/**` unauthenticated and requires authentication for everything else, plus the `PasswordEncoder` bean `AgentRegistrationService` needs. Task 7 replaces this file with its full two-chain version (the `passwordEncoder()` bean is written identically there, so no rework). Files list above updated to record this.

- [x] **Step 1: Write the failing test**

```java
package com.insuranceforms.agent;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentRegistrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    private Map<String, Object> validPayload(String email, String agentNumber) {
        return Map.of(
                "fullName", "Ruth Cohen",
                "email", email,
                "password", "correct-horse-battery",
                "israeliId", "123456782",
                "agencyName", "Cohen Insurance",
                "mobile", "0501234567",
                "agentNumber", agentNumber);
    }

    @Test
    void registersWithValidData() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("ruth1@example.com", "AG-2001"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(agentRepository.existsByEmail("ruth1@example.com")).isTrue();
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitively() {
        restTemplate.postForEntity("/api/auth/agent/register", validPayload("dup@example.com", "AG-2002"), Map.class);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("DUP@example.com", "AG-2003"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("email");
    }

    @Test
    void rejectsDuplicateAgentNumber() {
        restTemplate.postForEntity("/api/auth/agent/register", validPayload("first@example.com", "AG-2004"), Map.class);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/agent/register", validPayload("second@example.com", "AG-2004"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("agentNumber");
    }

    @Test
    void rejectsInvalidIsraeliIdCheckDigit() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("badid@example.com", "AG-2005"));
        payload.put("israeliId", "123456783");

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/register", payload, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("israeliId");
    }

    @Test
    void rejectsShortPassword() {
        Map<String, Object> payload = new java.util.HashMap<>(validPayload("shortpw@example.com", "AG-2006"));
        payload.put("password", "short1");

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/register", payload, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("password");
    }
}
```

- [x] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=AgentRegistrationTest`
Expected: FAIL — no registration endpoint exists yet (404/compile errors).

Actual: FAILED as expected, but with 401 Unauthorized on all 5 requests rather than 404/compile errors — see the deviation note above.

- [x] **Step 3: Write `RegisterAgentRequest.java`**

```java
package com.insuranceforms.agent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterAgentRequest(
        @NotBlank String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, message = "Password must be at least 10 characters") String password,
        @NotBlank String israeliId,
        @NotBlank String agencyName,
        @NotBlank String mobile,
        @NotBlank String agentNumber
) {
}
```

- [x] **Step 4: Write `AgentProfileResponse.java`**

```java
package com.insuranceforms.agent.dto;

import com.insuranceforms.agent.Agent;

import java.util.UUID;

public record AgentProfileResponse(
        UUID id, String fullName, String email, String agencyName, String mobile,
        String agentNumber, boolean logoUploaded
) {
    public static AgentProfileResponse from(Agent agent) {
        return new AgentProfileResponse(
                agent.getId(), agent.getFullName(), agent.getEmail(), agent.getAgencyName(),
                agent.getMobile(), agent.getAgentNumber(), agent.getLogoId() != null);
    }
}
```

- [x] **Step 5: Write `RegistrationException.java`**

```java
package com.insuranceforms.agent;

import java.util.Map;

public class RegistrationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public RegistrationException(Map<String, String> fieldErrors) {
        super("Registration validation failed: " + fieldErrors);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
```

- [x] **Step 6: Write `AgentRegistrationService.java`**

```java
package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.RegisterAgentRequest;
import com.insuranceforms.validation.IsraeliIdValidator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class AgentRegistrationService {

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;

    public AgentRegistrationService(AgentRepository agentRepository, PasswordEncoder passwordEncoder) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Agent register(RegisterAgentRequest request) {
        Map<String, String> errors = new HashMap<>();

        if (agentRepository.existsByEmail(request.email())) {
            errors.put("email", "Email is already registered");
        }
        if (agentRepository.existsByAgentNumber(request.agentNumber())) {
            errors.put("agentNumber", "Agent number is already registered");
        }
        if (!IsraeliIdValidator.isValid(request.israeliId())) {
            errors.put("israeliId", "Invalid Israeli ID number");
        }
        if (!errors.isEmpty()) {
            throw new RegistrationException(errors);
        }

        Agent agent = new Agent(
                request.fullName(),
                request.email(),
                passwordEncoder.encode(request.password()),
                request.israeliId(),
                request.agencyName(),
                request.mobile(),
                request.agentNumber());
        return agentRepository.save(agent);
    }
}
```

- [x] **Step 7: Write `AgentRegistrationController.java`**

```java
package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.AgentProfileResponse;
import com.insuranceforms.agent.dto.RegisterAgentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/agent")
public class AgentRegistrationController {

    private final AgentRegistrationService registrationService;

    public AgentRegistrationController(AgentRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<AgentProfileResponse> register(@Valid @RequestBody RegisterAgentRequest request) {
        Agent agent = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentProfileResponse.from(agent));
    }
}
```

- [x] **Step 8: Write `GlobalExceptionHandler.java`**

```java
package com.insuranceforms.web;

import com.insuranceforms.agent.RegistrationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<Map<String, Object>> handleRegistration(RegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errors", ex.getFieldErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("errors", errors));
    }
}
```

- [x] **Step 8.5 (added, deviation): Write a temporary minimal `SecurityConfig.java`**

Pulled forward from Task 7 to fix the 401s found in Step 2. Permits `/api/auth/**` unauthenticated, requires authentication elsewhere, and defines the `PasswordEncoder` bean `AgentRegistrationService` needs. Task 7 replaces this file with its full version.

```java
package com.insuranceforms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
```

- [x] **Step 9: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=AgentRegistrationTest`
Expected: PASS

Actual: PASS — 5/5. Also reran the full suite (`mvn -f backend/pom.xml test`): 16/16 passing, no regressions from the temporary `SecurityConfig`.

- [x] **Step 10: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/agent backend/src/main/java/com/insuranceforms/web backend/src/main/java/com/insuranceforms/security backend/src/test/java/com/insuranceforms/agent/AgentRegistrationTest.java
git commit -m "feat: add agent self-registration endpoint with validation"
```

---

## Task 7: Security core — two login endpoints, CSRF, CORS, session cookies

This is the largest task in the plan: it wires `AgentPrincipal`/`AdminPrincipal`, a `UserDetailsService` per role, a JSON login filter (SPAs post JSON, not form-urlencoded), two independent `SecurityFilterChain`s keyed by path, CSRF, CORS, and the session cookie/timeout settings already declared in `application.yml` (Task 1).

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentPrincipal.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentUserDetailsService.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/AdminPrincipal.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/AdminUserDetailsService.java`
- Create: `backend/src/main/java/com/insuranceforms/security/JsonAuthenticationFilter.java`
- Create: `backend/src/main/java/com/insuranceforms/security/JsonLoginSuccessHandler.java`
- Create: `backend/src/main/java/com/insuranceforms/security/JsonLoginFailureHandler.java`
- Create: `backend/src/main/java/com/insuranceforms/security/CsrfController.java`
- Create: `backend/src/main/java/com/insuranceforms/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/insuranceforms/security/LoginTest.java`
- Test: `backend/src/test/java/com/insuranceforms/security/CsrfTest.java`

**Interfaces:**
- Consumes: `AgentRepository`, `AdminRepository` (Task 4).
- Produces: `AgentPrincipal.getAgentId(): UUID` (consumed by Task 8's `@AuthenticationPrincipal AgentPrincipal`, Task 14's logo upload); `POST /api/auth/agent/login`, `POST /api/auth/admin/login`; `GET /api/csrf-token`; the two `SecurityFilterChain`s that every later task's `/api/agent/**` and `/api/admin/**` endpoints rely on for role enforcement.

- [ ] **Step 1: Write the failing tests**

```java
package com.insuranceforms.security;

import com.insuranceforms.admin.Admin;
import com.insuranceforms.admin.AdminRepository;
import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class LoginTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AdminRepository adminRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void agentLogsInWithValidCredentials() {
        agentRepository.save(new Agent("Ruth Cohen", "agent-login@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3001"));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/login",
                Map.of("email", "agent-login@example.com", "password", "correct-horse-battery"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("AGENT");
    }

    @Test
    void adminLogsInWithValidCredentials() {
        adminRepository.save(new Admin("Client Admin", "admin-login@example.com", encoder.encode("correct-horse-battery")));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/admin/login",
                Map.of("email", "admin-login@example.com", "password", "correct-horse-battery"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("ADMIN");
    }

    @Test
    void agentCredentialsRejectedOnAdminLoginEvenWithSameEmail() {
        String sharedEmail = "shared@example.com";
        agentRepository.save(new Agent("Ruth Cohen", sharedEmail, encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3002"));

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/admin/login",
                Map.of("email", sharedEmail, "password", "correct-horse-battery"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deactivatedAgentCannotLogIn() {
        Agent agent = new Agent("Ruth Cohen", "deactivated@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-3003");
        agent.setActive(false);
        agentRepository.save(agent);

        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/login",
                Map.of("email", "deactivated@example.com", "password", "correct-horse-battery"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

```java
package com.insuranceforms.security;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class CsrfTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void postWithoutCsrfCookieIsRejected() {
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/auth/agent/login",
                Map.of("email", "nobody@example.com", "password", "irrelevant-password"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void postWithFetchedCsrfCookieAndHeaderIsAccepted() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("XSRF-TOKEN=");
        String token = setCookie.split("XSRF-TOKEN=")[1].split(";")[0];

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", "nobody@example.com", "password", "irrelevant-password"), headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/auth/agent/login", HttpMethod.POST, request, Map.class);

        // 401 (bad credentials), not 403 — proves the request passed CSRF and reached the auth filter.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `mvn -f backend/pom.xml test -Dtest=LoginTest,CsrfTest`
Expected: FAIL — no login endpoints or CSRF wiring exist yet; Spring Security's default autoconfiguration currently blocks everything behind HTTP Basic.

- [ ] **Step 3: Write `AgentPrincipal.java`**

```java
package com.insuranceforms.agent;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

public class AgentPrincipal implements UserDetails {

    private final Agent agent;

    public AgentPrincipal(Agent agent) {
        this.agent = agent;
    }

    public UUID getAgentId() {
        return agent.getId();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_AGENT"));
    }

    @Override
    public String getPassword() {
        return agent.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return agent.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return agent.isActive();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
```

- [ ] **Step 4: Write `AgentUserDetailsService.java`**

```java
package com.insuranceforms.agent;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AgentUserDetailsService implements UserDetailsService {

    private final AgentRepository agentRepository;

    public AgentUserDetailsService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        Agent agent = agentRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No agent with email " + email));
        return new AgentPrincipal(agent);
    }
}
```

- [ ] **Step 5: Write `AdminPrincipal.java` and `AdminUserDetailsService.java`**

```java
package com.insuranceforms.admin;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

public class AdminPrincipal implements UserDetails {

    private final Admin admin;

    public AdminPrincipal(Admin admin) {
        this.admin = admin;
    }

    public UUID getAdminId() {
        return admin.getId();
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Override
    public String getPassword() {
        return admin.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return admin.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
```

```java
package com.insuranceforms.admin;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminRepository adminRepository;

    public AdminUserDetailsService(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        Admin admin = adminRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No admin with email " + email));
        return new AdminPrincipal(admin);
    }
}
```

- [ ] **Step 6: Write `JsonAuthenticationFilter.java`**

```java
package com.insuranceforms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;
import java.io.UncheckedIOException;

public class JsonAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationFilter(AuthenticationManager authenticationManager, String loginUrl,
                                     ObjectMapper objectMapper) {
        super(authenticationManager);
        this.objectMapper = objectMapper;
        setRequiresAuthenticationRequestMatcher(new AntPathRequestMatcher(loginUrl, "POST"));
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) {
        try {
            LoginRequest loginRequest = objectMapper.readValue(request.getInputStream(), LoginRequest.class);
            UsernamePasswordAuthenticationToken authRequest = UsernamePasswordAuthenticationToken
                    .unauthenticated(loginRequest.email(), loginRequest.password());
            setDetails(request, authRequest);
            return getAuthenticationManager().authenticate(authRequest);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed login request body", e);
        }
    }

    private record LoginRequest(String email, String password) {
    }
}
```

- [ ] **Step 7: Write `JsonLoginSuccessHandler.java` and `JsonLoginFailureHandler.java`**

```java
package com.insuranceforms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Map;

public class JsonLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final ObjectMapper objectMapper;
    private final String role;

    public JsonLoginSuccessHandler(ObjectMapper objectMapper, String role) {
        this.objectMapper = objectMapper;
        this.role = role;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("email", authentication.getName(), "role", role));
    }
}
```

```java
package com.insuranceforms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.Map;

public class JsonLoginFailureHandler implements AuthenticationFailureHandler {

    private final ObjectMapper objectMapper;

    public JsonLoginFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("error", "Invalid credentials"));
    }
}
```

- [ ] **Step 8: Write `CsrfController.java`**

Injecting `CsrfToken` as a controller parameter forces Spring Security's deferred token to materialize and be written as a cookie — without this, `CsrfFilter`'s lazy token never gets saved and no `Set-Cookie` header is sent.

```java
package com.insuranceforms.security;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

    @GetMapping("/api/csrf-token")
    public void bootstrap(CsrfToken csrfToken) {
        csrfToken.getToken();
    }
}
```

- [ ] **Step 9: Write `SecurityConfig.java`**

```java
package com.insuranceforms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insuranceforms.admin.AdminUserDetailsService;
import com.insuranceforms.agent.AgentUserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AgentUserDetailsService agentUserDetailsService;
    private final AdminUserDetailsService adminUserDetailsService;
    private final ObjectMapper objectMapper;

    @Value("${app.frontend-origin}")
    private String frontendOrigin;

    public SecurityConfig(AgentUserDetailsService agentUserDetailsService,
                           AdminUserDetailsService adminUserDetailsService,
                           ObjectMapper objectMapper) {
        this.agentUserDetailsService = agentUserDetailsService;
        this.adminUserDetailsService = adminUserDetailsService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private AuthenticationManager buildManager(HttpSecurity http, UserDetailsService userDetailsService)
            throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder());
        provider.setUserDetailsService(userDetailsService);
        builder.authenticationProvider(provider);
        return builder.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain agentChain(HttpSecurity http) throws Exception {
        AuthenticationManager manager = buildManager(http, agentUserDetailsService);
        JsonAuthenticationFilter loginFilter = new JsonAuthenticationFilter(manager, "/api/auth/agent/login", objectMapper);
        loginFilter.setAuthenticationSuccessHandler(new JsonLoginSuccessHandler(objectMapper, "AGENT"));
        loginFilter.setAuthenticationFailureHandler(new JsonLoginFailureHandler(objectMapper));

        http.securityMatcher("/api/auth/agent/**", "/api/agent/**")
                .authenticationManager(manager)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/agent/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/agent/login").permitAll()
                        .requestMatchers("/api/agent/**").hasRole("AGENT")
                        .anyRequest().authenticated())
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminChain(HttpSecurity http) throws Exception {
        AuthenticationManager manager = buildManager(http, adminUserDetailsService);
        JsonAuthenticationFilter loginFilter = new JsonAuthenticationFilter(manager, "/api/auth/admin/login", objectMapper);
        loginFilter.setAuthenticationSuccessHandler(new JsonLoginSuccessHandler(objectMapper, "ADMIN"));
        loginFilter.setAuthenticationFailureHandler(new JsonLoginFailureHandler(objectMapper));

        http.securityMatcher("/api/auth/admin/**", "/api/admin/**")
                .authenticationManager(manager)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/admin/login").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/logos/**", "/api/csrf-token")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

- [ ] **Step 10: Run the tests and verify they pass**

Run: `mvn -f backend/pom.xml test -Dtest=LoginTest,CsrfTest`
Expected: PASS. If `CsrfTest.postWithoutCsrfCookieIsRejected` instead returns 401, the CSRF filter isn't actually rejecting the request before authentication runs — double check `agentChain`'s CSRF config is active (this is the exact class of bug the spec's testing plan calls out: "proving the mechanism is actually wired end-to-end, not just believed to be configured").

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/security backend/src/main/java/com/insuranceforms/agent/AgentPrincipal.java backend/src/main/java/com/insuranceforms/agent/AgentUserDetailsService.java backend/src/main/java/com/insuranceforms/admin/AdminPrincipal.java backend/src/main/java/com/insuranceforms/admin/AdminUserDetailsService.java backend/src/test/java/com/insuranceforms/security
git commit -m "feat: add two-role session auth with JSON login and CSRF"
```

---

## Task 8: Agent account endpoints and tenant isolation

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/agent/dto/UpdateAgentProfileRequest.java`
- Create: `backend/src/main/java/com/insuranceforms/agent/AgentAccountController.java`
- Test: `backend/src/test/java/com/insuranceforms/agent/AgentAccountIsolationTest.java`

**Interfaces:**
- Consumes: `AgentPrincipal` (Task 7), `AgentRepository`, `AgentProfileResponse` (Task 6).
- Produces: `GET /api/agent/me`, `PATCH /api/agent/me` — the canonical example of "id always comes from the session, never from the request body," reused as the pattern every later agent-owned resource follows. Task 9's deactivation test uses `GET /api/agent/me` to prove a revoked session is rejected.

- [ ] **Step 1: Write the failing test — the tenant-isolation body-id-spoof test**

Agent A logs in and PATCHes `/api/agent/me` with a payload whose `id` field is agent B's id. The assertion: only A's row changes; B's is untouched.

```java
package com.insuranceforms.agent;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentAccountIsolationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void bodyIdIsIgnoredAndOnlyTheSessionAgentIsUpdated() {
        Agent agentA = agentRepository.save(new Agent("Agent A", "isoa@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency A", "0501111111", "AG-5001"));
        Agent agentB = agentRepository.save(new Agent("Agent B", "isob@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Agency B", "0502222222", "AG-5002"));

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "isoa@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        HttpHeaders patchHeaders = new HttpHeaders();
        patchHeaders.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        patchHeaders.add("X-XSRF-TOKEN", csrfToken);
        Map<String, Object> spoofedPayload = new HashMap<>();
        spoofedPayload.put("id", agentB.getId().toString());
        spoofedPayload.put("fullName", "Agent A Updated");
        spoofedPayload.put("agencyName", "Agency A Updated");
        spoofedPayload.put("mobile", "0501111199");

        ResponseEntity<Map> patchResponse = restTemplate.exchange("/api/agent/me", HttpMethod.PATCH,
                new HttpEntity<>(spoofedPayload, patchHeaders), Map.class);

        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResponse.getBody().get("id")).isEqualTo(agentA.getId().toString());

        Agent reloadedA = agentRepository.findById(agentA.getId()).orElseThrow();
        Agent reloadedB = agentRepository.findById(agentB.getId()).orElseThrow();
        assertThat(reloadedA.getFullName()).isEqualTo("Agent A Updated");
        assertThat(reloadedB.getFullName()).isEqualTo("Agent B");
        assertThat(reloadedB.getAgencyName()).isEqualTo("Agency B");
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
                .orElseThrow(() -> new IllegalStateException(name + " cookie not found in response"));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=AgentAccountIsolationTest`
Expected: FAIL — `/api/agent/me` doesn't exist yet.

- [ ] **Step 3: Write `UpdateAgentProfileRequest.java`**

The `id` field exists in the DTO but is deliberately never read by the controller below — this is what proves the isolation pattern under a client actively trying to override it, not merely a DTO shape that happens to omit the field.

```java
package com.insuranceforms.agent.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record UpdateAgentProfileRequest(
        UUID id,
        @NotBlank String fullName,
        @NotBlank String agencyName,
        @NotBlank String mobile
) {
}
```

- [ ] **Step 4: Write `AgentAccountController.java`**

```java
package com.insuranceforms.agent;

import com.insuranceforms.agent.dto.AgentProfileResponse;
import com.insuranceforms.agent.dto.UpdateAgentProfileRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/me")
public class AgentAccountController {

    private final AgentRepository agentRepository;

    public AgentAccountController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public AgentProfileResponse getProfile(@AuthenticationPrincipal AgentPrincipal principal) {
        Agent agent = agentRepository.findById(principal.getAgentId()).orElseThrow();
        return AgentProfileResponse.from(agent);
    }

    @PatchMapping
    public AgentProfileResponse updateProfile(@AuthenticationPrincipal AgentPrincipal principal,
                                               @Valid @RequestBody UpdateAgentProfileRequest request) {
        // The agent id always comes from the authenticated principal — request.id() is
        // intentionally never read, even though the DTO carries it, so a client cannot
        // override which row gets updated.
        Agent agent = agentRepository.findById(principal.getAgentId()).orElseThrow();
        agent.setFullName(request.fullName());
        agent.setAgencyName(request.agencyName());
        agent.setMobile(request.mobile());
        agentRepository.save(agent);
        return AgentProfileResponse.from(agent);
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=AgentAccountIsolationTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/agent/dto/UpdateAgentProfileRequest.java backend/src/main/java/com/insuranceforms/agent/AgentAccountController.java backend/src/test/java/com/insuranceforms/agent/AgentAccountIsolationTest.java
git commit -m "feat: add agent account endpoints proving tenant isolation from session"
```

---

## Task 9: Deactivation revokes existing sessions

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/security/AgentDeactivationService.java`
- Test: `backend/src/test/java/com/insuranceforms/security/AgentDeactivationServiceTest.java`

**Interfaces:**
- Consumes: `AgentRepository` (Task 4), Spring Session's `FindByIndexNameSessionRepository` (auto-configured by `spring-session-jdbc`), `GET /api/agent/me` (Task 8) as the endpoint the test uses to prove a revoked session is rejected.
- Produces: `AgentDeactivationService.deactivate(UUID agentId)` — sets `is_active = false` and deletes every stored session indexed under that agent's email. No HTTP endpoint yet (an admin-facing deactivation endpoint is out of scope for this stage per the spec); the test calls the service bean directly, matching how the spec's testing plan describes this scenario.

- [ ] **Step 1: Write the failing test**

This test needs a real HTTP round-trip to obtain a session cookie (a `TestRestTemplate` login), then calls `AgentDeactivationService` directly, then re-uses the captured cookie to prove the session is gone.

```java
package com.insuranceforms.security;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AgentDeactivationServiceTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AgentDeactivationService deactivationService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void deactivationInvalidatesAnAlreadyOpenSession() {
        Agent agent = agentRepository.save(new Agent("Ruth Cohen", "revoke@example.com",
                encoder.encode("correct-horse-battery"), "123456782", "Cohen Insurance", "0501234567", "AG-4001"));

        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "revoke@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        deactivationService.deactivate(agent.getId());

        HttpHeaders followUpHeaders = new HttpHeaders();
        followUpHeaders.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Map> followUp = restTemplate.exchange("/api/agent/me", HttpMethod.GET,
                new HttpEntity<>(followUpHeaders), Map.class);

        assertThat(followUp.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
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
                .orElseThrow(() -> new IllegalStateException(name + " cookie not found in response"));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=AgentDeactivationServiceTest`
Expected: FAIL — `AgentDeactivationService` doesn't exist yet.

- [ ] **Step 3: Write `AgentDeactivationService.java`**

```java
package com.insuranceforms.security;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class AgentDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(AgentDeactivationService.class);

    private final AgentRepository agentRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public AgentDeactivationService(AgentRepository agentRepository,
                                     FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public void deactivate(UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown agent " + agentId));
        agent.setActive(false);
        agentRepository.save(agent);

        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(agent.getEmail());
        sessions.keySet().forEach(sessionRepository::deleteById);
        log.info("Revoked {} active session(s) for deactivated agent {}", sessions.size(), agentId);
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=AgentDeactivationServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/security/AgentDeactivationService.java backend/src/test/java/com/insuranceforms/security/AgentDeactivationServiceTest.java
git commit -m "feat: revoke existing sessions on agent deactivation"
```

---

## Task 10: Admin bootstrap on startup

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/admin/AdminBootstrapRunner.java`
- Create: `.env.example` (or extend if it already exists)
- Test: `backend/src/test/java/com/insuranceforms/admin/AdminBootstrapRunnerTest.java`

**Interfaces:**
- Consumes: `AdminRepository` (Task 4), `app.admin-bootstrap-email`/`app.admin-bootstrap-password` (Task 1's `application.yml`).
- Produces: exactly one `admin` row on first startup when env vars are set and no admin exists yet; idempotent on every later startup.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.admin;

import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(PostgresTestContainerConfig.class)
class AdminBootstrapRunnerTest {

    @DynamicPropertySource
    static void bootstrapCredentials(DynamicPropertyRegistry registry) {
        registry.add("app.admin-bootstrap-email", () -> "owner@example.com");
        registry.add("app.admin-bootstrap-password", () -> "correct-horse-battery");
    }

    @Autowired
    private AdminRepository adminRepository;

    @Test
    void createsExactlyOneAdminFromEnvOnStartup() {
        assertThat(adminRepository.count()).isEqualTo(1);
        assertThat(adminRepository.findByEmail("owner@example.com")).isPresent();
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=AdminBootstrapRunnerTest`
Expected: FAIL — no admin row is created, `AdminBootstrapRunner` doesn't exist.

- [ ] **Step 3: Write `AdminBootstrapRunner.java`**

```java
package com.insuranceforms.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrapRunner(AdminRepository adminRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.admin-bootstrap-email}") String bootstrapEmail,
                                 @Value("${app.admin-bootstrap-password}") String bootstrapPassword) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminRepository.count() > 0) {
            return;
        }
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            return;
        }
        adminRepository.save(new Admin("Platform Admin", bootstrapEmail, passwordEncoder.encode(bootstrapPassword)));
    }
}
```

- [ ] **Step 4: Add the bootstrap variables to `.env.example`**

```bash
# .env.example
ADMIN_BOOTSTRAP_EMAIL=
ADMIN_BOOTSTRAP_PASSWORD=
DATABASE_URL=jdbc:postgresql://localhost:5432/insurance_forms
DB_USERNAME=insurance_forms
DB_PASSWORD=
FRONTEND_ORIGIN=http://localhost:3000
COOKIE_SECURE=false
COOKIE_SAME_SITE=lax
SESSION_TIMEOUT=8h
LOGO_STORAGE_DIR=./storage/logos
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=AdminBootstrapRunnerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/admin/AdminBootstrapRunner.java backend/src/test/java/com/insuranceforms/admin/AdminBootstrapRunnerTest.java .env.example
git commit -m "feat: bootstrap admin account from environment variables on startup"
```

---

## Task 11: Admin agent list and role-boundary enforcement

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/admin/dto/AdminAgentSummary.java`
- Create: `backend/src/main/java/com/insuranceforms/admin/AdminAgentController.java`
- Test: `backend/src/test/java/com/insuranceforms/admin/AdminAgentControllerTest.java`

**Interfaces:**
- Consumes: `AgentRepository` (Task 4) directly — intentional for stage 1 per the spec, since `agent` holds no insured data; must become a narrow projection query once stage 2 adds `submission`.
- Produces: `GET /api/admin/agents` — the admin/agent 403 cross-boundary test required by the spec.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.admin;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class AdminAgentControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private AdminRepository adminRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void adminSeesDefinedFieldsOnlyNoInsuredData() {
        agentRepository.save(new Agent("Ruth Cohen", "list1@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-6001"));
        adminRepository.save(new com.insuranceforms.admin.Admin("Client Admin", "listadmin@example.com",
                encoder.encode("correct-horse-battery")));

        String sessionCookie = loginAsAdmin("listadmin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<List> response = restTemplate.exchange("/api/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> first = (Map<String, Object>) response.getBody().get(0);
        assertThat(first.keySet()).containsExactlyInAnyOrder(
                "id", "fullName", "agencyName", "email", "mobile", "agentNumber", "isActive", "trialStartedAt");
    }

    @Test
    void adminCallingAgentOnlyEndpointGets403() {
        adminRepository.save(new com.insuranceforms.admin.Admin("Client Admin", "boundary-admin@example.com",
                encoder.encode("correct-horse-battery")));
        String sessionCookie = loginAsAdmin("boundary-admin@example.com");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/me", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void agentCallingAdminOnlyEndpointGets403() {
        agentRepository.save(new Agent("Ruth Cohen", "boundary-agent@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-6002"));
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "boundary-agent@example.com", "password", "correct-horse-battery"), loginHeaders),
                Map.class);
        String sessionCookie = extractCookie(loginResponse, "SESSION");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<List> response = restTemplate.exchange("/api/admin/agents", HttpMethod.GET,
                new HttpEntity<>(headers), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String loginAsAdmin(String email) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/admin/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), loginHeaders), Map.class);
        return extractCookie(loginResponse, "SESSION");
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
                .orElseThrow(() -> new IllegalStateException(name + " cookie not found in response"));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=AdminAgentControllerTest`
Expected: FAIL — `/api/admin/agents` doesn't exist yet.

- [ ] **Step 3: Write `AdminAgentSummary.java`**

```java
package com.insuranceforms.admin.dto;

import com.insuranceforms.agent.Agent;

import java.time.Instant;
import java.util.UUID;

public record AdminAgentSummary(
        UUID id, String fullName, String agencyName, String email, String mobile,
        String agentNumber, boolean isActive, Instant trialStartedAt
) {
    public static AdminAgentSummary from(Agent agent) {
        return new AdminAgentSummary(
                agent.getId(), agent.getFullName(), agent.getAgencyName(), agent.getEmail(),
                agent.getMobile(), agent.getAgentNumber(), agent.isActive(), agent.getTrialStartedAt());
    }
}
```

- [ ] **Step 4: Write `AdminAgentController.java`**

```java
package com.insuranceforms.admin;

import com.insuranceforms.admin.dto.AdminAgentSummary;
import com.insuranceforms.agent.AgentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/agents")
public class AdminAgentController {

    private final AgentRepository agentRepository;

    public AdminAgentController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public List<AdminAgentSummary> list() {
        // Direct dependency on AgentRepository is safe only because `agent` holds no
        // insured data. Stage 2 must replace this with a dedicated aggregate projection
        // once `submission` exists (see spec's Admin visibility boundary section).
        return agentRepository.findAll().stream().map(AdminAgentSummary::from).toList();
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=AdminAgentControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/admin/dto/AdminAgentSummary.java backend/src/main/java/com/insuranceforms/admin/AdminAgentController.java backend/src/test/java/com/insuranceforms/admin/AdminAgentControllerTest.java
git commit -m "feat: add admin agent list endpoint with role-boundary enforcement"
```

---

## Task 12: Logo storage — `LogoStorage` interface and local disk implementation

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoRef.java`
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoStorage.java`
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoNotFoundException.java`
- Create: `backend/src/main/java/com/insuranceforms/logo/LocalDiskLogoStorage.java`
- Test: `backend/src/test/java/com/insuranceforms/logo/LocalDiskLogoStorageTest.java`

**Interfaces:**
- Produces: `LogoStorage.store(bytes, contentType): LogoRef{id, url}`, `resolve(id): InputStream`, `delete(id)`. All stored logos are re-encoded PNG by the time they reach this layer (see Task 13), so `LocalDiskLogoStorage` always writes/serves `image/png` regardless of the `contentType` argument — this keeps the interface exactly as specced (`resolve` returns only an `InputStream`, no content type) while still letting the controller in Task 14 set a fixed `Content-Type: image/png` header.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.logo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDiskLogoStorageTest {

    private LocalDiskLogoStorage storage;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        storage = new LocalDiskLogoStorage(tempDir.toString());
    }

    @Test
    void storesAndResolvesBytes() throws IOException {
        byte[] original = {1, 2, 3, 4, 5};

        LogoRef ref = storage.store(original, "image/png");

        assertThat(ref.id()).isNotNull();
        assertThat(ref.url()).isEqualTo("/logos/" + ref.id());
        try (InputStream in = storage.resolve(ref.id())) {
            assertThat(in.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void deleteRemovesTheFile() {
        LogoRef ref = storage.store(new byte[]{9, 9, 9}, "image/png");

        storage.delete(ref.id());

        assertThatThrownBy(() -> storage.resolve(ref.id())).isInstanceOf(LogoNotFoundException.class);
    }

    @Test
    void resolvingAnUnknownIdThrows() {
        assertThatThrownBy(() -> storage.resolve(UUID.randomUUID())).isInstanceOf(LogoNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=LocalDiskLogoStorageTest`
Expected: FAIL — none of these classes exist yet.

- [ ] **Step 3: Write `LogoRef.java`**

```java
package com.insuranceforms.logo;

import java.util.UUID;

public record LogoRef(UUID id, String url) {
}
```

- [ ] **Step 4: Write `LogoStorage.java`**

```java
package com.insuranceforms.logo;

import java.io.InputStream;
import java.util.UUID;

public interface LogoStorage {
    LogoRef store(byte[] bytes, String contentType);
    InputStream resolve(UUID id);
    void delete(UUID id);
}
```

- [ ] **Step 5: Write `LogoNotFoundException.java`**

```java
package com.insuranceforms.logo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class LogoNotFoundException extends RuntimeException {
    public LogoNotFoundException(UUID id) {
        super("No logo found for id " + id);
    }
}
```

- [ ] **Step 6: Write `LocalDiskLogoStorage.java`**

```java
package com.insuranceforms.logo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class LocalDiskLogoStorage implements LogoStorage {

    private final Path storageDir;

    public LocalDiskLogoStorage(@Value("${logo.storage-dir}") String storageDir) {
        this.storageDir = Path.of(storageDir);
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public LogoRef store(byte[] bytes, String contentType) {
        UUID id = UUID.randomUUID();
        try {
            Files.write(pathFor(id), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LogoRef(id, "/logos/" + id);
    }

    @Override
    public InputStream resolve(UUID id) {
        Path path = pathFor(id);
        if (!Files.exists(path)) {
            throw new LogoNotFoundException(id);
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(UUID id) {
        try {
            Files.deleteIfExists(pathFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path pathFor(UUID id) {
        return storageDir.resolve(id + ".png");
    }
}
```

- [ ] **Step 7: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=LocalDiskLogoStorageTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/logo/LogoRef.java backend/src/main/java/com/insuranceforms/logo/LogoStorage.java backend/src/main/java/com/insuranceforms/logo/LogoNotFoundException.java backend/src/main/java/com/insuranceforms/logo/LocalDiskLogoStorage.java backend/src/test/java/com/insuranceforms/logo/LocalDiskLogoStorageTest.java
git commit -m "feat: add LogoStorage interface and local disk implementation"
```

---

## Task 13: Logo upload validation and reprocessing

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/logo/InvalidLogoException.java`
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoImageProcessor.java`
- Test: `backend/src/test/java/com/insuranceforms/logo/LogoImageProcessorTest.java`

**Interfaces:**
- Produces: `LogoImageProcessor.process(byte[] uploadedBytes): byte[]` — decodes, validates, strips metadata, resizes, and re-encodes to PNG, or throws `InvalidLogoException`. Consumed by Task 14's `LogoUploadService`.

- [ ] **Step 1: Write the failing test**

```java
package com.insuranceforms.logo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogoImageProcessorTest {

    private final LogoImageProcessor processor = new LogoImageProcessor();

    private byte[] renderSampleJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void rejectsBytesThatAreNotADecodableImage() {
        byte[] garbage = "not-an-image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(garbage)).isInstanceOf(InvalidLogoException.class);
    }

    @Test
    void rejectsFilesOverTwoMegabytesBeforeDecoding() {
        byte[] oversized = new byte[3 * 1024 * 1024];

        assertThatThrownBy(() -> processor.process(oversized)).isInstanceOf(InvalidLogoException.class);
    }

    @Test
    void resizesToAtMost512OnTheLongEdgePreservingAspectRatio() throws IOException {
        byte[] wideJpeg = renderSampleJpeg(1000, 500);

        byte[] processed = processor.process(wideJpeg);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(result.getWidth()).isEqualTo(512);
        assertThat(result.getHeight()).isEqualTo(256);
    }

    @Test
    void leavesSmallImagesAtOriginalSize() throws IOException {
        byte[] smallJpeg = renderSampleJpeg(100, 80);

        byte[] processed = processor.process(smallJpeg);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(80);
    }

    @Test
    void reEncodingDropsArbitraryEmbeddedBytesFromTheOriginalFile() throws IOException {
        byte[] cleanJpeg = renderSampleJpeg(300, 200);
        byte[] marker = "SECRET-EXIF-MARKER".getBytes(StandardCharsets.US_ASCII);
        byte[] withTrailingJunk = new byte[cleanJpeg.length + marker.length];
        System.arraycopy(cleanJpeg, 0, withTrailingJunk, 0, cleanJpeg.length);
        System.arraycopy(marker, 0, withTrailingJunk, cleanJpeg.length, marker.length);

        byte[] processed = processor.process(withTrailingJunk);

        assertThat(indexOf(processed, marker)).isEqualTo(-1);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=LogoImageProcessorTest`
Expected: FAIL — `LogoImageProcessor`/`InvalidLogoException` don't exist yet.

- [ ] **Step 3: Write `InvalidLogoException.java`**

```java
package com.insuranceforms.logo;

public class InvalidLogoException extends RuntimeException {
    public InvalidLogoException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write `LogoImageProcessor.java`**

```java
package com.insuranceforms.logo;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public class LogoImageProcessor {

    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 512;

    public byte[] process(byte[] uploadedBytes) {
        if (uploadedBytes.length > MAX_UPLOAD_BYTES) {
            throw new InvalidLogoException("File exceeds the 2MB upload limit");
        }

        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(uploadedBytes));
        } catch (IOException e) {
            throw new InvalidLogoException("File is not a decodable image");
        }
        if (decoded == null) {
            throw new InvalidLogoException("File is not a decodable image");
        }

        BufferedImage resized = resize(decoded);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BufferedImage resize(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);

        int targetWidth = width;
        int targetHeight = height;
        if (longEdge > MAX_DIMENSION) {
            double scale = MAX_DIMENSION / (double) longEdge;
            targetWidth = (int) Math.round(width * scale);
            targetHeight = (int) Math.round(height * scale);
        }

        // Drawing into a fresh BufferedImage discards any metadata the source carried —
        // ImageIO.read() already returns pixel data only, but this makes the "fresh file,
        // nothing carried over" guarantee explicit rather than incidental.
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        Image scaled = source.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
        graphics.drawImage(scaled, 0, 0, null);
        graphics.dispose();
        return result;
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=LogoImageProcessorTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/logo/InvalidLogoException.java backend/src/main/java/com/insuranceforms/logo/LogoImageProcessor.java backend/src/test/java/com/insuranceforms/logo/LogoImageProcessorTest.java
git commit -m "feat: validate and reprocess uploaded logos, stripping embedded metadata"
```

---

## Task 14: Logo endpoints — authenticated upload, public serving

**Files:**
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoUploadService.java`
- Create: `backend/src/main/java/com/insuranceforms/logo/LogoController.java`
- Test: `backend/src/test/java/com/insuranceforms/logo/LogoControllerTest.java`

**Interfaces:**
- Consumes: `LogoStorage` (Task 12), `LogoImageProcessor` (Task 13), `AgentRepository`, `AgentPrincipal` (Task 7).
- Produces: `POST /api/agent/logo` (`ROLE_AGENT` only, matched by `agentChain`'s `/api/agent/**`), `GET /logos/{id}` (public, matched by `publicChain`'s `/logos/**`, no session required at all).

- [ ] **Step 1: Write the failing test**

The `noSessionAtAllReachesThePublicRoute` test uses a bare `RestTemplate` with no cookie jar at all — not merely an unauthenticated agent session — matching exactly what an email client's image request looks like.

```java
package com.insuranceforms.logo;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import com.insuranceforms.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainerConfig.class)
class LogoControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgentRepository agentRepository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private byte[] renderSamplePng() throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void uploadingAndReplacingALogoInvalidatesTheOldUrlAndServesTheNew() throws IOException {
        Agent agent = agentRepository.save(new Agent("Ruth Cohen", "logo1@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7001"));
        String sessionCookie = loginAsAgent("logo1@example.com");

        String firstUrl = upload(sessionCookie, renderSamplePng());
        String secondUrl = upload(sessionCookie, renderSamplePng());

        assertThat(firstUrl).isNotEqualTo(secondUrl);

        RestTemplate plainClient = new RestTemplate();
        ResponseEntity<byte[]> oldResponse = plainClient.getForEntity(
                "http://localhost:" + port + firstUrl, byte[].class);
        assertThat(oldResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<byte[]> newResponse = plainClient.getForEntity(
                "http://localhost:" + port + secondUrl, byte[].class);
        assertThat(newResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(newResponse.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void publicLogoRouteRequiresNoSessionAtAll() throws IOException {
        agentRepository.save(new Agent("Ruth Cohen", "logo2@example.com", encoder.encode("correct-horse-battery"),
                "123456782", "Cohen Insurance", "0501234567", "AG-7002"));
        String sessionCookie = loginAsAgent("logo2@example.com");
        String url = upload(sessionCookie, renderSamplePng());

        // A brand-new RestTemplate with no cookie store at all — simulating an email client.
        RestTemplate noCookieClient = new RestTemplate();
        ResponseEntity<byte[]> response = noCookieClient.getForEntity("http://localhost:" + port + url, byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String upload(String sessionCookie, byte[] pngBytes) {
        String csrfToken = fetchCsrfTokenWithSession(sessionCookie);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie + "; XSRF-TOKEN=" + csrfToken);
        headers.add("X-XSRF-TOKEN", csrfToken);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pngBytes) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });

        ResponseEntity<Map> response = restTemplate.exchange("/api/agent/logo", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("logoUrl");
    }

    private String loginAsAgent(String email) {
        String csrfToken = fetchCsrfToken();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfToken);
        loginHeaders.add("X-XSRF-TOKEN", csrfToken);
        ResponseEntity<Map> loginResponse = restTemplate.exchange("/api/auth/agent/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "correct-horse-battery"), loginHeaders), Map.class);
        return extractCookie(loginResponse, "SESSION");
    }

    private String fetchCsrfToken() {
        ResponseEntity<Void> bootstrap = restTemplate.getForEntity("/api/csrf-token", Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String fetchCsrfTokenWithSession(String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "SESSION=" + sessionCookie);
        ResponseEntity<Void> bootstrap = restTemplate.exchange("/api/csrf-token", HttpMethod.GET,
                new HttpEntity<>(headers), Void.class);
        String setCookie = bootstrap.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return setCookie.split("XSRF-TOKEN=")[1].split(";")[0];
    }

    private String extractCookie(ResponseEntity<?> response, String name) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> c.split(name + "=")[1].split(";")[0])
                .orElseThrow(() -> new IllegalStateException(name + " cookie not found in response"));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f backend/pom.xml test -Dtest=LogoControllerTest`
Expected: FAIL — `/api/agent/logo` and `/logos/{id}` don't exist yet.

- [ ] **Step 3: Write `LogoUploadService.java`**

Order of operations matches the spec exactly: store the new file first, then point `agent.logo_id` at it, then delete the old file — a failure at the final delete is logged, not surfaced as a request failure, so `agent.logo_id` is never left pointing at a missing file.

```java
package com.insuranceforms.logo;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class LogoUploadService {

    private static final Logger log = LoggerFactory.getLogger(LogoUploadService.class);

    private final AgentRepository agentRepository;
    private final LogoStorage logoStorage;
    private final LogoImageProcessor processor;

    public LogoUploadService(AgentRepository agentRepository, LogoStorage logoStorage, LogoImageProcessor processor) {
        this.agentRepository = agentRepository;
        this.logoStorage = logoStorage;
        this.processor = processor;
    }

    public String replaceLogo(UUID agentId, byte[] uploadedBytes) {
        Agent agent = agentRepository.findById(agentId).orElseThrow();
        byte[] processed = processor.process(uploadedBytes);

        LogoRef newLogo = logoStorage.store(processed, "image/png");
        UUID oldLogoId = agent.getLogoId();

        agent.setLogoId(newLogo.id());
        agentRepository.save(agent);

        if (oldLogoId != null) {
            try {
                logoStorage.delete(oldLogoId);
            } catch (RuntimeException e) {
                log.error("Failed to delete replaced logo {} for agent {}", oldLogoId, agentId, e);
            }
        }
        return newLogo.url();
    }
}
```

- [ ] **Step 4: Write `LogoController.java`**

```java
package com.insuranceforms.logo;

import com.insuranceforms.agent.AgentPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

@RestController
public class LogoController {

    private final LogoUploadService logoUploadService;
    private final LogoStorage logoStorage;

    public LogoController(LogoUploadService logoUploadService, LogoStorage logoStorage) {
        this.logoUploadService = logoUploadService;
        this.logoStorage = logoStorage;
    }

    @PostMapping("/api/agent/logo")
    public Map<String, String> upload(@AuthenticationPrincipal AgentPrincipal principal,
                                       @RequestParam("file") MultipartFile file) {
        try {
            String url = logoUploadService.replaceLogo(principal.getAgentId(), file.getBytes());
            return Map.of("logoUrl", url);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/logos/{id}")
    public ResponseEntity<InputStreamResource> serve(@PathVariable UUID id) {
        InputStreamResource resource = new InputStreamResource(logoStorage.resolve(id));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(resource);
    }
}
```

- [ ] **Step 5: Run the test and verify it passes**

Run: `mvn -f backend/pom.xml test -Dtest=LogoControllerTest`
Expected: PASS

- [ ] **Step 6: Run the full backend test suite before moving to the frontend**

Run: `mvn -f backend/pom.xml test`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/insuranceforms/logo/LogoUploadService.java backend/src/main/java/com/insuranceforms/logo/LogoController.java backend/src/test/java/com/insuranceforms/logo/LogoControllerTest.java
git commit -m "feat: add authenticated logo upload and public logo serving"
```

---

## Task 15: Frontend — apiClient, login/registration/account pages

**Files:**
- Create: `frontend/src/api/apiClient.ts`
- Create: `frontend/src/pages/AgentLoginPage.tsx`
- Create: `frontend/src/pages/AdminLoginPage.tsx`
- Create: `frontend/src/pages/AgentRegisterPage.tsx`
- Create: `frontend/src/pages/AgentAccountPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`

**Interfaces:**
- Consumes: `POST /api/auth/agent/login`, `POST /api/auth/admin/login`, `POST /api/auth/agent/register`, `GET /api/agent/me`, `PATCH /api/agent/me`, `POST /api/agent/logo`, `GET /api/csrf-token` (all from prior tasks).
- Produces: `apiClient` (axios instance with `withCredentials` + CSRF cookie/header wiring), reused by every page.

- [ ] **Step 1: Write `apiClient.ts`**

```ts
import axios from 'axios'

const apiClient = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

export async function ensureCsrfCookie(): Promise<void> {
  await apiClient.get('/csrf-token')
}

export default apiClient
```

- [ ] **Step 2: Write the failing test — App now renders routed pages, keeping the existing title assertion true for the login route**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

describe('App', () => {
  it('renders the app title on the agent login route', () => {
    render(
      <MemoryRouter initialEntries={['/login']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByText('מערכת טפסי ביטוח')).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: Run the test and verify it fails**

Run: `npm --prefix frontend test`
Expected: FAIL — `App.tsx` doesn't accept being wrapped in an external router yet (it currently renders a bare heading with no routes).

- [ ] **Step 4: Write `AgentLoginPage.tsx`**

```tsx
import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

export default function AgentLoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await ensureCsrfCookie()
      await apiClient.post('/auth/agent/login', { email, password })
      navigate('/account')
    } catch {
      setError('אימייל או סיסמה שגויים')
    }
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>כניסת סוכן</h2>
      <form onSubmit={handleSubmit}>
        <label>
          אימייל
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          סיסמה
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p role="alert">{error}</p>}
        <button type="submit">כניסה</button>
      </form>
    </div>
  )
}
```

- [ ] **Step 5: Write `AdminLoginPage.tsx`**

```tsx
import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

export default function AdminLoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    try {
      await ensureCsrfCookie()
      await apiClient.post('/auth/admin/login', { email, password })
      navigate('/admin/agents')
    } catch {
      setError('אימייל או סיסמה שגויים')
    }
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>כניסת מנהל</h2>
      <form onSubmit={handleSubmit}>
        <label>
          אימייל
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          סיסמה
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        {error && <p role="alert">{error}</p>}
        <button type="submit">כניסה</button>
      </form>
    </div>
  )
}
```

- [ ] **Step 6: Write `AgentRegisterPage.tsx`**

```tsx
import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

const emptyForm = {
  fullName: '', email: '', password: '', israeliId: '', agencyName: '', mobile: '', agentNumber: '',
}

export default function AgentRegisterPage() {
  const [form, setForm] = useState(emptyForm)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const navigate = useNavigate()

  function update(field: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [field]: e.target.value })
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setErrors({})
    try {
      await ensureCsrfCookie()
      await apiClient.post('/auth/agent/register', form)
      navigate('/login')
    } catch (err: any) {
      setErrors(err?.response?.data?.errors ?? { general: 'ההרשמה נכשלה' })
    }
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>הרשמת סוכן</h2>
      <form onSubmit={handleSubmit}>
        <label>
          שם מלא
          <input value={form.fullName} onChange={update('fullName')} required />
        </label>
        {errors.fullName && <p role="alert">{errors.fullName}</p>}
        <label>
          אימייל
          <input type="email" value={form.email} onChange={update('email')} required />
        </label>
        {errors.email && <p role="alert">{errors.email}</p>}
        <label>
          סיסמה
          <input type="password" value={form.password} onChange={update('password')} required minLength={10} />
        </label>
        {errors.password && <p role="alert">{errors.password}</p>}
        <label>
          תעודת זהות
          <input value={form.israeliId} onChange={update('israeliId')} required />
        </label>
        {errors.israeliId && <p role="alert">{errors.israeliId}</p>}
        <label>
          שם הסוכנות
          <input value={form.agencyName} onChange={update('agencyName')} required />
        </label>
        <label>
          נייד
          <input value={form.mobile} onChange={update('mobile')} required />
        </label>
        <label>
          מספר סוכן
          <input value={form.agentNumber} onChange={update('agentNumber')} required />
        </label>
        {errors.agentNumber && <p role="alert">{errors.agentNumber}</p>}
        <button type="submit">הרשמה</button>
      </form>
    </div>
  )
}
```

- [ ] **Step 7: Write `AgentAccountPage.tsx`**

```tsx
import { FormEvent, useEffect, useState } from 'react'
import apiClient, { ensureCsrfCookie } from '../api/apiClient'

interface Profile {
  id: string
  fullName: string
  email: string
  agencyName: string
  mobile: string
  agentNumber: string
  logoUploaded: boolean
}

export default function AgentAccountPage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    apiClient.get<Profile>('/agent/me').then((res) => setProfile(res.data))
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!profile) return
    await ensureCsrfCookie()
    const res = await apiClient.patch<Profile>('/agent/me', {
      fullName: profile.fullName,
      agencyName: profile.agencyName,
      mobile: profile.mobile,
    })
    setProfile(res.data)
    setMessage('הפרטים עודכנו בהצלחה')
  }

  async function handleLogoUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file || !profile) return
    await ensureCsrfCookie()
    const body = new FormData()
    body.append('file', file)
    await apiClient.post('/agent/logo', body, { headers: { 'Content-Type': 'multipart/form-data' } })
    setProfile({ ...profile, logoUploaded: true })
  }

  if (!profile) {
    return <p>טוען...</p>
  }

  return (
    <div>
      <h1>מערכת טפסי ביטוח</h1>
      <h2>החשבון שלי</h2>
      <form onSubmit={handleSubmit}>
        <label>
          שם מלא
          <input value={profile.fullName} onChange={(e) => setProfile({ ...profile, fullName: e.target.value })} />
        </label>
        <label>
          שם הסוכנות
          <input value={profile.agencyName} onChange={(e) => setProfile({ ...profile, agencyName: e.target.value })} />
        </label>
        <label>
          נייד
          <input value={profile.mobile} onChange={(e) => setProfile({ ...profile, mobile: e.target.value })} />
        </label>
        <button type="submit">שמירה</button>
      </form>
      {message && <p role="status">{message}</p>}
      <label>
        לוגו
        <input type="file" accept="image/png,image/jpeg,image/webp" onChange={handleLogoUpload} />
      </label>
      {profile.logoUploaded && <p>לוגו הועלה בהצלחה</p>}
    </div>
  )
}
```

- [ ] **Step 8: Write `App.tsx` with routing**

```tsx
import { Route, Routes, Navigate } from 'react-router-dom'
import AgentLoginPage from './pages/AgentLoginPage'
import AdminLoginPage from './pages/AdminLoginPage'
import AgentRegisterPage from './pages/AgentRegisterPage'
import AgentAccountPage from './pages/AgentAccountPage'

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<AgentLoginPage />} />
      <Route path="/admin/login" element={<AdminLoginPage />} />
      <Route path="/register" element={<AgentRegisterPage />} />
      <Route path="/account" element={<AgentAccountPage />} />
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
```

- [ ] **Step 9: Update `main.tsx` to provide the router**

```tsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
```

- [ ] **Step 10: Run the test and verify it passes**

Run: `npm --prefix frontend test`
Expected: PASS

- [ ] **Step 11: Run the build**

Run: `npm --prefix frontend run build`
Expected: exits 0 with no TypeScript errors.

- [ ] **Step 12: Manual verification in a real browser — required by CLAUDE.md before claiming this works**

1. Start the backend: `mvn -f backend/pom.xml spring-boot:run` (requires a local PostgreSQL matching `application.yml`'s defaults, or override `DATABASE_URL`/`DB_USERNAME`/`DB_PASSWORD` via env).
2. Start the frontend: `npm --prefix frontend run dev`.
3. Open `http://localhost:3000/register`, submit a registration with a fresh email/agent number, confirm it redirects to `/login`.
4. Log in with those credentials at `/login`, confirm redirect to `/account` and the profile loads.
5. Edit the full name and agency name, save, reload the page, confirm the edit persisted.
6. Upload a PNG/JPEG logo, confirm "לוגו הועלה בהצלחה" appears; open the returned `/logos/{id}` URL directly in a new tab with no login, confirm the image renders.
7. Log out (clear cookies) and open `http://localhost:3000/admin/login`; log in with the `ADMIN_BOOTSTRAP_EMAIL`/`ADMIN_BOOTSTRAP_PASSWORD` set in your local env, confirm it succeeds.
8. Report explicitly which of these steps were actually run and observed working, per CLAUDE.md's "never claim something works until verified the way it will actually be used."

- [ ] **Step 13: Commit**

```bash
git add frontend/src
git commit -m "feat: add login, registration, and account pages wired to the backend"
```

---

## Definition of done for this stage

- All backend tests pass: `mvn -f backend/pom.xml test`
- Frontend build and tests pass: `npm --prefix frontend run build && npm --prefix frontend test`
- Every test from the spec's "Testing plan" section is covered by a task above: registration validation (Task 6), CSRF end-to-end (Task 7), login/session including cross-role rejection and deactivated-agent rejection (Task 7), session survives restart (implied by `spring-session-jdbc` + Testcontainers — not separately tested in this plan; see note below), deactivation revokes existing sessions (Task 9), tenant isolation body-id-spoof (Task 8), admin/agent role boundary (Task 11), admin agent list field contract (Task 11), logo upload validation and metadata stripping (Task 13), logo replacement invalidates old URL (Task 14), public logo route requires no session at all (Task 14).
- Manual browser verification from Task 15, Step 12 has actually been run and its results reported.

---

## Deferred to Stage 2 — do not consider tenant isolation fully covered without this

CLAUDE.md's tenant-isolation requirement calls for a test that logs in as agent A, requests agent B's resource **by id**, and asserts 403. Stage 1 covers only the body-id-spoof variant of this (Task 8's `AgentAccountIsolationTest`), because no id-addressable resource belonging to another agent exists yet in stage 1 — every agent-owned endpoint here is scoped via `/api/agent/me` (session-derived), not `/api/agent/{id}`. Verified against every endpoint added in this plan (Tasks 6–14): none reads an owning agent id from a URL path variable or a query string either — the only `@PathVariable`/`@RequestParam` ids in stage 1 are the logo's opaque public id (`GET /logos/{id}`, intentionally unauthenticated per CLAUDE.md) and the uploaded file part name, neither of which is a tenant-isolation vector.

- [ ] **Stage 2 must add:** once the `submission` table exists and agents can send requests to insured people by id (e.g. `GET /api/agent/requests/{id}` or similar), write a test — agent A logs in, requests agent B's submission by id in the URL path, asserts 403 — before stage 2 is considered done. Do not consider CLAUDE.md's tenant-isolation requirement fully satisfied until this test exists, even though stage 1's narrower body-id test passes cleanly on its own.

**Known gap, carried forward from the spec, not fixed in this stage:** "session survives a simulated backend restart" is not covered by an explicit automated test in this plan — `spring-session-jdbc` persisting to Postgres (rather than in-memory) is what makes this true architecturally, but proving it end-to-end would mean tearing down and rebuilding the Spring context mid-test against the same Testcontainers instance, which is disproportionate effort for this stage. If this matters before stage 3's real deployment, add it then as a `@DirtiesContext`-based test that restarts the application context against the same container and re-uses a previously captured session cookie.
