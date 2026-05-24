# Dev Log

---

## 2026-05-23 — Phase 1: Local dev environment fully wired

### What I built
- `docker-compose.yml` at project root with all four services: PostgreSQL 15,
  Redis 7, Zookeeper, and Kafka (Confluent 7.5). Named volumes on postgres and
  redis so data survives container restarts.
- `application-local.yml` with datasource config pointing at the Dockerized
  Postgres, HikariCP pool settings, and verbose SQL + security logging for dev.
- `spring.profiles.active=local` set in `application.properties` so IntelliJ
  and the Maven CLI both default to the local profile without any extra flags.
- Full dependency overhaul in `pom.xml`:
    - Flyway (flyway-core + flyway-database-postgresql) — schema migrations
    - JJWT 0.12.6 (api / impl / jackson split) — JWT auth tokens
    - MapStruct 1.6.3 + mapstruct-processor in annotation processor paths — entity ↔ DTO mapping
    - springdoc-openapi 2.8.4 — Swagger UI at /swagger-ui.html
    - spring-boot-starter-data-redis — Redis cache (Phase 3)
    - spring-kafka — Kafka event streaming (Phase 3)
    - stripe-java 27.1.0 — payments (Phase 4)
    - Fixed broken test dependencies (replaced non-existent starters with
      spring-boot-starter-test + spring-security-test + spring-kafka-test)
- Verified app starts successfully and Swagger UI loads in browser.

### What I learned
- Spring profiles: application.yml is always loaded, application-{profile}.yml
  layers on top. Environment variables override everything — that's how
  production secrets are injected without committing them to git.
- Why secrets never go in application-prod.yml: even one accidental commit
  leaves credentials in git history permanently. Production config lives in
  AWS Secrets Manager / Kubernetes Secrets / Railway env vars only.
- Why JJWT ships as three separate jars: jjwt-api is the stable interface you
  code against; jjwt-impl and jjwt-jackson are runtime-only so your code can
  never accidentally depend on internal implementation details.
- Why Lombok must come before MapStruct in annotationProcessorPaths: MapStruct
  generates mapping code by reading getters/setters — if Lombok hasn't run
  first to generate them, MapStruct sees an empty class and produces broken mappers.
- Named Docker volumes vs anonymous volumes: anonymous volumes are deleted on
  `docker-compose down`. Named volumes persist until you explicitly pass `-v`.
- Flyway 10+ split PostgreSQL support into a separate artifact
  (flyway-database-postgresql) — flyway-core alone is not enough.
- Spring Boot's default security locks every endpoint including Swagger UI
  behind a generated password (username: user, password printed in startup log).
  The permanent fix is a SecurityConfig that calls .permitAll() on Swagger paths.

### What's next
- Away for one week (back ~2026-05-30)
- First code when back: User.java entity implementing Spring Security UserDetails
- Then: RefreshToken.java entity
- Then: UserGoal.java entity
- After entities: write Flyway migration V1__create_users_table.sql manually
  (understand the schema before letting anything generate it)
- Switch application-local.yml ddl-auto from `update` → `validate` once
  first Flyway migration is in place

---

## 2026-05-21 — Phase 0: Design complete

### What I built
- ERD on dbdiagram.io — all 14 tables with relationships
- System architecture diagram on Eraser.io
- 7 ADRs written covering every major technology decision
- README with both diagrams, scale math, and build status
- docs/ folder with SYSTEM_DESIGN.md and schema.md

### What I learned
- RANGE vs HASH partitioning: meals needs RANGE by logged_at
  so date-range queries prune to one partition. exercise_sets
  needs HASH by user_id for even distribution regardless of
  when data was written.
- Martin Fowler's monolith-first principle — don't start
  microservices until you have a proven need, not anticipated.

### What's next
- Spring Initializr project setup
- PostgreSQL local database
- First entity: User.java
