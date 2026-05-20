# ADR-005: Redis Cache-Aside Pattern over No Caching / In-Process Cache

## Status
Accepted

## Date
2026-05-20

## Context
At 20 million users, the read access patterns for FitTrack Pro have two critical hotspots:

1. **Food item lookups** — every meal log resolves 1–5 food items by ID or barcode. At 3M DAU logging 3 meals with 3 items each, that's ~27 million food item lookups per day. The `food_items` table has ~2 million rows and is almost entirely read-only — food nutritional data rarely changes.

2. **Daily macro calculations** — the dashboard loads a user's daily macro totals on every app open. This requires a JOIN across `meals`, `meal_entries`, and `food_items`, then aggregation. At 3M DAU, this is potentially 3 million complex JOIN queries per day just for the dashboard.

Without caching, both patterns hit PostgreSQL on every request. A single RDS read replica handles ~5,000 queries/second — 27M food lookups/day is ~312 queries/second average, but the peak multiplier (3×) puts it at ~935 queries/second from food lookups alone. That's 18% of a single replica's capacity just for one query type.

Additionally, login brute-force protection is needed. Checking attempt counts in PostgreSQL on every login request adds unnecessary DB load.

## Decision
Use **Redis (AWS ElastiCache Cluster)** with the **cache-aside pattern** for food items and daily macro totals. Use Redis `INCR` + `EXPIRE` for rate limiting.

### Cache key strategy
| Key pattern | TTL | Invalidation trigger |
|---|---|---|
| `food:{uuid}` | 24 hours | Never (food data is immutable once created) |
| `daily:{userId}:{date}` | 25 hours | On every `POST /api/v1/meals` for that user+date |
| `rate:{ip}:login` | 60 seconds | Auto-expires |
| `session:{userId}` | 7 days | On logout / token revocation |

### Cache-aside flow
1. Check Redis for key
2. **HIT** → return immediately (~1ms), zero DB load
3. **MISS** → query PostgreSQL read replica (~20ms) → store in Redis with TTL → return result
4. **On write** → delete the affected cache key; next request repopulates from DB

## Consequences

### Positive
- **22× latency improvement** for food lookups — ~1ms Redis vs ~22ms PostgreSQL. Measurable and benchmarked.
- **~25.65M DB queries eliminated per day** — at 95% cache hit rate on 27M food lookups, only ~1.35M reach PostgreSQL. This directly reduces read replica load and extends their effective capacity.
- **Daily macro cache** — the complex JOIN query runs once per user per day in the worst case (after cache miss), not on every dashboard open. With a 25-hour TTL, a user who logs their last meal at 11PM has their macros cached until midnight the next day.
- **Rate limiting at ~0.5ms** — `INCR rate:{ip}:login` + `EXPIRE 60` costs one Redis roundtrip (~0.5ms). Equivalent DB-based rate limiting would require an `INSERT` or `UPDATE` on every login attempt — 5× slower and adds write load.
- **Shared across all pods** — unlike in-process caching (Caffeine, Guava), Redis is shared across all K8s pods. A cache population on Pod 1 is immediately visible to Pod 47.

### Negative
- **Cache invalidation complexity** — invalidating `daily:{userId}:{date}` on every meal write is straightforward, but bugs here cause stale macro data (user sees wrong totals). Requires careful testing.
- **Cache stampede risk** — when a popular cache key expires, many simultaneous requests all find a miss and all query the DB at once. Mitigated with Redis `SET NX` mutex lock or TTL jitter on population. Documented in `RedisCacheService`.
- **Operational overhead** — a 6-node ElastiCache cluster is another service to monitor, alert on, and handle failover for. Cost: ~$150/month reserved.
- **Data consistency window** — between a meal write invalidating the cache and the next read re-populating it, there's a brief window where two concurrent reads could both miss and both query the DB. Acceptable for this use case — daily macros don't require strong consistency.

## Alternatives Considered
- **No caching** — rejected. 27M food item DB queries/day at peak is unsustainable on a single read replica without significantly over-provisioning. The math doesn't work without caching.
- **In-process cache (Caffeine)** — rejected. Works within a single JVM but doesn't share state between K8s pods. Pod 1 warms its cache; Pod 2 starts cold on the next request. Cache invalidation on write becomes a broadcast problem across all pods. At 2–100 pods with HPA, per-pod caches are unreliable.
- **CDN caching (CloudFront)** — partially adopted for static assets and some GET endpoints. Rejected as the primary food item caching strategy because CloudFront cache keys are URL-based — food item lookups by UUID work, but cache invalidation on food data updates is complex and slow (CloudFront invalidation takes minutes, not milliseconds).
- **Memcached over Redis** — rejected. Memcached is a simple key-value store. Redis adds: persistence (RDB snapshots for warm restart), sorted sets (future leaderboard), pub/sub (future real-time features), and `INCR`+`EXPIRE` atomicity for rate limiting. The additional capabilities justify the marginal increase in operational complexity.
