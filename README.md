# Real-Time Ride Dispatch Matching Engine

A high-performance, concurrency-safe dispatch matching service implemented in Java 17 and Spring Boot. The system models a two-sided mobility platform with asynchronous event ingestion via Apache Kafka for high-throughput driver location telemetry and ride requests, maintains spatial indexes, computes multi-objective dispatch costs, and solves the driver-rider bipartite assignment problem while preventing double-booking races under extreme concurrency.

---

## 1. Problem Statement & Architecture

In real-time ride-hailing networks, dispatching rides involves four competing requirements:
1. **Asynchronous High-Throughput Ingestion:** Decoupling HTTP submission from matching computation using Kafka message queues, returning immediate `202 Accepted` receipts to clients.
2. **Low Latency & Spatial Indexing:** Finding candidate drivers near a pickup location without full fleet $O(N)$ linear scans.
3. **Global Assignment Optimality:** Minimizing deadhead travel time and balancing fleet distribution rather than greedily consuming suboptimal drivers.
4. **Strict Concurrency Safety:** Guaranteeing that under thousands of concurrent dispatch requests, no driver can ever be double-booked to two distinct ride requests (lost-update anomaly), and driver location telemetry updates never overwrite or clear active ride claims.

### System Architecture

```mermaid
graph TD
    subgraph Clients["API Clients & Mobile Drivers"]
        Ping["POST /ping (Telemetry)"]
        Reg["POST /register (Onboard)"]
        Req["POST /request (Ride Match)"]
    end

    subgraph API["REST Producer Layer (Spring Boot Web)"]
        DC["DriverController (Producer)"]
        RC["RideController (Producer)"]
        GEH["GlobalExceptionHandler (HTTP 400/503/500)"]
    end

    subgraph Kafka["Event Streaming (Apache Kafka)"]
        TP1["Topic: driver-pings (Key: driverId)"]
        TP2["Topic: ride-requests (Key: requestId)"]
    end

    subgraph Messaging["Consumer Layer"]
        DPC["DriverPingConsumer"]
        RRC["RideRequestConsumer (In-Memory Idempotency)"]
    end

    subgraph Domain["In-Memory Domain & Concurrency"]
        DR["DriverRegistry (ConcurrentHashMap)"]
        DS["DriverState (AtomicReference CAS)"]
    end

    subgraph Matching["Spatial & Matching Engine"]
        QT["QuadTree (Spatial 2D Index)"]
        CF["DispatchCostFunction (Multi-Objective)"]
        AM["AuctionMatcher (Bertsekas ε-Scaling)"]
        GM["GreedyMatcher (Progressive Window)"]
    end

    Ping --> DC
    Reg --> DC
    Req --> RC
    DC -->|Publish| TP1
    RC -->|Publish| TP2
    TP1 -->|Consume| DPC
    TP2 -->|Consume| RRC
    DPC --> DR
    DR --> DS
    RRC --> DR
    RRC --> AM
    RRC --> GM
    AM --> QT
    AM --> CF
```

---

## 2. Prerequisites & Build Commands

- **Java Development Kit (JDK):** Version 17 or higher
- **Build Tool:** Apache Maven 3.9+ (or the included `./mvnw` / `mvnw.cmd` wrapper)
- **Spring Boot:** 4.1.1
- **Apache Kafka:** Local broker or Docker container (see below)

### Local Infrastructure Startup (PostgreSQL & Kafka)

To start PostgreSQL and Kafka locally via Docker Compose:

```bash
# Start PostgreSQL (port 5432) and Kafka KRaft broker (port 9092)
docker compose up -d postgres kafka

# Check service health
docker compose ps
```

### Build & Run

```bash
# Compile and package application
./mvnw clean package

# Run the Spring Boot application locally (starts on http://localhost:8080)
./mvnw spring-boot:run
```

### Running Tests

```bash
# Run unit, concurrency, controller, consumer, and persistence tests (261 tests)
./mvnw clean test

# Run PostgreSQL integration tests using Testcontainers (requires Docker)
./mvnw test -Dtest=AssignmentPersistenceServiceIT
```
*Note: If Docker is unavailable, Testcontainers tests automatically detect its absence (`disabledWithoutDocker = true`) and skip cleanly without failing the build.*

---

## 3. Package & Module Structure

The project is structured under the root package `com.aagosh.dispatch`:

```
src/main/java/com/aagosh/dispatch/
├── DispatchApplication.java            # Spring Boot application bootstrap
├── api/                                # REST controllers (Kafka producers) and DTOs
│   ├── DriverController.java          # REST producer for /ping and /register (202 Accepted)
│   ├── RideController.java            # REST producer for /request (202 Accepted)
│   ├── DriverPingRequest.java         # Driver telemetry payload DTO
│   ├── DriverPingAcceptedResponse.java# Async 202 response for telemetry
│   ├── RideRequestDto.java            # Ride match request payload DTO
│   ├── RideAcceptedResponse.java      # Async 202 response for ride submission
│   ├── KafkaPublishException.java     # Exception mapped to HTTP 503 on broker failure
│   ├── ApiErrorResponse.java          # RFC 7807-style error payload DTO
│   └── GlobalExceptionHandler.java    # @RestControllerAdvice for validation & runtime exceptions
├── config/                             # Spring configuration and bean wiring
│   ├── DispatchConfig.java            # Fallback beans for Matcher and DispatchCostConfig
│   ├── KafkaConfig.java               # KafkaAdmin, ProducerFactory, ConsumerFactory, Topics
│   └── KafkaTopics.java               # Central constants for topic names
├── domain/                             # Core domain entities and concurrency primitives
│   ├── Driver.java                    # Driver record with ID, GeoPoint, and DriverState
│   ├── DriverRegistry.java            # Thread-safe in-memory registry (ConcurrentHashMap)
│   ├── DriverState.java               # AtomicReference-backed CAS claiming state machine
│   ├── GeoPoint.java                  # Validated coordinate record (lat/lon)
│   ├── MatchStatus.java               # Enum: AVAILABLE, CLAIMED, UNAVAILABLE
│   └── RideRequest.java               # Ride request domain record
├── matching/                           # Dispatch cost calculation and matching algorithms
│   ├── Assignment.java                # Immutable match pair (Driver, RideRequest, cost)
│   ├── AuctionMatcher.java            # Bertsekas' Auction Algorithm with ε-scaling
│   ├── DispatchCostConfig.java        # Immutable cost function weights and normalizers
│   ├── DispatchCostFunction.java      # Multi-objective normalized cost calculator
│   ├── GreedyMatcher.java             # Progressive-radius nearest-neighbor matcher
│   └── Matcher.java                   # Core matching strategy interface
├── messaging/                          # Kafka consumers
│   ├── DriverPingConsumer.java        # Consumes driver-pings, updates DriverRegistry
│   └── RideRequestConsumer.java       # Consumes ride-requests, matches & persists atomically
├── persistence/                        # Durable state & transactional persistence (Phase 5 Step 2)
│   ├── AssignmentEntity.java          # JPA entity for assignments table
│   ├── AssignmentPersistenceService.java # Transactional boundary, terminal status & claim reconciliation
│   ├── AssignmentRepository.java      # Spring Data JPA repository for assignments
│   ├── PersistOutcome.java            # Outcome enum (PERSISTED, DUPLICATE, FAILURE)
│   ├── RideRequestEntity.java         # JPA entity for ride_requests table
│   └── RideRequestRepository.java     # Spring Data JPA repository for ride requests
└── spatial/                            # Spatial indexing and geometric primitives
    ├── BoundingBox.java               # Coordinate bounding box with intersection logic
    └── QuadTree.java                  # Hierarchical 2D spatial index for proximity pruning

Database Migrations:
src/main/resources/db/migration/
├── V1__create_ride_requests.sql        # ride_requests table with UNIQUE(request_id)
└── V2__create_assignments.sql         # assignments table with UNIQUE(request_id) & FK to ride_requests
```

---

## 4. End-to-End Request Flows

### Flow 1: Driver Ingestion & Telemetry (`POST /ping`)
1. Client submits driver ID and current GPS coordinates.
2. `DriverController` synchronously validates coordinates (`[-90, 90]`, `[-180, 180]`).
3. Payload is published to Kafka topic `driver-pings` using `driverId` as message key (preserving partition affinity).
4. On durable broker confirmation, HTTP `202 Accepted` is returned. If Kafka fails, HTTP `503 Service Unavailable` is returned.
5. `DriverPingConsumer` processes the event asynchronously:
   - Updates coordinates via atomic CAS loop on `AtomicReference<Driver>`.
   - Strictly preserves the authoritative `DriverState` instance, ensuring that telemetry never resets an active ride claim.

### Flow 2: Ride Dispatch Matching (`POST /request`)
1. Client submits pickup location and request ID.
2. `RideController` synchronously validates input and publishes to `ride-requests` with `requestId` as message key.
3. On broker confirmation, HTTP `202 Accepted` is returned.
4. `RideRequestConsumer` receives the event:
   - Checks the in-memory idempotency set (ignoring duplicate deliveries).
   - Snapshots registered drivers from `DriverRegistry`.
   - Invokes the configured `Matcher` (`AuctionMatcher` by default).
   - If an assignment is computed, claims the driver atomically via `DriverState.tryClaim(requestId)`.
   - Acknowledges the Kafka offset (`MANUAL_IMMEDIATE`).

---

## 5. Core Algorithms & Cost Model

### 5.1 Spatial Indexing (`QuadTree` & Geographic Distance)
- **Indexing:** A hierarchical 2D `QuadTree` indexes driver coordinates into quadrants (NW, NE, SW, SE).
- **Pruning:** Spatial range queries test bounding-box overlap before descending, avoiding full-fleet linear scans.
- **Distance Metric:** Great-circle geographic distance is computed using the spherical Haversine formula over WGS84 coordinates ($R = 6371.0088\text{ km}$).

### 5.2 Matching Strategies (`AuctionMatcher` vs. `GreedyMatcher`)
- **`AuctionMatcher` (Bertsekas Algorithm):**
  - Formulates the driver-request dispatch as an asymmetric assignment problem.
  - Drivers act as objects with economic "prices"; ride requests act as bidders computing net benefit $\text{value} - \text{price}$.
  - Uses $\varepsilon$-scaling to guarantee convergence to an almost-optimal global assignment while avoiding cycling in degenerate cost landscapes.
- **`GreedyMatcher`:**
  - Evaluates candidate drivers within expanding progressive spatial windows ($1\text{ km} \to 3\text{ km} \to 5\text{ km}$).
  - Assigns the single lowest-cost driver available.

### 5.3 Multi-Objective Cost Model (`DispatchCostFunction`)
The composite dispatch cost balances customer pickup time and supply preservation:

$$\text{Total Cost} = w_{\text{eta}} \cdot \text{Normalized ETA} + w_{\text{density}} \cdot \text{Normalized Density} + w_{\text{opportunity}} \cdot \text{Normalized Opportunity}$$

- **Normalized ETA:** Straight-line travel time ($\text{Haversine Distance} \div 60\text{ km/h}$), normalized over reference maximum ($30\text{ minutes}$) and clamped to $[0, 1]$.
- **Normalized Competition Density:** Count of unmatched ride pickup points within $1\text{ km}$ of the candidate driver, normalized by maximum density reference ($10\text{ requests}$) and clamped to $[0, 1]$.
- **Deferred Opportunity Cost:** Evaluates to neutral **`0.0`**. The current domain model deliberately omits trip destinations and pricing tiers. Fabricating artificial opportunity values would compromise dispatch validity. Setting $w_{\text{opportunity}} = 0.0$ is supported and recommended; $w_{\text{opportunity}} > 0$ is preserved for forward compatibility.

---

## 6. Concurrency Architecture & Delivery Semantics

### 6.1 Atomic Driver Claiming (CAS)
In high-demand scenarios, multiple ride requests simultaneously compete for the nearest available driver. The system enforces zero double-booking via `AtomicReference<State>` in `DriverState`:

```java
State current = state.get();
if (current.status() != MatchStatus.AVAILABLE) {
    return false;
}
State claimed = new State(MatchStatus.CLAIMED, requestId);
return state.compareAndSet(current, claimed);
```
Atomic compare-and-set (CAS) using Java's `AtomicReference` ensures that only one request wins the claim. Losing requests cleanly fail without partial writes.

### 6.2 Location Updates Preserving Driver State
When a driver's GPS device pings `/ping`, their location updates in memory:
- `DriverRegistry` updates the driver's location record using a CAS loop on `AtomicReference<Driver>`.
- The new `Driver` record reuses the existing `DriverState` reference.
- **Invariant:** A telemetry ping arriving while a driver is in the `CLAIMED` status **never** resets them back to `AVAILABLE`.

### 6.3 Durable Persistence & Database Idempotency (Phase 5 Step 2)
In Phase 5 Step 2, the dispatch service pairs in-memory speed with durable PostgreSQL persistence via Spring Data JPA and Flyway versioned migrations:
- **`ride_requests` Table:** Persists the `request_id` (with a database `UNIQUE` constraint `uq_ride_requests_request_id`), pickup coordinates, durable status (`PENDING`, `MATCHED`, or `NO_DRIVERS`), and audit timestamps.
- **`assignments` Table:** Persists generated primary key, `request_id` (foreign key to `ride_requests.request_id` and database `UNIQUE` constraint `uq_assignments_request_id`), `driver_id`, assignment `cost`, and `assigned_at` timestamp.
- **Flyway Authority:** Production schema migrations (`V1__create_ride_requests.sql`, `V2__create_assignments.sql`) own table definitions. Hibernate DDL auto-generation is disabled (`spring.jpa.hibernate.ddl-auto=validate`).

### 6.4 Kafka Redelivery & Two-Tier Idempotency Lifecycle
1. **Tier 1 (In-Memory Fast Path):** `RideRequestConsumer` reserves incoming `requestId` in `inFlightRequests` monitor set. If already in-flight or in the LRU `processedRequests` cache (10,000 entries), redeliveries immediately acknowledge and exit without re-entering the matching engine.
2. **Tier 2 (Durable Database State):** Before matching, the consumer inspects PostgreSQL (`AssignmentPersistenceService.getRequestStatus(requestId)`):
   - If status is `MATCHED`, the persisted assignment is confirmed and the redelivery is acknowledged as an idempotent duplicate.
   - If status is `NO_DRIVERS`, the terminal outcome is respected and acknowledged without rematching.
3. **Atomic Finalization:** When matching finds a driver, `persistMatchedAssignment` writes the assignment row and sets the request status to `MATCHED` within a single database transaction. When no driver is found, `persistNoDrivers` writes `NO_DRIVERS` transactionally.
4. **Acknowledgement Guarantee:** Kafka offset acknowledgement (`ack.acknowledge()`) occurs **strictly after** the database transaction has committed.

### 6.5 What `@Transactional` Does and Does Not Guarantee
- **What it Guarantees:** ACID atomicity across the PostgreSQL tables. If the assignment write or status update fails, the relational database transaction rolls back completely, leaving no orphan rows or corrupt state.
- **What it Does NOT Guarantee:** Spring's `@Transactional` proxy governs only relational database connections; it cannot roll back JVM heap memory, thread-local state, or in-memory `DriverState` CAS claims.

### 6.6 The In-Memory / Database Boundary & Claim Compensation
Because driver claiming occurs via atomic CAS in memory *before* the matching result is persisted to PostgreSQL, a database failure creates an asymmetry where a driver is claimed in memory but unpersisted in the database.

The engine resolves this gap through **Ownership-Safe Driver Claim Compensation**:
- **Atomic CAS Release:** `DriverState.releaseClaim(requestId)` releases a driver from `CLAIMED` back to `AVAILABLE` only if the driver's current claim is owned by the exact same `requestId`.
- **Ownership Invariant:** A late compensation attempt from an old or aborted request can **never** release a driver that was subsequently claimed by a different request.
- **Ambiguous Failure Reconciliation:** If an exception occurs during persistence (such as a network socket timeout on the commit response), the consumer does not blindly release the claim. Instead, it queries PostgreSQL (`findAssignment(requestId)`):
  - If the assignment committed with our driver, the driver remains claimed and the event is acknowledged as an idempotent duplicate.
  - If a competing concurrent delivery won with a different driver, our driver's claim is released and the event is acknowledged.
  - If no assignment exists, rollback is confirmed: the driver claim is released, the in-flight reservation is cleared, and the exception propagates so Kafka's retry policy can act.

### 6.7 Recovery Limitations & Residual Failure Modes
1. **Abrupt JVM Termination (Kill -9 / Power Failure):** If the JVM crashes after claiming a driver in memory but before PostgreSQL commits, the driver claim is lost from heap upon restart (fleet state rehydrates via incoming telemetry pings). If the JVM crashes after PostgreSQL commit but before Kafka offset acknowledgement, the subsequent Kafka redelivery detects the committed row in PostgreSQL and acknowledges without rematching.
2. **Multi-Instance Driver Registry Partitioning:** `DriverRegistry` currently maintains an in-memory snapshot per JVM instance. In a multi-node cluster, distributed driver locking or Redis spatial coordination is required to prevent cross-node matching contention.
3. **Exhausted Kafka Retries:** If transient database outages outlast configured consumer retries, unhandled exceptions route to Spring Kafka's `DefaultErrorHandler`. Production deployments should configure a Dead-Letter Topic (DLT) for manual inspection.

---

## 7. API Quick Start

### 7.1 Register Driver (`POST /register`)

```bash
curl -X POST http://localhost:8080/register \
  -H "Content-Type: application/json" \
  -d '{
    "driverId": "D101",
    "latitude": 17.385044,
    "longitude": 78.486671
  }'
```

**Response (`202 Accepted`):**
```json
{
  "driverId": "D101",
  "message": "Driver registration accepted and queued for processing"
}
```

### 7.2 Ingest Telemetry (`POST /ping`)

```bash
curl -X POST http://localhost:8080/ping \
  -H "Content-Type: application/json" \
  -d '{
    "driverId": "D101",
    "latitude": 17.389200,
    "longitude": 78.489100,
    "action": "AUTO"
  }'
```

**Response (`202 Accepted`):**
```json
{
  "driverId": "D101",
  "message": "Driver ping accepted and queued for processing"
}
```

### 7.3 Request Ride Match (`POST /request`)

```bash
curl -X POST http://localhost:8080/request \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "REQ-789",
    "latitude": 17.387000,
    "longitude": 78.488000
  }'
```

**Response (`202 Accepted`):**
```json
{
  "requestId": "REQ-789",
  "message": "Ride request accepted and queued for matching"
}
```

---

## 8. Testing & Verification

The project enforces comprehensive test coverage across unit, spatial, mathematical, concurrency, and REST/messaging layers.

```bash
./mvnw clean test
```

### Verified Test Suite Breakdown (261 Unit Tests Passing + 8 Integration Tests)

| Test Class | Category | Test Count | Scope Verified |
| :--- | :--- | :---: | :--- |
| `QuadTreeTest` | Spatial | 16 | Quadrant subdivision, boundary conditions, range query pruning |
| `BoundingBoxTest` | Spatial | 10 | Bounding-box intersection, coordinate clamping, containment |
| `DispatchCostFunctionTest` | Mathematical | 61 | Normalization clamping, weights, edge cases, Haversine formula |
| `AuctionMatcherTest` | Algorithm | 40 | Bertsekas auction matching, ε-scaling, optimality comparisons |
| `GreedyMatcherTest` | Algorithm | 15 | Progressive radius windows, nearest-neighbor driver selection |
| `DispatchIntegrationTest` | Integration | 9 | End-to-end matching integration across cost model and spatial index |
| `DriverStateTest` | Concurrency | 2 | Single-thread state machine transitions |
| `DriverStateConcurrencyTest` | Concurrency | 20 | Multi-threaded CAS claims, state preservation during updates |
| `DriverRegistryTest` | Concurrency | 24 | Concurrent registrations, CAS location updates, snapshot isolation |
| `GeoPointTest` | Validation | 8 | Latitude/longitude bounds, NaN/infinite rejection, error messages |
| `DriverControllerTest` | Kafka Producer | 5 | Endpoints `/ping`, `/register`, topic routing, 202 Accepted, 503 error |
| `RideControllerTest` | Kafka Producer | 5 | Endpoint `/request`, `requestId` key partitioning, 202 Accepted, 503 error |
| `AdditionalControllerTest` | Controller / Concurrency | 3 | HTTP 500 error handling, 16-thread Greedy claims, concurrent `/ping` auto-fallback |
| `DriverPingConsumerTest` | Kafka Consumer | 6 | REGISTER/UPDATE/AUTO handling, claim state preservation, duplicate idempotency |
| `KafkaDeserializationErrorHandlingTest` | Kafka Consumer | 4 | Deserialization error handling, malformed payload recovery, header validation |
| `RideRequestConsumerTest` | Kafka Consumer | 16 | Matching delegation, atomic claim verification, compensation, durable duplicate reconciliation |
| `AssignmentPersistenceServiceTest` | Persistence / JPA | 7 | Transactional boundaries, status persistence, CAS claim compensation, rollback propagation |
| `AssignmentPersistenceServiceIT` | Integration / DB | 8 | Testcontainers PostgreSQL integration (auto-skips when Docker is inactive) |
| `DispatchConfigTest` | Spring Boot | 3 | Bean fallback resolution and custom Matcher bean override |
| `DispatchApplicationTests` | Smoke / Context | 7 | ApplicationContext initialization and bean discovery |
| **Total** | | **269 Tests (261 Unit + 8 IT)** | **100% Passing (269/269 passing, 0 failures, 0 errors, 0 skipped)** |

---

## 9. Controlled Double-Booking Experiment

To demonstrate the concrete failure mode of non-atomic state updates, a reproducible concurrency experiment is maintained on the isolated branch `experiment/double-booking`.

- **Check-then-Set Vulnerability:** Demonstrates how non-atomic reads followed by writes cause two concurrent threads to both claim the same driver.
- **Atomic CAS Resolution:** Contrasts the vulnerability against production `DriverState.tryClaim` using Java's `AtomicReference`, proving 100% single-winner determinism under high concurrency.
- **Full Experiment Report:** See [docs/double-booking-experiment.md](docs/double-booking-experiment.md).

---

## 10. Architectural Status & Current Limitations

1. **Declared Architectural Services:** `docker-compose.yml` configures Apache Kafka for messaging and PostgreSQL for durable state and assignment persistence in Phase 5 Step 2. Redis remains a blueprint stub for future distributed caching milestones.
2. **Two-Tier Idempotency Lifecycle:** Combines fast in-memory LRU filtering (10,000 entries) with durable PostgreSQL queries (`ride_requests` and `assignments` tables). On application restart, durable database state guarantees that already-matched requests or terminal outcomes are never rematched.
3. **Absence of Road Network Routing:** ETA estimates are derived using spherical Haversine straight-line distance at a nominal $60\text{ km/h}$. Turn penalties, one-way streets, and real-time traffic congestion are not modeled.
4. **Single-Node In-Memory Footprint:** The spatial index and driver registry reside entirely in JVM heap. Scaling across multiple replicas requires distributed spatial indexing (e.g., Uber H3 or Redis GEO) and distributed consensus.
5. **Driver Lifecycle Post-Match:** A successfully matched driver transitions to `CLAIMED` in memory and remains claimed until a future trip-completion or driver-availability operation explicitly releases the driver back to `AVAILABLE`.

---

## 11. Reference Documentation

- [REST API Specification](API_DOCUMENTATION.md) — Complete endpoint schemas, parameter matrices, error response examples, and Kafka event ingestion details.
- [Double-Booking Experiment Documentation](docs/double-booking-experiment.md) — In-depth concurrency race condition analysis and atomic CAS verification using Java's `AtomicReference`.
