# CLAUDE.md — FitTrack Pro

---

## 1. PROJECT OVERVIEW

FitTrack Pro is a production-grade fitness and nutrition tracking REST API. Free and open-source — no paywalls. Built to demonstrate senior backend engineering patterns at real scale.

**Scale target:** 20M users · 3M DAU · 5,200 peak RPS · 27M food lookups/day  
**Current phase:** Phase 1 — Core MVP (Auth + Meal Tracking). All Phase 1 items are still unbuilt.  
**Deployment target:** AWS EKS (Kubernetes) with HPA 2–100 pods, GitHub Actions CI/CD.

---

## 2. MY LEARNING RULES — READ THESE FIRST, EVERY SESSION

- I attempt every problem myself for 20–30 minutes before asking for help.
- Never write entire implementations for me unprompted — explain patterns, show focused examples (under 20 lines), let me implement.
- When I share code I wrote, review it honestly — point out junior patterns and explain the senior alternative.
- If I ask "how do I do X", give me the concept + a small targeted example, not the full class.
- Ask me "can you explain why this works?" after showing me a pattern.
- If I'm about to make a bad architectural decision, stop me and explain the trade-off.
- When something is broken, ask me what I've already tried before suggesting a fix.
- Remind me to update DEVLOG.md after every coding session.
- Never generate Flyway migration files automatically — I write those myself after understanding the entity.

---

## 3. TECH STACK

| Layer | Technology | Notes |
|---|---|---|
| Language | Java 21 | Records, sealed classes, virtual threads |
| Framework | Spring Boot 4.0.6 | (pom.xml actual; docs reference 3.x) |
| Database | PostgreSQL 15 (AWS RDS) | Multi-AZ + 3 read replicas, PgBouncer |
| Cache | Redis (AWS ElastiCache) | Cache-aside, rate limiting, sessions |
| Messaging | Apache Kafka (AWS MSK) | `meal.logged`, `workout.completed`, `payment.succeeded` |
| Payments | Stripe | PaymentIntent + idempotent webhook |
| Security | JWT (access 15min + refresh 7d) | BCrypt cost 12, stateless HPA-safe |
| Containerization | Docker + AWS EKS (Kubernetes) | HPA 2–100 pods, rolling deploys |
| CI/CD | GitHub Actions | push → test → build → ECR → deploy |
| Monitoring | CloudWatch + X-Ray + Actuator | Structured JSON logs, P99 alarms |

**Key dependencies in pom.xml (current):**
- `spring-boot-starter-webmvc` (Spring MVC)
- `spring-boot-starter-data-jpa` (Hibernate ORM)
- `spring-boot-starter-security` (JWT filter chain)
- `spring-boot-starter-validation` (Bean Validation)
- `spring-boot-starter-actuator` (health/liveness/readiness probes)
- `postgresql` (JDBC driver)
- `lombok` (boilerplate elimination)

**Not yet in pom.xml — add when phase begins:**
- `spring-boot-starter-data-redis` (Phase 3)
- `spring-kafka` (Phase 3)
- `flyway-core` (Phase 1, before first migration)
- `mapstruct` + `mapstruct-processor` (Phase 1)
- `jjwt-api` / `jjwt-impl` / `jjwt-jackson` (Phase 1)
- `stripe-java` (Phase 4)

---

## 4. ARCHITECTURE RULES — NEVER VIOLATE THESE

**Package structure:** `com.fittrack` root, then:
```
config/
domain/entity/
domain/dto/request/
domain/dto/response/
domain/enums/
repository/
service/
service/impl/
controller/
security/
mapper/
kafka/producer/
kafka/consumer/
cache/
exception/
```

**Layer rules:**
- Controllers depend on Service interfaces, never `ServiceImpl` directly.
- Never return JPA entities from controllers — always map to DTOs first.
- All business logic lives in `ServiceImpl` — zero business logic in controllers.
- `@Transactional` goes on `ServiceImpl` methods, never on controllers.
- `@Transactional(readOnly = true)` on every read-only `ServiceImpl` method.

**Data rules:**
- Money always stored as `INTEGER` cents — never `DECIMAL` or `double`.
- Nutrition always stored per 100g; always computed dynamically — never store calculated macros.
- `calories = (calories_per_100g × quantity_g) / 100` on every read.
- Enums always `@Enumerated(EnumType.STRING)` — never `ORDINAL`.
- Every entity needs `created_at` (`updatable=false`) and `updated_at`.
- UUID primary keys via `GenerationType.UUID` — never auto-increment.
- Always use `BigDecimal` for nutrition calculations — never `double` or `float`.
- `LAZY` fetch on all `@ManyToOne` and `@OneToMany` — never `EAGER`.
- Fix N+1 with `JOIN FETCH` in `@Query`, never with `EAGER` loading.
- Users and financial records are never hard-deleted — soft delete via `is_active` / status transitions.

---

## 5. DATABASE — 14 TABLES

### Auth domain
| Table | Key detail |
|---|---|
| `users` | Central table; soft-deleted via `is_active`; implements Spring Security `UserDetails`; `idx_users_email` |
| `refresh_tokens` | 7-day TTL; `is_revoked` for logout; `UNIQUE(token)`; cascade-deleted with user |
| `user_goals` | 1:1 with users via `UNIQUE(user_id)` PK; daily calorie/macro targets |

### Nutrition domain
| Table | Key detail |
|---|---|
| `food_items` | Most-read table; nutrition per 100g only; GIN trigram index on `name`; `UNIQUE(barcode)` |
| `meals` | Container for entries; **RANGE partitioned by `logged_at` (monthly)** — reaches ~3.28B rows/year |
| `meal_entries` | Junction (meal → food_item); stores only `quantity_g`; macros computed dynamically |

### Workout domain
| Table | Key detail |
|---|---|
| `exercises` | Shared library; `UNIQUE(name)`; `MuscleGroup` enum; `is_custom` flag |
| `workout_plans` | Named templates per user ('Push Day', 'Pull Day') |
| `workout_plan_exercises` | Junction (plan → exercise); `order_index` controls display order |
| `workout_sessions` | One instance of executing a plan; `total_volume_kg` cached on completion |
| `exercise_sets` | Most granular; **HASH partitioned by `user_id` (64 partitions)**; `user_id` denormalized for partition key |
| `weekly_schedules` | `UNIQUE(user_id, day_of_week)`; 1=Mon … 7=Sun ISO 8601 |

### Payments domain
| Table | Key detail |
|---|---|
| `payments` | `UNIQUE(stripe_payment_intent_id)` = idempotency guard; amount in cents; status transitions only |
| `notifications` | Created by Kafka workers; never deleted; `is_read` flag; partial index on unread |

---

## 6. CURRENT PHASE CHECKLIST

**Phase 0 — Design** ✅ Complete

**Phase 1 — Core MVP: Auth + Meal Tracking** 🚧 Active
- [ ] User entity + Spring Security UserDetails integration
- [ ] JWT auth — register / login / refresh / logout
- [ ] FoodItem entity + search endpoint (pg_trgm)
- [ ] Meal + MealEntry entities (computed macro pattern)
- [ ] Daily macro calculation endpoint
- [ ] Deploy to Railway.app — get a live URL

**Phase 2 — Workout Tracker** ⬜
- [ ] Exercise entity + MuscleGroup enum
- [ ] WorkoutPlan + WorkoutPlanExercise (many-to-many)
- [ ] WorkoutSession + ExerciseSet logging
- [ ] Weekly volume by muscle group (Collectors.groupingBy)
- [ ] WeeklySchedule entity

**Phase 3 — Redis + Kafka** ⬜
- [ ] Redis: food item cache (benchmark before/after latency)
- [ ] Redis: daily macro cache with invalidation on write
- [ ] Redis: rate limiting — 5 login attempts/min/IP
- [ ] Kafka: `meal.logged` topic + notification consumer
- [ ] Dead letter queue for failed notifications
- [ ] Barcode scanner: Redis → DB → Open Food Facts fallback chain

**Phase 4 — Stripe Payments** ⬜
- [ ] POST /api/v1/donations → Stripe PaymentIntent
- [ ] Idempotent webhook handler (raw body, verify-then-parse)
- [ ] UNIQUE constraint deduplication guard
- [ ] Payment receipt via AWS SES

**Phase 5 — Docker + Kubernetes + AWS** ⬜
- [ ] Multi-stage Dockerfile (~150MB image)
- [ ] Docker Compose: app + postgres + redis + kafka
- [ ] AWS EKS deployment with HPA (min 2, max 10 pods)
- [ ] GitHub Actions CI/CD: test → build → push ECR → deploy
- [ ] CloudWatch alarms: error rate > 1%, P99 > 500ms
- [ ] AWS X-Ray distributed tracing
- [ ] k6 load test — 1,000 concurrent users, screenshot HPA scaling

---

## 7. KEY PATTERNS TO USE — WITH EXAMPLES

**Cache-aside in a service method:**
```java
public FoodItemResponse getFoodItem(UUID id) {
    return cache.get("food:" + id, FoodItemResponse.class)
        .orElseGet(() -> {
            FoodItemResponse r = mapper.toResponse(repo.findById(id).orElseThrow());
            cache.set("food:" + id, r, Duration.ofHours(24));
            return r;
        });
}
```

**Optional.orElseThrow() over null checks:**
```java
User user = userRepository.findByEmail(email)
    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
```

**Builder pattern over constructors:**
```java
FoodItemResponse response = FoodItemResponse.builder()
    .id(entity.getId())
    .name(entity.getName())
    .caloriesPer100g(entity.getCaloriesPer100g())
    .build();
```

**Stream + map instead of for loops:**
```java
List<MealEntryResponse> entries = meal.getEntries().stream()
    .map(mapper::toResponse)
    .toList();
```

**Parameterized logging — always `{}` not string concat:**
```java
log.info("Meal logged for user {} on {}", userId, date);   // correct
log.info("Meal logged for user " + userId);                // never do this
```

**@Transactional(readOnly = true) on reads:**
```java
@Override
@Transactional(readOnly = true)
public DailyMacroResponse getDailyMacros(UUID userId, LocalDate date) { ... }
```

---

## 8. THINGS THAT WILL BREAK — WATCH FOR THESE

**@Transactional self-invocation trap**
Calling `this.someMethod()` inside the same class bypasses the Spring AOP proxy — `@Transactional` on `someMethod` is silently ignored. Always call transactional methods through an injected service interface.

**N+1 query problem**
Loading a `List<Meal>` and then accessing `meal.getEntries()` inside a loop triggers one SELECT per meal. Fix: `@Query("SELECT m FROM Meal m JOIN FETCH m.entries WHERE m.userId = :userId")`.

**BigDecimal comparison**
`bigDecimal1.equals(bigDecimal2)` compares value AND scale — `2.0` does not equal `2.00`. Always use `.compareTo() == 0` for equality checks.

**Webhook raw body**
Spring's `HttpMessageConverter` consumes the `InputStream` once. If Jackson parses the body first, the raw bytes needed for Stripe HMAC-SHA256 verification are gone. Receive as `String` in the controller parameter, verify signature, then parse manually.

**Kafka publish after @Transactional commit**
Never publish a Kafka event inside a `@Transactional` method. If the transaction rolls back after the event is published, the consumer processes an event for a write that never happened. Use `TransactionSynchronizationManager.registerSynchronization` to publish after commit.

**Cache invalidation order**
Always invalidate the cache key BEFORE publishing the Kafka event. If the event is published first and the consumer reads a stale cache key before invalidation completes, it gets wrong data.

---

## 9. INTERVIEW STORY ANCHORS

- **Redis benchmark:** Measured food item lookup at ~22ms from PostgreSQL vs ~1ms from Redis. At 27M lookups/day with 95% cache hit rate, Redis eliminates ~25.65M DB queries/day. Chose cache-aside with explicit key invalidation on meal write.
- **Kafka decoupling decision:** Meal log endpoint returns in ~20ms with HTTP response already sent. `meal.logged` event is processed asynchronously — goal-check logic and notification creation never block the request path. Chose Kafka over RabbitMQ specifically for consumer group isolation (notification worker and analytics worker read the same event independently) and 7-day event replay.
- **N+1 fix:** Lazy-loading `meal.getEntries()` inside a loop caused one SELECT per meal. Fixed with `JOIN FETCH` in a `@Query` — reduced daily-macro endpoint from N+1 queries to a single JOIN query.
- **Stripe idempotency:** Stripe can deliver the same webhook multiple times. `UNIQUE(stripe_payment_intent_id)` in the `payments` table means a duplicate INSERT throws `DataIntegrityViolationException`. Catch it, return 200 OK — the DB did deduplication atomically without a check-then-insert race condition.
- **20M user scale math:** 3M DAU × 3 meals × 3 items = 27M food lookups/day. Meals table grows at ~3.28B rows/year → RANGE partitioned by month. ExerciseSets grows at ~876M rows/year → HASH partitioned 64 ways. Peak RPS = 150M req/day ÷ 86,400s × 3× peak multiplier = ~5,200 RPS.

---

## 10. COMMANDS I USE REGULARLY

```bash
# Start dependencies
docker-compose up -d postgres redis kafka

# Run app
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Run tests
./mvnw test

# Check health
curl http://localhost:8080/actuator/health

# Swagger UI (when running locally)
open http://localhost:8080/swagger-ui.html
```
