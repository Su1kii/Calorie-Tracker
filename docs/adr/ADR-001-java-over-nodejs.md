# ADR-001: Java 21 + Spring Boot over Node.js / Python

## Status
Accepted

## Date
2026-05-20

## Context
Building a production REST API targeting 20 million users at ~5,200 peak RPS. The system needs a mature security ecosystem for JWT auth, BCrypt password hashing, and role-based access control. It also needs a production-grade ORM for a 14-table relational schema with complex JOIN queries and `@Transactional` boundaries across multiple repositories.

The core question: Java + Spring Boot vs Node.js/Express vs Python/FastAPI.

This decision was made early in the project before writing a single entity class, because the framework choice affects every subsequent pattern — how security is configured, how transactions are managed, how the service layer is structured, and how the app deploys on Kubernetes.

## Decision
Use **Java 21 + Spring Boot 3.x** as the primary language and framework.

## Consequences

### Positive
- **Compile-time type safety** catches bugs before they reach production — a missing field in a DTO is a compiler error, not a 3AM NullPointerException
- **Spring Security** is battle-tested for JWT filter chains, BCrypt, RBAC, CORS, and CSRF — not a DIY implementation that introduces vulnerabilities
- **Spring Data JPA + Hibernate** generates optimized SQL from method names, handles dirty checking, and enforces `@Transactional` boundaries without boilerplate
- **JVM performance** under sustained high RPS is excellent — JIT compilation means performance improves over time, not degrades
- **Spring Actuator** provides `/actuator/health/liveness` and `/actuator/health/readiness` endpoints that Kubernetes uses for probes out of the box
- **Strong ecosystem** — MapStruct for compile-time DTO mapping, Lombok for boilerplate elimination, Flyway for versioned migrations, Testcontainers for real DB tests
- **`@Transactional` is explicit and enforceable** — the boundary between transactional and non-transactional code is visible in the source, not implicit

### Negative
- More boilerplate than Node.js or Python — a simple CRUD endpoint requires Entity, Repository, Service interface, ServiceImpl, Controller, Request DTO, Response DTO
- Slower startup time due to JVM warmup — mitigated by Kubernetes readiness probes, which prevent traffic until the JVM is warm
- Higher baseline memory per pod (~512MB vs ~128MB for Node) — accounted for in EKS node sizing
- Steeper learning curve for engineers coming from scripting languages

## Alternatives Considered
- **Node.js / Express** — rejected. Dynamic typing increases the surface area for runtime bugs at scale. No equivalent to Spring Security's production-maturity. `async/await` error handling is less explicit than `@Transactional` rollback semantics.
- **Python / FastAPI** — rejected. Weaker ORM ecosystem compared to JPA/Hibernate. Lower sustained throughput ceiling on the JVM. FastAPI's async model is excellent but GIL limits true parallelism for CPU-bound operations like BCrypt hashing.
- **Kotlin / Spring Boot** — considered seriously. Kotlin's null safety and data classes would eliminate Lombok. Rejected only because Java 21 records and sealed classes close the gap, and Java has wider interview recognition for demonstrating backend engineering knowledge.
