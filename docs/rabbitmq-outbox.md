# RabbitMQ and transactional outbox

## Local services

RabbitMQ is pinned to `rabbitmq:4.1.8-management`; its AMQP and management ports bind to `127.0.0.1` only. Tempo is pinned to `grafana/tempo:2.8.2`; its query API and OTLP/HTTP receiver also bind to loopback. Copy `deploy/.env.example` to `deploy/.env`, set local passwords, then start the services with `docker compose --env-file deploy/.env -f deploy/compose.yaml up -d`.

The Spring application uses `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, and `RABBITMQ_PASSWORD`. Set those variables in the application process to the same credentials in Compose. When the application runs inside the Compose network, use `RABBITMQ_HOST=rabbitmq`, `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4318/v1/traces`, and a database URL that points at `mysql:3306`.

Management UI: `http://localhost:15672`  
Tempo query API: `http://localhost:3200`  
OTLP/HTTP ingest: `http://localhost:4318/v1/traces`

## Message flow

The direct exchange is `las.events.exchange`. `ORDER_EXPIRED` events enter `las.order.expiry.wait` with a per-message TTL calculated from the order's persisted `expires_at` and current MySQL time. Expiration dead-letters them to `las.order.expiry.execute`. Per-message TTL can still delay a short-lived message behind an earlier long-lived message in the same queue, so the database expiration scan remains enabled. Payment and refund success events route to separate notification queues. Each execution queue is a durable quorum queue with a delivery limit, a durable dead-letter queue, and a five-second retry queue. Consumer failures are retried at most three times; a failed retry publish or exhausted retry count rejects the message to its dead-letter queue. The broker delivery limit also bounds redelivery after a consumer process or connection crashes before it can handle a failure.

`Publisher Confirm` confirms that RabbitMQ accepted a publish. With `mandatory=true` and publisher returns enabled, the relay also rejects a returned, unroutable message. `Consumer ACK` is sent only after the proxied database service returns, which means its transaction committed. These are separate boundaries; the database `consumer_process` unique key and conditional order transition protect against duplicate business effects.

Outbox claims use short MySQL transactions with `FOR UPDATE SKIP LOCKED`, a lease, and a fencing token. Network publication happens after the claim transaction commits. An expired claim can be taken over. A lease token prevents a stale worker from overwriting a newer claim. The relay marks an event `SENT` only after an ACK confirm and no publisher return. The original `event_id` is reused for retries and operator replay.

Order expiry remains governed by `ticket_order.expires_at` and MySQL time. RabbitMQ TTL only schedules a wake-up; the Day 8 database scan remains enabled as a correctness fallback.

## API and trace lookup

`GET /notifications?limit=50` returns the authenticated user's simulated payment/refund notifications. An `ADMIN` can request a replay of a failed Outbox or failed consumer event with `POST /admin/outbox-events/{eventId}/replay`. Replays are rejected if any consumer has already committed that event.

The HTTP request trace context is stored in `outbox_event.traceparent`. The relay starts a producer span from that context, and Spring AMQP observations propagate context through RabbitMQ into the consumer listener. Logs include the trace and span IDs plus the business `eventId`. Use the trace ID in `http://localhost:3200/api/traces/{traceId}` to retrieve the trace from Tempo.
