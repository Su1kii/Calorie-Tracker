# ADR-006: Apache Kafka over RabbitMQ / AWS SQS

## Status
Accepted

## Date
2026-05-20

## Context
FitTrack Pro has several operations that should not block the HTTP response path:

1. **Goal achievement notifications** — after a meal is logged, check if the user hit their daily protein/calorie goal and create a `Notification` record. This check involves a DB query and conditional logic that the HTTP client shouldn't wait for.
2. **Analytics aggregation** — after a workout completes, update cached analytics. This is read-heavy and should not be in the write path.
3. **Payment confirmation emails** — after a Stripe `payment_intent.succeeded` webhook is received, send a receipt email via AWS SES. Email delivery latency should not affect webhook response time.

The requirement is an async messaging system that can:
- Deliver events to multiple independent consumers (notification worker AND analytics worker need to read the same `meal.logged` event)
- Guarantee ordering per user (a `meal.logged` event must be processed before a `goal.achieved` event for the same user)
- Allow event replay (if the analytics worker was down for 2 hours, it should be able to reprocess all events from that window)
- Scale to millions of events per day

## Decision
Use **Apache Kafka (AWS MSK)** with the following topics:
- `meal.logged` — published on every `POST /api/v1/meals`
- `workout.completed` — published when a `WorkoutSession` status transitions to `COMPLETED`
- `payment.succeeded` — published when Stripe webhook confirms payment

Partition key = `userId` on all topics — guarantees all events for a user land on the same partition and process in order.

## Consequences

### Positive
- **Consumer group isolation** — `notification-worker-group` and `analytics-worker-group` both subscribe to `meal.logged`. Kafka delivers the event to each group independently. Neither consumer blocks or affects the other. Not possible with RabbitMQ (message is deleted on first consumption) or standard SQS (one consumer gets the message).
- **Message replay** — 7-day retention means if the analytics worker has a bug and goes down for 6 hours, it can be fixed and restarted, and Kafka delivers all missed events in order from the committed offset. RabbitMQ and SQS delete messages on consumption — this capability doesn't exist.
- **Per-partition ordering** — partition key = `userId` ensures all events for user X are on partition N and processed sequentially. A `meal.logged` event is always processed before a `goal.achieved` event for the same user.
- **Scale** — Kafka handles millions of events per second. MSK Serverless scales automatically. At 9M meals/day + 1.2M workouts/day, the event volume is ~120 events/second average — well within Kafka's capacity.
- **Spot instance workers** — Kafka consumers are stateless and resumable. If a spot instance is reclaimed mid-processing, Kafka re-delivers the unacknowledged message. Workers run on spot nodes at 60% cost reduction.
- **Exactly-once for payments** — Kafka transactions + idempotent producers enable exactly-once delivery for `payment.succeeded` events. Combined with the `UNIQUE(stripe_payment_intent_id)` DB constraint, payment processing is protected at two independent layers.

### Negative
- **Cost** — MSK Serverless runs ~$200/month at production scale vs SQS ~$50/month. The $150/month premium buys ordering, replay, and consumer groups.
- **Local dev complexity** — running Kafka locally requires Docker Compose with Zookeeper + broker(s) + Kafka UI. Addressed with a pre-configured `docker-compose.yml` that starts the full stack with one command.
- **Eventual consistency** — notification delivery is async. A user logs a meal and may not see the goal-achieved notification for 1–5 seconds. Acceptable for this use case.
- **Dead letter queue setup** — after 3 failed retries, messages go to DLQ topics (`meal.logged.DLT`, etc.). Requires `@RetryableTopic` configuration and a DLQ monitor. More setup than RabbitMQ's built-in DLQ.

## Alternatives Considered
- **RabbitMQ (AWS MQ)** — rejected for two reasons: (1) messages are deleted on consumption — the analytics worker cannot independently consume the same `meal.logged` event as the notification worker without a fan-out exchange, which adds configuration complexity; (2) no message replay after consumption. ~$100/month, cheaper than MSK, but missing the features that justify using a message queue in the first place.
- **AWS SQS** — rejected. No per-user ordering guarantee (FIFO queues have 3,000 TPS limit and 300 message groups limit — insufficient for 20M users). No consumer groups — multiple workers reading the same queue compete for messages rather than independently consuming. No message replay. Cheapest option at ~$50/month but lacks the capabilities this architecture requires.
- **Synchronous HTTP calls (no queue)** — rejected. Notification logic in the meal-log request path adds 50–100ms and creates a coupling: if the notification service is slow or down, meal logging fails or degrades. The queue decouples these entirely — meal logging succeeds at ~20ms regardless of notification service health.
