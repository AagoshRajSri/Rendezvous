# Rendezvous Dispatch Platform — SRE Runbook

> **Classification:** Internal — Platform SRE  
> **Service:** `rendezvous-dispatch`  
> **Maintained by:** Platform SRE Team  
> **Last Reviewed:** 2026-11  

---

## Document Structure

This runbook covers the four primary alert families for the Rendezvous ride-dispatch engine.
Each section follows the **D.I.A.** pattern:

1. **Diagnose** — understand the exact scope and severity of the incident  
2. **Isolate** — identify the failing component without causing further damage  
3. **Act** — execute the mitigation in the correct order  

---

## Alert: `KafkaConsumerLagHigh`

**Severity:** Critical  
**Threshold:** Consumer group `dispatch-service` is > 1,000 messages behind on `ride-requests`  
**Runbook URL:** `/ops/RUNBOOK.md#kafkaconsumerlaghigh`

### What this means for users

Rider dispatch requests are queued in Kafka but not yet matched. Every message of lag at 60 req/s ingestion represents ~16 milliseconds of additional wait time for a rider. At 1,000 messages of lag, riders may be waiting 16+ extra seconds for a driver assignment.

### D — Diagnose

**Step 1: Confirm the lag is real and measure its magnitude.**

```bash
# Option A: Direct Kafka consumer group inspection
# (replace kafka_container_name if using docker compose, or pod name in Kubernetes)
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group dispatch-service \
  --describe

# Expected output columns:
# TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  ...
# ride-requests   0          44231           45432           1201  ...

# Option B: In Kubernetes
kubectl exec -n rendezvous \
  $(kubectl get pod -n rendezvous -l app=kafka -o jsonpath='{.items[0].metadata.name}') \
  -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --group dispatch-service \
  --describe
```

**Step 2: Identify whether the consumer is running.**

```bash
# Check if the backend pods are alive and healthy
kubectl get pods -n rendezvous -l app=rendezvous
# All pods should show STATUS=Running, RESTARTS=low

# Tail application logs for exceptions that prevent consumer polling
kubectl logs -n rendezvous -l app=rendezvous --tail=100 --follow
# Search for: "ERROR", "KafkaException", "ERROR", "WARN [RetryListener]"

# For docker compose:
docker compose logs --tail=100 -f backend 2>&1 | grep -E "ERROR|WARN|KafkaException|RetryListener"
```

**Step 3: Check if DLQ is receiving messages (indicates persistent consumer failures).**

```bash
# Count messages in DLQ
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dispatch-failures-dlq \
  --from-beginning \
  --max-messages 20 \
  --timeout-ms 5000

# In Kubernetes:
kubectl exec -n rendezvous \
  $(kubectl get pod -n rendezvous -l app=kafka -o jsonpath='{.items[0].metadata.name}') \
  -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic dispatch-failures-dlq \
  --from-beginning \
  --max-messages 20 \
  --timeout-ms 5000
```

**Step 4: Check lag trend — is it growing or stable?**

```bash
# In Grafana (http://localhost:3000), navigate to:
#   Dashboard > Rendezvous Dispatch > Kafka Consumer Lag
# Observe the rate of change: a growing slope indicates active starvation.

# From Prometheus directly (http://localhost:9090):
# Query:  sum(kafka_consumer_group_lag{consumergroup="dispatch-service"}) by (topic)
# If the value is increasing monotonically → consumers are stopped.
# If the value is oscillating → consumers are running but cannot keep up.
```

---

### I — Isolate

**Scenario A: Consumer is running but falling behind (oscillating lag)**

Root cause: Throughput of the matching engine (QuadTree + Auction) is insufficient to process 60 req/s. This can happen if:
- Fleet size is extremely large (QuadTree traversal O(log N) cost grows)
- DB write latency for `AssignmentPersistenceService.persistBatch()` is high
- Kafka is throttling due to broker resource pressure

**Scenario B: Consumer is stopped (lag growing monotonically, no consumer heartbeat)**

Root cause: All backend pods have crashed, OOM-killed, or the consumer thread has deadlocked.

**Scenario C: Consumer is retrying (DLQ receiving messages)**

Root cause: The Matcher or persistence layer is throwing unhandled exceptions, exhausting the 3-attempt exponential backoff and routing messages to the DLQ. Consumer lag grows because failed messages occupy time.

---

### A — Act

**For Scenario A (throughput insufficient):**

```bash
# 1. Check HPA status — if CPU > 70%, HPA should have scaled already.
kubectl get hpa rendezvous-hpa -n rendezvous
# TARGETS column shows current/desired CPU%. REPLICAS shows current pods.

# 2. If HPA has not scaled (Metrics Server not installed), scale manually:
kubectl scale deployment rendezvous -n rendezvous --replicas=5

# 3. If DB writes are slow, check PostgreSQL active query latency:
docker compose exec postgres \
  psql -U dispatch_user -d dispatch_db -c \
  "SELECT pid, now() - query_start AS duration, state, query
   FROM pg_stat_activity
   WHERE state != 'idle'
   ORDER BY duration DESC
   LIMIT 10;"

# 4. Reduce batch pressure: temporarily lower max-poll-records
# Edit application.properties: spring.kafka.consumer.max-poll-records=25
# Then rolling-restart the deployment:
kubectl rollout restart deployment/rendezvous -n rendezvous
kubectl rollout status deployment/rendezvous -n rendezvous
```

**For Scenario B (consumer stopped):**

```bash
# 1. Check pod events for OOM or crash reasons:
kubectl describe pods -n rendezvous -l app=rendezvous | grep -A 20 "Events:"

# 2. Restart the deployment (triggers a rolling restart of all backend pods):
kubectl rollout restart deployment/rendezvous -n rendezvous
kubectl rollout status deployment/rendezvous -n rendezvous

# 3. After restart, verify the consumer rebalance completes:
kubectl exec -n rendezvous \
  $(kubectl get pod -n rendezvous -l app=kafka -o jsonpath='{.items[0].metadata.name}') \
  -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --group dispatch-service \
  --describe
# All partitions should show a CONSUMER-ID within 30 seconds.

# 4. Monitor lag recovery. At 60 req/s it takes time/msgs_consumed_per_second to drain:
watch -n 2 'docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group dispatch-service \
  --describe 2>/dev/null | grep ride-requests'
```

**For Scenario C (DLQ receiving messages):**

```bash
# 1. Read DLQ messages to identify the exception type:
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dispatch-failures-dlq \
  --from-beginning \
  --max-messages 5 \
  --timeout-ms 5000

# 2. If the cause is transient (e.g., brief DB outage now resolved),
#    replay DLQ messages back to ride-requests:
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dispatch-failures-dlq \
  --from-beginning \
  --timeout-ms 10000 | \
  docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic ride-requests

# WARNING: Replaying DLQ messages may cause duplicate driver claims.
# The two-tier idempotency gate (in-memory LRU + DB unique constraint on request_id)
# will safely deduplicate. The DB UNIQUE constraint on request_id prevents double-booking.

# 3. Check application logs for the exception that caused DLQ routing:
kubectl logs -n rendezvous -l app=rendezvous | grep -A 5 "DLQ"
# Look for the pattern: "[DLQ] Routing failed record to 'dispatch-failures-dlq'. cause=..."
```

---

## Alert: `HighApiErrorRate`

**Severity:** Critical  
**Threshold:** HTTP 5xx error rate > 1% over 2 minutes  
**Runbook URL:** `/ops/RUNBOOK.md#highapierrorrate`

### D — Diagnose

```bash
# 1. Check which endpoint is generating 5xx errors
curl -s http://localhost:8080/actuator/prometheus | \
  grep 'http_server_requests_seconds_count' | \
  grep 'SERVER_ERROR'

# 2. Check Actuator health
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# 3. Tail logs for stack traces
kubectl logs -n rendezvous -l app=rendezvous --tail=200 | \
  grep -A 20 "ERROR\|Exception"
```

### I — Isolate

- **503 from `POST /request` or `POST /ping`:** Kafka broker is unreachable. The `KafkaPublishException` handler returns 503 when `KafkaTemplate.send()` times out after 5 seconds.
- **503 from `GET /api/requests/{id}`:** Database connection pool exhausted (check `PostgresConnectionExhaustion` alert).
- **500 from any endpoint:** Unhandled exception — check stack trace in logs.

### A — Act

```bash
# For Kafka-induced 503s:
# Check Kafka broker health
docker compose ps kafka
docker compose exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server localhost:9092

# For DB-induced 503s: see PostgresConnectionExhaustion section below.

# For rolling restart after a fix:
kubectl rollout restart deployment/rendezvous -n rendezvous
```

---

## Alert: `PostgresConnectionExhaustion`

**Severity:** Critical  
**Threshold:** HikariCP active connections > 90% of max pool size for 5 minutes  
**Runbook URL:** `/ops/RUNBOOK.md#postgresconnectionexhaustion`

### D — Diagnose

```bash
# 1. Check current pool utilization via Prometheus
curl -s http://localhost:8080/actuator/prometheus | \
  grep hikaricp_connections

# Expected output:
# hikaricp_connections_active{...} 9.0    ← active (holding a connection)
# hikaricp_connections_idle{...}   1.0    ← idle (available)
# hikaricp_connections_max{...}    10.0   ← pool max size

# 2. Check for long-running queries in PostgreSQL
docker compose exec postgres \
  psql -U dispatch_user -d dispatch_db -c "
    SELECT pid, now() - query_start AS duration, state, left(query, 120) AS query_snippet
    FROM pg_stat_activity
    WHERE state != 'idle'
      AND query_start IS NOT NULL
    ORDER BY duration DESC
    LIMIT 15;"

# 3. Check for lock contention
docker compose exec postgres \
  psql -U dispatch_user -d dispatch_db -c "
    SELECT blocked.pid, blocked.query, blocking.pid AS blocking_pid, blocking.query AS blocking_query
    FROM pg_stat_activity AS blocked
    JOIN pg_stat_activity AS blocking ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
    WHERE NOT blocked.granted;"
```

### A — Act

```bash
# Option 1: Kill long-running idle-in-transaction sessions:
docker compose exec postgres \
  psql -U dispatch_user -d dispatch_db -c "
    SELECT pg_terminate_backend(pid)
    FROM pg_stat_activity
    WHERE state = 'idle in transaction'
      AND now() - state_change > interval '30 seconds';"

# Option 2: Temporarily increase HikariCP pool size via environment variable
# and rolling-restart the deployment:
kubectl set env deployment/rendezvous -n rendezvous \
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
kubectl rollout status deployment/rendezvous -n rendezvous

# Option 3: Scale up replicas (more pods = more connections needed, not a fix for pool exhaustion
# on a single DB — but helps distribute load across the DB connection pool).
# Use this only if the DB can handle more total connections.
kubectl scale deployment rendezvous -n rendezvous --replicas=3
```

---

## Alert: `KafkaDlqMessagesGrowing`

**Severity:** Warning  
**Threshold:** DLQ receiving > 1 message/minute sustained for 5 minutes  

### D — Diagnose

```bash
# 1. Inspect DLQ message headers for exception class and message
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic dispatch-failures-dlq \
  --property print.headers=true \
  --from-beginning \
  --max-messages 10 \
  --timeout-ms 5000

# 2. Check application logs for the [RetryListener] WARN entries
kubectl logs -n rendezvous -l app=rendezvous --since=10m | \
  grep "RetryListener"
# Pattern: "[RetryListener] Retry attempt X/3 for record topic=ride-requests, ..."

# 3. Count total DLQ messages
docker compose exec kafka \
  /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:9092 \
  --topic dispatch-failures-dlq
```

### A — Act

```bash
# 1. Identify the root cause from DLQ headers (exception class name is in the
#    kafka_dlt-exception-cause-fqcn header).

# 2. If cause is transient and resolved (e.g., DB briefly unavailable):
#    Replay DLQ → ride-requests (see KafkaConsumerLagHigh Scenario C above).

# 3. If cause is a code bug: hotfix + rolling restart. Do NOT replay DLQ until
#    the bug is fixed or you'll re-trigger the same failures.

# 4. If cause is infrastructure (Kafka broker overload, disk full):
docker compose exec kafka df -h /tmp/kraft-combined-logs
# If disk > 80%: adjust log.retention.hours or add storage.
```

---

## Useful Operational One-Liners

```bash
# Live dispatch match rate (how many requests resulted in MATCHED vs NO_DRIVERS)
curl -s http://localhost:8080/api/telemetry/batches | python3 -m json.tool

# Live Kafka topic offsets
docker compose exec kafka \
  /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:9092 \
  --topic ride-requests

# List all consumer groups and their state
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --list

# Reset consumer offset to latest (use only after draining backlog is undesirable)
# WARNING: This permanently skips all messages in the current lag.
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group dispatch-service \
  --topic ride-requests \
  --reset-offsets \
  --to-latest \
  --execute

# Force a rolling restart of backend pods (safest remediation step)
kubectl rollout restart deployment/rendezvous -n rendezvous && \
  kubectl rollout status deployment/rendezvous -n rendezvous

# Check HPA scaling decisions
kubectl describe hpa rendezvous-hpa -n rendezvous | tail -20
```

---

## Escalation

| Condition | Action |
|-----------|--------|
| Lag > 5,000 messages and not draining after 10 min | Page engineering lead |
| DLQ > 500 messages in a single incident | Engineering post-mortem required |
| PostgreSQL data loss suspected | Immediate DB snapshot + stop writes |
| All replicas OOM-crashed simultaneously | Declare P0 incident |
