# Database Schema

Full ERD: see `docs/erd.png`

---

## Design Principles

**UUID primary keys** — all 14 tables use `gen_random_uuid()`. No sequential exposure, distributed-system safe. See [ADR-003](./adr/ADR-003-uuid-over-autoincrement.md).

**3NF normalization** — no repeating groups, no transitive dependencies. Meals and meal_entries are separate tables. Food nutrition lives in food_items, never duplicated in meal_entries.

**Audit columns everywhere** — every table has `created_at` (updatable=false, set on INSERT) and `updated_at` (auto-updated by Hibernate @UpdateTimestamp). You can always answer "when did this happen?"

**Money as INTEGER cents** — payments.amount_cents stores 1000 for $10.00. Floating point arithmetic is imprecise. Integer cents are always exact.

**Nutrition per 100g standard** — never store computed macros. Always calculate dynamically: `calories = (calories_per_100g × quantity_g) / 100`. If food data changes, all history auto-corrects.

**Enums as VARCHAR strings** — `@Enumerated(EnumType.STRING)`. Stores 'CHEST' not 2. Reordering enum values would silently corrupt ORDINAL data in production.

**Soft deletes** — users and financial records are never hard deleted. `is_active = false` on users, status transitions on payments. Audit trail always preserved.

---

## Tables

### users
Central table. Every record in the system belongs to a User. Implements Spring Security UserDetails. Soft-deleted via `is_active` — never hard deleted because all other tables FK to this one.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | No sequential exposure, distributed-safe |
| email | VARCHAR(255) | UNIQUE NOT NULL | Login identifier, indexed for fast lookup |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt cost 12. Name signals it's a hash, never plaintext |
| full_name | VARCHAR(100) | NOT NULL | Display name, separate from login email |
| role | VARCHAR(20) | NOT NULL DEFAULT 'USER' | RBAC foundation: USER or ADMIN |
| profile_picture_url | VARCHAR(500) | NULLABLE | S3 URL. Never store binary blobs in DB |
| is_active | BOOLEAN | NOT NULL DEFAULT true | Soft delete. Hard delete breaks all foreign keys |
| created_at | TIMESTAMP | NOT NULL updatable=false | Set on INSERT by @CreationTimestamp, never changes |
| updated_at | TIMESTAMP | NOT NULL | Auto-updated by @UpdateTimestamp on every save |

**Indexes:** `idx_users_email` on email (login lookup)

---

### refresh_tokens
Stores JWT refresh tokens so they can be revoked. Access tokens (15 min) are stateless — no DB lookup needed. Refresh tokens (7 days) require DB validation so logout and security incidents can invalidate them instantly.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| token | VARCHAR(500) | UNIQUE NOT NULL | The actual token value. UNIQUE for fast lookup on exchange |
| user_id | UUID | FK → users CASCADE DELETE | When user deleted, their tokens delete too |
| expires_at | TIMESTAMP | NOT NULL | Default 7 days from creation. Checked on every use |
| is_revoked | BOOLEAN | NOT NULL DEFAULT false | Logout sets true. Rejected even if not expired yet |
| created_at | TIMESTAMP | NOT NULL | Audit trail — when was this token issued |

**Indexes:** `idx_refresh_tokens_token` on token (exchange lookup)

---

### user_goals
Daily nutritional targets per user. Separate from users table for extensibility — goal history tracking possible in future. Currently one-to-one with users enforced by UNIQUE on user_id.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| user_id | UUID | PK FK → users UNIQUE | UNIQUE makes this one-to-one. One goal record per user |
| daily_calories | INTEGER | NOT NULL DEFAULT 2000 | Whole number calories. INTEGER is exact |
| daily_protein_g | DECIMAL(6,1) | NOT NULL DEFAULT 150.0 | DECIMAL for precision — 152.5g is a valid target |
| daily_carbs_g | DECIMAL(6,1) | NULLABLE | Optional — not everyone tracks carbs |
| daily_fat_g | DECIMAL(6,1) | NULLABLE | Optional target |
| daily_water_ml | INTEGER | NULLABLE | Optional water intake goal in milliliters |
| updated_at | TIMESTAMP | NOT NULL | When user last changed their goals |

---

### food_items
Most-read table in the system. Every food search and every meal log hits this. Cached aggressively in Redis (TTL 24h). Nutrition always stored per 100g — never computed values. Shared across all users.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| name | VARCHAR(200) | NOT NULL INDEXED | gin_trgm_ops index for fuzzy text search |
| brand | VARCHAR(100) | NULLABLE | 'Quaker' for branded. NULL for generic 'chicken breast' |
| barcode | VARCHAR(50) | UNIQUE NULLABLE | EAN/UPC. UNIQUE = one barcode one product. Not all foods have barcodes |
| calories_per_100g | DECIMAL(7,2) | NOT NULL | Always per 100g standard |
| protein_per_100g | DECIMAL(6,2) | NOT NULL | BigDecimal in Java — never double for nutrition |
| carbs_per_100g | DECIMAL(6,2) | NOT NULL | Same precision standard |
| fat_per_100g | DECIMAL(6,2) | NOT NULL | Same |
| fiber_per_100g | DECIMAL(6,2) | NULLABLE | Not all foods have fiber data |
| source | VARCHAR(50) | NOT NULL DEFAULT 'MANUAL' | MANUAL / OPEN_FOOD_FACTS / USDA. Data provenance |
| is_verified | BOOLEAN | NOT NULL DEFAULT false | Admin-verified = trusted nutritional data |
| created_at | TIMESTAMP | NOT NULL | Audit trail |

**Indexes:** `idx_food_items_name_trgm` GIN trigram index on name, `idx_food_items_barcode` on barcode

---

### meals
A meal is a container for food entries at a specific time. Separate from meal_entries to follow 3NF — no repeating groups. RANGE partitioned by logged_at (monthly) because this table reaches ~3.28B rows/year at 20M users.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK (composite with logged_at for partitioning) | Standard UUID PK |
| user_id | UUID | FK → users NOT NULL CASCADE DELETE | Which user. Delete user = delete their meals |
| meal_type | VARCHAR(20) | NOT NULL | BREAKFAST / LUNCH / DINNER / SNACK / PRE_WORKOUT / POST_WORKOUT |
| logged_at | TIMESTAMP | NOT NULL DEFAULT NOW() | Partition key. GROUP BY DATE(logged_at) = daily rollup |
| notes | VARCHAR(500) | NULLABLE | Optional user notes |
| created_at | TIMESTAMP | NOT NULL | Audit trail |

**Partitioning:** `PARTITION BY RANGE (logged_at)` — monthly partitions. Date-range queries only scan the relevant month.

**Indexes:** `idx_meals_user_date` on (user_id, logged_at DESC), `idx_meals_user_date_type` on (user_id, DATE(logged_at), meal_type)

---

### meal_entries
Each row = one food item within one meal. Junction table connecting meals to food_items. Nutrition computed dynamically from food_items data — never stored here. Storing computed values would go stale if food data changes.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| meal_id | UUID | FK → meals CASCADE DELETE | Which meal. Delete meal = delete its entries |
| food_item_id | UUID | FK → food_items RESTRICT | No CASCADE — food items shouldn't delete if referenced |
| quantity_g | DECIMAL(8,2) | NOT NULL | Grams consumed. 87.5g is valid. DECIMAL not INTEGER |
| created_at | TIMESTAMP | NOT NULL | Audit trail |

**Computed (never stored):** `calories = food_items.calories_per_100g × quantity_g / 100`

**Indexes:** `idx_meal_entries_meal_id` on meal_id, `idx_meal_entries_food_id` on food_item_id

---

### exercises
Library of exercises shared across all users. MuscleGroup enum enables volume analytics by muscle group. `is_custom = false` for system exercises, `true` for user-created ones.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| name | VARCHAR(100) | UNIQUE NOT NULL | Can't have two 'Bench Press' entries |
| muscle_group | VARCHAR(30) | NOT NULL | Primary group: CHEST / BACK / SHOULDERS / BICEPS / TRICEPS / LEGS / CORE / CARDIO |
| secondary_muscle_groups | VARCHAR(200) | NULLABLE | Comma-separated. 'TRICEPS,SHOULDERS' for bench press |
| equipment_type | VARCHAR(30) | NOT NULL | BARBELL / DUMBBELL / MACHINE / BODYWEIGHT / CABLE |
| instructions | TEXT | NULLABLE | TEXT not VARCHAR — no length limit for long descriptions |
| is_custom | BOOLEAN | NOT NULL DEFAULT false | false = system exercise, true = user-created |
| created_by_user_id | UUID | FK → users NULLABLE | NULL for system exercises. Set for custom exercises |
| created_at | TIMESTAMP | NOT NULL | Audit trail |

---

### workout_plans
Named templates: 'Push Day', 'Pull Day', 'Leg Day'. Plan is the template. Session is when it's actually performed. This separation allows a plan to be reused across many sessions.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| user_id | UUID | FK → users NOT NULL | Plans belong to specific users |
| name | VARCHAR(100) | NOT NULL | Plan name: 'Push Day', 'Pull Day A', 'Cardio' |
| target_muscle_groups | VARCHAR(200) | NULLABLE | Computed on save for quick display. Intentional denormalization |
| created_at | TIMESTAMP | NOT NULL | Audit trail |
| updated_at | TIMESTAMP | NOT NULL | Auto-updated by Hibernate |

---

### workout_plan_exercises
Junction table: many-to-many between workout_plans and exercises. One plan has many exercises. One exercise appears in many plans. `order_index` controls display order within a plan.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| workout_plan_id | UUID | FK → workout_plans NOT NULL | Which plan |
| exercise_id | UUID | FK → exercises NOT NULL | Which exercise |
| order_index | INTEGER | NOT NULL DEFAULT 0 | Display order. 0=first, 1=second |
| target_sets | INTEGER | NULLABLE | Planned sets. Nullable — user might not plan ahead |
| target_reps | INTEGER | NULLABLE | Planned reps per set |
| target_weight_kg | DECIMAL(6,2) | NULLABLE | Nullable for bodyweight exercises |
| rest_seconds | INTEGER | NULLABLE | Planned rest time. Drives rest timer default in UI |

---

### workout_sessions
A session = one instance of performing a plan. Links to plan (nullable for ad-hoc sessions). Records start/end for duration tracking. `total_volume_kg` computed on completion and cached here for analytics.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| user_id | UUID | FK → users NOT NULL | Who performed this session |
| workout_plan_id | UUID | FK → workout_plans NULLABLE | Which plan. Nullable for unplanned/ad-hoc sessions |
| started_at | TIMESTAMP | NOT NULL DEFAULT NOW() | When session began. Required for duration calculation |
| completed_at | TIMESTAMP | NULLABLE | Null if still in progress. Set on completion |
| status | VARCHAR(20) | NOT NULL DEFAULT 'IN_PROGRESS' | IN_PROGRESS / COMPLETED / CANCELLED |
| total_volume_kg | DECIMAL(10,2) | NULLABLE | SUM(weight × reps) computed on completion. Cached for analytics |
| created_at | TIMESTAMP | NOT NULL | Audit trail |

---

### exercise_sets
Most granular data. Every individual set logged during a session. Powers weekly volume analytics. HASH partitioned by user_id (64 partitions) — user-scoped queries hit 1/64th of the data. `user_id` is denormalized here specifically to serve as the partition key.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK (composite with user_id for partitioning) | Standard UUID PK |
| workout_session_id | UUID | FK → workout_sessions NOT NULL | Which session |
| exercise_id | UUID | FK → exercises NOT NULL | Which exercise |
| user_id | UUID | NOT NULL | Denormalized — partition key for HASH partitioning |
| set_number | INTEGER | NOT NULL | Set 1, 2, 3 within this exercise for this session |
| weight_kg | DECIMAL(6,2) | NULLABLE | Null for bodyweight exercises |
| reps | INTEGER | NULLABLE | Null for time-based exercises (planks) |
| duration_seconds | INTEGER | NULLABLE | For time-based: plank for 60 seconds |
| rpe | INTEGER | NULLABLE CHECK (rpe >= 1 AND rpe <= 10) | Rate of Perceived Exertion 1–10 |
| is_completed | BOOLEAN | NOT NULL DEFAULT false | false = planned but not done yet |
| logged_at | TIMESTAMP | NOT NULL DEFAULT NOW() | When this set was logged |

**Partitioning:** `PARTITION BY HASH (user_id)` — 64 partitions for even distribution regardless of write time.

**Indexes:** `idx_exercise_sets_session` on workout_session_id, `idx_exercise_sets_exercise` on exercise_id

---

### weekly_schedules
Maps days of week to workout plans. 1=Monday through 7=Sunday (ISO 8601). Composite UNIQUE on (user_id, day_of_week) prevents a user from having two entries for the same day.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| user_id | UUID | FK → users NOT NULL | Whose schedule this is |
| day_of_week | INTEGER | NOT NULL CHECK (1–7) | 1=Mon, 2=Tue, ... 7=Sun. ISO 8601 standard |
| workout_plan_id | UUID | FK → workout_plans NULLABLE | Which plan on this day. NULL = no workout assigned |
| is_rest_day | BOOLEAN | NOT NULL DEFAULT false | Explicit rest day. Better UX than just null plan_id |

**Constraints:** `UNIQUE (user_id, day_of_week)` — one schedule entry per day per user

---

### payments
Every Stripe payment recorded here. Never deleted — status transitions only. UNIQUE on stripe_payment_intent_id is the idempotency guard: duplicate Stripe webhook → duplicate INSERT → constraint violation → catch and ignore.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| user_id | UUID | FK → users NULLABLE | Nullable — anonymous donations possible |
| stripe_payment_intent_id | VARCHAR(100) | UNIQUE NOT NULL | THE idempotency key. Duplicate webhook = constraint violation = safe ignore |
| amount_cents | INTEGER | NOT NULL | CENTS not dollars. 1000 = $10.00. Integers are exact |
| currency | VARCHAR(3) | NOT NULL DEFAULT 'USD' | ISO 4217 code. Always 3 chars |
| status | VARCHAR(20) | NOT NULL | PENDING → SUCCEEDED / FAILED / REFUNDED |
| stripe_customer_id | VARCHAR(100) | NULLABLE | Stripe customer reference |
| receipt_email | VARCHAR(255) | NULLABLE | May differ from user account email |
| created_at | TIMESTAMP | NOT NULL | Audit trail — financial records never deleted |
| updated_at | TIMESTAMP | NOT NULL | Tracks status transitions |

**Indexes:** `idx_payments_stripe_id` on stripe_payment_intent_id (webhook idempotency check)

---

### notifications
In-app notifications created by Kafka workers. Frontend polls `/api/v1/notifications/unread` for badge count. Never deleted — marked read instead. Created asynchronously after meal/workout/payment events.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | Standard UUID PK |
| user_id | UUID | FK → users NOT NULL | Who this notification is for |
| type | VARCHAR(30) | NOT NULL | GOAL_ACHIEVED / MEAL_REMINDER / WORKOUT_REMINDER / STREAK_MILESTONE / PAYMENT_RECEIVED |
| title | VARCHAR(100) | NOT NULL | Short title for notification card |
| message | VARCHAR(500) | NOT NULL | Full notification message body |
| is_read | BOOLEAN | NOT NULL DEFAULT false | Frontend marks true when notification panel opened |
| created_at | TIMESTAMP | NOT NULL | When notification was created |

**Indexes:** `idx_notifications_user_unread` on (user_id, is_read) WHERE is_read = false