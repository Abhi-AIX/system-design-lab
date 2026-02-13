
# Event Bus v1 — Design, Reasoning, and Interview Notes

## 1. Goal of the Event Bus

The Event Bus is a **simple append-only log** that enables asynchronous communication between services.

Its purpose is **not** to:

* route messages
* guarantee exactly-once delivery
* understand business logic
* manage consumers

Its purpose **is** to:

* store events in order
* assign deterministic offsets
* allow replay from any offset
* remain dumb and reliable

This mirrors the *behavior* of systems like Kafka without infrastructure complexity.

---

## 2. Core Design Principles (Invariants)

These rules are **always true**:

1. **Append-only**

    * Events can never be updated
    * Events can never be deleted

2. **Ordered log**

    * Events are stored in the exact order they are appended
    * Order is deterministic and replayable

3. **Offsets are immutable**

    * Offset represents position in the log
    * Offset never changes once assigned

4. **Event Bus does not track consumers**

    * No consumer offsets stored
    * No deduplication
    * No delivery guarantees beyond at-least-once

5. **Correctness lives in consumers**

    * Consumers must handle duplicates
    * Consumers must track their own progress

---

## 3. Core Domain Model

### 3.1 Event (Business Fact)

An `Event` represents **something that already happened**.

It contains:

* `eventId` — unique identifier (UUID)
* `type` — e.g. OrderCreated, PaymentFailed
* `key` — business identifier (orderId, userId)
* `payload` — generic JSON data
* `createdAt` — timestamp

An Event:

* does NOT know its offset
* does NOT know its topic
* does NOT know where it is stored

This keeps business meaning separate from infrastructure.

---

### 3.2 com.event_bus.dto.EventRecord (Storage Wrapper)

An `com.event_bus.dto.EventRecord` represents how an Event is stored inside the bus.

It contains:

* `offset` — position in the topic log
* `event` — the actual Event

This separation ensures:

* business logic does not leak infrastructure details
* replay and storage metadata remain internal

---

## 4. Storage Model

### 4.1 High-Level Structure

```java
import com.event_bus.service.TopicLog;

Map<String, TopicLog>
```

Where:

* Key = topic name (e.g. "orders")
* Value = com.event_bus.service.TopicLog (log + lock)

This allows:

* independent ordering per topic
* independent locking per topic
* scalability without global contention

---

### 4.2 com.event_bus.service.TopicLog Responsibilities

Each `com.event_bus.service.TopicLog`:

* holds an ordered list of EventRecords
* assigns offsets
* protects append and read with a lock

Internal structure:

```java
import com.event_bus.dto.EventRecord;

List<EventRecord> events
ReentrantLock lock
```

Encapsulation ensures:

* append-only invariant is preserved
* offsets cannot be corrupted
* external code cannot mutate the log

---

## 5. Concurrency Design

### 5.1 Why Locking Is Required

Without locking, concurrent appends can:

* assign duplicate offsets
* break ordering guarantees

Concurrent reads during append can:

* see partial state
* throw runtime exceptions
* read inconsistent data

### 5.2 Locking Strategy

* **One lock per topic**
* Append and read both acquire the lock

Benefits:

* Correctness over performance
* Parallelism across topics
* No global bottleneck

This is conceptually similar to **partition-level locking** in real systems.

---

## 6. Append Semantics

Append operation:

```java
append(topic, event) → offset
```

Rules:

* Offset is computed inside the lock
* Offset = current size of log
* Event is appended immediately after
* Append is atomic

Append behavior:

* Accepts duplicates
* Accepts repeated eventIds
* Never rejects based on semantics

This ensures:

* simplicity
* replay safety
* producer retries are safe

---

## 7. Read (Replay) Semantics

Read operation:

```java
import com.event_bus.dto.EventRecord;

read(topic, fromOffset, limit) →List<EventRecord>
```

Rules:

| Condition              | Behavior                |
| ---------------------- | ----------------------- |
| fromOffset < 0         | Error (invalid request) |
| fromOffset >= log size | Empty list              |
| limit <= 0             | Empty list              |
| normal case            | Sublist                 |

Important decisions:

* Reading beyond end is NOT an error
* Empty response means "caught up"
* Read never mutates internal state

Returned list is a **copy**, not a view, to preserve thread safety.

---

## 8. Offset vs Consumer Offset (Critical Distinction)

### Event Offset

* Belongs to the Event Bus
* Represents position in log
* Immutable
* Derived from storage

### Consumer Offset

* Belongs to consumer
* Tracks last processed event
* Stored by consumer
* Used for resume

If consumerOffset = 5
Next event to process = offset 6

This distinction prevents many real-world bugs.

---

## 9. Delivery Semantics

The Event Bus provides:

* **At-least-once delivery**

Meaning:

* Events may be delivered multiple times
* Duplicates are expected
* Consumers must be idempotent

The bus intentionally does NOT provide:

* exactly-once delivery
* deduplication
* consumer progress tracking

This keeps the system simple and scalable.

---

## 10. What This Design Intentionally Does NOT Include

Out of scope for v1:

* partitions
* replication
* durability
* consumer groups
* retention policies
* schema registry

These are infrastructure optimizations, not core behavior.

---

## 11. Common Interview Questions & Answers

### Q1: Why is the event log append-only?

**Answer:**
To preserve history integrity and enable deterministic replay. Updating or deleting events breaks offsets and consumer recovery.

---

### Q2: Why use offsets instead of timestamps?

**Answer:**
Offsets provide strict ordering and deterministic replay. Timestamps can collide, drift, and cannot guarantee order.

---

### Q3: Why does the event bus allow duplicate events?

**Answer:**
Because retries and failures are normal in distributed systems. Deduplication requires business knowledge and belongs in consumers.

---

### Q4: Why doesn’t the event bus track consumer offsets?

**Answer:**
Tracking consumers increases complexity and coupling. Consumers are responsible for their own progress.

---

### Q5: Why lock per topic instead of globally?

**Answer:**
To allow parallelism across topics while preserving ordering within a topic.

---

### Q6: What happens if a consumer crashes after processing an event?

**Answer:**
On restart, it may reprocess the same event. Consumers must be idempotent.

---

## 12. Key Takeaway

This Event Bus is intentionally:

* simple
* dumb
* predictable
* breakable

It teaches **behavior**, not infrastructure.

Understanding this design makes Kafka, cloud messaging systems, and distributed architectures intuitive instead of magical.

