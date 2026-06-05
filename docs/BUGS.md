# Bug Log — FitTrack Pro

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