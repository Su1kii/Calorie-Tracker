# ADR-004: JWT Stateless Authentication over Session-Based Auth

## Status
Accepted

## Date
2026-05-20

## Context
FitTrack Pro's API runs on Kubernetes with Horizontal Pod Autoscaling — between 2 and 100 pods depending on traffic. Any pod can receive any request. The auth system must work correctly regardless of which pod handles a given request.

The two main approaches are:
1. **Session-based auth** — server stores session state, client sends a session ID cookie
2. **JWT (JSON Web Token)** — server issues a signed token, client sends it on every request, server validates the signature without any state lookup

The choice also determines whether CSRF protection is needed, how logout works, and how mobile clients are supported.

## Decision
Use **JWT** with two token types:
- **Access token** — 15-minute TTL, signed with HMAC-SHA256, stateless validation
- **Refresh token** — 7-day TTL, stored in the `refresh_tokens` PostgreSQL table, revocable

## Consequences

### Positive
- **True horizontal scalability** — `SessionCreationPolicy.STATELESS` in Spring Security means no session is ever created. Pod 1 can issue a token, Pod 47 can validate it without any shared state. Essential for K8s HPA.
- **No session store infrastructure** — session-based auth at scale requires a distributed session store (Redis, Memcached) that all pods share. JWT eliminates this dependency entirely for the hot path.
- **Works identically for web and mobile** — browsers, iOS apps, and Android apps all set the `Authorization: Bearer {token}` header the same way. No cookie handling differences across platforms.
- **CSRF not needed** — CSRF attacks work because browsers automatically attach cookies to cross-origin requests. JWT in the `Authorization` header is never automatically attached by browsers — JavaScript must explicitly set it. A malicious site cannot trigger authenticated requests. `csrf().disable()` is safe and correct here.
- **Refresh tokens in DB = revocation** — storing refresh tokens in PostgreSQL means logout (set `is_revoked = true`) and security incidents (revoke all tokens for a user) are handled instantly. The 15-minute access token window is the only irrevocable gap.

### Negative
- **Access tokens cannot be instantly revoked** — if a token is stolen, the attacker has up to 15 minutes of access. This window is the fundamental trade-off of stateless auth. Mitigated by: short TTL (15 min), refresh token revocation cutting off future token renewal, and HTTPS preventing token interception in transit.
- **Token size** — a JWT is ~200-300 bytes. A session cookie is ~50 bytes. At 150M requests/day, this is ~30GB of additional request payload per day. Negligible in practice.
- **Refresh token complexity** — clients must implement token refresh logic: detect 401, call `/auth/refresh`, retry original request. This is standard behavior but adds client complexity vs simple session cookies.

## Alternatives Considered
- **Session-based auth** — rejected. Requires a distributed session store that all K8s pods share. This shared state creates a single point of failure, adds infrastructure cost, and means a session store outage takes down auth for the entire system. Incompatible with true stateless horizontal scaling.
- **OAuth2 / OpenID Connect** — considered for future. Correct choice if FitTrack ever supports third-party login (Google, Apple) or if the system splits into microservices that need delegated auth. Overkill for the current single-service architecture — adds an authorization server, token introspection endpoints, and significant configuration complexity.
- **Opaque tokens (random string in DB)** — considered. Every request would require a DB lookup to validate the token. At 5,200 peak RPS, this is 5,200 DB queries/second just for auth validation. Rejected for performance reasons. JWT's stateless validation is O(1) cryptographic signature verification — no DB, no network, no latency.
