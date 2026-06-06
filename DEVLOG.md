# Dev Log

---

## 2026-06-05 — Phase 1: Steps 1-7 complete

### What I built
- `GlobalExceptionHandler` with `@ControllerAdvice` returning RFC 7807
  `ProblemDetail` for all five custom exceptions. Every error across the API
  now has the same shape — clients handle one format.
- Five custom exception classes: `ResourceNotFoundException` (404),
  `DuplicateEmailException` (409), `InvalidCredentialsException` (401),
  `InvalidTokenException` (401), `ForbiddenException` (403). All extend
  `RuntimeException` so they propagate freely to the handler.
- `User.java` entity implementing Spring Security `UserDetails` — maps to
  `users` table, `isEnabled()` delegates to `isActive` for soft-delete support,
  `getUsername()` returns email, `@UuidGenerator` for automatic UUID generation.
- `Role.java` enum with `USER` and `ADMIN` values, `@Enumerated(EnumType.STRING)`
  on the entity field.
- `V1__create_users_table.sql` — first Flyway migration. Creates `users` table
  with all constraints, `pg_trgm` extension for future food search, unique index
  on email, regular index on role.
- Switched `ddl-auto` from `update` to `validate` — Flyway owns the schema
  from this point forward. Hibernate only validates, never modifies.
- `UserRepository` extending `JpaRepository<User, UUID>` with two derived
  queries: `findByEmail` returning `Optional<User>` and `existsByEmail`
  returning `boolean`.
- Four DTOs: `RegisterRequest` and `LoginRequest` (records with Bean Validation
  annotations and `@JsonProperty(WRITE_ONLY)` on password), `AuthResponse` and
  `UserResponse` (plain records, no validation on responses).
- `JwtService` with token generation (access 15min, refresh 7d), claim
  extraction, and validation. jjwt exceptions wrapped in `InvalidTokenException`
  at the boundary so internal library types never leak into other layers.
- `UserMapper` via MapStruct — `User` entity to `UserResponse`. Zero manual
  field mapping needed because field names match.

### What I learned
- `@ControllerAdvice` intercepts exceptions from every controller globally.
  Without it, Spring returns a generic 500. With it, every exception maps to
  a deliberate HTTP status and RFC 7807 response shape.
- RFC 7807 `ProblemDetail` — Spring Boot 3 ships this built-in. `type`,
  `title`, `status`, `detail`, `instance`. Standardized error contract means
  clients only write one error handler. `setProperty()` adds structured fields
  beyond the standard ones.
- Why `RuntimeException` over checked `Exception` for API exceptions — the
  `@ControllerAdvice` IS the catch point. Checked exceptions would force
  `throws` declarations on every service and controller method signature for
  zero benefit.
- `@Enumerated(EnumType.STRING)` is non-negotiable. ORDINAL stores `0`, `1`,
  `2` — adding an enum value in the middle silently corrupts all existing rows.
  STRING stores `"USER"`, `"ADMIN"` — adding values never affects existing data.
- `Optional<User>` from `findByEmail` forces callers to handle the not-found
  case explicitly. Returning raw `User` would return null on miss and cause
  `NullPointerException` somewhere down the stack with a confusing trace.
- `existsByEmail` fires `SELECT 1` — not `SELECT *`. Use for duplicate checks
  before INSERT. Significantly cheaper than loading the full entity when you
  only need existence.
- JWT three parts: header (alg + type, written by jjwt automatically), payload
  (sub, iat, exp — set manually), signature (HMAC-SHA256 of header+payload
  using the secret key). The signature is what makes tokens unforgeable without
  the key.
- jjwt exception boundary — `ExpiredJwtException` and `JwtException` should
  never leak out of `JwtService`. Catch them, wrap in `InvalidTokenException`
  with a reason string ("expired" vs "invalid"). Your `GlobalExceptionHandler`
  handles the rest.
- MapStruct `@Mapper(componentModel = "spring")` — generates a Spring
  `@Component` at compile time. If field names match between entity and DTO,
  no `@Mapping` annotation needed. Only add `@Mapping` when names differ or
  transformation is needed.

### Bugs encountered
- **BUG-001** — Flyway migration not running before Hibernate validation in
  Spring Boot 4. See `docs/BUGS.md` for full root cause and fix.

### What's next
- Step 8: `RefreshToken` entity + `V2__create_refresh_tokens_table.sql` +
  `RefreshTokenRepository`
- Step 9: `SecurityConfig` + `UserDetailsService`
- Step 10: `JwtAuthenticationFilter`
- Step 11: `AuthService` interface + `AuthServiceImpl`
- Step 12: `AuthController`
- Step 13: Smoke test full auth flow via Swagger UI

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
    - MapStruct 1.6.3 + mapstruct-processor in annotation processor paths
    - springdoc-openapi 2.8.4 — Swagger UI at /swagger-ui.html
    - spring-boot-starter-data-redis — Redis cache (Phase 3)
    - spring-kafka — Kafka event streaming (Phase 3)
    - stripe-java 27.1.0 — payments (Phase 4)
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
  first, MapStruct sees an empty class and produces broken mappers.
- Named Docker volumes vs anonymous volumes: anonymous volumes are deleted on
  `docker-compose down`. Named volumes persist until you explicitly pass `-v`.
- Flyway 10+ split PostgreSQL support into a separate artifact
  (flyway-database-postgresql) — flyway-core alone is not enough.

### What's next
- Away for one week (back ~2026-05-30)
- First code when back: User.java entity implementing Spring Security UserDetails

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