# Authentication & Security

This document explains how authentication works in CalorieTracker, the design decisions behind it, and how to use the auth endpoints. Written for contributors, developers integrating the API, and anyone who wants to understand the security architecture.

---

## Overview

CalorieTracker uses **JWT (JSON Web Token)** authentication with **Spring Security**. There are no sessions — every request is stateless. The client receives a token on login or registration and sends it with every subsequent request.

```
Client → POST /auth/register → { token }
Client → POST /auth/login    → { token }
Client → GET  /api/...       → Authorization: Bearer <token>
```

---

## How JWT Works

A JWT is a self-contained string with three Base64-encoded parts separated by dots:

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGdtYWlsLmNvbSJ9.signature
       HEADER                     PAYLOAD                  SIGNATURE
```

**Header** — the algorithm used to sign the token (`HS256`).

**Payload** — the data stored inside the token (called "claims"). In this project:
```json
{
  "sub": "user@gmail.com",
  "iat": 1234567890,
  "exp": 1234654290
}
```

**Signature** — a cryptographic hash of the header + payload using a secret key only the server knows. If anyone tampers with the payload, the signature becomes invalid and the server rejects the token.

The server never stores tokens. It just validates the signature on every request. This makes the system horizontally scalable — any server instance can validate any token.

---

## Security Architecture

### Components

```
security/
  SecurityConfig.java          — filter chain rules, which endpoints are public
  JwtService.java              — generates and validates JWT tokens
  JwtAuthFilter.java           — intercepts every request, reads the token
  UserDetailsServiceImpl.java  — loads users from the database for Spring Security
```

### Request Flow

```
Incoming Request
      ↓
JwtAuthFilter (runs on every request)
      ↓
Is there an Authorization: Bearer <token> header?
      ├── No  → pass through (SecurityConfig decides if endpoint needs auth)
      └── Yes → extract token
                      ↓
              Is the token valid and not expired?
                      ├── No  → 401 Unauthorized
                      └── Yes → load user from DB
                                      ↓
                              set user in SecurityContextHolder
                                      ↓
                              request reaches Controller ✅
```

### SecurityConfig Rules

```java
.requestMatchers("/auth/**").permitAll()   // register and login — no token needed
.anyRequest().authenticated()              // everything else — token required
```

---

## Password Security

Passwords are **never stored in plain text**. The flow is:

```
Registration:  plaintext password → BCrypt hash → stored in DB
Login:         plaintext password → BCrypt.matches(input, storedHash) → true/false
```

BCrypt is a one-way hashing algorithm — there is no way to reverse a BCrypt hash back to the original password. Even if the database were compromised, passwords would not be exposed.

---

## API Reference

### Register

```
POST /auth/register
Content-Type: application/json
```

Request body:
```json
{
  "name": "Steven",
  "email": "steven@example.com",
  "password": "yourpassword"
}
```

Response `200 OK`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

---

### Login

```
POST /auth/login
Content-Type: application/json
```

Request body:
```json
{
  "email": "steven@example.com",
  "password": "yourpassword"
}
```

Response `200 OK`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Error responses:
- `401 Unauthorized` — wrong email or password
- `403 Forbidden` — valid token but insufficient permissions

---

### Using the Token

Include the token in the `Authorization` header on all protected requests:

```
GET /api/tracking
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

The `Bearer ` prefix is required. Requests without it are treated as unauthenticated.

---

## Token Configuration

Tokens are configured in `application.properties`:

```properties
jwt.secret=your-base64-encoded-secret-key
jwt.expiration=86400000
```

`jwt.expiration` is in milliseconds. `86400000` = 24 hours.

The secret key must be Base64-encoded and at least 32 characters when decoded. Never commit a real secret key to version control — use environment variables in production:

```properties
jwt.secret=${JWT_SECRET}
```

---

## Role-Based Access Control (Planned)

The foundation for RBAC is already in place. Future implementation will add a `role` field to the `User` entity and restrict endpoints by role:

```java
// Planned
.requestMatchers("/admin/**").hasRole("ADMIN")
.requestMatchers("/api/**").hasRole("USER")
```

Roles under consideration: `USER`, `ADMIN`.

---

## Running Locally

See the main [README](../README.md) for setup instructions. Once running, test auth with curl:

```bash
# Register
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Steven","email":"steven@example.com","password":"password123"}'

# Login
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"steven@example.com","password":"password123"}'

# Use token on protected endpoint
curl http://localhost:8080/api/tracking \
  -H "Authorization: Bearer <your_token_here>"
```

---

## Security Notes

- Tokens expire after 24 hours by default — users must re-login after expiry
- Passwords are BCrypt hashed with the default strength factor (10 rounds)
- CSRF protection is disabled — this is intentional for a stateless REST API
- Sessions are stateless (`SessionCreationPolicy.STATELESS`) — no server-side session storage
- Token refresh is not yet implemented — planned for a future release
