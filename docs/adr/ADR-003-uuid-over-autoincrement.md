# ADR-003: UUID Primary Keys over Auto-Increment INTEGER

## Status
Accepted

## Date
2026-05-20

## Context
Every table in FitTrack Pro needs a primary key strategy. The two main options are auto-increment INTEGER (the default in most tutorials and small projects) and UUID v4 (randomly generated 128-bit identifier).

This decision affects security, API design, database performance, and distributed system behavior. It also applies to all 14 tables — changing it later requires a full schema migration.

The question came up during Phase 0 schema design, before writing any entity classes.

## Decision
Use **UUID v4** (`gen_random_uuid()` in PostgreSQL, `GenerationType.UUID` in Hibernate) as the primary key for all 14 tables.

## Consequences

### Positive
- **No sequential ID exposure** — with auto-increment, `GET /api/v1/users/1`, `GET /api/v1/users/2` reveals how many users exist and lets attackers enumerate records by incrementing the ID. UUIDs expose nothing.
- **Distributed-system safe** — two application pods, two database shards, or two microservices can all generate primary keys simultaneously with zero coordination and zero collision risk. Auto-increment requires a centralized sequence that becomes a bottleneck.
- **Client-side generation** — a mobile app can generate a UUID locally before the server responds, enabling optimistic UI updates. The server just confirms. Not possible with server-side auto-increment.
- **Safe to expose in URLs** — `GET /api/v1/meals/550e8400-e29b-41d4-a716-446655440000` leaks nothing about the data model or row count.
- **Merge-safe** — importing data from another source (Open Food Facts API, USDA database) never produces a collision with existing rows.

### Negative
- **Storage: 16 bytes vs 4 bytes** per key. At 20M users with billions of meal_entries rows, this adds up — estimated additional ~10GB storage cost per year across all tables. Acceptable given the security and distribution benefits.
- **B-tree fragmentation** — UUID v4 is random, not sequential. Inserts land in random positions in the B-tree index rather than always appending to the end. This causes page splits and slightly slower write performance compared to sequential INTs. At FitTrack's scale this is measurable but not a bottleneck — the bottleneck is network and application logic, not index insertion speed.
- **Less readable in logs** — `userId=550e8400-e29b-41d4-a716-446655440000` is harder to read than `userId=42`. Mitigated by structured logging with user email as a secondary identifier.

## Alternatives Considered
- **Auto-increment INTEGER** — rejected. Sequential exposure is a real security concern. Incompatible with distributed generation. Cannot be used safely in client-side optimistic UI.
- **ULID (Universally Unique Lexicographically Sortable Identifier)** — seriously considered. ULIDs are time-ordered, which eliminates B-tree fragmentation while remaining globally unique. Rejected because PostgreSQL has no native ULID type (requires an extension or application-layer generation), and UUID v4 is natively supported by `gen_random_uuid()` with no additional dependencies. ULID is the right answer if B-tree fragmentation becomes a measured performance issue — it can be adopted table-by-table.
- **Snowflake ID** — considered. Time-sortable 64-bit integer used by Twitter/X at massive scale. Rejected because it requires a coordination service (or careful machine ID assignment) to guarantee uniqueness across nodes. Adds operational complexity that isn't justified at this scale.
