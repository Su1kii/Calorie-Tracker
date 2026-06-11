# Bug Log — FitTrack Pro

## BUG-003 — LazyInitializationException on refreshToken() and logout()

**Date:** 2026-06-11
**Symptom:** 500 error on POST /api/v1/auth/refresh with
`LazyInitializationException: Could not initialize proxy [User#...] - no session`
**Root cause:** `refreshToken.getUser()` triggered lazy loading outside a
Hibernate session. The `@ManyToOne(FetchType.LAZY)` on RefreshToken means
Hibernate doesn't load the User until it's accessed. Without `@Transactional`,
the session closes after `findByToken()` returns, so accessing `.getUser()`
has no session to load from.
**Fix:** Added `@Transactional` to `refreshToken()` and `logout()` in
`AuthServiceImpl`. Keeps the Hibernate session open for the entire method.
**Lesson:** Any service method that accesses LAZY-loaded associations must be
`@Transactional`. The session must stay open until all lazy loads are complete.

## BUG-002 — Same Flyway ordering issue on V2 migration (Spring Boot 4)

**Date:** 2026-06-10
**Symptom:** App crashed at startup with `Schema validation: missing table [refresh_tokens]`
even though V2__create_refresh_tokens_table.sql existed in the correct location.
**Root cause:** Same Spring Boot 4 initialization ordering issue as BUG-001.
Hibernate validated before Flyway ran. No Flyway output in logs.
**Fix:** Ran migration manually via Maven Flyway plugin. Note: backtick line
continuation is PowerShell syntax only — in bash (WSL) the command must be
on a single line.
**Command used:**
./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/fittrack_db -Dflyway.user=fittrack_user -Dflyway.password=fittrack_pass -Dflyway.locations=filesystem:src/main/resources/db/migration
**Permanent fix attempted:** Added `defer-datasource-initialization: true` under
`spring.jpa` in `application-local.yml`. Forces Spring to complete Flyway
migrations before Hibernate validation runs. Pending confirmation on V3.
**Lesson:** When the same bug appears twice, fix it at the root. Manual seeding
is a workaround, not a solution. The yml fix should eliminate this permanently.

## BUG-001 — Flyway migration not running before Hibernate validation (Spring Boot 4)

**Date:** 2026-06-05
**Symptom:** App crashed at startup with `Schema validation: missing table [users]`
even though V1__create_users_table.sql existed in the correct location.
**Root cause:** Spring Boot 4 changed Flyway autoconfiguration initialization
ordering. Hibernate was validating the schema before Flyway had a chance to run
the migration. Zero Flyway output appeared in logs despite correct config.
**Fix:** Ran migration manually via Maven Flyway plugin to seed the table into
the database. App started cleanly on next restart.
**Command used:**
./mvnw flyway:migrate \
-Dflyway.url=jdbc:postgresql://localhost:5432/fittrack_db \
-Dflyway.user=fittrack_user \
-Dflyway.password=fittrack_pass \
-Dflyway.locations=filesystem:src/main/resources/db/migration
**Lesson:** Spring Boot 4 Flyway autoconfiguration ordering differs from Boot 3.
When Flyway doesn't appear in startup logs at all, the migration is being skipped
entirely — not failing. Run manually to seed, then investigate autoconfiguration.
