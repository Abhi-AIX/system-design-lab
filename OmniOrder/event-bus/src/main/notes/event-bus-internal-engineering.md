# Event Bus Implementation: Internal Engineering Documentation

**Author**: Senior Systems Engineer  
**Audience**: Backend developers building distributed systems  
**Purpose**: Deep technical understanding of event bus fundamentals  
**Date**: February 2026

---

## Table of Contents

1. [What is an Event Bus?](#1-what-is-an-event-bus)
2. [What is a Topic?](#2-what-is-a-topic)
3. [What is an Offset?](#3-what-is-an-offset)
4. [Why Offsets Over Timestamps?](#4-why-offsets-over-timestamps)
5. [At-Least-Once Delivery](#5-at-least-once-delivery)
6. [Why Duplicates Are Acceptable](#6-why-duplicates-are-acceptable)
7. [Why the Event Bus Must Stay "Dumb"](#7-why-the-event-bus-must-stay-dumb)
8. [Event vs com.event_bus.dto.EventRecord](#8-event-vs-eventrecord)
9. [What We Are NOT Implementing](#9-what-we-are-not-implementing)
10. [Comparison to Apache Kafka](#10-comparison-to-apache-kafka)

---

## 1. What is an Event Bus?

### Conceptual Definition

An **Event Bus** is a **temporal decoupling mechanism** that breaks direct synchronous dependencies between producers and consumers. Instead of Service A calling Service B directly (RPC/REST), Service A writes facts to an immutable log, and Service B reads those facts at its own pace.

**Key Mental Model**: Think of it as a **persistent message queue with replayability**. Unlike traditional queues (RabbitMQ) where messages disappear after consumption, an event bus retains events, allowing:
- Multiple consumers to read the same event
- Consumers to replay history
- New consumers to bootstrap from historical data

### The Fundamental Contract

```
Producer --> [Event Bus] --> Consumer(s)
     |                            |
     |                            |
  Writes                      Reads
  "Fire and Forget"          "Poll when ready"
  (No blocking)              (No push)
```

**Core Guarantees**:
1. **Durability**: Once written, events survive producer crashes
2. **Ordering**: Events within a topic maintain append order
3. **Immutability**: Events cannot be modified or deleted (append-only)
4. **Replayability**: Consumers control their read position

### ASCII: Event Bus Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        EVENT BUS                            │
│                                                             │
│  ┌────────────────────────────────────────────────────┐   │
│  │ Topic: "order.created"                             │   │
│  │                                                     │   │
│  │ Offset 0: {orderId: 1, amount: 100, ts: ...}      │   │
│  │ Offset 1: {orderId: 2, amount: 200, ts: ...}      │   │
│  │ Offset 2: {orderId: 3, amount: 150, ts: ...}      │   │
│  │ Offset 3: {orderId: 4, amount: 300, ts: ...}      │   │
│  │           ▲ Append point (new events go here)     │   │
│  └────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌────────────────────────────────────────────────────┐   │
│  │ Topic: "payment.processed"                         │   │
│  │                                                     │   │
│  │ Offset 0: {paymentId: 501, status: "success"}     │   │
│  │ Offset 1: {paymentId: 502, status: "failed"}      │   │
│  │           ▲ Append point                           │   │
│  └────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
        ▲                                       │
        │                                       │
    Producer                               Consumer
    (Appends)                            (Polls from offset)
```

### Our Simple Implementation

```
// Conceptual storage model
Map<String, List<com.event_bus.dto.EventRecord>> topics = new ConcurrentHashMap<>();

// Publishing: O(1) append
topics.computeIfAbsent("order.created", k -> new ArrayList<>())
      .add(new com.event_bus.dto.EventRecord(event, timestamp, metadata));

// Consuming: Read from offset N
List<com.event_bus.dto.EventRecord> events = topics.get("order.created");
for (int i = consumerOffset; i < events.size(); i++) {
    process(events.get(i));
}
```

**What this simulates**:
- ✅ Append-only log structure
- ✅ Topic isolation
- ✅ Ordered event sequence
- ✅ Consumer-controlled offset

**What production systems add**:
- Disk persistence (we're in-memory only)
- Replication across nodes
- Log segmentation (splitting logs into chunks)
- Compaction (removing old events for same key)
- Access control and authentication
- Metrics and monitoring

### Failure Example: Why Event Bus vs Direct HTTP

**Scenario**: Order Service → Inventory Service

**Direct HTTP Approach**:
```
Order Service              Inventory Service
     |                            |
     |---HTTP POST /reserve------>| (Inventory is down)
     |<------503 Error-----------| 
     |                            |
     | What now?                  |
     | - Retry? How many times?   |
     | - Cache the request?       |
     | - Block the user?          |
```

**Event Bus Approach**:
```
Order Service              Event Bus           Inventory Service
     |                         |                      |
     |--append event---------->|                      |
     |<-----ack (stored)-------|                      |
     | ✓ Done, user notified   |                      |
     |                         |                      |
     |                         |<--poll (when ready)--| (comes back online)
     |                         |---events since #125->|
     |                         |                      | ✓ Processes backlog
```

**Key Insight**: The event bus absorbs temporal availability mismatches. The producer doesn't care when the consumer processes events.

---

## 2. What is a Topic?

### Conceptual Definition

A **Topic** is a **logical namespace** for related events. It's the fundamental unit of event categorization and represents a stream of events about a specific domain concept.

**Mental Model**: Think of a topic as a **dedicated append-only file** where all events about one type of business activity are written.

### Why Topics Exist

Without topics, you'd have one giant log:
```
Global Event Log:
  0: order.created
  1: payment.processed
  2: user.registered
  3: order.created
  4: inventory.reserved
  5: payment.processed
```

**Problems**:
1. Consumers interested in only orders must scan everything
2. No semantic grouping
3. Hard to scale (can't split work by topic)
4. No access control granularity

**With Topics**:
```
Topic: order.created          Topic: payment.processed
  0: {orderId: 1, ...}          0: {paymentId: 501, ...}
  1: {orderId: 2, ...}          1: {paymentId: 502, ...}
  2: {orderId: 3, ...}          2: {paymentId: 503, ...}

Topic: user.registered
  0: {userId: 10, ...}
  1: {userId: 11, ...}
```

### Topic Naming Conventions

**Good Topic Names** (resource.event pattern):
- `order.created`
- `payment.processed`
- `inventory.reserved`
- `user.deleted`

**Why this pattern?**
- `order` = Domain entity (noun)
- `created` = Past-tense verb (event already happened)
- Events are **facts**, not commands

**Bad Topic Names**:
- ❌ `createOrder` - Sounds like a command
- ❌ `orders` - Too vague, what about orders?
- ❌ `orderService` - Implementation detail, not business concept

### ASCII: Topic Isolation

```
┌─────────────────────────────────────────────────────────┐
│                  APPLICATION DOMAIN                     │
│                                                         │
│  Order Service          Inventory Service    Payment   │
│       │                        │             Service   │
│       │ publish                │ subscribe       │     │
│       ▼                        │                 │     │
│  ┌──────────────┐             │                 │     │
│  │Topic:        │◄────────────┘                 │     │
│  │order.created │                                │     │
│  │              │                                │     │
│  │ [events...]  │                                │     │
│  └──────────────┘                                │     │
│                                                   │     │
│       │ publish                                   │     │
│       ▼                                           │     │
│  ┌──────────────┐                                │     │
│  │Topic:        │◄──────────────────────────────┘     │
│  │payment.req   │                                      │
│  │              │                                      │
│  │ [events...]  │                                      │
│  └──────────────┘                                      │
│       │                                                 │
│       │ subscribe                                       │
│       ▼                                                 │
│  Payment Service processes                             │
└─────────────────────────────────────────────────────────┘
```

### Our Simple Implementation

```
// Storage: One List per Topic
private final Map<String, List<com.event_bus.dto.EventRecord>> topics = new ConcurrentHashMap<>();

// Publishing to a topic
public void publish(String topicName, Event event) {
    topics.computeIfAbsent(topicName, k -> 
        Collections.synchronizedList(new ArrayList<>()))
        .add(new com.event_bus.dto.EventRecord(event, ...));
}

// Consuming from a topic
public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset) {
    List<com.event_bus.dto.EventRecord> log = topics.get(topicName);
    if (log == null) return Collections.emptyList();
    return log.subList((int)fromOffset, log.size());
}
```

**What this simulates**:
- ✅ Logical isolation between event streams
- ✅ Independent consumption (reading `order.created` doesn't affect `payment.processed`)
- ✅ Dynamic topic creation (topics created on first publish)

**What production systems add**:
- Physical partitioning of topics across disks
- Replication of each topic to N nodes
- Retention policies per topic (delete after 7 days)
- Schema validation per topic
- Rate limiting per topic
- ACLs (who can publish/subscribe to each topic)

### Failure Example: Topic Isolation Prevents Cascading Failures

```
Scenario: Payment service is broken and flooding events

Without Topic Isolation (Single Global Log):
┌──────────────────────────────────────┐
│ Global Log (all events mixed)       │
│                                      │
│ payment.error  ◄─┐                  │
│ payment.error    │ Flooding         │
│ payment.error    │ (100k/sec)       │
│ payment.error    │                  │
│ order.created  ◄─┼─ Can't consume   │
│ payment.error    │   fast enough!   │
│ payment.error  ◄─┘                  │
└──────────────────────────────────────┘
  ▲ Order Service consumer can't keep up
    because it must skip past payment events

With Topic Isolation:
┌─────────────────────┐   ┌─────────────────────┐
│ Topic:              │   │ Topic:              │
│ payment.error       │   │ order.created       │
│                     │   │                     │
│ [flooding]          │   │ [normal flow]       │
│ 100k events/sec     │   │ 100 events/sec      │
└─────────────────────┘   └─────────────────────┘
         │                          │
         ▼                          ▼
   Payment Consumer          Order Consumer
   (falls behind)            (works fine)
```

**Key Insight**: Topics provide **fault isolation**. A problem in one event stream doesn't cascade to others.

---

## 3. What is an Offset?

### Conceptual Definition

An **Offset** is a **monotonically increasing integer** that represents the position of an event in a topic's append-only log. It's the fundamental mechanism for tracking consumption progress.

**Mental Model**: Think of offset as a **bookmark in a book**. You can close the book (crash), come back later, and resume from exactly where you left off.

### Why Offsets Exist

Without offsets, how would a consumer know what it has already processed?

```
Topic: order.created
  Event 1: {orderId: 101, ...}
  Event 2: {orderId: 102, ...}
  Event 3: {orderId: 103, ...}
  Event 4: {orderId: 104, ...}

Consumer crashes after processing Event 2.
When it restarts, how does it know to start from Event 3?

Options:
1. ❌ Scan all events and check "have I seen this orderId?" - O(N), expensive
2. ❌ Delete processed events - loses history, prevents replay
3. ✅ Remember "I last read offset 2, start from 3"
```

### Offset as Index

In our implementation, offset = array index:

```java
import com.event_bus.dto.EventRecord;List<EventRecord> log = topics.get("order.created");

// Offset 0 = log.get(0)
// Offset 1 = log.get(1)
// Offset 2 = log.get(2)
// ...
// Offset N = log.get(N)
```

**Properties**:
- Starts at 0
- Increments by 1 for each new event
- Never decreases
- Never has gaps (unlike timestamps)

### ASCII: Offset Tracking

```
Topic: order.created
┌─────────────────────────────────────────────────────────┐
│ Offset │ Event Data                    │ Timestamp      │
├────────┼───────────────────────────────┼────────────────┤
│   0    │ {orderId: 1, amount: 100}    │ 10:00:00.000   │
│   1    │ {orderId: 2, amount: 200}    │ 10:00:01.234   │
│   2    │ {orderId: 3, amount: 150}    │ 10:00:02.456   │
│   3    │ {orderId: 4, amount: 300}    │ 10:00:03.789   │
│   4    │ {orderId: 5, amount: 250}    │ 10:00:05.012   │
└─────────────────────────────────────────────────────────┘
         ▲                               ▲
         │                               │
    Logical Position              Physical Timestamp
    (what we track)               (when it happened)


Consumer State:
┌──────────────────────────────────────┐
│ Consumer: "inventory-service"       │
│ Topic: "order.created"               │
│ Last Committed Offset: 2             │
│                                      │
│ Next Read: Start from offset 3      │
└──────────────────────────────────────┘
```

### Consumer Offset Management (Manual)

In our system, **consumers track their own offsets**:

```
// Consumer-side code (NOT in event bus)
class OrderConsumer {
    private long currentOffset = 0; // Consumer's responsibility
    
    public void poll() {
        List<com.event_bus.dto.EventRecord> events = eventBus.poll("order.created", currentOffset);
        
        for (com.event_bus.dto.EventRecord event : events) {
            processEvent(event);
            currentOffset++; // Move forward
            saveOffset(currentOffset); // Persist to DB/file
        }
    }
    
    // On restart, load last offset from persistent storage
    public void initialize() {
        this.currentOffset = loadLastCommittedOffset();
    }
}
```

**Why consumers manage offsets?**
1. The event bus stays stateless (no consumer tracking)
2. Consumers control when to commit (after processing, or after DB write)
3. Flexibility: Different consumers can be at different offsets

### Offset Semantics

```
Topic Log:
┌───────────────────────────────────────────────────┐
│  0  │  1  │  2  │  3  │  4  │  5  │  6  │  7  │  │
│ [E] │ [E] │ [E] │ [E] │ [E] │ [E] │ [E] │ [E] │  │
└───────────────────────────────────────────────────┘
         ▲           ▲                       ▲
         │           │                       │
    Consumer A   Consumer B              Consumer C
    (offset 1)   (offset 3)              (offset 7)
    
Consumer A: Will read offsets 1, 2, 3, 4, 5, 6, 7, ...
Consumer B: Will read offsets 3, 4, 5, 6, 7, ...
Consumer C: Will read offsets 7, 8, 9, ... (waiting for new events)
```

**Key Insight**: Offset represents **"next position to read"** or **"last processed + 1"**.

### Our Simple Implementation

```
public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset, int maxRecords) {
    List<com.event_bus.dto.EventRecord> log = topics.get(topicName);
    if (log == null || fromOffset >= log.size()) {
        return Collections.emptyList();
    }
    
    int start = (int) fromOffset;
    int end = Math.min(start + maxRecords, log.size());
    
    return new ArrayList<>(log.subList(start, end));
}
```

**What this simulates**:
- ✅ Positional reading
- ✅ Replayability (can go back to offset 0)
- ✅ Consumer independence (bus doesn't track who read what)

**What production systems add**:
- Persistent offset storage in the broker (consumer groups)
- Automatic offset commit
- Offset reset strategies (earliest, latest, specific timestamp)
- Offset lag monitoring (how far behind is consumer?)
- Dead letter queues for poison messages

### Failure Example: Offset Enables Exactly-Where-You-Left-Off Resume

```
Timeline:

10:00:00 - Consumer starts, offset = 0
10:00:01 - Process event at offset 0 ✓
10:00:02 - Process event at offset 1 ✓
10:00:03 - Commit offset = 2 (saved to DB)
10:00:04 - Process event at offset 2 ✓
10:00:05 - CRASH! (Before committing offset 3)

10:05:00 - Consumer restarts
10:05:01 - Load last committed offset from DB: offset = 2
10:05:02 - Resume from offset 2
10:05:03 - Process event at offset 2 again (duplicate!)
10:05:04 - Process event at offset 3 ✓
10:05:05 - Commit offset = 4


Result: Event at offset 2 processed TWICE (at-least-once delivery)
```

**Key Insight**: Offset granularity determines replay precision. We might reprocess some events after a crash, which is why **idempotency** is critical.

---

## 4. Why Offsets Over Timestamps?

### The Problem with Timestamps

Many developers initially think: "Why not use timestamps to track position?"

```
// Tempting but wrong approach
public List<com.event_bus.dto.EventRecord> pollSince(String topicName, Instant since) {
    return topics.get(topicName).stream()
        .filter(e -> e.getTimestamp().isAfter(since))
        .collect(Collectors.toList());
}
```

**This breaks down catastrophically in production.** Here's why:

### Issue 1: Clock Skew

**Scenario**: Multi-node event bus (3 servers)

```
Producer connects to Node A (clock is 10:00:05.000)
   ↓
Event written with timestamp = 10:00:05.000

Producer connects to Node B (clock is 10:00:04.500 - 500ms behind!)
   ↓
Event written with timestamp = 10:00:04.500


Logical Order (what actually happened):
  1st: Event A
  2nd: Event B

Timestamp Order (what we'd read):
  1st: Event B (10:00:04.500)
  2nd: Event A (10:00:05.000)
  
❌ ORDER VIOLATED!
```

**Real-world**: NTP can only sync clocks to ~10ms accuracy. In distributed systems, clocks drift constantly.

### Issue 2: No Deterministic Ordering for Same Timestamp

```
Events arrive in same millisecond:

Timestamp: 10:00:00.123
  - Event A: {orderId: 1}
  - Event B: {orderId: 2}
  - Event C: {orderId: 3}

Which order do you read them?
- Timestamp doesn't tell you
- Database sort order might be non-deterministic
- Order might change between reads!

Consumer reads: A, B, C
(crash, restart)
Consumer reads: B, A, C  ❌ Different order!
```

### Issue 3: Gaps and Missed Events

```
Consumer tracks: "last read timestamp = 10:00:05.000"

Consumer crashes for 10 seconds.

Meanwhile, events are written:
  10:00:06.000 - Event X
  10:00:07.000 - Event Y
  10:00:08.000 - Event Z
  10:00:04.000 - Event W (LATE ARRIVAL - clock skew or delayed write)

Consumer restarts, polls since 10:00:05.000
   ↓
Reads: X, Y, Z
Misses: W (because its timestamp is in the "past")

❌ LOST EVENT!
```

### Issue 4: No Replayability Guarantees

```
Consumer wants to replay last hour of events:

poll(topicName, now() - 1.hour)

But:
- Some events might have been deleted (retention policy)
- Can't guarantee "all events from exactly 1 hour ago"
- What if multiple events at that exact timestamp?
- Do you start before or after that timestamp?
```

### Why Offsets Solve Everything

**Offsets are logical positions, not physical time.**

```
Offset = Sequence Number in Append Order

┌─────────────────────────────────────────────────┐
│ Offset │ Event      │ Timestamp (just metadata) │
├────────┼────────────┼───────────────────────────┤
│   0    │ Event A    │ 10:00:05.000              │
│   1    │ Event B    │ 10:00:04.500 ← Clock skew │
│   2    │ Event C    │ 10:00:05.123              │
│   3    │ Event D    │ 10:00:06.000              │
└─────────────────────────────────────────────────┘

Reading from offset 1 ALWAYS gives you: B, C, D, ...
(same order, every time, regardless of timestamps)
```

**Properties**:
1. ✅ **Monotonic**: Offsets only increase (0, 1, 2, 3, ...)
2. ✅ **Deterministic**: Same offset = same event, always
3. ✅ **Gapless**: No missing sequence numbers
4. ✅ **Replayable**: offset 0 = beginning of topic, always
5. ✅ **Fast**: Direct array index, O(1) lookup

### ASCII: Offset vs Timestamp Comparison

```
TIMESTAMP-BASED (wrong):
Consumer: "Give me events since 10:00:05.000"

┌────────────────────────────────────────────────┐
│ Events on Disk (unordered by time due to skew)│
│                                                │
│ E1: 10:00:04.500                               │
│ E2: 10:00:05.000                               │
│ E3: 10:00:05.123                               │
│ E4: 10:00:04.999  ← Late arrival!              │
│ E5: 10:00:06.000                               │
│                                                │
│ Query: WHERE timestamp > 10:00:05.000          │
│ Result: E3, E5  ❌ Missed E4!                  │
└────────────────────────────────────────────────┘


OFFSET-BASED (correct):
Consumer: "Give me events from offset 2"

┌────────────────────────────────────────────────┐
│ Events in Append Order                         │
│                                                │
│ Offset 0: E1 (ts: 10:00:04.500)               │
│ Offset 1: E2 (ts: 10:00:05.000)               │
│ Offset 2: E3 (ts: 10:00:05.123) ◄── Start     │
│ Offset 3: E4 (ts: 10:00:04.999)               │
│ Offset 4: E5 (ts: 10:00:06.000)               │
│                                                │
│ Query: SLICE [2:end]                           │
│ Result: E3, E4, E5  ✓ All events!              │
└────────────────────────────────────────────────┘
```

### When Timestamps ARE Useful

Timestamps complement offsets but don't replace them:

```java
class com.event_bus.dto.EventRecord {
    private long offset;           // Logical position ← For consumption tracking
    private Instant timestamp;     // When it happened ← For business logic
    private JsonNode payload;
}
```

**Timestamp use cases**:
- Debugging (when was this event produced?)
- Business logic (order was placed at 3pm)
- Monitoring (how old is the lag?)
- Retention (delete events older than 7 days)

**Offset use cases**:
- Consumption tracking (what's my position?)
- Replay (start from beginning)
- Ordering (what came before/after?)

### Our Simple Implementation

```
private final Map<String, List<com.event_bus.dto.EventRecord>> topics = new ConcurrentHashMap<>();

public void publish(String topicName, Event event) {
    List<com.event_bus.dto.EventRecord> log = topics.computeIfAbsent(topicName, 
        k -> Collections.synchronizedList(new ArrayList<>()));
    
    long offset = log.size(); // Implicit offset = list index
    com.event_bus.dto.EventRecord record = new com.event_bus.dto.EventRecord(
        offset,                    // Position in log
        event,                     // Business data
        Instant.now()              // Timestamp (metadata only)
    );
    
    log.add(record); // Append (offset auto-increments)
}

public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset) {
    List<com.event_bus.dto.EventRecord> log = topics.get(topicName);
    return log.subList((int)fromOffset, log.size()); // Direct index access
}
```

**What this simulates**:
- ✅ Offset = append order
- ✅ O(1) offset lookup
- ✅ Deterministic ordering

**What production systems add**:
- Offset stored in segment file names (00000000000000000000.log, 00000000000000001000.log)
- Offset index files for faster lookup
- Log structured merge trees for compaction

### Failure Example: Timestamp Query Loses Events

```
Scenario: Payment service polls for new payment events

10:00:00.000 - Consumer starts, records "last poll = 10:00:00.000"
10:00:01.000 - Event A written (offset 10)
10:00:02.000 - Event B written (offset 11)
10:00:03.000 - Event C written (offset 12)
10:00:03.500 - Consumer polls: "give me events since 10:00:00.000"
               Receives: A, B, C
               Updates: "last poll = 10:00:03.500"

10:00:04.000 - Event D written (offset 13)
10:00:05.000 - Network delay causes write retry
               Event E finally written (offset 14, but timestamp = 10:00:03.000!)
               
10:00:06.000 - Consumer polls: "give me events since 10:00:03.500"
               Receives: D
               MISSES: E (timestamp 10:00:03.000 < 10:00:03.500)

❌ Event E lost forever!


With Offsets:
Consumer tracks "last offset = 12"
Polls: "give me events from offset 13"
Receives: D (offset 13), E (offset 14)
✓ All events received!
```

**Key Insight**: Offsets represent **append order**, which is the only order that matters for reliable event processing. Timestamps represent **event time**, which is useful for business logic but unreliable for tracking consumption.

---

## 5. At-Least-Once Delivery

### Conceptual Definition

**At-Least-Once Delivery** means: Every event published to the bus will be delivered to consumers **one or more times**, but never zero times.

**Mathematical Guarantee**:
```
For all events E:
  delivery_count(E) >= 1

Not guaranteed:
  delivery_count(E) == 1  ← This is "exactly-once"
```

**Mental Model**: Think of it as a **reliable postal service that might deliver duplicates**. You're guaranteed to receive every letter, but sometimes you might get two copies of the same letter.

### Why This Matters

In distributed systems, there are only three delivery guarantees possible:

1. **At-Most-Once**: Delivered 0 or 1 times (fire-and-forget, might lose data)
2. **At-Least-Once**: Delivered 1+ times (guaranteed delivery, might duplicate)
3. **Exactly-Once**: Delivered exactly 1 time (theoretical ideal, extremely hard)

**Our choice**: At-least-once is the sweet spot for most systems:
- ✅ Reliable (no data loss)
- ✅ Achievable (doesn't require distributed transactions)
- ✅ Performance (no coordination overhead)
- ⚠️ Requires idempotent consumers

### How Duplicates Happen

**Scenario 1: Consumer Crash After Processing, Before Offset Commit**

```
┌──────────────────────────────────────────────────────────┐
│                      Timeline                            │
├──────────────────────────────────────────────────────────┤
│ 10:00:00 - Consumer reads event at offset 100           │
│ 10:00:01 - Consumer processes event (updates DB)        │
│ 10:00:02 - Consumer prepares to commit offset 101       │
│ 10:00:03 - 💥 CRASH (before commit)                     │
│                                                          │
│ 10:05:00 - Consumer restarts                            │
│ 10:05:01 - Loads last committed offset: 100             │
│ 10:05:02 - Reads event at offset 100 AGAIN              │
│ 10:05:03 - Processes event AGAIN (duplicate!)           │
│ 10:05:04 - Commits offset 101                           │
└──────────────────────────────────────────────────────────┘

Result: Event at offset 100 processed TWICE
```

**Scenario 2: Network Partition During Offset Commit**

```
Consumer                      Event Bus
   │                              │
   │─ Poll from offset 50 ───────>│
   │<──── Events 50-60 ───────────│
   │                              │
   │ ✓ Process events 50-60       │
   │                              │
   │─ Commit offset 61 ───────────>│
   │              ...network timeout...
   │              (was it saved??)
   │                              │
   │─ Retry commit offset 61 ─────>│ (saved!)
   │                              │
   │─ Poll from offset 61 ────────>│
   │<──── Events 61-70 ───────────│
   
   
OR (if first commit actually succeeded):

Consumer                      Event Bus
   │                              │
   │─ Commit offset 61 ───────────>│ (saved!)
   │              ...network failure, no ack...
   │                              │
   │ (timeout, assumes failed)    │
   │ Restarts, reloads offset     │
   │ Last confirmed offset: 50 ❌ │
   │                              │
   │─ Poll from offset 50 ────────>│ (duplicate read!)
   │<──── Events 50-60 ───────────│
```

**Scenario 3: Rebalancing in Consumer Groups**

```
Consumer A owns Topic Partition 0
   │
   │ Processing offset 100-150
   │
   │ Network hiccup → considered dead by coordinator
   │
   │ (Still processing! Doesn't know it's been revoked)
   │ ✓ Processes offset 150
   │ ❌ Tries to commit offset 151 → REJECTED (no longer owns partition)
   │
Consumer B takes over Partition 0
   │
   │ Starts from last committed offset: 150
   │ Processes offset 150 AGAIN (duplicate!)
```

### ASCII: At-Least-Once Flow

```
NORMAL FLOW (no duplicates):
┌────────────┐      ┌───────────┐      ┌────────────┐
│  Producer  │      │Event Bus  │      │  Consumer  │
└─────┬──────┘      └─────┬─────┘      └─────┬──────┘
      │                   │                   │
      │──① Publish────────>│                  │
      │                   │ (append @ offset 10)
      │<─② Ack────────────│                  │
      │                   │                   │
      │                   │<──③ Poll(offset 10)
      │                   │──④ Return event──>│
      │                   │                   │ ⑤ Process
      │                   │                   │ ⑥ Save to DB
      │                   │<──⑦ Commit(11)────│
      │                   │──⑧ Ack───────────>│
      │                   │                   │
      ✓ Event delivered ONCE


CRASH SCENARIO (duplicate):
┌────────────┐      ┌───────────┐      ┌────────────┐
│  Producer  │      │Event Bus  │      │  Consumer  │
└─────┬──────┘      └─────┬─────┘      └─────┬──────┘
      │                   │                   │
      │                   │<──① Poll(offset 10)
      │                   │──② Return event──>│
      │                   │                   │ ③ Process
      │                   │                   │ ④ Save to DB
      │                   │                   │ 💥 CRASH
      │                   │                   │
      │                   │                   │ (restart)
      │                   │<──⑤ Poll(offset 10) (same!)
      │                   │──⑥ Return event──>│
      │                   │                   │ ⑦ Process AGAIN
      │                   │                   │ ⑧ Save to DB AGAIN
      │                   │<──⑨ Commit(11)────│
      │                   │                   │
      ⚠️ Event delivered TWICE (at-least-once)
```

### Our Simple Implementation

```
// Event Bus (doesn't prevent duplicates)
public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset, int maxRecords) {
    List<com.event_bus.dto.EventRecord> log = topics.get(topicName);
    if (log == null || fromOffset >= log.size()) {
        return Collections.emptyList();
    }
    return new ArrayList<>(log.subList((int)fromOffset, 
                                       (int)Math.min(fromOffset + maxRecords, log.size())));
}

// Consumer (responsible for handling duplicates)
class PaymentConsumer {
    private long currentOffset = 0;
    
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<com.event_bus.dto.EventRecord> events = eventBus.poll("payment.created", currentOffset, 100);
        
        for (com.event_bus.dto.EventRecord record : events) {
            // ⚠️ processPayment might be called multiple times for same event
            processPayment(record);
            
            currentOffset = record.getOffset() + 1;
            
            // If crash happens here, before persisting offset...
            // On restart, we re-read from old offset → duplicate!
            saveOffsetToDatabase(currentOffset);
        }
    }
    
    // Must be idempotent!
    private void processPayment(com.event_bus.dto.EventRecord record) {
        String paymentId = record.getPayload().get("paymentId").asText();
        
        // Idempotent: Check if already processed
        if (paymentRepository.exists(paymentId)) {
            log.warn("Duplicate payment event for {}, skipping", paymentId);
            return; // Safe to skip
        }
        
        paymentRepository.save(new Payment(paymentId, ...));
    }
}
```

**What this simulates**:
- ✅ Events can be re-read from any offset
- ✅ No automatic deduplication
- ✅ Consumer crash → reprocess from last commit

**What production systems add**:
- Automatic offset commit with configurable intervals
- Exactly-once semantics via distributed transactions (Kafka's EOS)
- Idempotency tokens in event metadata
- Deduplication windows (cache last N event IDs)

### Why Not Exactly-Once?

**Exactly-once is HARD**:

```
For exactly-once, you need atomicity across:
1. Event consumption (read from bus)
2. Business logic (process event)
3. State update (save to DB)
4. Offset commit (mark as processed)

ALL FOUR must succeed or ALL FOUR must fail.

This requires:
- Distributed transactions (2PC/3PC)
- Or idempotency + deduplication
- Or transactional outbox pattern
- Complex coordination, performance overhead
```

**At-least-once trades complexity for performance**:
- No distributed transactions needed
- Consumers can crash and restart freely
- Offset commit can be batched (commit every 100 events)
- Fast, simple, reliable

**The contract**: Event bus guarantees delivery, consumers guarantee idempotency.

### Failure Example: Why At-Least-Once is Acceptable

```
Scenario: Order Service publishes "order.created" event

❌ At-Most-Once (unacceptable):
   - Order Service publishes event
   - Network fails before event reaches bus
   - Order Service assumes success, moves on
   - Event LOST → Inventory never reserved → Order broken!
   
✓ At-Least-Once (acceptable):
   - Order Service publishes event
   - Event stored in bus (durable)
   - Inventory Service processes event
   - Inventory Service crashes before committing offset
   - Inventory Service restarts, processes AGAIN
   - Idempotent handling: "Already reserved inventory for orderId 123, skip"
   - Result: No harm done, eventual consistency achieved
```

**Key Insight**: In event-driven systems, **data loss is catastrophic**, but **duplicates are manageable** (with idempotency). At-least-once chooses the lesser evil.

---

## 6. Why Duplicates Are Acceptable

### The Idempotency Principle

**Idempotency** means: Applying the same operation multiple times has the same effect as applying it once.

```
Mathematically:
f(x) = f(f(x)) = f(f(f(x))) = ...

For event processing:
process(event) = process(process(event))
```

**Mental Model**: Think of it like a **light switch**. Flipping it to "ON" 10 times is the same as flipping it "ON" once. The outcome is deterministic.

### Idempotent vs Non-Idempotent Operations

**Non-Idempotent (dangerous with duplicates)**:
```
// ❌ BAD: Incrementing
void processOrderCreated(Event event) {
    int currentCount = getOrderCount();
    setOrderCount(currentCount + 1); // If called twice → wrong count!
}

// ❌ BAD: Appending without checks
void processPayment(Event event) {
    payments.add(new Payment(event)); // Duplicate payment records!
}

// ❌ BAD: Charging money
void processCharge(Event event) {
    creditCard.charge(event.getAmount()); // Customer charged TWICE!
}
```

**Idempotent (safe with duplicates)**:
```
// ✅ GOOD: Set to absolute value
void processOrderCreated(Event event) {
    String orderId = event.get("orderId");
    if (!orders.containsKey(orderId)) {
        orders.put(orderId, new Order(event));
    }
    // Second call: orderId already exists, no-op
}

// ✅ GOOD: Update with unique key
void processPayment(Event event) {
    String paymentId = event.get("paymentId"); // Unique!
    paymentRepository.upsert(paymentId, new Payment(event));
    // Second call: Same paymentId → overwrites with identical data
}

// ✅ GOOD: Check before action
void processCharge(Event event) {
    String chargeId = event.get("chargeId");
    if (!chargeRepository.exists(chargeId)) {
        creditCard.charge(event.getAmount());
        chargeRepository.save(chargeId, COMPLETED);
    }
}
```

### Natural Idempotency in Business Events

Many business operations are **naturally idempotent**:

```
1. User Registration:
   Event: UserRegistered { userId: "123", email: "user@example.com" }
   Processing: INSERT INTO users (id, email) VALUES ('123', '...')
                ON CONFLICT (id) DO NOTHING
   Result: User 123 exists (whether processed 1x or 10x)

2. Order Status Update:
   Event: OrderShipped { orderId: "456", status: "SHIPPED" }
   Processing: UPDATE orders SET status = 'SHIPPED' WHERE id = '456'
   Result: Order 456 is SHIPPED (idempotent update)

3. Inventory Reservation:
   Event: InventoryReserved { reservationId: "789", sku: "ABC", qty: 5 }
   Processing: INSERT INTO reservations (id, sku, qty) VALUES ('789', 'ABC', 5)
                ON CONFLICT (id) DO NOTHING
   Result: Reservation 789 exists for 5 units
```

**Key Pattern**: Use **unique event identifiers** and **upsert semantics**.

### ASCII: Idempotent Event Processing

```
EVENT: OrderCreated { orderId: "123", customerId: "456", total: 100.00 }

┌─────────────────────────────────────────────────────────┐
│         First Processing (offset 50)                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ① Check: Does order "123" exist in DB?                │
│     → No                                                │
│                                                         │
│  ② Insert: INSERT INTO orders (id, ...) VALUES (123...) │
│     → Success                                           │
│                                                         │
│  ③ Commit offset 51                                     │
│     → 💥 CRASH before commit saved!                     │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│         Second Processing (offset 50 again)             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ① Check: Does order "123" exist in DB?                │
│     → YES! (from first processing)                      │
│                                                         │
│  ② Skip: Order already processed                        │
│     → No duplicate order created ✓                      │
│                                                         │
│  ③ Commit offset 51                                     │
│     → Success                                           │
│                                                         │
└─────────────────────────────────────────────────────────┘

RESULT: Despite processing event TWICE, only ONE order exists.
        System state is correct!
```

### Techniques for Idempotency

**1. Unique Constraint + Ignore Duplicates**
```
@Transactional
public void handleOrderCreated(Event event) {
    String orderId = event.get("orderId").asText();
    
    try {
        orderRepository.save(new Order(orderId, ...));
    } catch (DuplicateKeyException e) {
        // Already exists, this is a duplicate event
        log.info("Order {} already processed, skipping", orderId);
    }
}
```

**2. Explicit Deduplication Table**
```
@Transactional
public void handlePaymentProcessed(Event event) {
    String eventId = event.get("eventId").asText();
    
    // Check if we've seen this event before
    if (processedEvents.exists(eventId)) {
        log.info("Event {} already processed", eventId);
        return;
    }
    
    // Process the event
    processPayment(event);
    
    // Mark as processed
    processedEvents.save(eventId, Instant.now());
}
```

**3. Versioning / Compare-And-Set**
```
@Transactional
public void handleInventoryUpdate(Event event) {
    String sku = event.get("sku").asText();
    int newQuantity = event.get("quantity").asInt();
    int eventVersion = event.get("version").asInt();
    
    Inventory inventory = inventoryRepository.findBySku(sku);
    
    if (inventory.getVersion() >= eventVersion) {
        // We've already processed a newer version
        log.info("Stale event for SKU {}, skipping", sku);
        return;
    }
    
    inventory.setQuantity(newQuantity);
    inventory.setVersion(eventVersion);
    inventoryRepository.save(inventory);
}
```

**4. State Machine Transitions**
```
public void handleOrderShipped(Event event) {
    String orderId = event.get("orderId").asText();
    Order order = orderRepository.findById(orderId);
    
    // Idempotent state transition
    if (order.getStatus() == PENDING) {
        order.setStatus(SHIPPED);
        orderRepository.save(order);
    } else if (order.getStatus() == SHIPPED) {
        // Already shipped, no-op (idempotent)
        log.info("Order {} already shipped", orderId);
    } else {
        log.warn("Invalid transition for order {}", orderId);
    }
}
```

### Our Simple Implementation Philosophy

```
// Event Bus: Does NOT prevent duplicates
public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset, int maxRecords) {
    // Returns same events if you poll same offset multiple times
    // This is INTENTIONAL - bus stays dumb
}

// Consumer: MUST handle duplicates
@Service
public class OrderConsumer {
    
    @Transactional
    public void processEvent(com.event_bus.dto.EventRecord record) {
        String orderId = record.getPayload().get("orderId").asText();
        
        // Idempotent: Upsert instead of insert
        orderRepository.save(Order.builder()
            .id(orderId)  // Primary key
            .customerId(...)
            .total(...)
            .build());
        
        // If called twice for same orderId → same database row updated
        // No harm done!
    }
}
```

**What this simulates**:
- ✅ Bus allows duplicate reads (at-least-once)
- ✅ Consumer responsibility to handle duplicates
- ✅ Separation of concerns (transport vs business logic)

**What production systems add**:
- Built-in deduplication windows (Kafka Streams)
- Exactly-once semantics via transactions
- Automatic idempotency token injection
- Metrics on duplicate detection rate

### When Duplicates ARE Harmful

**Financial Transactions** (non-idempotent by nature):
```
// ❌ DANGEROUS without idempotency
void processRefund(Event event) {
    double amount = event.get("amount").asDouble();
    creditCard.refund(amount); // If called twice → double refund! 💸
}

// ✅ SAFE with idempotency token
void processRefund(Event event) {
    String refundId = event.get("refundId").asText();
    
    if (refundRepository.exists(refundId)) {
        return; // Already refunded
    }
    
    double amount = event.get("amount").asDouble();
    creditCard.refund(amount, idempotencyKey: refundId);
    
    refundRepository.save(refundId, COMPLETED);
}
```

**External API Calls**:
```
// ❌ DANGEROUS
void sendEmail(Event event) {
    emailService.send(event.get("to"), event.get("subject"), ...);
    // User gets duplicate emails!
}

// ✅ SAFE
void sendEmail(Event event) {
    String emailId = event.get("emailId").asText();
    
    if (sentEmails.contains(emailId)) {
        return; // Already sent
    }
    
    emailService.send(...);
    sentEmails.add(emailId);
}
```

### Failure Example: Non-Idempotent Handler

```
Event: OrderCreated { orderId: "123", total: 100.00 }

BAD Implementation (non-idempotent):
┌────────────────────────────────────────────┐
│ void handleOrderCreated(Event e) {        │
│     totalRevenue += e.get("total");       │
│     orderCount++;                         │
│     orders.add(new Order(e));             │
│ }                                          │
└────────────────────────────────────────────┘

Timeline:
10:00:00 - Process event (offset 10)
           totalRevenue = 100, orderCount = 1, orders = [Order 123]
           
10:00:01 - Commit offset 11 → 💥 CRASH
           
10:00:10 - Restart, replay from offset 10
           Process event AGAIN
           totalRevenue = 200 ❌ (should be 100)
           orderCount = 2 ❌ (should be 1)
           orders = [Order 123, Order 123] ❌ (duplicate)
           
SYSTEM STATE CORRUPTED!


GOOD Implementation (idempotent):
┌────────────────────────────────────────────┐
│ void handleOrderCreated(Event e) {        │
│     String orderId = e.get("orderId");    │
│     if (orders.containsKey(orderId)) {    │
│         return; // Already processed      │
│     }                                      │
│     orders.put(orderId, new Order(e));    │
│ }                                          │
└────────────────────────────────────────────┘

Timeline:
10:00:00 - Process event (offset 10)
           orders = {123: Order}
           
10:00:01 - Commit offset 11 → 💥 CRASH
           
10:00:10 - Restart, replay from offset 10
           Process event AGAIN
           Check: orders.containsKey("123") → true
           Skip processing
           orders = {123: Order} ✓ (correct)
           
SYSTEM STATE CORRECT!
```

**Key Insight**: Duplicates are acceptable **IF** your processing is idempotent. The event bus provides **at-least-once delivery**, and consumers provide **idempotent processing**. Together, they achieve **exactly-once effect** (even though the event might be processed multiple times, the outcome is as if it was processed once).

---

## 7. Why the Event Bus Must Stay "Dumb"

### The Smart Endpoint, Dumb Pipe Principle

This is a core distributed systems philosophy:

**Dumb Pipe**: The transport layer (event bus) should be simple, fast, and reliable.
**Smart Endpoint**: The services (producers/consumers) contain business logic.

```
┌─────────────────────────────────────────────────────────┐
│                   ANTI-PATTERN                          │
│         "Smart" Event Bus (don't do this)               │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  - Schema validation                                    │
│  - Consumer group tracking                              │
│  - Deduplication                                        │
│  - Transformation (JSON → Avro)                         │
│  - Routing logic (if order.amount > 1000, send to VIP) │
│  - Retry policies                                       │
│  - Dead letter queues                                   │
│  - Monitoring per consumer                              │
│  - ACLs per consumer                                    │
│                                                         │
│  Problem: Bus becomes bottleneck, SPOF, hard to scale  │
└─────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────┐
│                  GOOD PATTERN                           │
│          "Dumb" Event Bus (our approach)                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  - Append events to log                                 │
│  - Return events by offset                              │
│  - That's it!                                           │
│                                                         │
│  Benefits: Simple, fast, reliable, scalable             │
└─────────────────────────────────────────────────────────┘
```

### Why Dumb is Better

**1. Single Responsibility**

```
// Event Bus: One job only
class EventBus {
    private final Map<String, List<com.event_bus.dto.EventRecord>> topics = ...;
    
    public void publish(String topic, Event event) {
        // Append to log, that's it
        topics.get(topic).add(new com.event_bus.dto.EventRecord(event));
    }
    
    public List<com.event_bus.dto.EventRecord> poll(String topic, long offset, int max) {
        // Return slice of log, that's it
        return topics.get(topic).subList(offset, offset + max);
    }
}

// NO:
// - Consumer tracking
// - Schema validation
// - Transformation
// - Deduplication
// - Retry logic
// - Monitoring
```

**Why?** Each responsibility is a potential failure mode. Keep it simple.

**2. Scalability**

```
Smart Bus (centralized logic):
┌──────────────────────────────────────────┐
│          Event Bus (bottleneck)          │
│                                          │
│  For each event:                         │
│  - Validate schema                       │
│  - Check ACLs                            │
│  - Route to correct consumer group       │
│  - Track consumption offsets             │
│  - Apply transformations                 │
│  - Deduplicate                           │
│                                          │
│  CPU: 100% 🔥                            │
│  Throughput: 1,000 msg/sec (limited)     │
└──────────────────────────────────────────┘


Dumb Bus (distributed logic):
┌────────────────────────────────────────────────────┐
│       Event Bus (simple append/read)               │
│                                                    │
│  For each event:                                   │
│  - Append to log (O(1))                            │
│  - Return by offset (O(1))                         │
│                                                    │
│  CPU: 10% ✓                                        │
│  Throughput: 100,000 msg/sec (scalable)            │
└────────────────────────────────────────────────────┘
       │                                │
       ▼                                ▼
  Consumer 1                       Consumer 2
  - Validate                       - Validate
  - Transform                      - Transform
  - Dedupe                         - Dedupe
  - Business logic                 - Business logic
  
  (Each consumer scales independently)
```

**Why?** Pushing logic to consumers allows **horizontal scaling**. Add more consumer instances without touching the bus.

**3. Flexibility**

```
Scenario: New consumer needs events in different format

Smart Bus Approach:
┌────────────────────────────────────────────┐
│ Event Bus must now support:               │
│ - JSON output (for Consumer A)             │
│ - Avro output (for Consumer B)             │
│ - Protobuf output (for Consumer C)         │
│                                            │
│ → Bus gets more complex with each consumer │
│ → Deployment coupling (all changes at bus) │
└────────────────────────────────────────────┘


Dumb Bus Approach:
┌────────────────────────────────────────────┐
│ Event Bus stores:                          │
│ - Raw JsonNode (generic)                   │
│                                            │
│ Consumers handle their own format:         │
│ - Consumer A: Parse JSON                   │
│ - Consumer B: Convert JSON → Avro          │
│ - Consumer C: Convert JSON → Protobuf      │
│                                            │
│ → Bus unchanged                            │
│ → Consumers evolve independently           │
└────────────────────────────────────────────┘
```

**4. Failure Isolation**

```
Smart Bus (shared failure modes):

Consumer A has a bug → sends malformed schema
                    → Bus validation fails
                    → Bus rejects event
                    → Producer fails
                    → Consumer B can't receive events either
                    → CASCADING FAILURE


Dumb Bus (isolated failures):

Consumer A has a bug → processes event incorrectly
                    → Consumer A's business logic fails
                    → Consumer A logs error
                    → Bus unaffected
                    → Consumer B continues normally
                    → ISOLATED FAILURE
```

### What "Dumb" Means in Practice

**Event Bus Responsibilities** (what we SHOULD do):
- ✅ Accept events (publish API)
- ✅ Store events durably
- ✅ Maintain append order
- ✅ Return events by offset (poll API)
- ✅ Handle concurrent writes safely

**NOT Event Bus Responsibilities** (what we should NOT do):
- ❌ Track which consumers exist
- ❌ Track what offset each consumer is at
- ❌ Validate event schemas
- ❌ Transform event payloads
- ❌ Retry failed deliveries
- ❌ Deduplicate events
- ❌ Route events based on content
- ❌ Apply business rules

### ASCII: Smart vs Dumb Architecture

```
SMART BUS (coupled, complex):
┌────────────────────────────────────────────────────┐
│              Event Bus (doing too much)            │
│                                                    │
│  ┌──────────────────────────────────────────┐    │
│  │ Consumer Registry                        │    │
│  │ - Consumer A: offset 100, group "orders" │    │
│  │ - Consumer B: offset 200, group "orders" │    │
│  │ - Consumer C: offset 50, group "payment" │    │
│  └──────────────────────────────────────────┘    │
│                                                    │
│  ┌──────────────────────────────────────────┐    │
│  │ Routing Logic                            │    │
│  │ - If event.type == "VIP" → priority queue│    │
│  │ - If event.region == "EU" → GDPR checks  │    │
│  └──────────────────────────────────────────┘    │
│                                                    │
│  ┌──────────────────────────────────────────┐    │
│  │ Transformation Engine                    │    │
│  │ - JSON → Avro for Consumer A             │    │
│  │ - JSON → Protobuf for Consumer B         │    │
│  └──────────────────────────────────────────┘    │
│                                                    │
│  Problem: Every new consumer requirement changes  │
│           the central bus. Hard to evolve.        │
└────────────────────────────────────────────────────┘


DUMB BUS (decoupled, simple):
┌────────────────────────────────────────────────────┐
│              Event Bus (minimal)                   │
│                                                    │
│  ┌──────────────────────────────────────────┐    │
│  │ Topics                                   │    │
│  │ - order.created: [events...]             │    │
│  │ - payment.processed: [events...]         │    │
│  └──────────────────────────────────────────┘    │
│                                                    │
│  API:                                              │
│  - publish(topic, event)                           │
│  - poll(topic, offset, max)                        │
│                                                    │
│  That's it!                                        │
└────────────────────────────────────────────────────┘
         │                           │
         ▼                           ▼
    Consumer A                  Consumer B
    ┌─────────────────┐        ┌─────────────────┐
    │ - Track offset  │        │ - Track offset  │
    │ - Validate      │        │ - Validate      │
    │ - Transform     │        │ - Transform     │
    │ - Dedupe        │        │ - Dedupe        │
    │ - Business logic│        │ - Business logic│
    └─────────────────┘        └─────────────────┘
    
    (Each consumer is self-sufficient and independent)
```

### Our Simple Implementation

```java
import com.event_bus.dto.EventRecord;@Service
public class EventBusService {
    
    private final Map<String, List<EventRecord>> topics = new ConcurrentHashMap<>();
    
    // ONLY responsibility: Store events
    public long publish(String topicName, Event event) {
        List<EventRecord> log = topics.computeIfAbsent(
            topicName, 
            k -> Collections.synchronizedList(new ArrayList<>())
        );
        
        long offset = log.size();
        EventRecord record = new EventRecord(
            offset,
            event.getPayload(), // JsonNode (generic, no validation)
            Instant.now(),
            event.getMetadata()
        );
        
        log.add(record);
        return offset;
    }
    
    // ONLY responsibility: Return events
    public List<EventRecord> poll(String topicName, long fromOffset, int maxRecords) {
        List<EventRecord> log = topics.get(topicName);
        if (log == null || fromOffset >= log.size()) {
            return Collections.emptyList();
        }
        
        int start = (int) fromOffset;
        int end = Math.min(start + maxRecords, log.size());
        
        return new ArrayList<>(log.subList(start, end));
    }
    
    // NO consumer tracking
    // NO schema validation
    // NO deduplication
    // NO transformation
    // NO retry logic
    // NO routing
}
```

**What this simulates**:
- ✅ Separation of concerns (transport vs business logic)
- ✅ Consumer independence
- ✅ Simple, testable bus

**What production systems add**:
- Disk-based persistence
- Replication
- Log compaction
- **BUT STILL**: Consumers track own offsets (Kafka consumer groups)

### When to Add Smarts (and where)

**If you need schema validation** → Add a schema registry **SERVICE** (separate from bus)
```
Producer → [Schema Registry] → [Event Bus] → Consumer
                    ↓
              Validates schema,
              returns schema ID
```

**If you need consumer group tracking** → Add a coordinator **SERVICE** (separate from bus)
```
Consumer → [Group Coordinator] → knows which consumer owns which partition
                               → [Event Bus] for actual events
```

**If you need transformations** → Add in consumer (or a dedicated transformer service)
```
[Event Bus] → [Consumer] → internal transformation logic
                        → process

OR

[Event Bus] → [Transformer Service] → [New Topic] → [Consumer]
```

**Key Principle**: Add features as **separate services**, not in the bus itself.

### Failure Example: Smart Bus Bottleneck

```
Scenario: Black Friday - 100,000 orders/sec

Smart Bus (doing schema validation + consumer tracking):
┌───────────────────────────────────────────┐
│ Event Bus (single instance)              │
│                                           │
│ For each of 100,000 events/sec:          │
│ 1. Validate JSON schema (5ms)            │
│ 2. Update consumer group offsets (2ms)   │
│ 3. Check ACLs (1ms)                      │
│ 4. Append to log (1ms)                   │
│                                           │
│ Total: 9ms per event                     │
│ Max throughput: 111 events/sec 💥        │
│                                           │
│ BOTTLENECK! Queue backs up, producers    │
│ timeout, orders lost!                    │
└───────────────────────────────────────────┘


Dumb Bus (append-only):
┌───────────────────────────────────────────┐
│ Event Bus (3 instances, load balanced)   │
│                                           │
│ For each event:                           │
│ 1. Append to log (1ms)                    │
│                                           │
│ Total: 1ms per event                      │
│ Max throughput: 1,000 events/sec          │
│ × 3 instances = 3,000 events/sec          │
│                                           │
│ Need more? Add more instances!            │
│ Horizontal scaling ✓                      │
└───────────────────────────────────────────┘
         │
         ▼
   Consumers (100 instances, distributed)
   - Each validates schemas independently
   - Each tracks own offset
   - Each applies business logic
   
   Total throughput: 100,000 events/sec ✓
```

**Key Insight**: A "dumb" bus is **fast** and **scalable** because it does one thing well. Complexity belongs in consumers, which can scale horizontally without central coordination.

---

## 8. Event vs com.event_bus.dto.EventRecord

### Conceptual Definition

**Event**: The **business fact** that occurred (domain concept)
**com.event_bus.dto.EventRecord**: The **storage envelope** that wraps the event with metadata (infrastructure concept)

**Mental Model**:
- `Event` = The letter's content (what you want to communicate)
- `com.event_bus.dto.EventRecord` = The envelope with postmark, tracking number, timestamp (how it's delivered)

### Why Separate?

**1. Separation of Concerns**

```java
// DOMAIN LAYER (business logic)
class Event {
    private String eventType;        // "order.created"
    private JsonNode payload;        // {orderId: 123, amount: 100}
    private Map<String, String> metadata; // {userId: "456", source: "web"}
}

// INFRASTRUCTURE LAYER (storage/transport)
class com.event_bus.dto.EventRecord {
    private long offset;             // Position in log (infrastructure concern)
    private Event event;             // The actual business event
    private Instant timestamp;       // When persisted (infrastructure concern)
    private String topicName;        // Which topic (infrastructure concern)
}
```

**Why?** When processing events, consumers care about business data (`Event`). When managing consumption, consumers care about position (`com.event_bus.dto.EventRecord.offset`). These are orthogonal concerns.

**2. Immutability of Business Events**

```
The Event is immutable (business fact):
  "Order 123 was created at 10:00am with amount $100"
  
The com.event_bus.dto.EventRecord adds context that varies:
  - In Topic A, it might be offset 10
  - In Topic B, it might be offset 500
  - Timestamp depends on when it was appended
  
Same Event, different EventRecords in different contexts.
```

**3. Replaying with Different Metadata**

```
// Scenario: Republishing an event to a different topic

// Original in "order.created" topic
com.event_bus.dto.EventRecord original = new com.event_bus.dto.EventRecord(
    offset: 100,
    event: orderCreatedEvent,
    timestamp: Instant.parse("2026-02-11T10:00:00Z"),
    topic: "order.created"
);

// Republish same Event to "order.failed" topic (for retry)
com.event_bus.dto.EventRecord retry = new com.event_bus.dto.EventRecord(
    offset: 0,              // New offset in new topic
    event: orderCreatedEvent,  // SAME business event
    timestamp: Instant.parse("2026-02-11T10:05:00Z"), // Later
    topic: "order.failed"
);
```

**Same Event (business fact unchanged), different EventRecords (infrastructure context changed).**

### ASCII: Event vs com.event_bus.dto.EventRecord

```
┌─────────────────────────────────────────────────────────┐
│                    EVENT (Business Layer)               │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  eventType: "order.created"                             │
│                                                         │
│  payload: {                                             │
│    "orderId": "123",                                    │
│    "customerId": "456",                                 │
│    "items": [...],                                      │
│    "total": 100.00                                      │
│  }                                                      │
│                                                         │
│  metadata: {                                            │
│    "userId": "789",                                     │
│    "source": "mobile-app",                              │
│    "correlationId": "abc-123"                           │
│  }                                                      │
│                                                         │
│  ← This is what business logic cares about              │
└─────────────────────────────────────────────────────────┘
                        │
                        │ Wrapped by infrastructure
                        ▼
┌─────────────────────────────────────────────────────────┐
│              EVENT RECORD (Infrastructure Layer)        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  offset: 100  ← Position in log (transport concern)     │
│                                                         │
│  timestamp: 2026-02-11T10:00:00.123Z                    │
│             ← When appended (infrastructure concern)    │
│                                                         │
│  topicName: "order.created"                             │
│             ← Where stored (infrastructure concern)     │
│                                                         │
│  event: { ...Event object from above... }              │
│         ← The actual business data                      │
│                                                         │
│  ← This is what event bus stores                        │
└─────────────────────────────────────────────────────────┘
```

### Layering: Who Uses What?

```
┌────────────────────────────────────────────┐
│          Producer (Business Logic)         │
│                                            │
│  Creates Event:                            │
│  - eventType                               │
│  - payload                                 │
│  - metadata                                │
│                                            │
│  eventBus.publish(topic, event)            │
│                │                           │
└────────────────┼────────────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────────┐
│        Event Bus (Infrastructure)          │
│                                            │
│  Wraps Event in com.event_bus.dto.EventRecord:               │
│  - Assigns offset                          │
│  - Adds timestamp                          │
│  - Associates with topic                   │
│                                            │
│  topics.get(topic).add(eventRecord)        │
│                │                           │
└────────────────┼────────────────────────────┘
                 │
                 ▼
┌────────────────────────────────────────────┐
│         Consumer (Business Logic)          │
│                                            │
│  Receives com.event_bus.dto.EventRecord, extracts Event:     │
│                                            │
│  for (com.event_bus.dto.EventRecord record : records) {      │
│      Event event = record.getEvent();      │
│      process(event.getPayload());          │
│      offset = record.getOffset() + 1;      │
│  }                                         │
│                                            │
└────────────────────────────────────────────┘
```

### Our Simple Implementation

```
// DOMAIN: Business event (what happened)
public class Event {
    private String eventType;              // e.g., "order.created"
    private JsonNode payload;              // Business data
    private Map<String, String> metadata;  // Context (userId, traceId, etc.)
    
    // Constructor, getters
}

// INFRASTRUCTURE: Storage envelope (where/when it happened)
public class com.event_bus.dto.EventRecord {
    private long offset;           // Position in topic log
    private String topicName;      // Which topic
    private Instant timestamp;     // When appended to log
    private Event event;           // The business event
    
    // Constructor, getters
}

// Event Bus: Wraps Events in EventRecords
@Service
public class EventBusService {
    
    public long publish(String topicName, Event event) {
        List<com.event_bus.dto.EventRecord> log = topics.get(topicName);
        
        // Infrastructure metadata
        long offset = log.size();
        Instant timestamp = Instant.now();
        
        // Wrap business event in infrastructure envelope
        com.event_bus.dto.EventRecord record = new com.event_bus.dto.EventRecord(
            offset,
            topicName,
            timestamp,
            event  // Business data preserved as-is
        );
        
        log.add(record);
        return offset;
    }
    
    public List<com.event_bus.dto.EventRecord> poll(String topicName, long fromOffset) {
        // Returns EventRecords (infrastructure + business)
        return topics.get(topicName).subList(fromOffset, ...);
    }
}

// Consumer: Unwraps com.event_bus.dto.EventRecord to get Event
@Service
public class OrderConsumer {
    
    public void processEvents() {
        List<com.event_bus.dto.EventRecord> records = eventBus.poll("order.created", currentOffset);
        
        for (com.event_bus.dto.EventRecord record : records) {
            // Infrastructure: Track position
            long offset = record.getOffset();
            
            // Business: Process event
            Event event = record.getEvent();
            JsonNode payload = event.getPayload();
            
            processOrder(payload);
            
            currentOffset = offset + 1;
        }
    }
}
```

**What this simulates**:
- ✅ Clean separation between business (Event) and infrastructure (com.event_bus.dto.EventRecord)
- ✅ Event is reusable across different contexts
- ✅ com.event_bus.dto.EventRecord provides transport metadata

**What production systems add**:
- Event headers (Kafka headers) separate from payload
- Schema evolution (Avro schema ID in record)
- Partition key (hash to determine partition)
- Compression metadata

### Benefits of Separation

**1. Testability**

```
// Testing business logic: Use Event (no infrastructure)
@Test
public void testOrderProcessing() {
    Event event = new Event(
        "order.created",
        objectMapper.readTree("{\"orderId\":\"123\"}"),
        Map.of()
    );
    
    orderService.process(event);
    
    verify(orderRepository).save(any());
}

// Testing infrastructure: Use com.event_bus.dto.EventRecord (with metadata)
@Test
public void testOffsetTracking() {
    com.event_bus.dto.EventRecord record = new com.event_bus.dto.EventRecord(100, "orders", Instant.now(), mockEvent);
    
    consumer.process(record);
    
    assertEquals(101, consumer.getCurrentOffset());
}
```

**2. Serialization Flexibility**

```
// Event serializes to JSON (for storage)
{
    "eventType": "order.created",
    "payload": { "orderId": "123" },
    "metadata": { "userId": "456" }
}

// com.event_bus.dto.EventRecord serializes with additional fields
{
    "offset": 100,
    "topicName": "order.created",
    "timestamp": "2026-02-11T10:00:00Z",
    "event": {
        "eventType": "order.created",
        "payload": { "orderId": "123" },
        "metadata": { "userId": "456" }
    }
}
```

**3. Cross-Topic Republishing**

```
// Scenario: Dead letter queue
com.event_bus.dto.EventRecord failed = poll("order.created", offset);
Event originalEvent = failed.getEvent(); // Extract business event

// Republish to DLQ topic (new offset, new timestamp, same Event)
publish("order.failed", originalEvent);
```

### Failure Example: Not Separating Event and com.event_bus.dto.EventRecord

```
// BAD: Mixing concerns
class Event {
    private String eventType;
    private JsonNode payload;
    private long offset;         // ❌ Infrastructure leak into domain
    private Instant timestamp;   // ❌ Infrastructure leak into domain
    private String topicName;    // ❌ Infrastructure leak into domain
}

// Problems:

1. Testing business logic requires mock offsets
   @Test
   public void testOrder() {
       Event event = new Event(..., 100, Instant.now(), "orders"); // Irrelevant!
       orderService.process(event);
   }

2. Republishing requires changing offset
   Event event = failedEvent;
   event.setOffset(0);  // ❌ Mutating domain object for infrastructure reason
   publish("retry-topic", event);

3. Consumers coupled to infrastructure
   void processOrder(Event event) {
       String orderId = event.getPayload().get("orderId"); // Business
       long offset = event.getOffset(); // Infrastructure
       // Mixing concerns in same method!
   }
```

**Key Insight**: `Event` represents **what happened** (immutable business fact). `com.event_bus.dto.EventRecord` represents **how we stored it** (infrastructure metadata). Keep them separate for clarity, testability, and flexibility.

---

## 9. What We Are NOT Implementing

### Introduction

This section clarifies what **production event buses like Kafka include** but our **learning implementation intentionally omits**. Understanding what we're NOT building is as important as understanding what we ARE building.

### 9.1 Partitioning

**What it is**: Splitting a topic into multiple independent sub-logs called partitions.

```
WITHOUT PARTITIONING (our implementation):
┌───────────────────────────────────────────────┐
│ Topic: "order.created"                        │
│                                               │
│ Offset 0: Event A                             │
│ Offset 1: Event B                             │
│ Offset 2: Event C                             │
│ Offset 3: Event D                             │
│ Offset 4: Event E                             │
│ ...                                           │
│                                               │
│ Single sequential log                         │
└───────────────────────────────────────────────┘
     │
     └──> Single consumer reads sequentially


WITH PARTITIONING (Kafka):
┌──────────────────────────────────────────────────────┐
│ Topic: "order.created" (3 partitions)                │
│                                                      │
│ Partition 0:          Partition 1:      Partition 2:│
│ ┌────────────┐       ┌────────────┐   ┌───────────┐│
│ │Offset 0: A │       │Offset 0: B │   │Offset 0: C││
│ │Offset 1: D │       │Offset 1: E │   │Offset 1: F││
│ │Offset 2: G │       │Offset 2: H │   │Offset 2: I││
│ └────────────┘       └────────────┘   └───────────┘│
└──────────────────────────────────────────────────────┘
      │                    │                 │
      └────────────────────┴─────────────────┘
                          │
              3 consumers read in parallel
```

**Why it exists (in Kafka)**:
1. **Parallelism**: 3 partitions → 3 consumers can read simultaneously
2. **Scalability**: Distribute load across multiple disks/machines
3. **Ordering**: Events with same key go to same partition (e.g., all orders for customer 123 in partition 1)

**Why we're NOT implementing it**:
- Adds complexity (partition assignment, rebalancing)
- Our focus is on core event bus concepts
- Single partition is sufficient for learning

**Simulation**: We treat each topic as a **single logical partition**.

**What production adds**:
```
// Kafka: Partition by key
producer.send(new ProducerRecord<>(
    "order.created",
    customerId,      // Partition key (all events for same customer → same partition)
    orderEvent
));

// Consumer group: Each consumer gets subset of partitions
Consumer 1: Partitions [0, 1]
Consumer 2: Partitions [2, 3]
Consumer 3: Partitions [4, 5]
```

---

### 9.2 Replication

**What it is**: Storing copies of each partition on multiple servers for fault tolerance.

```
WITHOUT REPLICATION (our implementation):
┌────────────────────────────────┐
│ Event Bus (single instance)   │
│                                │
│ Topic: order.created           │
│ [events...]                    │
│                                │
│ 💥 If this crashes → DATA LOST │
└────────────────────────────────┘


WITH REPLICATION (Kafka):
┌─────────────────────────────────────────────────────┐
│ Kafka Cluster                                       │
│                                                     │
│ Broker 1 (Leader)    Broker 2 (Follower) Broker 3  │
│ ┌──────────────┐    ┌──────────────┐  ┌──────────┐│
│ │Partition 0   │───>│Partition 0   │─>│Part 0    ││
│ │[events...]   │    │[replica]     │  │[replica] ││
│ └──────────────┘    └──────────────┘  └──────────┘│
│                                                     │
│ Replication Factor = 3                              │
│ 💥 Broker 1 fails → Broker 2 becomes leader ✓       │
└─────────────────────────────────────────────────────┘
```

**Why it exists (in Kafka)**:
1. **Durability**: Data survives machine failures
2. **Availability**: Service continues if nodes crash
3. **Disaster recovery**: Replicas in different data centers

**Why we're NOT implementing it**:
- Requires distributed consensus (Raft/Zookeeper)
- Complex failure detection and leader election
- Our in-memory implementation loses data on restart anyway

**Simulation**: We accept that **our event bus is ephemeral** (in-memory only).

**What production adds**:
```
Replication protocol:
1. Producer writes to leader partition
2. Leader replicates to N followers
3. Leader waits for min.insync.replicas to ack
4. Leader responds to producer with success
5. If leader fails, follower promoted to leader
```

---

### 9.3 Consumer Groups

**What it is**: Multiple consumer instances that coordinate to divide work and track progress collectively.

```
WITHOUT CONSUMER GROUPS (our implementation):
┌────────────────────────────────────────────┐
│ Topic: order.created                       │
│ [Event 0, Event 1, Event 2, Event 3, ...]  │
└────────────────────────────────────────────┘
     │            │            │
     ▼            ▼            ▼
 Consumer A   Consumer B   Consumer C
 (offset 0)   (offset 0)   (offset 0)
 
 Each tracks own offset independently.
 All read ALL events (no work division).


WITH CONSUMER GROUPS (Kafka):
┌─────────────────────────────────────────────────────┐
│ Topic: order.created (Partition 0, 1, 2)            │
└─────────────────────────────────────────────────────┘
     │                  │                  │
     ▼                  ▼                  ▼
┌──────────────────────────────────────────────────┐
│ Consumer Group: "order-processor"                │
│                                                  │
│ Consumer A      Consumer B      Consumer C       │
│ (P0, offset 10) (P1, offset 20) (P2, offset 30) │
│                                                  │
│ Group tracks: {P0:10, P1:20, P2:30}             │
└──────────────────────────────────────────────────┘

Work divided: Each consumer handles different partitions.
Group offset stored in Kafka (automatic tracking).
```

**Why it exists (in Kafka)**:
1. **Load balancing**: Divide partitions among consumers
2. **Fault tolerance**: If consumer dies, partitions reassigned
3. **Automatic offset management**: Group coordinator tracks progress

**Why we're NOT implementing it**:
- Requires group coordinator service
- Partition assignment algorithms (round-robin, sticky, etc.)
- Rebalancing protocol
- Our bus doesn't track consumers at all (stays "dumb")

**Simulation**: Each consumer **manually tracks its own offset** in its own database.

**What production adds**:
```
// Kafka: Consumer group configuration
Properties props = new Properties();
props.put("group.id", "order-processor");  // Consumer group
props.put("enable.auto.commit", "true");   // Auto-commit offsets

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("order.created"));

// Kafka handles:
// - Partition assignment
// - Offset tracking
// - Rebalancing when consumers join/leave
```

**Our approach**:

```java
import com.event_bus.dto.EventRecord;@Service
public class OrderConsumer {
    private long currentOffset = 0; // Manual tracking
    
    @PostConstruct
    public void init() {
        currentOffset = offsetRepository.load(); // Load from DB
    }
    
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<EventRecord> events = eventBus.poll("order.created", currentOffset);
        
        for (EventRecord event : events) {
            process(event);
            currentOffset = event.getOffset() + 1;
            offsetRepository.save(currentOffset); // Manual save
        }
    }
}
```

---

### 9.4 Exactly-Once Semantics

**What it is**: Guarantee that each event is processed exactly one time (neither lost nor duplicated).

```
AT-LEAST-ONCE (our implementation):
Timeline:
1. Consumer reads event at offset 10
2. Consumer processes event (writes to DB)
3. 💥 Crash before committing offset 11
4. Consumer restarts, loads offset 10
5. Consumer processes event AGAIN
Result: Event processed TWICE ✓ (acceptable, requires idempotency)


EXACTLY-ONCE (Kafka EOS):
Timeline:
1. Consumer reads event at offset 10
2. Consumer starts transaction
3. Consumer processes event (writes to DB)
4. Consumer commits offset 11
5. 💥 Crash during commit
6. Consumer restarts, transaction aborted
7. Consumer loads offset 10
8. DB write rolled back (transactional)
9. Consumer processes event ONE MORE TIME
10. Transaction commits atomically
Result: Event processed ONCE ✓ (guaranteed, even with crashes)
```

**Why it exists (in Kafka)**:
1. **Stronger guarantees**: Critical for financial systems
2. **Transactional writes**: Produce + consume in atomic transaction
3. **Idempotence**: Retries don't create duplicates

**Why we're NOT implementing it**:
- Requires distributed transactions (2PC)
- Coordination between event bus and consumer databases
- Complex failure recovery
- Performance overhead

**Simulation**: We accept **at-least-once** and rely on **idempotent consumers**.

**What production adds**:
```
// Kafka: Exactly-once with transactions
Properties props = new Properties();
props.put("enable.idempotence", "true");
props.put("transactional.id", "order-processor-1");

KafkaProducer<String, String> producer = new KafkaProducer<>(props);
producer.initTransactions();

try {
    producer.beginTransaction();
    producer.send(new ProducerRecord<>("output-topic", event));
    producer.sendOffsetsToTransaction(offsets, consumerGroupId);
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
```

---

### 9.5 Schema Registry

**What it is**: Centralized service for managing and validating event schemas.

```
WITHOUT SCHEMA REGISTRY (our implementation):
Producer → Event Bus (any JSON accepted)
                ↓
           Consumer (hopes JSON is correct)


WITH SCHEMA REGISTRY (Confluent):
Producer → [Schema Registry] (validate + get schema ID)
                ↓
           Event Bus (stores schema ID + binary payload)
                ↓
           Consumer (fetches schema by ID, deserializes)
```

**Why it exists (in Kafka)**:
1. **Schema validation**: Prevent bad data from entering
2. **Schema evolution**: Manage backward/forward compatibility
3. **Space efficiency**: Store schema once, reference by ID

**Why we're NOT implementing it**:
- Adds external service dependency
- Schema evolution rules (Avro, Protobuf)
- Our focus is on event bus mechanics, not data governance

**Simulation**: We store **raw JsonNode** (no validation).

**What production adds**:
```
// Confluent Schema Registry
String schema = "{\"type\":\"record\",\"name\":\"Order\",\"fields\":[{\"name\":\"orderId\",\"type\":\"string\"}]}";
int schemaId = schemaRegistry.register("order.created-value", schema);

// Producer: Serialize with schema
byte[] payload = avroSerializer.serialize(order, schemaId);

// Consumer: Deserialize with schema
Order order = avroDeserializer.deserialize(payload, schemaId);
```

---

### 9.6 Compaction

**What it is**: Keeping only the latest value for each key, deleting old values.

```
WITHOUT COMPACTION (our implementation):
Topic: user.profile (retention: forever)
  Offset 0: {userId: 123, name: "Alice", age: 25}
  Offset 1: {userId: 123, name: "Alice", age: 26}  ← Updated age
  Offset 2: {userId: 123, name: "Alicia", age: 26} ← Changed name
  Offset 3: {userId: 456, name: "Bob", age: 30}
  
  All 4 events stored forever (waste of space).


WITH COMPACTION (Kafka):
Topic: user.profile (compacted)
  (background process removes old versions)
  
  After compaction:
  Offset 2: {userId: 123, name: "Alicia", age: 26} ← Latest for 123
  Offset 3: {userId: 456, name: "Bob", age: 30}    ← Latest for 456
  
  Offsets 0 and 1 deleted (superseded by offset 2).
```

**Why it exists (in Kafka)**:
1. **Space efficiency**: Don't store full history for all time
2. **State snapshots**: Latest value = current state
3. **Rebuilding**: New consumers bootstrap from compacted log

**Why we're NOT implementing it**:
- Requires background compaction process
- Key extraction from events
- Tombstone handling (nulls for deletions)

**Simulation**: We keep **all events forever** (or until process restarts).

**What production adds**:

```
Kafka compaction:
- Topic config: cleanup.policy=compact
- Background thread scans log segments
- Keeps only latest record per key
- Preserves at least last N hours (min.compaction.lag.ms)
```

---

### 9.7 Retention Policies

**What it is**: Automatically deleting old events based on time or size.

```
WITHOUT RETENTION (our implementation):
Events stay in memory until process restarts.
No automatic cleanup.


WITH RETENTION (Kafka):
Topic: logs (retention: 7 days)

Day 1: Events 0-100
Day 2: Events 101-200
...
Day 8: Events 701-800
       ↓ Retention policy triggers
       Delete events 0-100 (older than 7 days)
```

**Why it exists (in Kafka)**:
1. **Disk management**: Prevent infinite growth
2. **Compliance**: GDPR (delete after N days)
3. **Cost**: Old data less valuable, delete to save money

**Why we're NOT implementing it**:
- Requires background cleanup threads
- Time-based or size-based eviction
- Segment file management

**Simulation**: Events live until **process restart** (in-memory).

**What production adds**:
```
# Kafka topic configuration
retention.ms=604800000        # 7 days
retention.bytes=1073741824    # 1GB max
segment.ms=86400000           # New segment daily
```

---

### 9.8 Multi-Datacenter Replication

**What it is**: Replicating events across geographically distributed data centers.

```
WITHOUT MULTI-DC (our implementation):
Single instance in one location.


WITH MULTI-DC (Kafka MirrorMaker):
┌──────────────────┐       ┌──────────────────┐
│ DC 1 (US-East)   │       │ DC 2 (EU-West)   │
│                  │       │                  │
│ Kafka Cluster    │──────>│ Kafka Cluster    │
│ - order.created  │ Mirror│ - order.created  │
│                  │       │ (replica)        │
└──────────────────┘       └──────────────────┘

Producers in US → Write to DC 1
Consumers in EU → Read from DC 2 (lower latency)
```

**Why it exists**:
1. **Disaster recovery**: DC 1 burns down, DC 2 takes over
2. **Low latency**: Serve consumers from nearest DC
3. **Compliance**: Data residency (EU data stays in EU)

**Why we're NOT implementing it**:
- Requires cross-DC networking
- Conflict resolution (what if both DCs receive writes?)
- Our single-instance design

---

### 9.9 Summary: What's Included vs Omitted

| Feature | Our Implementation | Kafka (Production) |
|---------|-------------------|-------------------|
| Append-only log | ✅ Yes | ✅ Yes |
| Topic isolation | ✅ Yes | ✅ Yes |
| Offset-based consumption | ✅ Yes | ✅ Yes |
| At-least-once delivery | ✅ Yes | ✅ Yes |
| Partitioning | ❌ No (single partition) | ✅ Yes (multiple partitions) |
| Replication | ❌ No (in-memory) | ✅ Yes (N replicas) |
| Consumer groups | ❌ No (manual offset tracking) | ✅ Yes (automatic) |
| Exactly-once | ❌ No (at-least-once only) | ✅ Yes (EOS) |
| Schema registry | ❌ No (raw JSON) | ✅ Yes (Avro/Protobuf) |
| Compaction | ❌ No | ✅ Yes |
| Retention policies | ❌ No | ✅ Yes |
| Persistence | ❌ No (in-memory) | ✅ Yes (disk) |
| Multi-DC | ❌ No | ✅ Yes |

**Our Philosophy**: Implement **core primitives** (append log, offsets, at-least-once) to understand **fundamental concepts**. Omit **operational features** (replication, partitioning, consumer groups) that add complexity without changing mental models.

---

## 10. Comparison to Apache Kafka

### Conceptual Similarities

**Our Event Bus and Kafka share the same core mental model:**

```
1. Append-only log
   Ours: List<com.event_bus.dto.EventRecord>
   Kafka: Log segments on disk

2. Topic-based isolation
   Ours: Map<String, List<com.event_bus.dto.EventRecord>>
   Kafka: Directory per topic

3. Offset-based consumption
   Ours: poll(topic, offset, max)
   Kafka: poll(Duration.ofMillis(100))

4. At-least-once delivery
   Ours: Manual offset commit (can reprocess)
   Kafka: Auto/manual commit (can reprocess)

5. Dumb broker
   Ours: No consumer tracking
   Kafka: Minimal broker logic (consumers self-manage)
```

**Mental Model Alignment**:

```
Our Implementation              Kafka
──────────────────              ─────
Map<String, List<>>        ←→   Topic with partitions
List<com.event_bus.dto.EventRecord>          ←→   Log segment files
com.event_bus.dto.EventRecord.offset         ←→   Partition offset
poll(topic, offset)        ←→   consumer.poll()
Manual offset save         ←→   consumer.commitSync()
JsonNode payload           ←→   byte[] (any format)
In-memory                  ←→   Disk-based
Single partition           ←→   Multiple partitions
```

### ASCII: Architecture Comparison

```
OUR EVENT BUS (simplified):
┌─────────────────────────────────────────────────┐
│              Event Bus Service                  │
│                                                 │
│  topics = {                                     │
│    "order.created": [                           │
│      com.event_bus.dto.EventRecord(offset=0, event=...),          │
│      com.event_bus.dto.EventRecord(offset=1, event=...),          │
│      com.event_bus.dto.EventRecord(offset=2, event=...),          │
│    ],                                           │
│    "payment.processed": [...]                   │
│  }                                              │
│                                                 │
│  API:                                           │
│  - publish(topic, event)                        │
│  - poll(topic, offset, max)                     │
└─────────────────────────────────────────────────┘
         ▲                           │
         │                           ▼
    Producers                    Consumers
                              (track own offset)


KAFKA (production):
┌─────────────────────────────────────────────────────┐
│              Kafka Broker Cluster                   │
│                                                     │
│  Topic: "order.created"                             │
│  ├─ Partition 0 (Leader: Broker 1)                 │
│  │  └─ /data/order.created-0/                      │
│  │     ├─ 00000000000000000000.log (segment)       │
│  │     ├─ 00000000000000001000.log                 │
│  │     └─ 00000000000000002000.log                 │
│  ├─ Partition 1 (Leader: Broker 2)                 │
│  └─ Partition 2 (Leader: Broker 3)                 │
│                                                     │
│  API:                                               │
│  - producer.send(record)                            │
│  - consumer.poll(duration)                          │
│                                                     │
│  + Replication                                      │
│  + Consumer group coordination                      │
│  + Exactly-once semantics                           │
└─────────────────────────────────────────────────────┘
         ▲                           │
         │                           ▼
    Producers              Consumer Groups
                        (auto offset tracking)
```

### What Kafka Adds on Top

**1. Partitioning for Parallelism**

```
Our Implementation:
  Topic "order.created" = Single list
  → Only 1 consumer can read efficiently

Kafka:
  Topic "order.created" = 10 partitions
  → 10 consumers can read in parallel (10x throughput)
  
  Events with same key → same partition (ordering preserved)
```

**2. Disk-Based Persistence**

```
Our Implementation:
  events = new ArrayList<>() // In-memory
  → Lost on restart

Kafka:
  Log segments on disk
  → Survives restarts
  → Can replay months of history
  → Sequential disk writes (very fast)
```

**3. Replication for Fault Tolerance**

```
Our Implementation:
  Single instance
  → Crash = downtime

Kafka:
  Each partition replicated N times
  → Leader crash → follower promoted
  → Zero downtime
```

**4. Consumer Groups for Load Balancing**

```
Our Implementation:
  Each consumer tracks own offset
  → Manual coordination if multiple instances

Kafka:
  Consumer group coordinator
  → Automatic partition assignment
  → Automatic rebalancing on failure
  → Shared offset tracking
```

**5. Exactly-Once Semantics**

```
Our Implementation:
  At-least-once only
  → Rely on idempotent consumers

Kafka:
  Optional exactly-once
  → Transactional produce + consume
  → Atomic offset commits
```

**6. Performance Optimizations**

```
Our Implementation:
  - Simple ArrayList
  - No batching
  - No compression
  - Throughput: ~1,000 msg/sec

Kafka:
  - Zero-copy sends (OS page cache)
  - Batching (group writes)
  - Compression (GZIP, Snappy, LZ4)
  - Throughput: ~1,000,000 msg/sec
```

### Kafka's Log Structure (Deep Dive)

```
Kafka Topic Partition on Disk:

/var/kafka/order.created-0/
├── 00000000000000000000.index    ← Offset → file position mapping
├── 00000000000000000000.log      ← Actual event data (segment 0)
├── 00000000000000000000.timeindex ← Timestamp → offset mapping
├── 00000000000001000000.index
├── 00000000000001000000.log      ← Segment 1 (offsets 1M-2M)
├── 00000000000001000000.timeindex
└── ...

Each .log file:
┌─────────────────────────────────────────────────┐
│ Offset: 0    | Length: 256  | Data: {...}      │
│ Offset: 1    | Length: 312  | Data: {...}      │
│ Offset: 2    | Length: 289  | Data: {...}      │
│ ...                                             │
└─────────────────────────────────────────────────┘

Our in-memory equivalent:
List<com.event_bus.dto.EventRecord> log = topics.get("order.created");
```

### Simplified Kafka Consumer Example

```
// Kafka (similar to our approach conceptually)
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("group.id", "order-processor");
props.put("enable.auto.commit", "false"); // Manual commit (like us)

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("order.created"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    
    for (ConsumerRecord<String, String> record : records) {
        System.out.printf("offset = %d, key = %s, value = %s%n", 
                          record.offset(), record.key(), record.value());
        
        processOrder(record.value());
        
        // Manual offset commit (same concept as our implementation)
        consumer.commitSync(Collections.singletonMap(
            new TopicPartition(record.topic(), record.partition()),
            new OffsetAndMetadata(record.offset() + 1)
        ));
    }
}
```

**Comparison to our consumer**:
```
// Our implementation (conceptually identical)
@Scheduled(fixedDelay = 100)
public void poll() {
    List<com.event_bus.dto.EventRecord> records = eventBus.poll("order.created", currentOffset, 100);
    
    for (com.event_bus.dto.EventRecord record : records) {
        System.out.printf("offset = %d, payload = %s%n", 
                          record.getOffset(), record.getEvent().getPayload());
        
        processOrder(record.getEvent().getPayload());
        
        // Manual offset save (same concept)
        currentOffset = record.getOffset() + 1;
        offsetRepository.save(currentOffset);
    }
}
```

**The pattern is identical!** Kafka just adds production features around it.

### When to Use Our Simple Bus vs Kafka

**Use Our Simple Bus For**:
- ✅ Learning event-driven architecture
- ✅ Local development/testing
- ✅ Single-process applications
- ✅ Low-throughput scenarios (<1,000 msg/sec)
- ✅ Prototyping before investing in infrastructure

**Use Kafka For**:
- ✅ Production systems
- ✅ High throughput (>10,000 msg/sec)
- ✅ Multi-service architectures
- ✅ Fault tolerance requirements
- ✅ Long-term event retention (months/years)
- ✅ Compliance requirements (exactly-once)

### The Learning Path

```
1. Start Here (Our Implementation):
   ┌────────────────────────────────┐
   │ - Understand append-only log   │
   │ - Understand offsets           │
   │ - Understand at-least-once     │
   │ - Understand idempotency       │
   │ - Build mental models          │
   └────────────────────────────────┘
                │
                ▼
2. Add Complexity (Kafka):
   ┌────────────────────────────────┐
   │ Same concepts + production:    │
   │ - Partitioning                 │
   │ - Replication                  │
   │ - Consumer groups              │
   │ - Exactly-once                 │
   │ - Disk persistence             │
   └────────────────────────────────┘
```

**Key Insight**: Kafka implements the **same mental models** we've built (append log, offsets, topics, at-least-once), but with **production-grade operational features** (replication, partitioning, consumer groups). By building a simple version first, you deeply understand the core concepts before dealing with operational complexity.

---

## Conclusion

This document covered the foundational concepts of event-driven systems:

1. **Event Bus**: Temporal decoupling via persistent, replayable logs
2. **Topics**: Logical isolation of event streams
3. **Offsets**: Monotonic positions that enable deterministic consumption tracking
4. **Offsets vs Timestamps**: Why logical positions beat wall-clock time
5. **At-Least-Once**: Reliable delivery through idempotent consumers
6. **Duplicates**: Acceptable with proper idempotency design
7. **Dumb Bus**: Simplicity and scalability through smart endpoints
8. **Event vs com.event_bus.dto.EventRecord**: Separation of business data and infrastructure metadata
9. **What We're NOT Building**: Production features intentionally omitted
10. **Kafka Comparison**: How production systems extend these core concepts

**Core Principles**:
- Immutability (append-only)
- Offset-based consumption (not timestamps)
- At-least-once delivery (via idempotency)
- Dumb broker, smart endpoints
- Separation of concerns (domain vs infrastructure)

**Next Steps**:
- Implement this design in Java + Spring Boot
- Add REST APIs for publish/poll
- Build example producers/consumers
- Test failure scenarios (crash during processing)
- Measure performance (throughput, latency)
- Consider migrating to Kafka when ready for production

---

**Remember**: Distributed systems are about **tradeoffs**, not perfection. Our simple event bus trades operational features (replication, exactly-once) for **simplicity** and **learning clarity**. Once you master these fundamentals, adding production features (Kafka) becomes a matter of understanding **how** they're implemented, not **why** they exist.
