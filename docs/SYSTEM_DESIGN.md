# FitTrack Pro — System Design

## Architecture
![Architecture](architecture.png)

## Database Schema (ERD)
![ERD](erd.png)

## Scale targets
| Metric | Value |
|--------|-------|
| Total users | 20,000,000 |
| Daily active users | 3,000,000 (15%) |
| Peak RPS | ~5,200 |
| Food lookups/day | 27,000,000 |
| Cache hit target | 95%+ |

## Key decisions
- [ADR-001: Java + Spring Boot over Node.js](./adr/ADR-001-java-over-nodejs.md)
- [ADR-002: PostgreSQL over MySQL](./adr/ADR-002-postgresql-over-mysql.md)

## Tech stack
| Layer | Technology | Why |
|-------|-----------|-----|
| API | Java 21 + Spring Boot 3 | Type safety, Spring Security, JVM perf |
| Database | PostgreSQL 15 (RDS) | WINDOW functions, partitioning, UUID native |
| Cache | Redis (ElastiCache) | 1ms food lookups, rate limiting, sessions |
| Queue | Kafka (MSK) | Ordered events, replay, consumer groups |
| Deploy | AWS EKS (Kubernetes) | HPA, rolling deploys, self-healing |
| CI/CD | GitHub Actions | Push to main → test → build → deploy |