# FitTrack Pro — Implementation Roadmap

This is the full ordered build guide for FitTrack Pro, from first line of business logic to production on AWS. Every step lists **what** to build, **why it comes at this exact position** in the sequence, and **senior-level tips** for that specific piece. No copy-paste. Read a step, understand it, then implement it yourself.

---

## How to read this document

- Each step is a unit of work. Do not start step N+1 until step N compiles and the behavior makes sense to you.
- "Why this order" explains the dependency that forces this sequencing. These are not opinions — violating them creates compile errors or runtime failures.
- "Senior tip" is the non-obvious thing you will not find by just reading the Spring docs.

---

## PHASE 1 — Core MVP: Auth + Meal Tracking

### Step 1 — Exception Hierarchy + Global Exception Handler

**What:** Create your custom exception classes and a single `@ControllerAdvice` that catches all of them and returns consistent error responses.

Exceptions to create:
- `ResourceNotFoundException` (404) — entity not found by ID
- `DuplicateEmailException` (409) — registration with an already-used email
- `InvalidCredentialsException` (401) — bad login
- `InvalidTokenException` (401) — expired or revoked JWT/refresh token
- `ForbiddenException` (403) — user tries to access another user's resource

**Why this comes first:** Every single layer you build from here on will throw these. If you write the User entity first, the first time you write a service method you will immediately need `ResourceNotFoundException`. Define the shared vocabulary before writing the sentences. Do it once here and never think about error shape again.

**Senior tip:** Spring 6 (and therefore Spring Boot 3+) ships with `ProblemDetail` as a first-class built-in — it is the RFC 7807 standard. Your `GlobalExceptionHandler` should return `ResponseEntity<ProblemDetail>` for every handler method. This gives API consumers a consistent, standardized error contract: `type`, `title`, `status`, `detail`, `instance`. Never return a raw `String` or a custom error object when `ProblemDetail` is already there. Your interview story: "We standardized on RFC 7807 so every error across the API has the same shape — clients only need to handle one error format."

---

### Step 2 — User Entity

**What:** Create `domain/entity/User.java`. This class implements Spring Security's `UserDetails` interface and maps to the `users` table.

Fields: UUID id, String email, String passwordHash, String firstName, String lastName, Role role (enum), boolean isActive (soft delete), LocalDateTime createdAt, LocalDateTime updatedAt.

**Why this comes before everything else:** Spring Security is built around the `UserDetails` contract. You cannot write `UserDetailsService`, cannot configure `SecurityConfig`, cannot write `JwtService` that references `UserDetails` — until this class exists. It is the foundation that everything security-related sits on top of. This is not a preference; it is a compile-time dependency.

**Senior tips:**
- Implement `UserDetails` directly on the entity. This is a deliberate choice. It keeps the security contract close to the data model. The alternative is a separate adapter class, but that adds indirection without benefit at this scale.
- `isEnabled()` should delegate to `isActive`. When you soft-delete a user by setting `is_active = false`, Spring Security automatically rejects their login attempts — no extra code needed.
- `getUsername()` must return `email` (not a username field), because that is what `UserDetailsService` will look up.
- `getAuthorities()` returns `List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))`. The `ROLE_` prefix is a Spring Security convention.
- `@Enumerated(EnumType.STRING)` on the `role` field. Non-negotiable. If you use `ORDINAL`, adding a new enum value in the middle of the list silently corrupts all existing rows.
- `@Column(updatable = false)` on `createdAt`. It should never change after first insert.
- `@UpdateTimestamp` (Hibernate) on `updatedAt` so Hibernate manages it automatically.
- `@ToString.Exclude` on any collection fields to prevent accidental LAZY load chains in log statements.

---

### Step 3 — Flyway Migration V1 + Switch ddl-auto to validate

**What:** Write `src/main/resources/db/migration/V1__create_users_table.sql` by hand. After the migration runs successfully, change `spring.jpa.hibernate.ddl-auto` from `update` to `validate` in `application-local.yml`.

**Why now:** You just finished understanding the User entity — its fields, types, constraints, and indexes. Write the migration while that understanding is fresh. The critical action here is also switching to `validate`. Right now `ddl-auto: update` means Hibernate silently modifies your database schema whenever your entity changes. That is catastrophic in production. `validate` makes Hibernate fail fast at startup if the schema does not match the entities — Flyway owns the schema from this point forward, not Hibernate.

**Senior tips:**
- Never edit a migration file after it has been executed against any database. If you made a mistake, create V2 to fix it. Flyway stores a checksum of each migration; a mismatch crashes the app at startup.
- Add `CREATE EXTENSION IF NOT EXISTS pg_trgm;` in this migration even though you do not use trigrams until FoodItem. It is a one-time database-level operation. Get it done now so V3 can just create the index without worrying about the extension.
- Index `email` with a unique index — it is your primary lookup column for auth. The index name should follow a pattern: `idx_users_email`.
- `password_hash` is the column name in SQL, `passwordHash` is the field name in Java. Know the convention: Hibernate by default converts camelCase to snake_case.

---

### Step 4 — UserRepository

**What:** Create `repository/UserRepository.java`, extending `JpaRepository<User, UUID>`.

Methods to declare: `Optional<User> findByEmail(String email)` and `boolean existsByEmail(String email)`.

**Why before services:** Repositories depend only on entities. Services depend on repositories. You build bottom-up. You cannot write the service interface's method signatures until you know what data access methods are available to the service. Define the data access layer, then build upward.

**Senior tips:**
- Always `Optional<User>` from `findByEmail`, never `User` (which would return null on miss). This forces the caller to handle the not-found case explicitly. The pattern in services: `.orElseThrow(() -> new ResourceNotFoundException("User not found: " + email))`.
- `existsByEmail(String email)` fires `SELECT 1 FROM users WHERE email = ?` — it does not `SELECT *`. Use this for the duplicate-check in `register()` before attempting the insert. It is significantly cheaper than `findByEmail` when you only care about existence.
- Spring Data JPA generates the implementation for these methods at compile time from the method name. No `@Query` needed here.

---

### Step 5 — User DTOs

**What:** Create the request and response objects for auth:
- `domain/dto/request/RegisterRequest.java` — email, password, firstName, lastName
- `domain/dto/request/LoginRequest.java` — email, password
- `domain/dto/response/AuthResponse.java` — accessToken, refreshToken, expiresIn
- `domain/dto/response/UserResponse.java` — id, email, firstName, lastName, role, createdAt

**Why before the service:** Service method signatures are defined by these types. If you write the service first and pass entities around, you will have to retrofit DTO mapping later and probably leave entity fields exposed in your API responses. Define the contract first — what goes in and what comes out — then write the code that fulfills the contract.

**Senior tips:**
- Validation annotations go on request objects only, never on responses. `RegisterRequest` needs `@NotBlank` on email/password/names, `@Email` on email, `@Size(min = 8)` on password.
- Make `AuthResponse` a record or use Lombok `@Builder`. It has no behavior — it is just data out. Records are the modern Java choice.
- Never put the password or passwordHash in `UserResponse`. This should be physically impossible — the field simply should not exist on the response DTO.
- `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)` on the password field in `RegisterRequest` prevents it from ever appearing in serialized output even if someone accidentally includes it in a response.

---

### Step 6 — JwtService

**What:** Create `security/JwtService.java`. This class handles all JWT operations: generate access tokens, generate refresh tokens, validate tokens, extract claims.

**Why before SecurityConfig:** The `JwtAuthenticationFilter` (which runs on every request) will inject `JwtService`. That filter is registered inside `SecurityConfig`. You cannot write the filter without `JwtService` existing, and you cannot finalize `SecurityConfig` without the filter. `JwtService` itself has zero dependencies on Spring Security internals — it only uses the jjwt library and `@Value` config injection. Build it in isolation first, then plug it into the security layer.

**Senior tips:**
- Read `jwt.secret`, `jwt.access-token-expiry-ms`, and `jwt.refresh-token-expiry-days` from `application.yml` via `@Value`. Never hardcode these.
- The secret must be at least 256 bits for HMAC-SHA256. Store it Base64-encoded in config. Use `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))` from jjwt to build the signing key.
- The access token claims: `sub` (email/userId), `iat` (issued at), `exp` (expiry). Keep it minimal — every claim adds bytes to every request header.
- `extractUsername(String token)` is the method the filter will call on every authenticated request. Make it cheap: just parse the claims, extract the subject.
- `validateToken(String token, UserDetails userDetails)` should check: is the token expired AND does the `sub` match `userDetails.getUsername()`. Both conditions must pass.
- Wrap jjwt exceptions (`ExpiredJwtException`, `MalformedJwtException`, `SignatureException`) in your `InvalidTokenException` at the boundary. Do not let jjwt's exception types leak into other layers.

---

### Step 7 — UserMapper

**What:** Create `mapper/UserMapper.java` using MapStruct. Map `User` → `UserResponse`.

**Why before the service:** Services return DTOs, not entities. Mappers are what convert entities to DTOs. If you write the service before the mapper, you end up doing manual field-by-field assignment inside the service, which is the mapper's job. Define the mapper first so the service can call it cleanly.

**Senior tips:**
- `@Mapper(componentModel = "spring")` makes MapStruct generate a Spring `@Component` implementation that you inject via `@Autowired`. This is the only production-correct setup — `componentModel = "default"` would give you a Mappers.getMapper() singleton that does not participate in Spring's DI lifecycle.
- MapStruct generates the implementation class at compile time (during `mvn compile`). If you see `@Autowired` injection failures in tests, it is usually because the annotation processor did not run — check your Maven `annotationProcessorPaths` in `pom.xml`.
- If field names match between entity and DTO, no `@Mapping` annotation is needed. Only add `@Mapping` when names differ or when you need a custom expression.
- There is no `User` → `AuthResponse` mapping because `AuthResponse` contains tokens, not user fields. The service assembles `AuthResponse` manually after calling `JwtService`.

---

### Step 8 — RefreshToken Entity + Migration + Repository

**What:** Create `domain/entity/RefreshToken.java`, write `V2__create_refresh_tokens_table.sql`, and create `repository/RefreshTokenRepository.java`.

Fields on entity: UUID id, User user (ManyToOne), String token (the actual token string, UNIQUE), LocalDateTime expiresAt, boolean isRevoked, LocalDateTime createdAt.

**Why after User:** RefreshToken has a `@ManyToOne` foreign key to `users`. You cannot create a migration that references a table that does not exist yet. The rule is: when entity A depends on entity B, entity B comes first in every layer — entity, migration, repository.

**Senior tips:**
- Index the `token` column — `findByToken(String token)` is called on every token refresh request. Without an index this is a full table scan.
- `isRevoked = false` by default. Logout sets `isRevoked = true`. This preserves an audit trail. Hard-deleting refresh tokens means you can never investigate a security incident.
- Add `cascade = CascadeType.ALL, orphanRemoval = true` on the `user` side if you add a `@OneToMany` back-reference — though you may not need it. Alternatively, handle cleanup via a `@Query` delete.
- `findByToken(String token)` returns `Optional<RefreshToken>` — same pattern as `findByEmail`.
- `findByUserAndIsRevokedFalse(User user)` is useful for revoking all tokens on password change or security event.

---

### Step 9 — SecurityConfig + UserDetailsService

**What:** Create `config/SecurityConfig.java`. This is the Spring Security filter chain configuration. Also either create a `UserDetailsServiceImpl.java` or define `UserDetailsService` as a lambda bean directly in `SecurityConfig`.

**Why now:** `AuthServiceImpl` needs two beans to function: `AuthenticationManager` and `PasswordEncoder`. Both of these beans are declared in `SecurityConfig`. If you try to write `AuthServiceImpl` before `SecurityConfig`, those beans do not exist yet and you will get `UnsatisfiedDependencyException` at startup. You build the foundation (beans) before the thing that needs the foundation (service).

**Senior tips:**
- Declare `PasswordEncoder` as a `@Bean` inside `SecurityConfig`. BCrypt with cost factor 12. Cost 12 means ~250ms per hash on modern hardware — slow enough to frustrate brute force, fast enough to be invisible to users.
- Expose `AuthenticationManager` as a `@Bean` via `authenticationConfiguration.getAuthenticationManager()`. This is the Spring Boot 3+ way — do not `@Autowire AuthenticationManager` directly.
- `UserDetailsService` lambda bean calls `userRepository.findByEmail(username).orElseThrow(...)`. That is the entire implementation.
- The `SecurityFilterChain` bean: `SessionCreationPolicy.STATELESS` (no server-side sessions — your JWT is the session), CSRF disabled (REST APIs with JWT do not need CSRF protection), `requestMatchers("/api/v1/auth/**").permitAll()`, `.anyRequest().authenticated()`.
- Do NOT add `JwtAuthenticationFilter` to the chain yet — you will add it in Step 10 once the filter exists.

---

### Step 10 — JwtAuthenticationFilter

**What:** Create `security/JwtAuthenticationFilter.java`, extending `OncePerRequestFilter`. This filter runs before every request, extracts the Bearer token, validates it, and populates `SecurityContextHolder`.

**Why after SecurityConfig:** The filter must be registered in `SecurityConfig` via `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)`. The filter class must exist before you can reference it in `SecurityConfig`. Go back to `SecurityConfig` after creating this filter and add the `addFilterBefore` line.

**Senior tips:**
- `OncePerRequestFilter` guarantees exactly one execution per request, even in a Servlet forward/include chain. Never extend plain `Filter` — it can run multiple times.
- The guard clause: if the `Authorization` header is missing or does not start with `"Bearer "`, call `filterChain.doFilter()` and return immediately. Do not throw — unauthenticated requests to public endpoints should pass through silently.
- Check `SecurityContextHolder.getContext().getAuthentication() == null` before setting it. Do not overwrite an already-authenticated context.
- After extracting username and validating the token, set: `UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())`. Then set `authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request))` before putting it in the `SecurityContextHolder`. That detail object carries the IP address — useful for rate limiting and audit logs.
- Do not inject `UserRepository` here. Inject `JwtService` and `UserDetailsService`. The filter's job is authentication; `UserDetailsService` does the database lookup.

---

### Step 11 — AuthService Interface + AuthServiceImpl

**What:** Create `service/AuthService.java` (interface) and `service/impl/AuthServiceImpl.java` (implementation with `@Service`).

Methods: `register(RegisterRequest)`, `login(LoginRequest)`, `refreshToken(String token)`, `logout(String token)`.

**Why now:** This is the payoff for building the foundation first. You now have everything `AuthServiceImpl` needs: `UserRepository`, `UserMapper`, `JwtService`, `RefreshTokenRepository`, `PasswordEncoder` (injected as interface), `AuthenticationManager` (injected as interface). Every single dependency exists. If you had tried to write this in Step 2, none of those dependencies would have been available.

**Senior tips:**
- `register()` flow: `userRepository.existsByEmail()` → throw `DuplicateEmailException` → `passwordEncoder.encode(request.password())` → save User → call `JwtService.generateAccessToken()` and `generateRefreshToken()` → save RefreshToken entity → return `AuthResponse`.
- `login()` flow: call `authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password))`. This one call does everything: loads the user via `UserDetailsService`, hashes the password, compares, checks `isEnabled()`. If authentication fails it throws `BadCredentialsException` — catch it and throw your `InvalidCredentialsException`. Do not call `passwordEncoder.matches()` manually in login. That is `AuthenticationManager`'s job.
- `refreshToken()` flow: find by token string → check `isRevoked` → check `expiresAt` → load user → generate new access token → return. Do not generate a new refresh token on each refresh (this is called refresh token rotation and adds complexity — do it only if you have a security reason).
- `logout()` flow: find refresh token → set `isRevoked = true` → save. The access token becomes invalid when it expires naturally (15 minutes). This is the stateless tradeoff.
- `@Transactional` on `register()` and `login()` (write operations). `@Transactional(readOnly = true)` is not appropriate on methods that write, even if most of the work is reading.

---

### Step 12 — AuthController

**What:** Create `controller/AuthController.java` with `@RestController` and `@RequestMapping("/api/v1/auth")`.

Endpoints: `POST /register`, `POST /login`, `POST /refresh`, `POST /logout`.

**Why last in auth:** Controllers are the thinnest layer. They receive HTTP input, delegate to a service, return HTTP output. Zero business logic. There is nothing to write here until the service exists to delegate to.

**Senior tips:**
- Return `ResponseEntity<AuthResponse>` with explicit status codes: `201 Created` for register, `200 OK` for login and refresh.
- `@Valid` on `@RequestBody` parameters activates Bean Validation. Without `@Valid`, your `@NotBlank` and `@Email` annotations on the request DTOs do nothing.
- For `logout`, the token comes from the `Authorization` request header, not the request body. Extract it in the controller: `request.getHeader("Authorization").substring(7)`. That is the HTTP convention for Bearer tokens.
- Add `@Operation` (springdoc) annotations to each endpoint now, while you understand the contract. Documenting as you go is dramatically easier than documenting at the end.

---

### Step 13 — Smoke Test Auth End-to-End

**What:** Run the application, open Swagger UI at `/swagger-ui.html`, and manually verify the full auth flow.

Tests to run: register a user, confirm 201, login with that user, confirm you receive both tokens, call a protected endpoint without the token and confirm 401, call it with the Bearer token and confirm it passes, use the refresh token to get a new access token, logout and confirm the refresh token is revoked.

**Why now:** Before you add FoodItem, Meal, and everything else on top, verify the foundation is solid. A bug in the JWT filter or the SecurityConfig will corrupt every subsequent feature. Find it now when the system is small, not after you have written 20 more classes.

**Senior tip:** Also test the sad paths: register the same email twice (should get 409), login with wrong password (should get 401), use an expired/malformed token (should get 401), try to refresh with a revoked token (should get 401). If any of these return 500 instead of the expected status, your `GlobalExceptionHandler` is missing a handler for that exception type.

---

### Step 14 — FoodItem Entity + Migration

**What:** Create `domain/entity/FoodItem.java` and write `V3__create_food_items_table.sql`.

Fields: UUID id, String name, String barcode (nullable, UNIQUE), BigDecimal caloriesPer100g, BigDecimal proteinPer100g, BigDecimal carbsPer100g, BigDecimal fatPer100g, BigDecimal fiberPer100g, LocalDateTime createdAt, LocalDateTime updatedAt.

**Why after auth:** FoodItem has no FK dependencies on User or auth. It is a standalone domain. You build it now because `MealEntry` will eventually have a FK to `food_items` — so FoodItem must exist before you can create the Meal domain.

**Senior tips:**
- The GIN trigram index syntax in the migration: `CREATE INDEX idx_food_items_name_trgm ON food_items USING GIN (name gin_trgm_ops);`. This enables `ILIKE '%chicken%'` queries to use an index instead of doing a sequential scan across 2 million rows.
- The `pg_trgm` extension was created in V1. Now you just need the index.
- `UNIQUE(barcode)` should be a partial unique index: `CREATE UNIQUE INDEX idx_food_items_barcode ON food_items (barcode) WHERE barcode IS NOT NULL;`. Thousands of food items do not have barcodes. A regular UNIQUE constraint would treat all NULL barcodes as duplicates in some databases (though PostgreSQL treats NULLs as distinct — still, the partial index is the explicit, documented intent).
- All nutrition fields are `NUMERIC(10, 4)` in SQL (BigDecimal maps to NUMERIC). Never `FLOAT` or `DOUBLE PRECISION` for nutritional data — floating point arithmetic errors compound when you multiply by quantity and then sum across entries.

---

### Step 15 — FoodItemRepository + DTOs + Mapper

**What:** Create `repository/FoodItemRepository.java`, the request/response DTOs, and `mapper/FoodItemMapper.java`.

**Why this order:** Same bottom-up rule. Repository → DTOs → Mapper before Service.

**Senior tips:**
- The search query uses `pg_trgm` similarity. JPQL does not understand PostgreSQL-specific functions. You need `nativeQuery = true`: `@Query(value = "SELECT * FROM food_items WHERE name ILIKE %:query% ORDER BY similarity(name, :query) DESC LIMIT :limit OFFSET :offset", nativeQuery = true)`. Alternatively, define it as a paginated query with a `Pageable` parameter — Spring Data can combine native queries with pagination, but the count query must be declared separately.
- `Optional<FoodItem> findByBarcode(String barcode)` is a derived query — no `@Query` annotation needed.
- `FoodItemResponse` should use BigDecimal for all nutritional fields, matching the entity.
- `FoodItemMapper` maps nutritional BigDecimal fields directly. No computation here — FoodItem stores raw per-100g data with no transformation needed.

---

### Step 16 — FoodItemService + FoodItemController

**What:** Create the service interface, implementation, and controller for FoodItem operations.

Key operations: search by name (paginated), find by ID, find by barcode, create a food item.

**Senior tips:**
- `searchByName(String query, Pageable pageable)` returns `Page<FoodItemResponse>`. At 2 million food items, pagination is not optional — it is a contract. Set a reasonable `@PageableDefault(size = 20)` in the controller.
- `findById` and `findByBarcode` both call `.orElseThrow(() -> new ResourceNotFoundException(...))`.
- For `createFoodItem`, you should think about authorization: should any authenticated user be able to add food items, or only admins? For now, any authenticated user is fine. Add role-based restrictions later when you add a proper admin role.
- The controller returns `ResponseEntity<Page<FoodItemResponse>>` for search and `ResponseEntity<FoodItemResponse>` for single-item lookups.
- `GET /api/v1/food-items/search?q=chicken&page=0&size=20` is the endpoint contract. The `q` parameter drives the search, `page` and `size` are standard Spring pagination.

---

### Step 17 — Meal Entity + MealEntry Entity + Migrations

**What:** Create `domain/entity/Meal.java`, `domain/entity/MealEntry.java`, and write `V4__create_meals_table.sql` and `V5__create_meal_entries_table.sql`.

Meal fields: UUID id, UUID userId (denormalized for partition key), String name (optional label), LocalDateTime loggedAt (partition key), LocalDateTime createdAt, LocalDateTime updatedAt. One-to-many relationship to MealEntry.

MealEntry fields: UUID id, Meal meal, FoodItem foodItem, BigDecimal quantityG, LocalDateTime createdAt. No macro fields — those are computed.

**Why Meal before MealEntry:** MealEntry has a FK to `meals`. You cannot write the migration or the entity before the table it references exists. Schema dependency is code dependency. Always build the parent before the child.

**Senior tips:**
- The `meals` table migration must include the RANGE partition declaration: `CREATE TABLE meals (...) PARTITION BY RANGE (logged_at);` and at least the first partition: `CREATE TABLE meals_2026_01 PARTITION OF meals FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');`. Hibernate does not manage partitioned tables — the migration defines the partition structure.
- `userId` is stored directly on `Meal` as a UUID column (denormalized), not as a `@ManyToOne User` relationship. This is intentional for partition pruning. If you join to `users` on every meal query, PostgreSQL cannot prune partitions effectively. Know why this denormalization exists.
- On `MealEntry`, `@ManyToOne(fetch = FetchType.LAZY)` on both `meal` and `foodItem`. Never EAGER. LAZY means: do not hit the database for these associations until something actually accesses them.
- `quantityG` is `NUMERIC(8, 2)` — grams with two decimal places is enough precision for any realistic portion.
- There are no calories, protein, carbs, or fat columns on `meal_entries`. This is the computed-macro pattern. Those values come from `(foodItem.xxxPer100g × quantityG) / 100` at read time, every time.

---

### Step 18 — MealRepository + MealEntryRepository

**What:** Create `repository/MealRepository.java` and `repository/MealEntryRepository.java`. Write the JOIN FETCH query that prevents N+1.

**Why define the JOIN FETCH query now, before the service:** If you write the service first, the naive approach is to call `findAll()` or `findByUserId()` and then access `meal.getEntries()` inside a loop. That is the N+1 problem. It feels fine in development with 5 rows of data and becomes catastrophic in production with 3 billion rows. By writing the JOIN FETCH query here, you force yourself to think about data access patterns before writing service logic.

**Senior tips:**
- The query for daily macros: `@Query("SELECT m FROM Meal m JOIN FETCH m.entries e JOIN FETCH e.foodItem WHERE m.userId = :userId AND DATE(m.loggedAt) = :date")`. This loads Meal + all MealEntry + all FoodItem in a single SQL JOIN. Without this, each `.getEntries()` call triggers a new SELECT, and each `.getFoodItem()` call inside the entries loop triggers another SELECT. One query vs. (1 + N + N×M) queries.
- Return `List<Meal>` from this query — not `Page<Meal>`. For daily macro calculation you need all meals for a date, and a user has at most 5-10 meals per day. Pagination is unnecessary here.
- `findByUserIdAndLoggedAtBetween(UUID userId, LocalDateTime start, LocalDateTime end)` is useful for weekly summary features later.

---

### Step 19 — Meal DTOs + MealMapper

**What:** Create all the meal-related DTOs and `mapper/MealMapper.java`.

DTOs: `CreateMealRequest` (name, loggedAt, entries list), `MealEntryRequest` (foodItemId, quantityG), `MealResponse` (id, name, loggedAt, entries list with computed macros), `MealEntryResponse` (id, foodItemName, quantityG, computedCalories, computedProtein, computedCarbs, computedFat), `DailyMacroResponse` (date, totalCalories, totalProtein, totalCarbs, totalFat, meals list).

**Why mapper before service:** The macro computation logic belongs in the mapper. `MealEntryResponse.computedCalories` = `(foodItem.caloriesPer100g × entry.quantityG) / 100`. This is a transformation of data from entity to response shape — that is the mapper's job, not the service's job. Define the mapper, then the service can call it cleanly.

**Senior tips:**
- Always `BigDecimal` for macro computation. `new BigDecimal(entry.getQuantityG()).multiply(entry.getFoodItem().getCaloriesPer100g()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)`. The `RoundingMode.HALF_UP` is required or `divide()` throws `ArithmeticException` on non-terminating decimals.
- `DailyMacroResponse` totals are computed in the service by summing across all `MealEntryResponse` objects — the mapper handles per-entry computation, the service handles aggregation.
- MapStruct `@Mapping` with expression: for computed fields that have no direct source field, use `expression = "java(computeMacros(source))"` and define a default method in the mapper interface.

---

### Step 20 — MealService + MealController

**What:** Create `service/MealService.java` (interface) and `service/impl/MealServiceImpl.java`, then `controller/MealController.java`.

Operations: log a meal, get a meal by ID, get daily macros for a date, delete a meal.

**Senior tips:**
- `logMeal()` must verify the user is logging for themselves — extract userId from `SecurityContextHolder`, not from the request body. Never trust the client to tell you which user they are.
- `getDailyMacros(UUID userId, LocalDate date)` — this is the method that uses your JOIN FETCH query. Pull all meals for that user+date, map each to `MealResponse` via `MealMapper`, sum the totals.
- `deleteMeal()` must verify the meal belongs to the authenticated user before deleting. This is an authorization check, not authentication. If user A tries to delete user B's meal, throw `ForbiddenException` (403).
- `@Transactional(readOnly = true)` on `getMealById` and `getDailyMacros`. `@Transactional` (write) on `logMeal` and `deleteMeal`.
- `GET /api/v1/meals/daily-macros?date=2026-06-01` — the date comes in as a query param, parsed by Spring as `LocalDate` if you annotate the controller param with `@RequestParam @DateTimeFormat(iso = ISO.DATE)`.

---

### Step 21 — UserGoal Entity + Migration

**What:** Create `domain/entity/UserGoal.java` and `V6__create_user_goals_table.sql`.

Fields: UUID id, User user (OneToOne), Integer dailyCalorieTarget, BigDecimal dailyProteinTarget, BigDecimal dailyCarbsTarget, BigDecimal dailyFatTarget, LocalDateTime createdAt, LocalDateTime updatedAt.

**Why last in Phase 1:** UserGoal is supplementary. Auth works without it. Meal tracking works without it. The daily macro endpoint can function without goals (it just returns actuals, not actuals-vs-targets). Building it last means the core features are validated before you add enhancements.

**Senior tips:**
- `@OneToOne(fetch = FetchType.LAZY)` on the User side. Even OneToOne should be LAZY — EAGER loads the entire User entity every time you load a goal.
- `UNIQUE(user_id)` in the migration. This enforces the 1:1 constraint at the database level. The application code alone is not enough.
- In `getDailyMacros`, use `Optional<UserGoal>` — most users will not have set goals initially. Include `goalCalories`, `goalProtein` etc. in `DailyMacroResponse` as nullable fields. If no goals are set, those fields are null and the client renders "—" instead of a target.

---

### Step 22 — Deploy Phase 1 to Railway.app

**What:** Package the app as a JAR, configure Railway environment variables, deploy, and get a live URL.

**Why now:** Phase 1 is a complete, shippable product. Deploy it before building Phase 2. This forces you to handle real-world concerns: environment variables for secrets, production-grade logging, the difference between local and production database URLs. A live URL is also the portfolio artifact.

**Senior tips:**
- Railway reads environment variables directly. Set `DATABASE_URL`, `JWT_SECRET`, `JWT_ACCESS_TOKEN_EXPIRY_MS`, `JWT_REFRESH_TOKEN_EXPIRY_DAYS`, `SPRING_PROFILES_ACTIVE=production`.
- Create `application-production.yml` that reads from environment variables: `spring.datasource.url: ${DATABASE_URL}`. Never commit actual secrets to git.
- Switch `show-sql` to `false` and `ddl-auto` to `validate` in the production profile. SQL logging is only for local development.
- Verify health at `/actuator/health` after deploy. If it returns `UP`, the database connection, Flyway migrations, and Spring context all initialized correctly.

---

## PHASE 2 — Workout Tracker

The workout domain has a clear internal dependency chain: you need exercises before you can add them to plans, plans before you can execute sessions, sessions before you can log sets. Build in dependency order.

---

### Step 23 — MuscleGroup Enum

**What:** Create `domain/enums/MuscleGroup.java` with values: CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, LEGS, GLUTES, CORE, CARDIO.

**Why first:** The `Exercise` entity references this enum. An enum has no dependencies — it is the leaf node of the dependency tree. Define leaves before branches.

**Senior tip:** This enum lives in `domain/enums/`. It is a domain concept, not a Java util. The naming convention for enums in Spring is `SCREAMING_SNAKE_CASE` values. `@Enumerated(EnumType.STRING)` on the `Exercise` entity field, as always.

---

### Step 24 — Exercise Entity + Migration

**What:** Create `domain/entity/Exercise.java` and `V7__create_exercises_table.sql`.

Fields: UUID id, String name (UNIQUE), MuscleGroup muscleGroup, String description, boolean isCustom (false = global library, true = user-created), UUID createdByUserId (nullable — null for global exercises), LocalDateTime createdAt, LocalDateTime updatedAt.

**Senior tips:**
- `UNIQUE(name)` in the migration. Exercise names are the human-visible identifier.
- `is_custom = false` exercises are visible to all users (the shared exercise library). `is_custom = true` exercises belong to the user who created them. Your repository query must `WHERE is_custom = false OR created_by_user_id = :userId` to return the right set.
- Do not `@ManyToOne` the `createdByUserId` — keep it as a plain `UUID` column. You rarely need to join to the user when loading exercises; denormalizing the ID avoids the join.

---

### Step 25 — Exercise Stack (Repository → DTOs → Mapper → Service → Controller)

**What:** Build the full vertical slice for Exercise in one step: repository, DTOs, mapper, service interface + impl, controller.

**Senior tips:**
- `GET /api/v1/exercises?muscleGroup=CHEST` — filter by muscle group with an optional query param.
- `GET /api/v1/exercises/search?q=bench` — trigram search is valuable here too, same pattern as FoodItem.
- Creating a custom exercise: POST with `isCustom = true`, set `createdByUserId` from the security context.
- Deleting a custom exercise: verify ownership. You cannot delete a global exercise (isCustom = false) as a regular user.

---

### Step 26 — WorkoutPlan Entity + Migration

**What:** Create `domain/entity/WorkoutPlan.java` and `V8__create_workout_plans_table.sql`.

Fields: UUID id, UUID userId, String name, boolean isActive (soft-delete pattern), LocalDateTime createdAt, LocalDateTime updatedAt.

**Why before WorkoutPlanExercise:** WorkoutPlanExercise is a junction table that references both `workout_plans` and `exercises`. Both must exist before the junction.

**Senior tip:** A user can have multiple plans (Push Day, Pull Day, etc.). `isActive` lets users archive old plans without losing history. A plan with historical `WorkoutSession` records should never be hard-deleted.

---

### Step 27 — WorkoutPlanExercise Entity + Migration

**What:** Create `domain/entity/WorkoutPlanExercise.java` and `V9__create_workout_plan_exercises_table.sql`.

Fields: UUID id, WorkoutPlan workoutPlan, Exercise exercise, int orderIndex, int defaultSets, int defaultReps, BigDecimal defaultWeightKg (nullable), LocalDateTime createdAt.

**Why after both WorkoutPlan and Exercise:** This is the junction between the two. You cannot define a FK to a table that does not exist yet. Both parent tables must be created before the child junction.

**Senior tips:**
- `orderIndex` is what determines the display order in a plan. When a user drags exercises to reorder them, you update `orderIndex` values. This is far simpler than using a linked list pattern.
- Include `defaultSets`, `defaultReps`, `defaultWeightKg` on the plan exercise. When a user starts a session, these become the suggested values for each set — they do not have to re-enter their working weights every session.
- Use a composite unique constraint: `UNIQUE(workout_plan_id, exercise_id)` — the same exercise should not appear twice in one plan.

---

### Step 28-29 — WorkoutPlan Full Stack (Repository → DTOs → Mapper → Service → Controller)

**What:** The full vertical slice for WorkoutPlan, including operations for adding and reordering exercises.

**Senior tips:**
- `createPlan()` should accept the plan name and optionally an initial list of exercises with order indices — creating a plan and adding exercises in one transaction is the better UX.
- `reorderExercises(UUID planId, List<UUID> exerciseIdsInOrder)` — takes the new order as a list of IDs, computes new `orderIndex` values, saves all. This is one transaction, not one HTTP call per exercise.
- `GET /api/v1/workout-plans` returns the authenticated user's plans. Join fetch the exercises when loading the plan details view.

---

### Step 30 — WorkoutSession Entity + Migration

**What:** Create `domain/entity/WorkoutSession.java` and `V10__create_workout_sessions_table.sql`.

Fields: UUID id, WorkoutPlan workoutPlan, UUID userId (denormalized), LocalDateTime startedAt, LocalDateTime completedAt, BigDecimal totalVolumeKg (nullable, computed on completion), LocalDateTime createdAt.

**Why after WorkoutPlan:** Session has a FK to `workout_plans`. Dependency ordering.

**Senior tip:** `totalVolumeKg` is cached on the entity when the session is completed — it is the sum of (weight × reps) across all sets in the session. This is the one case where pre-computed data is acceptable: you compute it once at completion time and read it many times for history and analytics. The alternative (computing it dynamically from `exercise_sets` every time) requires joining 876M rows.

---

### Step 31 — ExerciseSet Entity + Migration

**What:** Create `domain/entity/ExerciseSet.java` and `V11__create_exercise_sets_table.sql`.

Fields: UUID id, WorkoutSession workoutSession, Exercise exercise, UUID userId (denormalized for partition key), int setNumber, int reps, BigDecimal weightKg, boolean isCompleted, LocalDateTime createdAt.

**Why after WorkoutSession:** ExerciseSet has a FK to `workout_sessions`. Dependency ordering.

**Senior tips:**
- `userId` is denormalized here for the same reason it is on `Meal` — it is the HASH partition key. PostgreSQL's partition pruning requires the partition key to be present in the row itself. You cannot prune based on a join to another table.
- The migration must declare the HASH partition: `CREATE TABLE exercise_sets (...) PARTITION BY HASH (user_id);` followed by 64 partition declarations. This is verbose SQL — it is correct and necessary.
- `setNumber` is the ordinal within the session for that exercise (set 1, set 2, set 3). Useful for display and for knowing when a user skips a set.

---

### Step 32-33 — WorkoutSession Full Stack (Repository → DTOs → Mapper → Service → Controller)

**What:** The full vertical slice for WorkoutSession and ExerciseSet together.

**Senior tips:**
- `startSession(UUID planId)` — creates a `WorkoutSession` record with `startedAt = now()`, `completedAt = null`. Returns the session ID. The client then logs sets against this session ID.
- `logSet(UUID sessionId, LogSetRequest)` — adds one `ExerciseSet` to the session. The user logs their actual weight and reps (which may differ from the plan defaults).
- `completeSession(UUID sessionId)` — sets `completedAt`, computes `totalVolumeKg` by summing all sets, saves. This is the one method that does aggregate math from the `exercise_sets` table.
- JOIN FETCH the sets when loading a session detail view. Same N+1 prevention pattern as Meal.
- `GET /api/v1/workout-sessions/history` — paginated list of past sessions. Do not JOIN FETCH sets here — just load session-level data (name, totalVolumeKg, completedAt). Sets are loaded only on the detail view.

---

### Step 34 — WeeklySchedule Entity + Migration

**What:** Create `domain/entity/WeeklySchedule.java` and `V12__create_weekly_schedules_table.sql`.

Fields: UUID id, User user, WorkoutPlan workoutPlan, int dayOfWeek (1=Monday … 7=Sunday ISO 8601), LocalDateTime createdAt, LocalDateTime updatedAt.

**Senior tip:** `UNIQUE(user_id, day_of_week)` is the critical constraint — one plan per day. Upsert semantics: if the user sets Wednesday to "Push Day" and later changes it to "Pull Day", the application performs a delete-then-insert or an actual upsert on the UNIQUE key. Do not create duplicate schedule rows.

---

### Step 35 — WeeklySchedule Stack + Weekly Volume Calculation

**What:** Complete the WeeklySchedule vertical slice. Add the weekly volume by muscle group endpoint.

**Senior tips:**
- `getWeeklySchedule(UUID userId)` returns a map of day → plan name. Easy reference for "what am I doing today?"
- Weekly volume by muscle group is the `Collectors.groupingBy` story. The algorithm: load all `ExerciseSet` records for the user for the past 7 days → stream → `collect(groupingBy(set -> set.getExercise().getMuscleGroup(), summingDouble(set -> set.getWeightKg() × set.getReps())))`. This produces a `Map<MuscleGroup, Double>` of total kg lifted per muscle group. Use `BigDecimal` not `Double` for the sum. The interview answer: "I used `Collectors.groupingBy` with `Collectors.reducing` to aggregate volume by muscle group in a single stream pass."

---

## PHASE 3 — Redis + Kafka

This phase does not change the API surface. It adds performance (Redis) and decoupling (Kafka) to the existing implementation. You are layering on top of working code, not rewriting it.

---

### Step 36 — Redis Configuration

**What:** Create `config/RedisConfig.java`. Configure a `RedisTemplate<String, Object>` bean with proper serialization settings.

**Why before anything Redis-related:** Every component that touches Redis needs this template. The configuration defines how keys and values are serialized. Get it right once here.

**Senior tips:**
- Use `StringRedisSerializer` for keys. Redis keys are always strings.
- Use `Jackson2JsonRedisSerializer` for values. This serializes Java objects to JSON for storage in Redis. Without proper configuration, the default serializer uses Java object serialization (binary, unreadable, fragile across class changes).
- Configure the Jackson `ObjectMapper` with `defaultTyping` enabled so deserialization knows what class to reconstruct. Without this, deserializing `Object` gives you a `LinkedHashMap`, not your DTO.
- Add the Redis config to `application-local.yml`: `spring.data.redis.host: localhost`, `spring.data.redis.port: 6379`.

---

### Step 37 — CacheService (Generic Wrapper)

**What:** Create `cache/CacheService.java` — a thin wrapper around `RedisTemplate` with three operations: `get(String key, Class<T> type)`, `set(String key, Object value, Duration ttl)`, `evict(String key)`.

**Why a wrapper instead of using RedisTemplate directly:** If you inject `RedisTemplate` into every service that needs caching, all of them become dependent on the Redis client library. If you ever switch from Jedis to Lettuce, or change serialization, you update one class. Also: the wrapper is unit-testable — you can mock `CacheService` in service tests without needing a Redis connection.

**Senior tip:** `get()` returns `Optional<T>`. Cache miss = empty Optional. Cache hit = present Optional. This forces the caller to handle the miss case explicitly: `cacheService.get("food:" + id, FoodItemResponse.class).orElseGet(() -> loadFromDb(id))`. That is the cache-aside pattern expressed cleanly.

---

### Step 38 — Food Item Cache

**What:** Modify `FoodItemServiceImpl.findById()` to add cache-aside behavior using `CacheService`.

Cache key pattern: `food:{uuid}`. TTL: 24 hours. On `updateFoodItem()` or `deleteFoodItem()`: call `cacheService.evict("food:" + id)`.

**Senior tip:** The sequence matters: check cache → miss → query DB → store in cache → return. If you store in cache on the way out, the next 24 hours of requests for that food item never touch the database. At 27M food lookups per day with a 95% cache hit rate, you eliminate 25.65M database queries per day. That is the interview story.

---

### Step 39 — Daily Macro Cache

**What:** Modify `MealServiceImpl.getDailyMacros()` to cache the result. Modify `logMeal()` and `deleteMeal()` to invalidate the cache.

Cache key pattern: `macros:{userId}:{date}`. TTL: 1 hour (users re-log meals frequently throughout the day).

**Why separate cache from food item cache:** Different TTL, different invalidation events. Food items rarely change (24h TTL). Daily macros change every time a user logs a meal (shorter TTL, explicit invalidation on write).

**Senior tip (from CLAUDE.md — this is a gotcha):** Always invalidate the cache BEFORE publishing any Kafka event. If you publish the Kafka event first, and a consumer reads the stale cache key before your invalidation executes, the consumer gets wrong data. The ordering rule: evict cache → publish event. Not the other way around.

---

### Step 40 — Login Rate Limiting

**What:** Add rate limiting to the login endpoint using Redis. Limit: 5 failed attempts per minute per IP address.

**Why here:** After Redis infrastructure exists. This is a security feature, not a business feature — it belongs in the security layer (a filter or interceptor), not the service layer.

**Senior tips:**
- Key pattern: `rate:login:{ipAddress}`.
- INCR + EXPIRE is the Redis pattern: `redisTemplate.opsForValue().increment(key)` on each attempt. On the first increment (when the key did not previously exist), also call `expire(key, 1, MINUTES)`. If the count exceeds 5, throw your custom `TooManyRequestsException` (429).
- Redis INCR is atomic — no race condition between count check and increment.
- Rate limit on failed attempts only, not all attempts. Successful logins should not consume the rate limit budget.
- Implement this in a dedicated `RateLimitingFilter` extending `OncePerRequestFilter` or as an aspect on `AuthServiceImpl.login()`. A filter is cleaner.

---

### Step 41 — Kafka Configuration

**What:** Create `config/KafkaConfig.java`. Configure `ProducerFactory`, `KafkaTemplate`, `ConsumerFactory`, and `ConcurrentKafkaListenerContainerFactory` beans. Define topic beans: `meal.logged`, `workout.completed`.

**Senior tips:**
- Producer config: `StringSerializer` for keys, `JsonSerializer` for values.
- Consumer config: `StringDeserializer` for keys, `JsonDeserializer` for values. Set `TRUSTED_PACKAGES` to your base package name — the deserializer needs to know which classes to trust.
- Consumer group ID in `application.yml`: `spring.kafka.consumer.group-id: fittrack-notification-worker`. This name is important — it is what Kafka uses to track committed offsets. If you change it, all consumers start from the beginning of the topic.
- `AUTO_OFFSET_RESET_CONFIG: earliest` for the notification consumer — process all messages since the consumer last stopped, not just new ones.

---

### Step 42 — MealLoggedEvent POJO

**What:** Create `kafka/producer/MealLoggedEvent.java` (or a shared events package). Fields: userId, mealId, loggedAt, mealName, totalCalories.

**Why before producer or consumer:** Both the producer and the consumer depend on this class. Define the shared contract (the event schema) before writing either side.

**Senior tip:** This is a plain Java object (POJO/record) with no Spring annotations. It must be deserializable by the consumer from JSON. If you use a Java record, ensure it has a no-args constructor (records do not — use a class instead, or configure the deserializer to use the canonical constructor).

---

### Step 43 — MealEventProducer

**What:** Create `kafka/producer/MealEventProducer.java`. Inject `KafkaTemplate<String, MealLoggedEvent>`. Method: `publishMealLogged(MealLoggedEvent event)`.

Call this from `MealServiceImpl.logMeal()`.

**Why here:** Infrastructure (KafkaConfig, MealLoggedEvent) exists. The pattern can now be implemented correctly.

**Senior tip (critical gotcha from CLAUDE.md):** Never call `kafkaTemplate.send()` inside a `@Transactional` method. If the transaction rolls back after the event is published, the Kafka consumer processes an event for a write that never happened. The correct pattern: use `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() { afterCommit() { kafkaTemplate.send(...) } })` inside the transactional method. The event fires only after the database transaction successfully commits. This is one of the most important patterns in event-driven Spring applications.

---

### Step 44 — Notification Entity + Migration

**What:** Create `domain/entity/Notification.java` and `V13__create_notifications_table.sql`.

Fields: UUID id, UUID userId, String message, NotificationType type (enum), boolean isRead, LocalDateTime createdAt.

**Senior tips:**
- Partial index on unread notifications: `CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE is_read = false;`. Unread notification queries are the overwhelming majority. Full table scans on 14 billion rows is not acceptable.
- Notifications are never hard-deleted. The `is_read` flag is the only state transition.
- `NotificationType` enum: MEAL_GOAL_REACHED, WORKOUT_REMINDER, SYSTEM. `@Enumerated(EnumType.STRING)`.

---

### Step 45 — NotificationConsumer + Dead Letter Queue

**What:** Create `kafka/consumer/NotificationConsumer.java`. `@KafkaListener(topics = "meal.logged", groupId = "fittrack-notification-worker")`. Create the dead letter topic configuration.

**Senior tips:**
- The consumer creates a `Notification` entity and saves it. Simple: `notificationRepository.save(...)`.
- Configure a `DefaultErrorHandler` in `KafkaConfig` with retry + dead letter publishing. After N retries (3 is standard), failed messages go to `meal.logged.DLT` (the dead letter topic).
- The consumer group ID being different from any other consumer of `meal.logged` is what makes Kafka fan-out work: both the notification worker and any future analytics worker receive every event independently. This is the Kafka decoupling story.
- `@Transactional` on the consumer method: Kafka message acknowledgment + database write in the same transaction. Either both succeed or neither does.

---

## PHASE 4 — Stripe Payments

---

### Step 46 — PaymentStatus Enum + Payment Entity + Migration

**What:** Create `domain/enums/PaymentStatus.java` (PENDING, SUCCEEDED, FAILED, REFUNDED) and `domain/entity/Payment.java`. Write `V14__create_payments_table.sql`.

Fields: UUID id, UUID userId, String stripePaymentIntentId (UNIQUE), Long amountCents, String currency, PaymentStatus status, String receiptEmail, LocalDateTime createdAt, LocalDateTime updatedAt.

**Why PaymentStatus first:** Same pattern as MuscleGroup — define the leaf enum before the entity that uses it.

**Senior tip:** The `UNIQUE(stripe_payment_intent_id)` constraint is the idempotency guard. This is a database-level guarantee: if Stripe delivers the same webhook twice, the second INSERT throws `DataIntegrityViolationException`. You catch it in the webhook controller and return `200 OK`. The database did the deduplication atomically. This is better than a check-then-insert pattern, which has a race condition.

---

### Step 47 — Payment DTOs + Repository

**What:** `CreatePaymentRequest` (amountCents, currency, receiptEmail), `PaymentResponse` (id, status, clientSecret, amountCents), `PaymentRepository`.

**Senior tip:** `findByStripePaymentIntentId(String id)` is critical — the webhook controller uses this to look up the payment record and update its status.

---

### Step 48 — PaymentService + PaymentController

**What:** `POST /api/v1/donations` — creates a Stripe PaymentIntent and saves a PENDING payment record.

**Senior tips:**
- The Stripe SDK call: `PaymentIntent.create(PaymentIntentCreateParams.builder().setAmount(amountCents).setCurrency(currency).build())`. Save the `paymentIntent.getId()` as `stripePaymentIntentId`.
- Return the `clientSecret` from the Stripe PaymentIntent. The client uses this to confirm payment using Stripe.js on the frontend. The server never handles raw card data.
- Set `idempotencyKey` on every Stripe API call to your internal payment UUID. This prevents double-charges if the Stripe API call is retried due to a network timeout.

---

### Step 49 — Webhook Controller (Most Complex Step)

**What:** Create `controller/WebhookController.java`. This is the endpoint Stripe calls after a payment is confirmed.

**Why this is the hardest step:** Spring's Jackson integration reads the request body once as a parsed object. For Stripe webhook verification, you need the raw bytes to compute the HMAC-SHA256 signature. If Jackson parses first, the bytes are gone.

**Senior tips:**
- Controller method parameter must be `String rawBody` (not a DTO). `@RequestBody String rawBody` tells Spring to give you the raw body as a string, not to parse it.
- Signature verification: `Webhook.constructEvent(rawBody, stripeSignatureHeader, webhookSecret)`. This is the Stripe SDK call. It throws `SignatureVerificationException` if the signature is invalid — return 400.
- Status update flow: parse the event type, look up `Payment` by `paymentIntentId`, update `status` to SUCCEEDED or FAILED, save.
- Duplicate webhook handling: wrap the `paymentRepository.save()` in a try-catch for `DataIntegrityViolationException`. Catch it, return `200 OK`. Stripe expects 200 to stop retrying. Any non-200 response causes Stripe to retry the webhook for up to 72 hours.
- Never return `500` from a webhook endpoint unless you genuinely want Stripe to retry the event. A lookup failure (payment not found) should return `200` with a log entry, not a retryable error.

---

### Step 50 — Payment Receipt Email (Optional)

**What:** If deploying to AWS, send a receipt email via SES when payment succeeds. Triggered from the webhook handler.

**Senior tip:** Send via a Kafka event, not synchronously in the webhook handler. The webhook must return quickly. Fire a `payment.succeeded` event → SES consumer handles the email. This is the same decoupling pattern as `meal.logged`.

---

## PHASE 5 — Docker + Kubernetes + AWS

---

### Step 51 — Multi-Stage Dockerfile

**What:** Write a two-stage `Dockerfile` in the project root.

Stage 1 (builder): Start from a Maven + JDK 21 image. Copy `pom.xml` and the Maven wrapper, download dependencies (this layer is cached — rebuilds are fast unless `pom.xml` changes). Copy `src/`, run `mvn package -DskipTests`.

Stage 2 (runtime): Start from Eclipse Temurin JRE 21 (not JDK — no compiler needed in production). Copy the JAR from the builder stage. `EXPOSE 8080`. `ENTRYPOINT ["java", "-jar", "app.jar"]`.

**Why multi-stage:** The builder image has Maven, the JDK, and build-time tools. Final image has only the JRE and the JAR — roughly 150MB instead of 600MB. Smaller images = faster pulls, smaller attack surface.

**Senior tips:**
- Copy `pom.xml` and download dependencies as a separate layer before copying source code. Maven dependency download is slow; source code changes are frequent. By separating them into different Docker layers, only the source code layer is invalidated on code changes — dependencies are cached.
- Add `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` to `ENTRYPOINT`. These JVM flags let the JVM respect the container's memory limit instead of trying to use the host's total memory.
- Run as a non-root user in the runtime stage. `RUN adduser --system appuser` and `USER appuser`. Root in a container is a security risk.

---

### Step 52 — Docker Compose (Full Stack)

**What:** Update `docker-compose.yml` to add the application service alongside the existing postgres, redis, kafka, and zookeeper services.

**Senior tips:**
- The app service: `depends_on: {postgres: {condition: service_healthy}, redis: {condition: service_healthy}}`. Health checks are already on your infrastructure services — use them.
- Environment variables from an `.env` file. `env_file: .env`. Never hardcode secrets in `docker-compose.yml`. `.env` is gitignored.
- The app container and the postgres container share a Docker network. The app's `DATABASE_URL` should use the service name as hostname: `jdbc:postgresql://postgres:5432/fittrack_db`.

---

### Step 53-54 — Kubernetes Manifests + Secrets

**What:** Write K8s YAML manifests: `Deployment`, `Service`, `HorizontalPodAutoscaler`, `Ingress`, `Secret`, `ConfigMap`.

**Senior tips:**
- `readinessProbe` and `livenessProbe` both point to `/actuator/health`. Readiness = ready to receive traffic. Liveness = alive (restart if failing). Use `/actuator/health/readiness` and `/actuator/health/liveness` respectively — Spring Actuator exposes both.
- HPA: `minReplicas: 2`, `maxReplicas: 100`, `metrics.resource.cpu.target.averageUtilization: 70`. This is the setting that demonstrates scaling in the k6 story.
- Secrets are base64-encoded values in YAML. `kubectl create secret generic fittrack-secrets --from-env-file=.env` is the correct command — do not manually base64-encode each value.
- `ConfigMap` for non-sensitive settings: `SPRING_PROFILES_ACTIVE: production`, `LOG_LEVEL: INFO`.

---

### Step 55 — GitHub Actions CI/CD

**What:** Write `.github/workflows/deploy.yml`. Pipeline: push to `main` → run tests → build Docker image → push to ECR → rolling deploy to EKS.

**Senior tips:**
- Matrix: run tests on every push to every branch, but only deploy on push to `main`.
- Cache the Maven dependency layer in GitHub Actions: `actions/cache` with the `~/.m2` path. First run downloads everything; subsequent runs use the cache.
- Store AWS credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`, `ECR_REPOSITORY`) as GitHub repo secrets.
- Rolling deploy: `kubectl set image deployment/fittrack app=<ecr-url>:<git-sha>`. Using the git SHA as the image tag is the production convention — you can always trace which commit is deployed.
- Add a health check step after deploy: loop until `/actuator/health` returns `UP`, then mark the pipeline successful. Never declare a deploy done before verifying the app is healthy.

---

### Step 56 — CloudWatch + X-Ray + Structured Logging

**What:** Configure structured JSON logging with `logback-spring.xml`, enable AWS X-Ray distributed tracing, and set up CloudWatch alarms.

**Senior tips:**
- Structured JSON logs: every log line is a JSON object with `timestamp`, `level`, `logger`, `message`, and MDC context. This makes logs queryable in CloudWatch Logs Insights. `logstash-logback-encoder` is the library.
- Add `traceId` and `userId` to MDC (Mapped Diagnostic Context) in the JWT filter, so every log line for a request includes the trace ID. Debugging in production becomes tractable.
- CloudWatch alarms: error rate > 1% over 5 minutes, P99 latency > 500ms over 5 minutes. These are the alarms your oncall engineer wakes up to.
- X-Ray: add `aws-xray-recorder-sdk-spring` dependency, configure the servlet filter. Each request generates a trace that shows time spent in the controller, service, JPA, and Redis — you can see where latency lives.

---

### Step 57 — k6 Load Test

**What:** Write a k6 script that simulates 1,000 concurrent users hitting the food item search endpoint. Run it against your EKS deployment and screenshot HPA scaling.

**Why this is the final step:** This is the proof that the architecture works at the scale you designed for. It is also the portfolio story: "I ran a load test at 1,000 concurrent users, watched the HPA scale from 2 to N pods in real time, and measured P99 latency staying under 200ms."

**Senior tips:**
- The k6 script should ramp up (0 → 1000 users over 30 seconds), sustain (1000 users for 2 minutes), ramp down (1000 → 0 over 30 seconds). This simulates realistic traffic growth, not an instantaneous spike.
- Target the food item search endpoint: it is the most read-heavy, it hits Redis first (fast path), and it exercises the full cache-aside pattern.
- Check thresholds in the k6 script: `http_req_duration['p(99)'] < 200`. If this threshold fails, the test fails — treat it like a failing test suite.
- Run the load test while watching the HPA: `kubectl get hpa -w`. Screenshot the pod count increasing. That screenshot is your portfolio artifact.

---

## Cross-Phase Principles

These apply throughout every phase. Keep them in mind on every class you write.

**Dependency direction:** Controllers → Service interfaces → ServiceImpl → Repository. Never skip a layer. Never let a controller touch a repository directly. Never inject a ServiceImpl (always the interface).

**Transactional boundaries:** `@Transactional` goes on `ServiceImpl` methods. Never on controllers. `readOnly = true` on every method that does not write. The database knows read-only transactions and can route them to read replicas.

**Never return entities from controllers:** Map to a DTO before the response leaves the service layer. Entities are JPA objects with proxies, lazy collections, and bidirectional references — they are not JSON-serializable safely.

**N+1 is never acceptable:** Before every service method that loads a collection, ask: "Will accessing associations inside a loop trigger additional queries?" If yes, write the JOIN FETCH query in the repository.

**BigDecimal for money and nutrition:** Always. One `double` in the wrong place poisons all computations that touch it.

**Verify authorization, not just authentication:** Authentication asks "who are you?" Authorization asks "are you allowed to do this?" Every endpoint that operates on a resource must verify the authenticated user owns or has access to that resource.

**Update DEVLOG.md after every session.** Document what you built, what you learned, and what confused you. This is your learning record and becomes interview material.
