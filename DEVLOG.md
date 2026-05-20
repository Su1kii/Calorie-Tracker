# Dev Log

---

## 2026-05-21 — Phase 0: Design complete

### What I built
- ERD on dbdiagram.io — all 14 tables with relationships
- System architecture diagram on Eraser.io
- 7 ADRs written covering every major technology decision
- README with both diagrams, scale math, and build status
- docs/ folder with SYSTEM_DESIGN.md and schema.md

### What I learned
- RANGE vs HASH partitioning: meals needs RANGE by logged_at
  so date-range queries prune to one partition. exercise_sets
  needs HASH by user_id for even distribution regardless of
  when data was written.
- Martin Fowler's monolith-first principle — don't start
  microservices until you have a proven need, not anticipated.

### What's next
- Spring Initializr project setup
- PostgreSQL local database
- First entity: User.java