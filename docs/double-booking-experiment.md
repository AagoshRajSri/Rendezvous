# Double-Booking Experiment

> ⚠️ **Educational experiment — branch `experiment/double-booking` only.**
> The unsafe code lives exclusively in the test source set and must never be
> merged into `main`.

---

## 1. What problem does this demonstrate?

A _double-booking_ occurs when two concurrent ride requests are both told
that they have successfully been assigned the same driver.

This is a classic _lost-update_ race condition caused by a **check-then-set**
compound operation that is not performed atomically:

```
if (status == AVAILABLE) {          // ← check: read status
    // ... thread gets preempted here ...
    status = CLAIMED;               // ← set: write status
    return true;                    // ← both threads return true
}
```

---

## 2. The race condition in detail

### Thread interleaving that causes the bug

| Wall-clock order | Thread A (request-1)           | Thread B (request-2)           |
|-----------------|-------------------------------|-------------------------------|
| t=0             | reads status → **AVAILABLE**  |                               |
| t=1             |                               | reads status → **AVAILABLE**  |
| t=2             | writes status ← **CLAIMED**   |                               |
| t=3             |                               | writes status ← **CLAIMED**   |
| t=4             | returns **true** ✓            |                               |
| t=5             |                               | returns **true** ✓            |

**Result:** Two requests both received `true`. Both believe they own the
driver. One ride will be dispatched to a driver who was already sent to
another pickup — a real-world customer safety incident.

### Why `volatile` alone doesn't fix it

`UnsafeDriverState` uses a `volatile` field for the status. `volatile`
provides visibility (each thread always reads the latest value) but **not
atomicity** of compound operations. The check-then-set is still two
separate instructions and the preemption window remains open.

---

## 3. Why CAS makes the transition atomic

The production `DriverState` uses `AtomicReference<State>` with
`compareAndSet`:

```java
State current = state.get();
if (current.status() != MatchStatus.AVAILABLE) return false;

State claimed = new State(MatchStatus.CLAIMED, requestId);
return state.compareAndSet(current, claimed);
//           ↑ atomic: succeeds ONLY if state == current at this exact moment
```

Java's `AtomicReference.compareAndSet` guarantees atomicity: only one thread's CAS will observe the expected value unchanged and succeed; all concurrent CAS calls with the same witness value will fail.

### Thread interleaving with CAS

| Wall-clock order | Thread A (request-1)                        | Thread B (request-2)                        |
|-----------------|---------------------------------------------|---------------------------------------------|
| t=0             | reads current → `{AVAILABLE, null}`        |                                             |
| t=1             |                                             | reads current → `{AVAILABLE, null}`        |
| t=2             | CAS(`{AVAILABLE}` → `{CLAIMED}`) → **✓**  |                                             |
| t=3             |                                             | CAS(`{AVAILABLE}` → `{CLAIMED}`) → **✗** (value changed) |
| t=4             | returns **true** ✓                          |                                             |
| t=5             |                                             | returns **false** ✗                         |

**Result:** Exactly one thread wins. The loser receives `false` and the
caller signals HTTP 409 Conflict to the client.

---

## 4. Experiment setup

### Key files

| File | Role |
|------|------|
| `src/test/java/.../experiment/UnsafeDriverState.java` | Broken check-then-set implementation (experiment branch only) |
| `src/test/java/.../experiment/DoubleBookingExperimentTest.java` | JUnit 5 experiment: asserts broken invariant on unsafe, single-winner invariant on safe |

### Artificial delay

`UnsafeDriverState` introduces a 5 ms `Thread.sleep` between the check and
the set. This widening of the vulnerability window is necessary only to make
the race _reliably_ reproducible in a single-machine unit test. In
production, an OS scheduler preemption between any two bytecodes is
sufficient to trigger the same bug under load — no sleep is needed.

---

## 5. Experiment results

### Unsafe (check-then-set)

```
Run 1: winners = 2  ← double-booking confirmed
Run 2: winners = 2  ← double-booking confirmed
Run 3: winners = 2  ← double-booking confirmed
Run 4: winners = 2  ← double-booking confirmed
Run 5: winners = 2  ← double-booking confirmed
```

With 8 threads the winner count exceeds 2, showing the bug scales with
concurrency.

### Safe (CAS — production DriverState)

```
Run 1: winners = 1  ✓
Run 2: winners = 1  ✓
Run 3: winners = 1  ✓
(16-thread rounds) winners = 1 on every run  ✓
```

---

## 6. Limitations of this experiment

| Limitation | Explanation |
|-----------|-------------|
| Artificial sleep required | On a single machine, threads may not interleave without the explicit gap. Real servers under load achieve the same interleaving without any sleep. |
| Single-machine JVM | Distributed systems introduce additional failure modes (partial failures, network partitions) that a single-JVM test cannot reproduce. |
| In-memory only | The experiment targets the in-memory `DriverState`. A database-backed implementation would need a different concurrency mechanism (e.g., `SELECT FOR UPDATE` or an optimistic version column). |
| No retry logic | The safe experiment asserts a single winner but doesn't demonstrate how the losing thread's caller should handle `false` (it should return HTTP 409). |

---

## 7. How to run

```bash
# Checkout the experiment branch to run tests
git checkout experiment/double-booking

# Run the entire experiment class
mvn test -pl . \
  -Dtest=DoubleBookingExperimentTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# Run only the unsafe sub-suite
mvn test -pl . \
  -Dtest="DoubleBookingExperimentTest\$UnsafeExperiment" \
  -Dsurefire.failIfNoSpecifiedTests=false

# Run only the safe sub-suite
mvn test -pl . \
  -Dtest="DoubleBookingExperimentTest\$SafeVerification" \
  -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 8. Relationship to production code

The production `DriverState.tryClaim` has been correct since the initial
Phase 4 Step 1 implementation. This experiment exists to make the
correctness argument concrete:

- [`DriverState.java`](../src/main/java/com/aagosh/dispatch/domain/DriverState.java) — production CAS implementation
- [`DriverRegistry.java`](../src/main/java/com/aagosh/dispatch/domain/DriverRegistry.java) — uses the shared `DriverState` across location updates
- [`RideController.java`](../src/main/java/com/aagosh/dispatch/api/RideController.java) — HTTP layer; returns 409 when `tryClaim` returns `false`
- [`RideControllerTest.java`](../src/test/java/com/aagosh/dispatch/api/RideControllerTest.java) — HTTP-level 16-thread stress test confirming single-winner at the API surface

---

> **Do not merge this branch into `main`.** The `UnsafeDriverState` class is
> deliberately broken and belongs only in this educational experiment.
