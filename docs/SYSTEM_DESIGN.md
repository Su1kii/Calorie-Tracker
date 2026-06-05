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
- [ADR-001: Java + Spring Boot over Node.js / Python](./adr/ADR-001-java-over-nodejs.md)
- [ADR-002: PostgreSQL over MySQL / MongoDB](./adr/ADR-002-postgresql-over-mysql.md)
- [ADR-003: UUID primary keys over auto-increment INTEGER](./adr/ADR-003-uuid-over-autoincrement.md)
- [ADR-004: JWT stateless auth over session-based auth](./adr/ADR-004-jwt-stateless-auth.md)
- [ADR-005: Redis cache-aside over no caching / in-process cache](./adr/ADR-005-redis-cache-aside.md)
- [ADR-006: Apache Kafka over RabbitMQ / AWS SQS](./adr/ADR-006-kafka-over-rabbitmq.md)
- [ADR-007: Layered monolith over microservices](./adr/ADR-007-monolith-over-microservices.md)

## Tech stack
| Layer | Technology | Why |
|-------|-----------|-----|
| API | Java 21 + Spring Boot 3 | Type safety, Spring Security, JVM perf |
| Database | PostgreSQL 15 (RDS) | WINDOW functions, partitioning, UUID native |
| Cache | Redis (ElastiCache) | 1ms food lookups, rate limiting, sessions |
| Queue | Kafka (MSK) | Ordered events, replay, consumer groups |
| Deploy | AWS EKS (Kubernetes) | HPA, rolling deploys, self-healing |
| CI/CD | GitHub Actions | Push to main → test → build → deploy |
