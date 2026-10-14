# Dispatch Service REST API & Event Ingestion Documentation

This document specifies the REST API endpoints and asynchronous Kafka event ingestion contracts for the dispatch matching service (`com.aagosh.dispatch`).

---

## 1. Architecture Overview (Phase 5: Asynchronous Ingestion)

In Phase 5 Step 1, the REST API acts as an event ingestion gateway (Kafka producers). Incoming HTTP requests are validated synchronously, published to dedicated Kafka topics, and acknowledged with **HTTP 202 Accepted**.

Dedicated consumers process the queued events asynchronously using the thread-safe `DriverRegistry` and the configured `Matcher` domain engine.

```
+------------------+         HTTP POST         +------------------+
|  Mobile Client   | ------------------------> | DriverController |
|  (Driver Ping)   | <------------------------ | (REST Producer)  |
+------------------+      202 Accepted         +--------+---------+
                                                        |
                                                        | driver-pings
                                                        v
                                               [  Kafka Broker  ]
                                                        |
                                                        v
                                               +--------+---------+
                                               |DriverPingConsumer|
                                               | (Async Consumer) |
                                               +--------+---------+
                                                        |
                                                        v
                                               +------------------+
                                               |  DriverRegistry  |
                                               +------------------+

+------------------+         HTTP POST         +------------------+
|  Rider Client    | ------------------------> |  RideController  |
|  (Ride Request)  | <------------------------ | (REST Producer)  |
+------------------+      202 Accepted         +--------+---------+
                                                        |
                                                        | ride-requests
                                                        v
                                               [  Kafka Broker  ]
                                                        |
                                                        v
                                               +--------+---------+
                                               |RideReqConsumer   |
                                               | (Async Consumer) |
                                               +--------+---------+
                                                        |
                                       +----------------+----------------+
                                       |                                 |
                                       v                                 v
                              +------------------+             +-------------------+
                              |  DriverRegistry  |             |  Matcher Engine   |
                              +------------------+             +-------------------+
```

---

## 2. Driver Telemetry Endpoints

### 2.1 Ingest Location Telemetry / Ping

- **URL:** `POST /ping`
- **Content-Type:** `application/json`
- **HTTP Status on Success:** `202 Accepted`
- **Description:** Validates driver coordinates and publishes the telemetry ping to the `driver-pings` Kafka topic with `driverId` as the message key.
- **Asynchronous Processing:** `DriverPingConsumer` processes the event:
  - If `action` is `AUTO` (default): registers the driver if absent, or updates location while strictly preserving existing `DriverState` and active ride claims.
  - If `action` is `REGISTER`: registers the driver if absent; duplicate events are acknowledged idempotently.
  - If `action` is `UPDATE`: updates location if registered; unknown drivers are logged and acknowledged.
- **Caveat on Telemetry Ordering:** Location telemetry has no chronological or event-time ordering guarantee. Stored driver coordinates reflect whichever compare-and-set committed last.

#### Request Schema (`DriverPingRequest`)

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `driverId` | `String` | Yes | Non-blank driver identifier (e.g., `"D1"`). |
| `latitude` | `Double` | Yes | Finite latitude between `-90.0` and `90.0`. |
| `longitude` | `Double` | Yes | Finite longitude between `-180.0` and `180.0`. |
| `action` | `String` | No | Optional action mode: `"AUTO"` (default), `"UPDATE"`, or `"REGISTER"`. |

#### Example: Location Telemetry Ping

**Request:**
```http
POST /ping HTTP/1.1
Content-Type: application/json

{
  "driverId": "D1",
  "latitude": 17.3850,
  "longitude": 78.4867
}
```

**Response (202 Accepted):**
```json
{
  "driverId": "D1",
  "message": "Driver ping accepted and queued for processing"
}
```

---

### 2.2 Explicit Driver Registration

- **URL:** `POST /register`
- **Content-Type:** `application/json`
- **HTTP Status on Success:** `202 Accepted`
- **Description:** Validates input and publishes an event with `action: "REGISTER"` to the `driver-pings` Kafka topic.

#### Example: Driver Registration

**Request:**
```http
POST /register HTTP/1.1
Content-Type: application/json

{
  "driverId": "D10",
  "latitude": 17.3900,
  "longitude": 78.4900
}
```

**Response (202 Accepted):**
```json
{
  "driverId": "D10",
  "message": "Driver registration accepted and queued for processing"
}
```

---

## 3. Ride Dispatch Endpoint

### 3.1 Submit Ride Request

- **URL:** `POST /request`
- **Content-Type:** `application/json`
- **HTTP Status on Success:** `202 Accepted`
- **Description:** Validates pickup coordinates and publishes the request to the `ride-requests` Kafka topic with `requestId` as the message key.
- **Asynchronous Match Processing:**
  - `RideRequestConsumer` receives the event and verifies the in-memory idempotency set.
  - The configured `Matcher` (`AuctionMatcher` or `GreedyMatcher`) is invoked with a snapshot of registered drivers.
  - If a driver is assigned, the driver is atomically claimed using `DriverState.tryClaim(requestId)`.
- **Contractual Limitation:** The `202 Accepted` response confirms durable receipt by Kafka, **not** that a driver was assigned. Result query endpoints or push notifications are deferred to subsequent architectural phases.

#### Request Schema (`RideRequestDto`)

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `requestId` (or `id`) | `String` | Yes | Non-blank request identifier (e.g., `"REQ-101"`). |
| `latitude` | `Double` | Yes | Finite pickup latitude between `-90.0` and `90.0`. |
| `longitude` | `Double` | Yes | Finite pickup longitude between `-180.0` and `180.0`. |

#### Example: Ride Request Submission

**Request:**
```http
POST /request HTTP/1.1
Content-Type: application/json

{
  "requestId": "REQ-101",
  "latitude": 17.3860,
  "longitude": 78.4870
}
```

**Response (202 Accepted):**
```json
{
  "requestId": "REQ-101",
  "message": "Ride request accepted and queued for matching"
}
```

---

## 4. Matching Strategy & Matcher Selection

Matching is executed asynchronously by `RideRequestConsumer`. The matching engine utilizes Spring bean configuration in `com.aagosh.dispatch.config.DispatchConfig`:

- **Default Matcher:** `AuctionMatcher` is registered with `@Fallback` and `@ConditionalOnMissingBean`.
- **Custom Matcher Injection:** If an alternative `Matcher` bean (e.g., `GreedyMatcher`) is supplied:
  ```java
  @Configuration
  public class CustomDispatchConfig {
      @Bean
      public Matcher customMatcher() {
          return new GreedyMatcher();
      }
  }
  ```
  Spring injects the custom matcher into `RideRequestConsumer` without ambiguity.

### Available Matchers
1. **`AuctionMatcher` (Default):**
   - Implements Bertsekas' Auction Algorithm for bipartite matching with $\varepsilon$-scaling.
   - Computes global cost-optimal assignments across batched candidates.
2. **`GreedyMatcher`:**
   - Evaluates candidate drivers in increasing order of cost using a progressive spatial window.
   - Selects the single lowest-cost available driver.

---

## 5. Dispatch Cost Model & Configuration (`DispatchCostConfig`)

Candidate drivers are evaluated using `DispatchCostFunction`:

$$\text{Total Cost} = w_{\text{eta}} \cdot \text{Normalized ETA} + w_{\text{density}} \cdot \text{Normalized Density} + w_{\text{opportunity}} \cdot \text{Normalized Opportunity}$$

| Parameter | Type | Default Value | Description |
| :--- | :--- | :--- | :--- |
| `wEta` | `double` | `1.0` | Weight applied to normalized travel ETA. |
| `wDensity` | `double` | `1.0` | Weight applied to normalized competing request density. |
| `wOpportunity` | `double` | `1.0` | Weight applied to normalized opportunity cost ($0.0$ placeholder). |
| `avgSpeedKmh` | `double` | `60.0` | Assumed vehicle velocity ($km/h$). |
| `maxEtaHours` | `double` | `0.5` | Reference maximum ETA ($30\text{ min}$) for $[0, 1]$ clamping. |
| `densityRadiusKm` | `double` | `1.0` | Radius ($km$) around driver for counting competing requests. |
| `maxDensityCount` | `double` | `10.0` | Reference maximum competing requests for $[0, 1]$ clamping. |

---

## 6. Error Handling & Broker Failures

### 6.1 Validation Errors (HTTP 400 Bad Request)
Synchronous validation errors prevent publishing to Kafka:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "latitude must be a finite number between -90.0 and 90.0"
}
```

### 6.2 Broker Unavailability (HTTP 503 Service Unavailable)
If the Kafka broker is offline or fails to acknowledge within 5 seconds:

```json
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "Event could not be published; the message broker is temporarily unavailable"
}
```

---

## 7. Delivery Semantics, Idempotency, and Limitations

1. **At-Least-Once Delivery:** Kafka ensures at-least-once delivery. Redelivered messages may occur on network partitions or consumer restarts.
2. **In-Memory Idempotency (Ride Consumer):** `RideRequestConsumer` maintains a bounded in-memory `ConcurrentHashMap` set of processed `requestId`s (up to 10,000 entries). Duplicate requests within the running process are acknowledged without re-matching.
3. **Restart Limitation:** The in-memory idempotency set does not survive application restarts. Durable, database-backed idempotency is deferred to Phase 5 Step 2.
4. **Manual Acknowledgment:** `AckMode.MANUAL_IMMEDIATE` is used. Offsets are committed only after successful processing. On failure, the error handler retries twice at 1-second intervals before advancing the offset.
5. **Local Kafka Startup:**
   ```bash
   docker compose up -d kafka
   mvn spring-boot:run
   ```
