# FitTrack Pro

**Production-grade fitness and nutrition tracking API built with Java 21 + Spring Boot.**

Free and open source — no paywalls, no locked features. Built as a real-world demonstration of senior backend engineering patterns: JWT auth with refresh token rotation, Redis caching with cache-aside invalidation, Kafka event streaming, Stripe webhook idempotency, and a 14-table PostgreSQL schema in 3NF.

> ⚠️ **Active Development** — Currently in Phase 1 (Core MVP). See [Build Status](#build-status) for what's done and what's next.

---

## Why This Project Exists

Most fitness apps lock macro tracking, barcode scanning, and goal analytics behind subscriptions. This project exists to prove that a production-quality backend can power all of it for free — and to demonstrate every pattern a senior backend engineer needs to know: from schema design to Kubernetes deployment.

---

## Architecture

```
Client (React / Mobile)
       │
  CloudFront CDN
       │
  AWS ALB (SSL termination)
       │
  Spring Boot API  ──── Redis (ElastiCache) ── food cache, macro cache, rate limiting
       │
  PostgreSQL (RDS) ── 14 tables, 3NF, UUID PKs, indexed for analytics
       │
  Apache Kafka (MSK) ── meal.logged, workout.completed, payment.received
       │
  Workers ── notification consumer, analytics consumer
```

Full architecture diagram, ERD, and request-flow trace in [`/docs`](./docs).

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| API | Java 21 + Spring Boot 3 | Type safety, Spring Security, mature ORM |
| Database | PostgreSQL 15 (AWS RDS) | JSONB, window functions, UUID support |
| Cache | Redis (AWS ElastiCache) | ~1ms food lookups vs ~20ms DB; rate limiting |
| Messaging | Apache Kafka (AWS MSK) | Async events, decoupled notification workers |
| Payments | Stripe + idempotent webhooks | SHA256 idempotency key, UNIQUE constraint guard |
| Security | JWT (access 15min + refresh 7d) | Stateless, scales horizontally on EKS |
| Containerization | Docker + Kubernetes (AWS EKS) | Rolling deploys, HPA auto-scaling |
| CI/CD | GitHub Actions | Push to main → test → build → deploy |
| Monitoring | CloudWatch + Spring Actuator | Structured JSON logs, P99 latency alarms |

---

## Key Engineering Decisions

**UUID primary keys over auto-increment** — No sequential ID exposure, distributed-system safe, client-side generation possible. B-tree fragmentation trade-off is negligible at this scale.

**Money stored as INTEGER cents** — Floating point is imprecise (`0.1 + 0.2 = 0.30000000000000004`). Integer cents are always exact.

**Nutrition stored per 100g, computed dynamically** — Never store computed values. If source data changes, stored calculations go stale. `calories = (per_100g × quantity_g) / 100` on every read.

**Interface + Impl service pattern** — Controllers depend on the interface, never the implementation. Enables easy mocking in tests and implementation swaps without touching callers (Dependency Inversion Principle).

**Cache-aside with explicit invalidation** — Key: `daily:{userId}:{date}`. Invalidated on every new meal log. TTL: 25 hours. Cache hit: ~1ms. Cache miss with DB fallback: ~20ms.

**Kafka over synchronous notifications** — Meal log request returns immediately. Notification worker processes the `meal.logged` event asynchronously. Dead letter queue handles failures without blocking the partition.

**Idempotent Stripe webhooks** — Stripe can deliver the same event multiple times. `UNIQUE(stripe_payment_intent_id)` means a duplicate INSERT throws `DataIntegrityViolationException` — catch it, return 200 OK. The DB did the deduplication.

Full trade-off table with pros, cons, and verdicts in [`/docs/architecture-decisions.md`](./docs/architecture-decisions.md).

---

## Database Schema

14 tables in 3NF. Every table has UUID primary key, `created_at` (immutable), and `updated_at` (auto-updated).

```
users ──────────── refresh_tokens
  │ ├─────────────── user_goals
  │ ├─────────────── meals ──── meal_entries ──── food_items
  │ ├─────────────── workout_plans ──── workout_plan_exercises ──── exercises
  │ ├─────────────── workout_sessions ──── exercise_sets
  │ ├─────────────── weekly_schedules
  │ ├─────────────── payments
  └─────────────── notifications
```

Full schema with column-level annotations and design rationale in [`/docs/schema.md`](./docs/schema.md).

---

## Project Structure

```
src/main/java/com/fittrack/
├── config/             # SecurityConfig, RedisConfig, KafkaConfig, StripeConfig
├── domain/
│   ├── entity/         # JPA @Entity classes — DB representation
│   ├── dto/
│   │   ├── request/    # Inbound API contracts
│   │   └── response/   # Outbound API contracts
│   └── enums/          # MealType, MuscleGroup, PaymentStatus, etc.
├── repository/         # Spring Data JPA interfaces
├── service/            # Interfaces (WHAT)
│   └── impl/           # Implementations (HOW)
├── controller/         # REST endpoints
├── security/           # JwtTokenProvider, JwtAuthenticationFilter
├── mapper/             # Entity ↔ DTO (MapStruct)
├── kafka/              # Producers + @KafkaListener consumers
├── cache/              # RedisCacheService
└── exception/          # GlobalExceptionHandler (@ControllerAdvice)
```

---

## Build Status

**Phase 0 — Design** ✅
- [x] ERD designed on dbdiagram.io
- [x] System architecture documented
- [x] ADRs written for major decisions

**Phase 1 — Core MVP: Auth + Meal Tracking** 🚧
- [ ] User entity + JWT auth (register / login / refresh)
- [ ] FoodItem entity + repository + search endpoint
- [ ] Meal + MealEntry entities
- [ ] Daily macro calculation endpoint
- [ ] Deploy to Railway.app (live URL)

**Phase 2 — Workout Tracker** ⬜
- [ ] WorkoutPlan + Exercise entities
- [ ] WorkoutSession + ExerciseSet logging
- [ ] Weekly volume by muscle group endpoint
- [ ] WeeklySchedule entity

**Phase 3 — Redis + Kafka** ⬜
- [ ] Redis: food item cache (benchmark before/after)
- [ ] Redis: daily macro cache with invalidation
- [ ] Redis: rate limiting (5 login attempts/min per IP)
- [ ] Kafka: `meal.logged` topic + notification consumer
- [ ] Barcode scanner: Redis → DB → Open Food Facts chain

**Phase 4 — Stripe Payments** ⬜
- [ ] PaymentIntent creation endpoint
- [ ] Idempotent webhook handler
- [ ] UNIQUE constraint deduplication guard

**Phase 5 — Docker + Kubernetes + AWS** ⬜
- [ ] Multi-stage Dockerfile (~150MB image)
- [ ] Docker Compose: app + postgres + redis + kafka
- [ ] EKS deployment with HPA (min 2, max 10 pods)
- [ ] GitHub Actions CI/CD pipeline
- [ ] CloudWatch alarms: error rate > 1%, P99 > 500ms

---

## Running Locally

**Prerequisites:** Java 21, Maven, Docker (for postgres + redis + kafka)

```bash
git clone https://github.com/Su1kii/fittrack-pro.git
cd fittrack-pro

# Start dependencies
docker-compose up -d postgres redis kafka

# Configure environment
cp src/main/resources/application.example.yml src/main/resources/application-local.yml
# Edit application-local.yml with your DB credentials

# Run
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**Endpoints:** `http://localhost:8080`  
**Swagger UI:** `http://localhost:8080/swagger-ui.html`  
**Health check:** `http://localhost:8080/actuator/health`

---

## API Overview

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/auth/register` | Register new user |
| POST | `/api/v1/auth/login` | Login, receive access + refresh tokens |
| POST | `/api/v1/auth/refresh` | Exchange refresh token for new access token |
| GET | `/api/v1/meals/daily-macros?date=` | Daily calorie + macro summary |
| POST | `/api/v1/meals` | Log a meal with food entries |
| GET | `/api/v1/foods/search?q=` | Search food database |
| GET | `/api/v1/foods/barcode/{code}` | Look up food by barcode |
| GET | `/api/v1/workouts/volume/weekly` | Weekly volume by muscle group |
| POST | `/api/v1/donations` | Create Stripe PaymentIntent |
| POST | `/api/webhooks/stripe` | Stripe webhook handler (raw body, sig verified) |

Full API docs available at `/swagger-ui.html` when running locally.

---

## Contributing

Issues and PRs are welcome. If there's a feature you wish a fitness app had without a paywall, open an issue.

For significant changes, open an issue first to discuss the approach.

---

## License

MIT — free forever, for everyone.
