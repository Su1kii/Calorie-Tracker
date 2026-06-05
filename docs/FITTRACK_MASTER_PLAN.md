# FitTrack Pro — Master Engineering Plan
### Built for Steven Echeverria Nava · 20M User Scale · Senior-Level Portfolio Project

> **Rule #1:** This document is your source of truth. Before writing a single line of Java, you understand WHY every decision was made, what you gave up, and how to defend it in any interview. Juniors implement. Seniors justify.

---

## Table of Contents
1. [Project Philosophy & Learning Goals](#1-project-philosophy--learning-goals)
2. [System Scale Requirements](#2-system-scale-requirements)
3. [Technology Stack — Every Decision Justified](#3-technology-stack--every-decision-justified)
4. [Architecture Overview](#4-architecture-overview)
5. [Database Design Philosophy](#5-database-design-philosophy)
6. [Build Phases (12-Week Roadmap)](#6-build-phases-12-week-roadmap)
7. [Architecture Decision Records (ADRs)](#7-architecture-decision-records-adrs)
8. [System Design Trade-offs Table](#8-system-design-trade-offs-table)
9. [IntelliJ Claude Plan-Mode Prompt](#9-intellij-claude-plan-mode-prompt)
10. [Interview Story Map](#10-interview-story-map)

---

## 1. Project Philosophy & Learning Goals

### What You're Building
FitTrack Pro is a **production-grade fitness tracking API** built to support **20 million users**. It covers meal logging with macro calculation, workout tracking with volume analytics, Redis caching, Kafka event streaming, Stripe payments, JWT security, AWS deployment on Kubernetes, and distributed systems patterns.

The goal is not to finish fast. The goal is to **think like a senior engineer the entire time** — making decisions with intention, documenting trade-offs, benchmarking before and after, and building something you can talk about for 45 minutes in any interview.

### Learning Goals by Phase
| Phase | What You Learn |
|-------|---------------|
| 0 — Planning | System design thinking, ERD modeling, ADR writing |
| 1 — Core MVP | Spring Boot internals, JWT/Security, JPA patterns, REST design |
| 2 — Workouts | Complex domain modeling, analytics queries, stream API |
| 3 — Redis + Kafka | Distributed caching, event-driven architecture, async patterns |
| 4 — Payments | Idempotency, webhook security, financial data handling |
| 5 — Docker + K8s | Containerization, orchestration, cloud deployment |
| 6 — AWS + Observability | Production monitoring, alerting, CI/CD pipelines |

### The Rule: Claude is Your Thinking Partner, Not Your Code Generator
- Use Claude to **understand patterns** before implementing them
- Ask Claude to **explain why** before asking how
- Ask Claude to **review your code** after you write it
- Ask Claude to **challenge your decisions** — "what's wrong with this approach?"
- Never paste AI code you can't explain line-by-line

---

## 2. System Scale Requirements

### Traffic Math for 20M Users
You need to be able to walk through this math in any system design interview without notes.

| Metric | Calculation | Result |
|--------|-------------|--------|
| Total Users | Given | 20,000,000 |
| Daily Active Users (DAU) | 20M × 15% typical DAU rate | 3,000,000/day |
| Meals Logged/Day | 3M DAU × 3 meals avg | 9,000,000/day |
| Workout Sessions/Day | 3M DAU × 40% workout rate | 1,200,000/day |
| API Requests/Day | 3M DAU × 50 avg requests | 150,000,000/day |
| Average RPS | 150M ÷ 86,400 seconds | ~1,736 RPS |
| Peak RPS (3× multiplier) | Average × 3 | ~5,200 RPS |
| Food Item Reads/Day | 9M meals × 3 items avg | 27,000,000/day |
| Cache Hit Target | 95%+ food items cached | ~1.35M DB reads saved |

### Why This Math Matters
At 5,200 peak RPS, a single PostgreSQL instance with no caching would collapse. This forces every architectural decision:
- Redis exists because 27M food lookups/day cannot hit Postgres
- Read replicas exist because reads outnumber writes 10:1
- Kafka exists because meal logging cannot block the HTTP response waiting for notification delivery
- HPA on K8s exists because load spikes during morning/evening meal times

### Storage Estimates (Show This in Interviews)
| Table | Rows at 20M Users | Avg Row Size | Total |
|-------|-------------------|-------------|-------|
| users | 20,000,000 | 500 bytes | 10 GB |
| meals | 20M × 3 meals × 365 days | 200 bytes | ~438 GB/year |
| meal_entries | meals × 3 items avg | 100 bytes | ~657 GB/year |
| exercise_sets | 1.2M sessions/day × 365 | 150 bytes | ~98 GB/year |
| food_items | ~2,000,000 unique items | 400 bytes | 800 MB |

**Key insight:** food_items is tiny (800MB) and shared across all users — this is why Redis can cache the entire table in RAM.

---

## 3. Technology Stack — Every Decision Justified

### Core Backend: Java 21 + Spring Boot 3.x

**Why Java over Node.js/Python:**
- **Strong typing** catches bugs at compile time, not at 3AM in production
- **Spring Security** is battle-tested for enterprise auth — JWT, BCrypt, RBAC, CSRF — all production-grade
- **JVM performance** is excellent for CPU-bound tasks at scale; JIT compilation means sustained high-RPS performance
- **Spring Data JPA** generates optimized SQL from method names, preventing boilerplate
- **Spring Actuator** provides `/health` endpoints that Kubernetes probes out of the box

**What you gave up:** More boilerplate than Node/Python, slower startup time (JVM warmup), higher memory baseline

**Verdict:** The type safety and Spring ecosystem are worth the verbosity for a production API you want to talk about in interviews

---

### Database: PostgreSQL 15

**Why PostgreSQL over MySQL:**
- `gen_random_uuid()` — UUID primary keys without an extension
- **WINDOW functions** for analytics (weekly volume by muscle group in a single query)
- **JSONB** for flexible schema evolution in the future
- **Table partitioning** — native support for RANGE (by date) and HASH (by user_id) partitioning
- `pg_trgm` extension for trigram full-text search on food item names
- **Better SQL standard compliance**

**What you gave up:** MySQL has wider institutional knowledge in legacy orgs; marginally higher default memory footprint

**Verdict:** PostgreSQL wins on every dimension that matters for analytics-heavy applications

---

### Caching: Redis (AWS ElastiCache)

**Why Redis:**
- Food item lookups: ~1ms Redis vs ~20ms PostgreSQL — that's a 20× speedup
- Daily macro totals: complex JOIN query vs instant cache hit
- Rate limiting: `INCR` + `EXPIRE` — max login attempts without touching the database
- Redis Cluster supports 20M user session data at scale

**Cache Key Strategy:**
```
food:{foodItemId}           → Food item data (TTL: 24 hours, rarely changes)
daily:{userId}:{date}       → Daily macro totals (TTL: 25 hours, invalidate on new meal)
session:{userId}            → JWT refresh token validation (TTL: 7 days)
rate:{ip}:login             → Login attempt counter (TTL: 60 seconds)
```

**Cache-Aside Pattern** (know this pattern cold):
1. Check Redis for key
2. Cache HIT → return immediately, zero DB load
3. Cache MISS → query PostgreSQL, store in Redis with TTL, return result
4. On data change → delete cache key (invalidation), next request repopulates

**What you gave up:** Added infrastructure complexity, cache invalidation bugs are subtle, extra cost in production

**Verdict:** The latency benchmark story alone justifies Redis. Benchmark before/after. Tell that story in every interview.

---

### Message Queue: Apache Kafka (AWS MSK)

**Why Kafka over RabbitMQ/SQS:**
- **Message ordering** — per-partition guarantee, order by `user_id` key means a user's events always process in sequence
- **Replay capability** — can reprocess historical events for analytics; RabbitMQ deletes on consumption
- **Consumer groups** — notification workers and analytics workers consume the same event independently
- **Exactly-once semantics** — critical for payment events (Kafka transactions)
- **Scale** — MSK handles millions of events/second; SQS works but lacks ordering guarantees

**Topics in FitTrack:**
```
meal.logged          → Notification worker checks goal progress, sends alert
workout.completed    → Analytics worker updates weekly volume cache
payment.succeeded    → Receipt worker sends email via AWS SES
```

**What you gave up:** SQS is $50/month vs MSK ~$200/month; Kafka has complex local dev setup; eventual consistency

**Verdict:** Kafka's replay + ordering + consumer groups justify the cost for a system with analytics requirements

---

### Security: JWT + BCrypt

**JWT Strategy:**
- **Access token:** 15-minute TTL — self-contained, stateless, no DB lookup per request
- **Refresh token:** 7-day TTL — stored in PostgreSQL, revocable on logout/security incident
- **Why stateless:** Any pod handles any request — essential for horizontal scaling on K8s

**BCrypt Cost Factor: 12**
- Cost 12 = ~4096 rounds = ~300ms per hash attempt
- A brute-force attacker making 1M guesses/second on a cracked hash DB? At 300ms/attempt they can only try ~3/second
- Login latency of 300ms is acceptable; security margin is not negotiable
- OWASP 2024 recommends cost 10-12; cost 12 is the senior engineer choice

**Why CSRF is disabled:**
- CSRF attacks work because browsers automatically attach cookies to requests
- JWT in the `Authorization` header is NOT automatically sent by browsers — JavaScript code must explicitly set it
- An attacker's malicious site cannot access your `localStorage`
- CSRF protection is unnecessary (and adds complexity) when using header-based JWT auth

---

### Containerization: Docker + Kubernetes (AWS EKS)

**Multi-Stage Dockerfile:**
- Stage 1 (build): Full JDK + Maven = ~600MB image
- Stage 2 (runtime): Minimal JRE + JAR only = ~150MB image
- The 4× size reduction means faster pod startup, faster CI/CD, lower attack surface

**Kubernetes Resources You'll Learn:**
- `Deployment` — manages pod replicas, rolling updates
- `Service` — internal DNS-based service discovery
- `Ingress` — routes external traffic, SSL termination
- `ConfigMap` — non-sensitive env vars (spring profile, log level)
- `Secret` — sensitive data (DB password, JWT secret, Stripe key)
- `HPA` — Horizontal Pod Autoscaler: scale pods based on CPU/memory

**HPA Configuration:**
```yaml
minReplicas: 2      # Always 2 pods for high availability
maxReplicas: 100    # Can scale to 100 pods at peak load
targetCPUUtilization: 70%
```

**Liveness vs Readiness Probes (know this cold):**
- **Liveness:** Is the container alive? Fails → K8s restarts it. Use for deadlock detection.
- **Readiness:** Is the container ready for traffic? Fails → K8s removes from load balancer (no restart). Use for JVM warmup.

---

### Payments: Stripe

**Idempotency Strategy:**
- Stripe may deliver the same webhook multiple times (network retries)
- `UNIQUE` constraint on `stripe_payment_intent_id` — duplicate INSERT throws `DataIntegrityViolationException`
- Catch it, return `200 OK` — the database did the deduplication
- This is the correct enterprise pattern; never check-then-insert (race condition)

**Webhook Security:**
- Receive webhook as raw `String` (not parsed JSON)
- Stripe signature verification uses HMAC-SHA256 over the **raw request body bytes**
- Jackson reformatting (reordering keys, changing whitespace) before verification = HMAC fails
- Always: receive raw → verify signature → then parse

---

## 4. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     CLIENT LAYER                                 │
│        React Web App · iOS (future) · Android (future)         │
└─────────────────────────┬───────────────────────────────────────┘
                          │ HTTPS
┌─────────────────────────▼───────────────────────────────────────┐
│                  EDGE & CDN (AWS CloudFront)                     │
│     Static assets · API response caching · DDoS protection      │
└─────────────────────────┬───────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────┐
│              LOAD BALANCER (AWS ALB)                             │
│         SSL termination · Health checks · Traffic routing        │
└─────────────────────────┬───────────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────────┐
│            COMPUTE LAYER — AWS EKS (Kubernetes)                  │
│                                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐          │
│  │  Auth    │ │  Meal    │ │ Workout  │ │ Payment  │  ...     │
│  │ 3-10 pods│ │ 5-20 pods│ │ 3-15 pods│ │ 2-5 pods │          │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘          │
│                                                                  │
│  ┌────────────────────────────────────────────────┐            │
│  │           Async Workers                         │            │
│  │  Meal Worker · Notification Worker · Analytics  │            │
│  └────────────────────────────────────────────────┘            │
└──────────┬───────────────────┬──────────────────────────────────┘
           │                   │
┌──────────▼──────┐  ┌─────────▼──────────────────────────────────┐
│  Redis Cluster  │  │         Apache Kafka (AWS MSK)              │
│  (ElastiCache)  │  │  meal.logged · workout.completed           │
│  6 nodes        │  │  payment.succeeded · DLQ topics            │
└──────────┬──────┘  └─────────────────────────────────────────────┘
           │
┌──────────▼───────────────────────────────────────────────────────┐
│                    DATABASE LAYER                                  │
│                                                                    │
│  ┌──────────────────┐    ┌──────────────────────────────────────┐ │
│  │ PostgreSQL Primary│    │      Read Replicas (3×)              │ │
│  │ (Writes only)    │    │ Replica 1 · Replica 2 · Replica 3   │ │
│  └──────────────────┘    └──────────────────────────────────────┘ │
│                                                                    │
│  14 tables · 3NF normalized · UUID PKs · Table partitioning       │
└────────────────────────────────────────────────────────────────────┘
           │
┌──────────▼───────────────────────────────────────────────────────┐
│                 EXTERNAL SERVICES                                  │
│    Stripe (payments) · Open Food Facts API · AWS SES (email)     │
└────────────────────────────────────────────────────────────────────┘
           │
┌──────────▼───────────────────────────────────────────────────────┐
│                   OBSERVABILITY                                    │
│    CloudWatch (metrics + logs) · X-Ray (distributed tracing)     │
│    Alarms: error rate >1% · P99 latency >500ms                   │
└────────────────────────────────────────────────────────────────────┘
```

---

## 5. Database Design Philosophy

### The 6 Rules Behind Every Table

**1. UUID Primary Keys — Not Auto-Increment INT**
- Security: sequential IDs expose row counts (`/users/1`, `/users/2` → how many users do you have?)
- Distributed systems: two servers can generate the same INT but never the same UUID
- Client-side generation: mobile apps can create UUIDs before server confirmation (optimistic UI)
- Cost: UUIDs are 16 bytes vs 4 bytes; non-sequential causes B-tree fragmentation — negligible at FitTrack scale

**2. 3NF Normalization**
- 1NF: each column holds one atomic value
- 2NF: every non-key column depends on the WHOLE primary key
- 3NF: no transitive dependencies — meals and meal_entries are separate tables; food nutrition lives in food_items, never duplicated in meal_entries

**3. Money as INTEGER Cents — Never DECIMAL**
- `0.1 + 0.2` in IEEE 754 floating point = `0.30000000000000004` — not 0.30
- `10 + 20` cents = `30` cents — always exact
- $10.00 stored as `1000` cents — trivial to convert for display

**4. Nutrition Stored Per 100g Standard**
- Never store computed amounts (these go stale when food data changes)
- Always compute dynamically: `calories = (calories_per_100g * quantity_g) / 100`
- Changing a food item's nutritional data automatically fixes all historical calculations

**5. Enums as VARCHAR Strings**
- `@Enumerated(EnumType.STRING)` — store `'CHEST'`, not `2`
- Adding/reordering an enum changes ORDINAL values in production — silent data corruption
- VARCHAR is slightly larger but correct: VARCHAR wins every time

**6. Audit Columns Everywhere**
- `created_at` (updatable=false) + `updated_at` on every single table
- Set by Hibernate `@CreationTimestamp` / `@UpdateTimestamp` — never application code
- You can always answer "when did this happen?" — essential for debugging production incidents

### Table Partitioning Strategy (Senior-Level Decision)

**meals table → RANGE partition by `logged_at` (monthly)**
- 3M DAU × 3 meals × 365 days = 3.28 billion rows/year
- Without partitioning: table scan for date-range queries scans ALL rows
- With monthly partitions: PostgreSQL only scans the relevant month's partition
- Partition pruning is automatic when `WHERE logged_at BETWEEN x AND y`

**exercise_sets → HASH partition by `user_id` (64 partitions)**
- Even distribution across partitions regardless of user activity patterns
- Each partition is ~1/64th the size: indexes stay in memory, queries stay fast
- 64 partitions = good balance between parallelism and overhead

---

## 6. Build Phases (12-Week Roadmap)

### Phase 0 — Planning (Week 1)
Before you write a single line of code:
- [ ] Draw ERD on dbdiagram.io — paste all 14 table DBML schemas
- [ ] Draw system architecture on Excalidraw — print it, keep it visible
- [ ] Initialize Spring Boot project at `start.spring.io`
- [ ] Set up PostgreSQL locally (fittrack_db / fittrack_user)
- [ ] Create Git repo — commit every working state
- [ ] Write ADR-001: "Why Java + Spring Boot over Node.js"
- [ ] Write ADR-002: "Why PostgreSQL over MySQL"

**Deliverable:** Architecture diagram + ERD + initialized project on GitHub. Zero working endpoints yet.

### Phase 1 — Core MVP: Auth + Meal Tracking (Weeks 2-3)
- [ ] User entity + Spring Security UserDetails integration
- [ ] BCrypt password hashing, register endpoint
- [ ] JWT access token (15 min) + refresh token (7 days)
- [ ] FoodItem entity + repository + seeded test data
- [ ] Meal + MealEntry entities (computed nutrition pattern)
- [ ] POST /api/v1/meals — log a meal
- [ ] GET /api/v1/meals/daily-macros — daily macro totals query
- [ ] Deploy MVP to Railway.app — get a live URL on your resume

**Benchmark:** Measure response time for daily macro query with no caching.

**Deliverable:** Live URL. User can register, login, log meals, see daily macros.

### Phase 2 — Workout Tracker (Weeks 4-5)
- [ ] Exercise entity + MuscleGroup enum
- [ ] WorkoutPlan entity (named templates: Push Day, Pull Day)
- [ ] WorkoutPlanExercise junction table
- [ ] WorkoutSession entity (one instance of performing a plan)
- [ ] ExerciseSet entity (individual rep/weight logging)
- [ ] WeeklySchedule entity (maps days to plans)
- [ ] Weekly volume analytics endpoint (Collectors.groupingBy by MuscleGroup)
- [ ] JOIN FETCH to solve N+1 on session queries

**Benchmark:** Measure weekly volume query with and without JOIN FETCH. Document the query count difference.

### Phase 3 — Redis + Kafka (Weeks 6-7)
- [ ] Redis dependency + RedisCacheService wrapper class
- [ ] Cache food item lookups (TTL: 24 hours)
- [ ] Cache daily macro totals (TTL: 25 hours, invalidate on new meal)
- [ ] Redis rate limiting on login endpoint (5 attempts/min/IP)
- [ ] Kafka dependency + KafkaConfig (producer/consumer factory)
- [ ] `meal.logged` Kafka topic + MealEventProducer
- [ ] NotificationWorker consumer — checks goal progress, creates Notification record
- [ ] Dead Letter Queue for failed notification processing

**Benchmark:** Re-measure food lookup and daily macro latency. Document the before/after numbers. This is your interview story.

### Phase 4 — Stripe Payments (Weeks 8-9)
- [ ] Stripe dependency + POST /api/v1/donations endpoint
- [ ] PaymentIntent creation with idempotency key (SHA256 of userId:amount:date)
- [ ] Webhook endpoint — raw String body, verify signature FIRST
- [ ] UNIQUE constraint on stripe_payment_intent_id — duplicate protection
- [ ] Payment entity with status state machine: PENDING → SUCCEEDED/FAILED/REFUNDED
- [ ] Payment confirmation triggers Kafka `payment.succeeded` event
- [ ] Worker sends receipt email via AWS SES

### Phase 5 — Docker + Kubernetes + AWS (Weeks 10-11)
- [ ] Multi-stage Dockerfile (Maven build → JRE runtime = ~150MB)
- [ ] docker-compose.yml (app + postgres + redis + kafka + zookeeper)
- [ ] Test: entire stack starts with `docker-compose up`
- [ ] AWS account setup + billing alarm ($10 threshold)
- [ ] RDS PostgreSQL + ElastiCache Redis (both free tier 12 months)
- [ ] ECR repository — push Docker image
- [ ] EKS cluster: `eksctl create cluster --name fittrack --nodes 2`
- [ ] Deployment.yaml + Service.yaml + Ingress.yaml + HPA.yaml
- [ ] GitHub Actions CI/CD: push to main → tests → build → push ECR → kubectl rollout

### Phase 6 — Observability + Portfolio Polish (Week 12)
- [ ] CloudWatch structured JSON logging
- [ ] CloudWatch Alarms: error rate >1%, P99 latency >500ms
- [ ] AWS X-Ray distributed tracing integration
- [ ] k6 load test — simulate 1,000 concurrent users
- [ ] Screenshot HPA scaling pods during load test
- [ ] Write README with architecture diagram, tech decisions, performance benchmarks
- [ ] Record 2-minute demo video

**Final deliverable:** Live AWS URL, load test screenshots, architecture diagram, GitHub with clean commit history.

---

## 7. Architecture Decision Records (ADRs)

Every major decision gets an ADR. Create `/docs/adr/ADR-00X-decision-title.md` for each one. This is what separates junior engineers from senior engineers in interviews.

### ADR Template
```markdown
# ADR-001: [Decision Title]

## Status: Accepted

## Date: YYYY-MM-DD

## Context
[What problem are we solving? What constraints exist?]

## Decision
[What did we decide to do?]

## Consequences
### Positive
- [Benefit 1]
- [Benefit 2]

### Negative
- [Trade-off 1]
- [Trade-off 2]

## Alternatives Considered
- [Alternative 1] — rejected because [reason]
- [Alternative 2] — rejected because [reason]
```

### ADRs to Write (in order)
1. ADR-001: Java + Spring Boot over Node.js/Python
2. ADR-002: PostgreSQL over MySQL/MongoDB
3. ADR-003: UUID primary keys over auto-increment INT
4. ADR-004: JWT stateless auth over session-based auth
5. ADR-005: Redis cache-aside over no caching
6. ADR-006: Kafka over RabbitMQ/SQS
7. ADR-007: Money as INTEGER cents over DECIMAL
8. ADR-008: Monolith with clean layering over microservices
9. ADR-009: Table partitioning strategy (RANGE + HASH)
10. ADR-010: Multi-stage Docker build

---

## 8. System Design Trade-offs Table

The complete senior engineer justification for every decision. Know this table cold.

| Decision | Chose | Alternative | Why We Chose It | What We Gave Up |
|----------|-------|-------------|-----------------|-----------------|
| Primary key type | UUID | Auto-increment INT | Security (no enumeration), distributed-safe, client-side generation | 4× larger, B-tree fragmentation |
| Database | PostgreSQL | MySQL | JSONB, WINDOW functions, table partitioning, UUID native, better SQL compliance | MySQL wider legacy adoption |
| ORM | JPA/Hibernate | Raw JDBC, MyBatis | Generated SQL, entity relationships, migration support | Learning curve, N+1 risk |
| Caching | Redis (ElastiCache) | Memcached, no cache | Data structures beyond strings, persistence, pub/sub, rate limiting | Infrastructure complexity |
| Message queue | Kafka (MSK) | RabbitMQ, SQS | Message replay, ordering, consumer groups, exactly-once | Cost ($200/mo vs $50), complexity |
| Auth | JWT (stateless) | Session-based | Horizontal scaling, no session store, works across services | Can't instantly revoke (15-min window) |
| Password hashing | BCrypt cost 12 | MD5, SHA-256, BCrypt 10 | Adaptive cost, rainbow table resistant, OWASP recommended | 300ms login latency |
| Architecture | Monolith (layered) | Microservices | Simpler deployment, single transaction, easier debugging | Can't scale features independently |
| Language | Java 21 | Node.js, Python | Strong typing, Spring ecosystem, JVM performance | More boilerplate, slower startup |
| Deployment | K8s (EKS) | EC2 direct, Lambda | Auto-scaling, self-healing, rolling deploys, HPA | Complexity, ops overhead |
| CI/CD | GitHub Actions | Jenkins, CircleCI | Native GitHub integration, free tier, YAML-based | Less flexible than Jenkins |
| Monitoring | CloudWatch + X-Ray | Datadog, New Relic | Native AWS integration, free tier, no external vendor | Less rich UI than Datadog |
| Payment | Stripe + webhooks | PayPal, manual | Best developer experience, idempotency support, PCI compliance | Vendor lock-in, 2.9% + $0.30/tx |
| Food data | Open Food Facts + manual | USDA API only | Free, no rate limits at scale, crowdsourced coverage | Data quality variance |

---

## 9. IntelliJ Claude Plan-Mode Prompt

Copy this **verbatim** into Claude in IntelliJ when starting each phase. It sets the context so Claude acts as a senior mentor, not a code generator.

---

### Master Context Prompt (paste this first, once)

```
You are acting as a senior backend engineer mentor for my FitTrack Pro project. Here is the project context:

PROJECT: FitTrack Pro — production-grade fitness tracking API
SCALE TARGET: 20 million users, 5,200 peak RPS
STACK: Java 21, Spring Boot 3.x, PostgreSQL 15, Redis (ElastiCache), Kafka (MSK), AWS EKS, Docker
ARCHITECTURE: Monolith with clean layering (Controller → Service Interface → ServiceImpl → Repository)
DATABASE: 14 tables, 3NF normalized, UUID primary keys, table partitioning for meals (RANGE) and exercise_sets (HASH)
AUTH: JWT (15-min access + 7-day refresh token in DB), BCrypt cost 12
CACHING: Cache-aside pattern, Redis cluster
EVENTS: Kafka topics: meal.logged, workout.completed, payment.succeeded
DEPLOYMENT: AWS EKS, multi-stage Docker, GitHub Actions CI/CD, CloudWatch monitoring

MY LEARNING GOALS:
- I want to understand every pattern before I implement it
- I want to be able to defend every line of code I write in an interview
- I do NOT want you to write entire implementations for me
- I want you to explain WHY before HOW
- I want you to point out senior vs junior patterns
- I want to learn system design thinking, not just syntax

YOUR RULES AS MY MENTOR:
1. If I ask "how do I do X", give me the concept + a small focused example (≤20 lines), not the entire implementation
2. If I write code and paste it for review, give me honest feedback — point out junior patterns and explain the senior alternative
3. If I'm about to make a bad architectural decision, stop me and explain the trade-off
4. Remind me to write ADRs when I make major decisions
5. Ask me "can you explain WHY this works?" after showing me a pattern
6. Help me think through scale implications ("what happens to this query at 20M users?")

I am currently in [PHASE X - DESCRIBE WHAT YOU'RE WORKING ON].
```

---

### Phase-Specific Prompts (append to master context)

**Phase 1 — Starting Auth:**
```
I'm implementing JWT authentication. Before I write any code:
1. Explain to me how Spring Security's filter chain works conceptually
2. Explain why OncePerRequestFilter is the right base class for JWT validation
3. Explain the difference between authentication and authorization in Spring Security
4. What is the ONE most important thing a junior gets wrong with @Transactional that I should watch out for?
Then let me try to implement it and I'll share the code for review.
```

**Phase 1 — Daily Macro Query:**
```
I need to implement getDailyMacros(userId, date). The query needs to:
- Find all meals for this user on this date
- For each meal, find all meal_entries
- For each entry, calculate (calories_per_100g * quantity_g / 100)
- Sum everything into a DailyMacroResponse

Before I write the JPQL:
1. What is the N+1 problem and how would it manifest here?
2. Show me two approaches: the bad (N+1) version and the fixed (JOIN FETCH) version
3. How do I measure the actual number of queries Hibernate fires? (Hint: spring.jpa.show-sql)
```

**Phase 3 — Implementing Redis:**
```
I'm adding Redis caching to the food item lookup. Before I implement:
1. Explain the cache-aside pattern conceptually — what happens on hit vs miss vs invalidation?
2. What is cache stampede (thundering herd) and how would I prevent it?
3. For the key "food:{uuid}", what TTL makes sense and why?
4. What is the Redis data type I should use for this (string/hash/etc) and why?

After I implement it, I'll share the code and I want you to:
- Check if I'm handling deserialization correctly
- Check if my TTL choices make sense
- Check if I'm handling Redis connection failures gracefully
```

**Phase 3 — Implementing Kafka:**
```
I'm adding Kafka for the meal.logged event. Before I write any code:
1. What is the difference between a Kafka topic, partition, and consumer group?
2. In a consumer group with 3 consumers and 3 partitions — what happens if one consumer crashes?
3. What is at-least-once delivery and how do I make my consumer idempotent to handle duplicate events?
4. What is a Dead Letter Queue and when does a message end up there?
5. Should I use @KafkaListener or @RetryableTopic for retry logic — explain the difference.
```

**Phase 4 — Stripe Webhooks:**
```
I'm implementing the Stripe webhook handler. I know idempotency is critical. Before I write code:
1. Why must I receive the webhook as a raw String body and not let Spring parse it automatically?
2. Walk me through exactly how HMAC-SHA256 signature verification works
3. How does the UNIQUE constraint on stripe_payment_intent_id give me idempotency?
4. What exception do I catch when the constraint is violated and what do I return to Stripe?
5. Why do I return 200 OK even when I detect a duplicate — what happens if I return 400?
```

**Phase 5 — Kubernetes:**
```
I'm writing my Kubernetes YAML files. Before I write them:
1. Explain liveness vs readiness probes — what does K8s do when each one fails?
2. What is the difference between a ConfigMap and a Secret — when should I use each?
3. How does HPA decide when to scale up? What metric does it use by default?
4. What is a rolling update strategy and why is it safer than recreate?
5. What does `kubectl rollout undo` do and when would I use it?
```

---

## 10. Interview Story Map

These are the stories you tell in interviews. Prepare a 2-minute version of each.

### Story 1: The Redis Benchmark
> "I implemented cache-aside for food item lookups. Before Redis, each lookup required a database query that averaged 22ms. After Redis with a 24-hour TTL, cache hits return in under 2ms — an 11× improvement. At 27 million food lookups per day, that's 27 million potential database queries eliminated. I measured this with Spring Actuator metrics and documented it in the project README."

### Story 2: The Kafka Decoupling Decision
> "When a user logs a meal, I could have synchronously checked goal progress and sent a notification in the same request. But that adds 50-100ms to every meal log request, and if the notification service is slow or down, the meal logging fails. Instead, I publish a `meal.logged` Kafka event and return immediately. A separate notification worker consumes the event asynchronously. The meal logging request is fast and reliable. The notification is eventually consistent — and I can replay events if the worker was down."

### Story 3: The N+1 Discovery
> "I was loading workout sessions with their exercise sets and noticed 101 database queries for 100 sessions. Each session triggered a separate query to load its sets. I fixed it with `JOIN FETCH` in the JPQL query — reducing 101 queries to 1. I showed this with `spring.jpa.show-sql=true` and Hibernate statistics. This is now a pattern I apply whenever loading parent-child relationships."

### Story 4: The Idempotent Stripe Webhook
> "Stripe guarantees at-least-once delivery — the same event might arrive multiple times. My first instinct was to check if the payment existed before inserting. But that's a race condition — two concurrent webhook deliveries could both check, both find nothing, and both insert. Instead, I rely on a UNIQUE database constraint on `stripe_payment_intent_id`. The second duplicate INSERT throws a `DataIntegrityViolationException` which I catch and respond with `200 OK`. The database is the source of truth for idempotency, not application logic."

### Story 5: The 20M User Scale Calculation
> "At 20 million users with 15% daily active, that's 3 million DAU. Three meals per user gives 9 million food lookups per day. If I route every lookup to PostgreSQL, that's the primary bottleneck — a busy read replica handles ~5,000 queries/second, so 9 million per day is manageable but with no headroom. Redis eliminates 95%+ of those reads entirely. The read replica handles actual cache misses and new user queries. This math drove the decision to invest in Redis before we had any real users."

---

*Last updated: 2026-05-20 | Author: Steven Echeverria Nava*
*Study it. Own it. Ship it.*
