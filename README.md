# FitTrack Pro 🥗

**Free, open source fitness and nutrition tracking — no paywalls, no subscriptions, no locked features.**

I got tired of apps that charge $10/month just to scan a barcode. Every useful feature hidden behind a paywall. So I built my own — completely free, forever, for everyone.

> ⚠️ **In Active Development** — Core auth and meal tracking in progress. [See build status](#build-status)

---

## Why This Exists

Most fitness apps give you just enough for free to get hooked, then lock the actually useful stuff:

- 🔒 Barcode scanner? **Premium.**
- 🔒 Macro goals and progress? **Premium.**
- 🔒 Workout tracking? **Premium.**

FitTrack Pro is built to give you all of it for free. Scan barcodes, track macros, log workouts, set goals — no credit card, no trial, no catch.

---

## Features

**Nutrition Tracking**
- Log meals and track daily calories, protein, carbs, and fat
- Search millions of foods by name
- Scan barcodes — instant nutrition lookup, no paywall
- Set daily macro goals and track progress against them

**Workout Tracking**
- Build custom workout plans with exercises, sets, and reps
- Log workout sessions and track your actual performance
- View weekly volume by muscle group
- Schedule your weekly training split

**Account**
- Free forever — no subscription tiers
- Secure JWT authentication
- Your data stays yours

---

## Getting Started

**Prerequisites:** Java 21, Maven, Docker

```bash
git clone https://github.com/Su1kii/Calorie-Tracker.git
cd Calorie-Tracker

# Start dependencies
docker-compose up -d postgres redis kafka

# Configure environment
cp src/main/resources/application.example.yml src/main/resources/application-local.yml
# Edit application-local.yml with your DB credentials

# Run
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**App:** `http://localhost:8080`
**Swagger UI:** `http://localhost:8080/swagger-ui.html`
**Health:** `http://localhost:8080/actuator/health`

---

## Build Status

**Phase 0 — Design** ✅
- [x] 14-table database schema (ERD on dbdiagram.io)
- [x] System architecture diagram (Eraser.io)
- [x] Architecture Decision Records for all major technology choices
- [x] Scale planning for 20M users

**Phase 1 — Auth + Meal Tracking** 🚧
- [x] Exception hierarchy + RFC 7807 error responses
- [x] User entity + Spring Security integration
- [x] Flyway database migrations
- [x] JWT auth — register / login / refresh / logout
- [ ] Food item search (pg_trgm full-text)
- [ ] Meal logging + daily macro calculation
- [ ] Deploy — live URL

**Phase 2 — Workout Tracker** ⬜
- [ ] Exercise library + muscle group filtering
- [ ] Workout plans and session logging
- [ ] Weekly volume tracking
- [ ] Training schedule

**Phase 3 — Performance** ⬜
- [ ] Redis caching — food lookups, daily macros
- [ ] Barcode scanner with Open Food Facts fallback
- [ ] Login rate limiting

**Phase 4 — Payments** ⬜
- [ ] Optional donation support via Stripe

**Phase 5 — Infrastructure** ⬜
- [ ] Docker + Kubernetes deployment
- [ ] GitHub Actions CI/CD
- [ ] AWS EKS with autoscaling

---

## For Engineers

Built as a production-grade system, not a tutorial project. If you want to see the engineering decisions behind the architecture:

- [`/docs/SYSTEM_DESIGN.md`](./docs/SYSTEM_DESIGN.md) — scale math, trade-offs, cost model
- [`/docs/schema.md`](./docs/schema.md) — full schema with column-level annotations
- [`/docs/adr/`](./docs/adr/) — Architecture Decision Records for every major choice
- [`/docs/BUGS.md`](./docs/BUGS.md) — real bugs encountered and how they were fixed

**Tech stack:** Java 21 · Spring Boot 3 · PostgreSQL 15 · Redis · Apache Kafka · Stripe · Docker · Kubernetes · AWS EKS

---

## Contributing

Issues and PRs welcome. If there's a feature you wish existed without a paywall, open an issue.

---

## License

MIT — free forever, for everyone.