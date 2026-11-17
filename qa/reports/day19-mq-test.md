# Day 19 RabbitMQ reliability and reconciliation record

Date: 2026-11-07  
Status: test scenarios are implemented; real-service execution and final query output are pending.

## Environment observed for this run

| Dependency | Observation |
|---|---|
| MySQL | `127.0.0.1:3306` accepts TCP; the `DB_PASSWORD` environment variable is not set in this process, so the application test connection was not attempted |
| RabbitMQ | `127.0.0.1:5672` and management port `15672` do not accept TCP connections |
| Tempo | OTLP port `4318` does not accept TCP connections |
| Container runtime | Docker, Podman and Nerdctl commands were not found |
| Build | Maven 3.9.16 with IntelliJ JBR 25 compiled all 253 main Java sources and 66 test Java sources successfully using `-DskipTests test-compile`. No test methods were executed because RabbitMQ is unavailable |

No database invariant output, Broker queue state, or test pass count is recorded as passed. The report intentionally leaves these results pending until the scenarios run against a disposable database and a real RabbitMQ service.

## Fault-injection coverage

| Scenario | Test or evidence | Result |
|---|---|---|
| Order and Outbox commit together | `TicketOrderCreationMySqlIntegrationTest.createsForTheTrustedBuyerForEitherConsumerRole` | Not run |
| Order transaction rollback removes its Outbox event | `TicketOrderCreationMySqlIntegrationTest.failuresInsideTheRealCreationTransactionLeaveNoOrdersOrResourceChanges` | Not run |
| Broker connection failure leaves event retryable; live Broker later accepts it | `RabbitMqOutboxReliabilityMySqlIntegrationTest.brokerConnectionFailureLeavesOutboxRetryableAndLiveBrokerRecoversIt` | Not run |
| Confirm ACK plus unroutable Return does not set `SENT` | `RabbitMqOutboxReliabilityMySqlIntegrationTest.publisherReturnForUnroutableMessageNeverMarksOutboxSent` | Not run |
| Confirmed publish before Outbox update can be repeated with original ID | `RabbitMqOutboxReliabilityMySqlIntegrationTest.confirmedPublishLeaseExpiryAndConsumerRedeliveryKeepOneBusinessEffect` | Not run |
| Consumer commits, channel closes before ACK, then RabbitMQ redelivers | Same test; it leaves the first raw delivery unacked, closes its channel, then asserts a redelivery and one notification | Not run |
| Repeated payment and refund events produce one notification each | `confirmedPublishLeaseExpiryAndConsumerRedeliveryKeepOneBusinessEffect`; `repeatedRefundNotificationCreatesOnlyOneNotification` | Not run |
| Expired TTL message crosses the wait queue/DLX; a PAID order remains paid | `RabbitMqOutboxReliabilityMySqlIntegrationTest.ttlDeadLetterTriggersExpiryConsumerButCannotClosePaidOrder` | Not run |
| Database expiry scan closes overdue pending orders without timely MQ delivery | `TicketOrderClosingMySqlIntegrationTest.scanningClosesOnlyOneBoundedBatchInExpiryOrder` | Not run |
| Consumer failures stop after the configured attempts and reach DLQ with a queryable reason | `RabbitMqOutboxReliabilityMySqlIntegrationTest.repeatedConsumerFailuresAreBoundedAndDeadLetteredWithQueryableReason` | Not run |
| ADMIN can replay a failed event with the original ID; PUBLISHER cannot; processed effects cannot replay | `RabbitMqOutboxReliabilityMySqlIntegrationTest.publisherCanReplayFailedEventWithOriginalIdAndCannotReplayCommittedEffect` | Not run |

The RabbitMQ test class is gated by `RUN_MQ_RELIABILITY_TESTS=true`. It requires live MySQL and RabbitMQ and deliberately rejects non-empty application queues so test messages cannot be confused with existing work. Use a disposable test database and Broker. The unavailable-Broker case uses a real Rabbit client configured to a just-closed local TCP port, then retries against the live RabbitMQ connection factory; it tests the publisher's connection-failure/recovery path without stopping a shared Broker process.

## Running the real-service acceptance

Start the local stack after setting values in `deploy/.env`. If using the project Compose database, it is exposed on port `3307` and uses the `app_user` credentials. Configure the test process without writing secrets into this report:

```powershell
$env:RUN_MQ_RELIABILITY_TESTS = 'true'
$env:DB_PASSWORD = '<test database password>'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3307/limit_activity_system?serverTimezone=Asia/Shanghai'
$env:SPRING_DATASOURCE_USERNAME = 'app_user'
$env:RABBITMQ_HOST = 'localhost'
$env:RABBITMQ_PORT = '5672'
$env:RABBITMQ_USER = '<local broker user>'
$env:RABBITMQ_PASSWORD = '<local broker password>'
mvn '-Dtest=TicketOrderCreationMySqlIntegrationTest,TicketOrderClosingMySqlIntegrationTest,RabbitMqOutboxReliabilityMySqlIntegrationTest' test
```

Run the broader cross-domain checks after worker transactions finish. All violation result sets must be empty; save the final `reconciliation` result set from [invariants.sql](../../tests/sql/invariants.sql), plus the payment, refund and product stock checks, into this report.

```powershell
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/invariants.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/payment_invariants.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/refund_invariants.sql
mysql --protocol=TCP -h 127.0.0.1 -P 3307 -u app_user -p limit_activity_system < tests/sql/product_invariants.sql
docker compose --env-file deploy/.env -f deploy/compose.yaml exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

### Final query output

Pending. The current environment has no available RabbitMQ Broker and no configured test database password. No reconciliation query was run, so there are no query results to report yet.

## Follow-up review (2026-11-08)

Local TCP probes found MySQL on `127.0.0.1:3306`, but not the Compose port `3307`. RabbitMQ AMQP `5672` and management `15672` were unavailable. No Docker, Podman, Nerdctl, or RabbitMQ service was available in this environment. The Day 19 real-service scenarios remain unrun, so Day 19 is not confirmed complete.
