# Dispatch Service REST API Documentation

This document specifies the REST API endpoints exposed by the dispatch matching service (`com.aagosh.dispatch.api`).

---

## 1. Driver Endpoints

### 1.1 Ingest Location Telemetry / Ping

- **URL:** `POST /ping`
- **Content-Type:** `application/json`
- **Description:** Ingests driver location telemetry. By default (`action: "AUTO"` or omitted), registers a new driver if absent, or atomically updates the driver's coordinates if already registered while strictly preserving the driver's authoritative `DriverState` (and any active ride claim).
- **Caveat on Telemetry Ordering:** **Concurrent location updates have no chronological or event-time ordering guarantee.** Coordinates are updated via an atomic CAS loop on memory references. If network packets arrive out of order, the last update to succeed its CAS will commit as the active location. Sequence numbers or event timestamps are deferred to subsequent phases.

#### Request Schema (`DriverPingRequest`)

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `driverId` | `String` | Yes | Non-blank driver identifier (e.g., `"D1"`). |
| `latitude` | `Double` | Yes | Finite latitude between `-90.0` and `90.0`. |
| `longitude` | `Double` | Yes | Finite longitude between `-180.0` and `180.0`. |
| `action` | `String` | No | Optional action mode: `"AUTO"` (default), `"UPDATE"`, or `"REGISTER"`. |

#### Example 1: Location Update (Existing Driver)

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

**Response (200 OK):**
```json
{
  "driverId": "D1",
  "status": "UPDATED",
  "latitude": 17.3850,
  "longitude": 78.4867,
  "matchStatus": "AVAILABLE",
  "message": "Driver location updated successfully"
}
```

#### Example 2: Automatic Registration (New Driver)

**Request:**
```http
POST /ping HTTP/1.1
Content-Type: application/json

{
  "driverId": "D2",
  "latitude": 17.4400,
  "longitude": 78.3489
}
```

**Response (201 Created):**
```json
{
  "driverId": "D2",
  "status": "REGISTERED",
  "latitude": 17.4400,
  "longitude": 78.3489,
  "matchStatus": "AVAILABLE",
  "message": "Driver registered successfully"
}
```

#### Example 3: Explicit Update for Unknown Driver

**Request:**
```http
POST /ping HTTP/1.1
Content-Type: application/json

{
  "driverId": "D_UNKNOWN",
  "latitude": 17.4400,
  "longitude": 78.3489,
  "action": "UPDATE"
}
```

**Response (404 Not Found):**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "No driver registered with ID: D_UNKNOWN"
}
```

---

### 1.2 Explicit Driver Registration

- **URL:** `POST /register`
- **Content-Type:** `application/json`
- **Description:** Explicitly registers a new driver with an initial location. Returns HTTP 201 on success, or HTTP 409 Conflict if the driver ID is already registered.

#### Example 1: Successful Registration

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

**Response (201 Created):**
```json
{
  "driverId": "D10",
  "status": "REGISTERED",
  "latitude": 17.3900,
  "longitude": 78.4900,
  "matchStatus": "AVAILABLE",
  "message": "Driver registered successfully"
}
```

#### Example 2: Duplicate Registration Error

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

**Response (409 Conflict):**
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Driver already registered: D10"
}
```

---

## 2. Ride Dispatch Endpoint

### 2.1 Request a Ride Match

- **URL:** `POST /request`
- **Content-Type:** `application/json`
- **Description:** Evaluates available fleet drivers against the ride request pickup location using the injected `Matcher` (`AuctionMatcher` by default) and normalized `DispatchCostFunction`. If a driver is assigned, the driver is atomically claimed with `MatchStatus.CLAIMED`.

#### Request Schema (`RideRequestDto`)

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `requestId` (or `id`) | `String` | Yes | Non-blank request identifier (e.g., `"REQ-101"`). |
| `latitude` | `Double` | Yes | Finite pickup latitude between `-90.0` and `90.0`. |
| `longitude` | `Double` | Yes | Finite pickup longitude between `-180.0` and `180.0`. |

#### Example 1: Successful Match

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

**Response (200 OK):**
```json
{
  "requestId": "REQ-101",
  "matched": true,
  "driverId": "D1",
  "driverLatitude": 17.3850,
  "driverLongitude": 78.4867,
  "driverStatus": "CLAIMED",
  "cost": 0.04523,
  "message": "Driver assigned successfully"
}
```

#### Example 2: No-Match Outcome (Empty Fleet or All Claimed)

**Request:**
```http
POST /request HTTP/1.1
Content-Type: application/json

{
  "requestId": "REQ-102",
  "latitude": 17.3860,
  "longitude": 78.4870
}
```

**Response (200 OK):**
```json
{
  "requestId": "REQ-102",
  "matched": false,
  "driverId": null,
  "driverLatitude": null,
  "driverLongitude": null,
  "driverStatus": null,
  "cost": null,
  "message": "No available driver could be matched"
}
```

---

## 3. Error Handling and Validation

All validation and parsing errors are intercepted by `GlobalExceptionHandler` and returned as standard `ApiErrorResponse` objects with HTTP 400 Bad Request.

#### Example: Invalid Latitude

**Request:**
```http
POST /request HTTP/1.1
Content-Type: application/json

{
  "requestId": "REQ-999",
  "latitude": 95.0,
  "longitude": 78.0
}
```

**Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "latitude must be a finite number between -90.0 and 90.0"
}
```

#### Example: Missing Request ID

**Request:**
```http
POST /request HTTP/1.1
Content-Type: application/json

{
  "latitude": 17.3850,
  "longitude": 78.4867
}
```

**Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "requestId must not be null or blank"
}
```

#### Example: Malformed JSON Syntax

**Request:**
```http
POST /ping HTTP/1.1
Content-Type: application/json

{invalid json}
```

**Response (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Malformed or unreadable JSON request body"
}
```
