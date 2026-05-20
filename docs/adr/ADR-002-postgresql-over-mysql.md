# ADR-002: PostgreSQL 15 over MySQL / MongoDB

## Status
Accepted

## Date
2026-05-20

## Context
FitTrack Pro needs a relational database that can handle several non-trivial requirements simultaneously:

1. **Analytics queries** — weekly training volume aggregated by muscle group requires WINDOW functions or complex subqueries
2. **UUID primary keys** — all 14 tables use UUID PKs for security and distributed-system safety; this needs native support
3. **Full-text search** — food item search by name needs to match partial strings ("chick" → "chicken breast", "chicken nuggets")
4. **Table partitioning** — the `meals` table will reach ~3.28 billion rows per year at 20M users, requiring RANGE partitioning by date; `exercise_sets` requires HASH partitioning by `user_id`
5. **3NF relational schema** — the data is deeply relational (users → meals → meal_entries → food_items), ruling out document databases

The database choice is the hardest decision to reverse in the entire stack. Getting this wrong means a migration months into the project.

## Decision
Use **PostgreSQL 15** hosted on AWS RDS with Multi-AZ and 3 read replicas.

## Consequences

### Positive
- **`gen_random_uuid()`** built in — UUID generation without an extension or application-layer workaround
- **WINDOW functions** (`OVER PARTITION BY`) enable weekly volume analytics in a single query without subqueries or multiple round trips
- **`pg_trgm` extension** — trigram-based GIN index on `food_items.name` enables fuzzy full-text search. `CREATE INDEX idx_food_name_trgm ON food_items USING gin(name gin_trgm_ops)` — done
- **Native RANGE and HASH partitioning** — `PARTITION BY RANGE (logged_at)` on meals, `PARTITION BY HASH (user_id)` with 64 partitions on exercise_sets. Partition pruning is automatic on range queries
- **JSONB** — available if schema evolution requires flexible fields in future (workout plan templates, custom exercise parameters)
- **Better SQL standard compliance** — fewer proprietary quirks than MySQL
- **`ON CONFLICT DO NOTHING` / `DO UPDATE`** — clean upsert syntax used in idempotency patterns

### Negative
- MySQL has wider legacy adoption in enterprise environments — some orgs standardize on MySQL and PostgreSQL knowledge doesn't transfer 1:1
- PostgreSQL default memory configuration needs tuning for production (`shared_buffers`, `work_mem`, `effective_cache_size`) — RDS managed configs handle this but it's not zero-config
- Slightly more complex replication setup than MySQL, though RDS abstracts this away

## Alternatives Considered
- **MySQL 8** — rejected. UUID support requires the `UUID_TO_BIN()` / `BIN_TO_UUID()` workaround or a BINARY(16) column. WINDOW function support exists but is less mature. No `pg_trgm` equivalent for food search. Inferior table partitioning syntax.
- **MongoDB** — rejected. FitTrack's data is deeply relational — users own meals which reference food items, workouts reference exercises, payments reference users. Modeling this in documents requires either embedding (denormalization that goes stale) or application-level joins (N+1 problem reimplemented manually). The relational model is the right fit.
- **PlanetScale (MySQL-compatible)** — rejected. Branching workflow is interesting for teams but adds operational complexity for a solo project. No foreign key support by default is a dealbreaker for referential integrity.
