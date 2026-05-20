# ADR-007: Layered Monolith over Microservices

## Status
Accepted

## Date
2026-05-20

## Context
FitTrack Pro has four clearly identifiable domain boundaries: Auth, Nutrition, Workouts, and Payments. Each maps to a distinct set of entities, business logic, and external integrations. At face value, this looks like a microservices decomposition waiting to happen.

The question is: should these domains be deployed as separate services from day one, or as a single deployable unit with clean internal boundaries?

This decision was made during Phase 0 before writing any code. It directly affects deployment complexity, debugging workflows, transaction boundaries, and how quickly features can be shipped and iterated on.

## Decision
Build a **layered monolith** with clean domain-oriented package structure:

```
com.fittrack/
  ├── auth/         (User, RefreshToken, UserGoal)
  ├── nutrition/    (FoodItem, Meal, MealEntry)
  ├── workout/      (Exercise, WorkoutPlan, WorkoutSession, ExerciseSet)
  └── payment/      (Payment, Notification)
```

Each domain has its own Controller, Service interface, ServiceImpl, and Repository layers. Cross-domain calls go through service interfaces — never directly between repositories. The domain boundaries are designed as microservice seams, so extraction is possible without a full rewrite if the need is proven.

## Consequences

### Positive
- **Single deployment unit** — one Docker image, one `kubectl apply`, one rolling deploy. Zero inter-service networking, zero service discovery configuration, zero distributed tracing setup just to debug a simple request.
- **Single database transaction boundary** — `@Transactional` on a service method covers the entire operation atomically. In microservices, a meal log touching the nutrition service and the notification service would require a saga pattern or two-phase commit to maintain consistency. In the monolith, it's one annotation.
- **Faster iteration** — changing the interface between nutrition and notification logic requires editing two files, not versioning an API contract between two services, negotiating breaking changes, and coordinating deployments.
- **One log stream** — debugging a failed request means searching one CloudWatch log group, not correlating traces across five services with distributed tracing. X-Ray is added for performance profiling, not as a necessity for basic debugging.
- **Simpler testing** — integration tests spin up one Spring context. No service mocks, no test doubles for HTTP clients, no contract testing.
- **Domain boundaries are still clean** — the service interface pattern (Controller → `MealService` interface → `MealServiceImpl`) means the nutrition domain never calls `WorkoutRepository` directly. The seams exist. Extraction later is a deployment decision, not a refactor.

### Negative
- **Cannot scale domains independently** — at peak load, the meal logging endpoints see 10× the traffic of the payment endpoints. In the monolith, all pods run all code. Scaling for meal traffic also scales the payment code. Wasteful but manageable — HPA scales the entire app, and the extra payment code per pod is not the bottleneck.
- **Full redeploy for any change** — fixing a bug in the notification logic requires redeploying the entire application, not just the notification service. Rolling deployments on K8s make this zero-downtime, but it's still a full image build and rollout.
- **Technology constraint** — the entire monolith is Java. A future analytics component that would benefit from Python's data science ecosystem would require a separate service anyway. This constraint is acceptable given the current scope.
- **Organizational scaling** — microservices exist primarily to let multiple teams work independently without stepping on each other. FitTrack Pro is currently a solo project. The organizational benefit of microservices does not apply.

## Alternatives Considered
- **Microservices from day one** — rejected. Martin Fowler's monolith-first principle: "don't start a new project with microservices, even if you're sure your application will be big enough to make it worthwhile." The overhead of distributed systems — network calls between services, distributed transactions, service discovery, inter-service auth, polyglot persistence, multiple deployment pipelines — is only justified when the scale and team size make the monolith genuinely painful. We are not there. The domain boundaries in the monolith are designed as extraction seams precisely so this decision can be revisited when the need is proven, not anticipated.
- **Modular monolith (Java modules)** — considered. Using Java 9+ module system to enforce hard boundaries between domains at compile time. Rejected as over-engineering for the current stage — package-level conventions enforced by code review are sufficient, and adding a module system adds build complexity without meaningful benefit at this team size.
- **Backend-for-Frontend (BFF) pattern** — considered for the React client. A thin BFF layer could aggregate responses from multiple services. Rejected because there is currently one client (React web) and the API is not complex enough to require response aggregation. Can be added later if a mobile client needs different response shapes.
