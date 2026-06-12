# Dev Log

---

## 2026-06-12 — Phase 1: Steps 14-16 complete

### What I built
- `FoodItem.java` entity with `BigDecimal` nutrition fields at
  `NUMERIC(10, 4)` precision. Explicit `@Column(name = ...)` annotations
  required because Hibernate converts `caloriesPer100g` to `calories_per100g`
  but the SQL column was `calories_per_100g` — explicit names resolve the
  mismatch.
- `V3__create_food_items_table.sql` — `NUMERIC(10, 4)` for all nutrition
  columns, GIN trigram index on name for fuzzy search, partial unique index
  on barcode `WHERE barcode IS NOT NULL`.
- `FoodItemRequest` record — `@NotNull` on `BigDecimal` fields (not
  `@NotBlank` which is String-only), optional barcode with no validation.
- `FoodItemResponse` record — all nutrition fields plus id and timestamps.
- `FoodItemMapper` via MapStruct — `FoodItem` → `FoodItemResponse`.
- `FoodItemRepository` — `findByBarcode` derived query + native `ILIKE`
  trigram search query ordered by `similarity()`.
- `FoodItemServiceImpl` — `searchByName` wraps raw list in `PageImpl`,
  `findById` and `findByBarcode` with `ResourceNotFoundException`,
  `createFoodItem` with `@Transactional`.
- `FoodItemController` — four endpoints: search, findById, findByBarcode,
  createFoodItem.

### What I learned
- `@NotBlank` is for Strings only — checks non-null and non-whitespace.
  `@NotNull` is for any object type including `BigDecimal`. Using `@NotBlank`
  on `BigDecimal` compiles but does nothing useful.
- `ILIKE` vs `LIKE` — `LIKE` is case-sensitive, `ILIKE` is case-insensitive.
  PostgreSQL-specific. Users search "chicken", "Chicken", "CHICKEN" — all
  should return the same results. `ILIKE` handles this automatically.
- `::` method reference syntax — shorthand for a lambda.
  `foodItemMapper::toFoodItemResponse` is identical to
  `item -> foodItemMapper.toFoodItemResponse(item)`. Must use the instance
  (`foodItemMapper`) not the class (`FoodItemMapper`) — non-static methods
  require an instance to call on.
- Stream pipeline — `.stream()` converts list to lazy pipeline, `.map()`
  transforms each element, `.toList()` is the terminal operation that
  executes everything and collects results.
- `PageImpl` wraps a raw list into Spring's `Page` type. Three arguments:
  the data, the `PageRequest` (which page/size), and the total count.
- Offset calculation for pagination: `page * size`. Page 0 = offset 0,
  page 1 = offset 20, page 2 = offset 40.
- Capture `foodItemRepository.save()` return value — it returns the saved
  entity with generated UUID and timestamps. Ignoring the return value means
  mapping an entity with null id.
- Indexing at scale — B-tree for exact/range lookups, GIN trigram for fuzzy
  text search, partial indexes for nullable unique columns. Without the GIN
  index, `ILIKE '%chicken%'` on 2M rows is a full table scan.
- N+1 problem — loading N parents then hitting the DB once per parent for
  children = N+1 queries. Fix: `JOIN FETCH` loads everything in one query.
  Critical for Step 18 when building meal queries.
- Unit tests belong after each vertical slice — DTOs + mapper + repo +
  service + controller. Never build on top of untested code.
- Integration tests come after Phase 1 deployment — they test the full
  HTTP → database flow with a real test database via Testcontainers.

### What's next
- Monday: `FoodItemServiceImplTest` unit tests
- Monday: Postman smoke test all four food item endpoints
- Step 17: `Meal` + `MealEntry` entities + migrations (computed macro pattern)
- Step 18: `MealRepository` + `MealEntryRepository` with JOIN FETCH
- Step 19: Meal DTOs + `MealMapper` with BigDecimal macro computation
- Step 20: `MealService` + `MealController`
- Step 21: `UserGoal` entity + migration
- Step 22: Deploy to Railway — live URL

## 2026-06-11 — Phase 1: Steps 12-13.5 complete

### What I built
- `AuthController` with four endpoints — `POST /register` (201), `POST /login`
  (200), `POST /refresh` (200), `POST /logout` (204). Zero business logic —
  delegates entirely to `AuthService`. `@Valid` on request bodies activates
  Bean Validation. Bearer token extracted from Authorization header for refresh
  and logout via `@RequestHeader`.
- Full auth flow smoke tested end to end via Swagger UI and Postman:
    - Register → 201 with access + refresh tokens
    - Login → 200 with tokens
    - Protected endpoint without token → 401
    - Protected endpoint with Bearer token → passes through
    - Refresh → 200 with new access token
    - Logout → 204 No Content
    - Refresh after logout → 401 revoked
- `AuthServiceImplTest` — 8 unit tests with JUnit 5 + Mockito, all passing:
    - register happy path and duplicate email
    - login happy path and bad credentials
    - refreshToken — token not found, revoked, expired, and valid
    - logout — verifies save called with revoked token

### What I learned
- `@Valid` on `@RequestBody` is required to activate Bean Validation — without
  it `@NotBlank` and `@Email` annotations on DTOs do nothing. The annotation
  is the trigger, not the DTO itself.
- `204 No Content` is the correct status for operations that succeed but return
  nothing. `200 OK` implies a body. Logout returns void so 204 is correct.
- Bearer token convention — `Authorization: Bearer <token>`. The space between
  `Bearer` and the token is required. `substring(7)` strips the prefix. Your
  `JwtAuthenticationFilter` checks `startsWith("Bearer ")` — no space means
  it doesn't recognize it.
- Postman vs Swagger — Swagger is good for quick exploration but has quirks
  with custom headers. Postman gives full control over every header and is the
  production standard for API testing.
- Unit tests with Mockito — `@Mock` creates a fake dependency, `@InjectMocks`
  creates the real class with fakes injected, `when().thenReturn()` scripts
  the fake behavior, `assertThrows()` verifies exceptions, `verify()` confirms
  side effects happened. No database, no Spring context, runs in milliseconds.
- The difference between unit and integration tests — unit tests one class in
  isolation with mocks, integration tests the full stack with real infrastructure.
- Lambda syntax `() ->` — passes a block of code as an argument. `assertThrows`
  needs it so it can run the code itself and catch the exception, rather than
  the exception escaping before `assertThrows` can intercept it.
- Why `mockUser` is needed even in tests that don't seem to need a User —
  `userRepository.save()` returns a User in real life. Without telling the mock
  what to return, it returns null and everything downstream crashes.

### Bugs encountered
- **BUG-003** — `LazyInitializationException` on `refreshToken()` and `logout()`
  when accessing `refreshToken.getUser()` outside a Hibernate session. Fixed by
  adding `@Transactional` to both methods. See `docs/BUGS.md`.
- Postman sending stale cached token from a previous session — caused repeated
  401s that looked like a code bug. Always verify the exact token string being
  sent matches what's in the database when debugging auth issues.
- Swagger Authorization box requires `Bearer <token>` with a space — `BearerXXX`
  without the space is not recognized by the filter's `startsWith("Bearer ")`
  check.

### What's next
- Step 14: `FoodItem` entity + `V3__create_food_items_table.sql`
- Step 15: `FoodItemRepository` + DTOs + `FoodItemMapper`
- Step 16: `FoodItemService` + `FoodItemController`
- Step 17: `Meal` + `MealEntry` entities + migrations
- Step 18: `MealRepository` + `MealEntryRepository`
- Step 19: Meal DTOs + `MealMapper`
- Step 20: `MealService` + `MealController`
- Step 21: `UserGoal` entity + migration
- Step 22: Deploy Phase 1 to Railway — live URL

## 2026-06-10 — Phase 1: Steps 8-11 complete

### What I built
- `RefreshToken.java` entity with `@ManyToOne(FetchType.LAZY)` to `User`,
  `@Builder.Default` on `isRevoked = false`, `@CreationTimestamp` on `createdAt`.
- `V2__create_refresh_tokens_table.sql` — FK to users with `ON DELETE CASCADE`,
  unique index on token, regular index on user_id.
- `RefreshTokenRepository` — `findByToken(String)` and
  `findByUserAndIsRevokedFalse(User)`.
- `SecurityConfig` — three beans: `PasswordEncoder` (BCrypt cost 12),
  `AuthenticationManager`, `SecurityFilterChain`. Stateless sessions, CSRF
  disabled, auth + Swagger endpoints permitted, JWT filter registered via
  `addFilterBefore`.
- `UserDetailsServiceImpl` — implements Spring Security's `UserDetailsService`,
  `loadUserByUsername()` delegates to `userRepository.findByEmail()`. User
  entity implements `UserDetails` so no mapping needed.
- `JwtAuthenticationFilter` extending `OncePerRequestFilter` — extracts Bearer
  token from Authorization header, validates via `JwtService`, sets
  `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`. Guard clause
  passes unauthenticated requests through silently for public endpoints.
- `AuthService` interface — `register`, `login`, `refreshToken`, `logout`.
- `AuthServiceImpl` — full auth flow implementation:
    - `register()` — duplicate email check, BCrypt hash, save user, issue both
      tokens, save `RefreshToken` entity, return `AuthResponse`
    - `login()` — `AuthenticationManager.authenticate()` handles password
      verification, generate tokens, save refresh token, return `AuthResponse`
    - `refreshToken()` — validate not revoked and not expired, generate new
      access token, return same refresh token
    - `logout()` — find refresh token, set `isRevoked = true`, save

### What I learned
- `SessionCreationPolicy.STATELESS` — server stores zero session state. Every
  request is self-contained. Any pod handles any request. Essential for
  horizontal scaling on Kubernetes — session-based auth breaks when requests
  hit different pods.
- `SecurityContextHolder` is thread-local — each request thread has its own
  isolated authentication context. Your JWT filter must explicitly set it on
  every request because there's no session to load it from automatically.
- Why `authenticationManager.authenticate()` in `login()` instead of manual
  password comparison — it handles the full Spring Security authentication
  pipeline: load user via `UserDetailsService`, hash the password, compare,
  check `isEnabled()`. One call replaces 20 lines of manual logic.
- `BadCredentialsException` → `InvalidCredentialsException` boundary — Spring
  Security's exception types should never leak into your API response. Map them
  to your own at the service boundary.
- Three-argument `UsernamePasswordAuthenticationToken` vs two-argument — three
  arguments marks the token as authenticated. Two arguments marks it as not yet
  authenticated. Wrong choice means Spring Security rejects every request.
- Access token is stateless — generated string, never stored. Fast, no DB
  lookup on every request. Refresh token is stored — needs to be revocable on
  logout. That asymmetry is the entire point of the two-token pattern.
- `org.springframework.transaction.annotation.Transactional` not
  `jakarta.transaction.Transactional` — Spring's version supports `readOnly`
  and integrates with Spring's transaction management properly.

### Bugs encountered
- **BUG-002** — Same Flyway ordering issue on V2 migration. See `docs/BUGS.md`.
  Permanent fix: `defer-datasource-initialization: true` in `application-local.yml`.

### What's next
- Step 12: `AuthController` — four endpoints, delegates to `AuthService`
- Step 13: Smoke test full auth flow via Swagger UI
- Step 13.5: Unit tests for `AuthServiceImpl` (JUnit 5 + Mockito)
- Step 14: `FoodItem` entity + migration

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