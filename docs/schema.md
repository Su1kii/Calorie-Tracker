# Database Schema

Full ERD: see `docs/erd.png`

## Design principles

**UUID primary keys** — all 14 tables use `gen_random_uuid()`.
No sequential exposure, distributed-system safe. See ADR-003.

**3NF normalization** — no repeating groups, no transitive
dependencies. Meals and meal_entries are separate tables.
Food nutrition lives in food_items, never duplicated in
meal_entries.

**Audit columns everywhere** — every table has `created_at`
(updatable=false, set on INSERT) and `updated_at`
(auto-updated by Hibernate @UpdateTimestamp). You can always
answer "when did this happen?"

**Money as INTEGER cents** — payments.amount_cents stores
1000 for $10.00. Floating point arithmetic is imprecise.
Integer cents are always exact.

**Nutrition per 100g standard** — never store computed
macros. Always calculate dynamically:
`calories = (calories_per_100g × quantity_g) / 100`.
If food data changes, all history auto-corrects.

**Enums as VARCHAR strings** — @Enumerated(EnumType.STRING).
Stores 'CHEST' not 2. Reordering enum values would silently
corrupt ORDINAL data in production.

---

## Tables

### users
Central table. Every record in the system belongs to a User.
Implements Spring Security UserDetails.

| Column | Type | Constraints | Why |
|--------|------|-------------|-----|
| id | UUID | PK | No sequential exposure |
| email | VARCHAR(255) | UNIQUE NOT NULL | Login identifier |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt cost 12. Name signals it's a hash |
| full_name | VARCHAR(100) | NOT NULL | Display name, separate from login |
| role | VARCHAR(20) | NOT NULL DEFAULT 'USER' | RBAC: USER or ADMIN |
| profile_picture_url | VARCHAR(500) | NULLABLE | S3 URL. Never store binary in DB |
| is_active | BOOLEAN | NOT NULL DEFAULT true | Soft delete. Never delete users — breaks FKs |
| created_at | TIMESTAMP | NOT NULL updatable=false | Set on INSERT, never changes |
| updated_at | TIMESTAMP | NOT NULL | Auto-updated by Hibernate |

**Indexes:** `idx_users_email` on email (login lookup)

---

### refresh_tokens
...and so on for all 14 tables