# Networking to REST — Deep Practical Notes (NetTrace Project)

> A learning lab for understanding the journey from low-level networking to well-designed REST APIs.
> This document is meant to be read slowly, understood deeply, and referenced in interviews.

---

## 1. Networking Foundations (Conceptual)

### IP vs Port — Two Different Jobs

An **IP address** identifies a machine on a network. Think of it as a building's street address.

A **port** identifies a specific service running on that machine. Think of it as an apartment number inside the building.

```
IP Address:  127.0.0.1    → "Which machine?"
Port:        8080         → "Which door on that machine?"
```

When you run this project with `mvn spring-boot:run`, Tomcat (the embedded server) binds to `0.0.0.0:8080` — meaning it listens on port 8080 on all network interfaces of your machine.

### Why One Machine Can Run Multiple Services

Your machine has 65,535 ports available. Each service claims a port and listens there:
- Port 5432 → PostgreSQL
- Port 3306 → MySQL  
- Port 8080 → This Spring Boot app
- Port 443 → HTTPS web server

The operating system keeps track of which process owns which port. When a request arrives at port 8080, the OS delivers it to Tomcat, not to PostgreSQL.

### What `localhost` Really Means

`localhost` is an alias for `127.0.0.1` — the **loopback address**. It means "this machine, talking to itself."

When you call `curl http://localhost:8080/health`:
1. The request never leaves your machine
2. It goes through the network stack but loops back
3. It arrives at Tomcat as if it came from outside

This is why `localhost` is perfect for development — you test the full HTTP cycle without network latency or firewall issues.

### OS vs Application Responsibility — Where the Line Is

| Layer | Responsibility | Who Handles It |
|-------|----------------|----------------|
| Network routing | Delivering packets to the right machine | OS + Network |
| Port binding | Claiming a port, accepting connections | OS (via system calls) |
| TCP handshake | Establishing reliable connection | OS (kernel) |
| HTTP parsing | Understanding requests | Application (Tomcat) |
| Business logic | Deciding what to return | Application (Your code) |

**Key insight:** Your application code (the controllers in this project) never touches TCP directly. Tomcat handles HTTP. The OS handles TCP. You only deal with the request/response abstraction.

### Where the Application Starts and Networking Ends

When a request hits `/health`:
1. **OS level:** TCP connection established, bytes received
2. **Tomcat level:** Bytes parsed into HTTP request object
3. **Spring level:** Request routed to `HealthController.healthCheck()`
4. **Your code:** You return a `HealthResponse` object

Your code starts at step 4. Everything before that is invisible to you — by design.

---

## 2. Transport Layer (TCP) — What We Assume, Not Implement

### TCP Handshake and Reliability

TCP provides reliable, ordered delivery through:
1. **Three-way handshake:** SYN → SYN-ACK → ACK (before any data)
2. **Acknowledgments:** Every packet is acknowledged
3. **Retransmission:** Lost packets are resent
4. **Ordering:** Packets reassembled in correct order

You never see this in your application logs. That's correct behavior.

### Why Application Code Never Opens/Closes TCP Directly

In this project:
- Tomcat opens the TCP listener socket on startup
- Tomcat accepts connections and creates worker threads
- Your controller code receives a fully-parsed `HttpServletRequest`
- When you return a response, Tomcat serializes it and writes to the socket

**You trade control for simplicity.** This is the right tradeoff for 99% of applications.

### How TCP Behavior Still Affects Your Application

Even though you don't manage TCP, its behavior affects you:

| TCP Behavior | Application Impact |
|--------------|-------------------|
| Connection refused | Client gets immediate error (server not running) |
| Connection timeout | Slow failure (server unreachable) |
| Connection reset | Mid-request failure (server crashed) |
| Slow ACKs | Increased latency (network congestion) |
| Keep-alive timeout | Connection dropped between requests |

When a client retries after a TCP timeout, your application might process the same request twice. This is why idempotency matters — TCP reliability doesn't extend to application logic.

### Why Not Seeing TCP Logs Is Expected

This project logs at the HTTP level, not the TCP level. If you want TCP visibility:
- Use Wireshark to capture packets
- Enable Tomcat's low-level connector logging
- Use `netstat` or `ss` to see connection states

But for learning REST, HTTP-level observation is sufficient.

---

## 3. HTTP Fundamentals Observed in the Project

### Request–Response Lifecycle

Every HTTP interaction follows this pattern:
1. Client sends a request (method + URL + headers + optional body)
2. Server processes and returns a response (status + headers + optional body)
3. Connection may close or stay open (keep-alive)

From `HealthController`:
```java
@GetMapping("/health")
public ResponseEntity<HealthResponse> healthCheck() {
    // 1. Request arrived (GET /health)
    HealthResponse response = new HealthResponse(...);
    // 2. Response constructed
    return ResponseEntity.status(HttpStatus.OK).body(response);
    // 3. Spring/Tomcat serialize and send
}
```

The client never "pushes" multiple requests on one connection in parallel (HTTP/1.1). It waits for each response before sending the next.

### HTTP Methods and Why They Matter

| Method | Semantic | Safe? | Idempotent? |
|--------|----------|-------|-------------|
| GET | Retrieve data | Yes | Yes |
| POST | Create/action | No | No |
| PUT | Replace/upsert | No | Yes |
| DELETE | Remove | No | Yes |
| PATCH | Partial update | No | No |

**Safe** = Does not modify server state  
**Idempotent** = Calling N times has same effect as calling once

From this project:
- `GET /health` — Safe, idempotent (correct)
- `GET /bad-get` — Mutates state (VIOLATION)
- `POST /orders` — Creates new order (correct)
- `PUT /orders/{id}` — Upserts order (correct, idempotent)

### Status Codes as Machine Control Signals

Status codes are for programs, not humans. From `HealthController`:

```java
return ResponseEntity
    .status(isHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
    .body(response);
```

Why this matters:
- Load balancers read status codes to route traffic
- Monitoring systems trigger alerts on 5xx rates
- Clients decide whether to retry based on status

Returning `200 OK` with `{"status": "DOWN"}` in the body would fool machines. The load balancer would keep sending traffic to a sick instance.

### Headers vs Body Responsibilities

| Headers | Body |
|---------|------|
| Metadata about the message | The actual content |
| Always present | Optional |
| Key-value pairs | Any format (JSON, XML, binary) |
| Used for control flow | Used for data exchange |

From `EchoStateController`:
```java
@GetMapping("/echo-state")
public Map<String, Object> echoState(
    @RequestHeader("X-Client-State") String clientState
) { ... }
```

The `X-Client-State` header carries metadata (client's state), not application data. This is the stateless pattern — client carries its own context.

From `PaymentController`:
```java
@RequestHeader("Idempotency-Key") String idempotencyKey
```

The idempotency key is control information, so it belongs in headers, not the body.

### HTTP Success Does Not Mean Application Success

Consider `UserController`:
```java
if (emails.contains(req.email())) {
    return ResponseEntity.unprocessableEntity().body(...);  // 422
}
```

HTTP worked perfectly — request received, parsed, routed. But the application rejected it. The 422 status tells the client: "I understood you, but I can't do what you asked."

**Rule:** HTTP status = transport outcome + application outcome combined.

---

## 4. REST as an Architectural Discipline (Not a Framework)

REST is a set of constraints, not a library you import. Spring helps you follow REST, but it doesn't enforce it.

### Resource-Based URLs vs Action-Based URLs

**Resource-based (RESTful):**
```
GET    /users/123      → Get user 123
POST   /users          → Create a user
PUT    /users/123      → Replace user 123
DELETE /users/123      → Delete user 123
```

**Action-based (RPC-style):**
```
POST /getUser
POST /createUser
POST /updateUser
POST /deleteUser
```

From this project:
```java
@RequestMapping("/users")        // Resource
@PostMapping                     // Action determined by method
public ResponseEntity<?> createUser(...) { ... }
```

This is correct REST. The noun (`/users`) stays constant; the verb (POST, GET) changes.

### Statelessness — Why Server Memory Is Dangerous

Statelessness means: **Each request contains all information needed to process it.**

In this project, `HealthController` has:
```java
public volatile boolean isHealthy = true;
```

This is internal server state (for simulation). It's not client session state.

**Anti-pattern:** Storing user sessions in server memory.
- Server restarts → sessions lost
- Multiple servers → session affinity required
- Horizontal scaling → complicated

**Stateless pattern:** Client carries tokens (JWT), server validates and extracts identity from each request.

### Client-Carries-State Pattern

From `EchoStateController`:
```java
@RequestHeader("X-Client-State") String clientState
```

The client sends its state on every request. The server doesn't remember anything between requests. This enables:
- Any server can handle any request
- Easy horizontal scaling
- No session synchronization needed

### Uniform Interface — Predictability

REST's power comes from predictability:
- `GET` always retrieves
- `POST` always creates/acts
- `DELETE` always removes
- Status codes always mean the same thing

When you see `DELETE /orders/123` returning `204`, you know the order is gone without reading documentation.

### Bad REST vs Good REST — Both "Work"

**Bad REST that works:**
```java
@GetMapping("/bad-get")  // GET that mutates state
public Map<String, Object> badGet() {
    value++;  // Side effect!
    return Map.of("value", value, ...);
}
```

This "works" locally. You call it, get a response, life goes on.

**Why it's bad:**
- Browsers prefetch links (they assume GET is safe)
- Proxies cache GET responses
- Retry logic assumes GET is idempotent
- Crawlers will call your endpoint and mutate state

**Good REST:**
```java
@PostMapping("/orders")  // POST for creation
public Map<String, Object> placeOrder() {
    orderCount++;
    return Map.of("orderId", orderCount, ...);
}
```

The method correctly signals: "This request has side effects. Don't cache. Don't auto-retry."

---

## 5. HTTP Method Safety (GET Must Not Change State)

### What "Safe" and "Idempotent" Mean

**Safe:** The request does not modify any state on the server. You can call it a million times, and the server's data is unchanged.

**Idempotent:** Calling the request N times produces the same server state as calling it once. The result may differ (timestamps), but the state is the same.

| Method | Safe | Idempotent |
|--------|------|------------|
| GET | ✅ | ✅ |
| HEAD | ✅ | ✅ |
| POST | ❌ | ❌ |
| PUT | ❌ | ✅ |
| DELETE | ❌ | ✅ |

### Why GET Must Never Mutate State

From `BadGetController`:
```java
@GetMapping("/bad-get")
public Map<String, Object> badGet() {
    value++;  // VIOLATION
    return Map.of("value", value, ...);
}
```

**What goes wrong:**

1. **Browser prefetching:** Modern browsers prefetch links. They'll call your `/bad-get` to speed up navigation — and mutate your state.

2. **Proxy caching:** Proxies assume GET responses are cacheable. If the response is cached, subsequent calls don't even reach your server — but the first one already mutated state.

3. **Retry middleware:** If a network blip occurs, HTTP clients retry GET requests automatically. Each retry increments your counter.

4. **Crawlers and bots:** Search engines crawl links. They'll increment your counter while indexing.

### The Golden Rule (From the Comments)

> "If a request changes server state, it must NOT be GET."

> "GET may be repeated, cached, or retried without warning."

### Why "It Worked Locally" Is Misleading

Locally:
- No proxy caching
- No prefetching (unless you open it in a browser)
- No retry middleware (if you're using curl)
- No crawlers

In production:
- CDNs aggressively cache GET
- Load balancers retry failed GET requests
- Users' browsers prefetch links
- Bots constantly probe your endpoints

The same code behaves differently in production. That's the trap.

---

## 6. Idempotency and Retries (POST vs PUT)

### Why POST Is Not Safe to Retry

From `OrderController`:
```java
@PostMapping("/orders")
public Map<String, Object> placeOrder() {
    orderCount++;  // New order every time
    return Map.of("orderId", orderCount, ...);
}
```

If a network timeout occurs:
1. Client sends POST
2. Server processes, creates order #1
3. Response lost in transit
4. Client doesn't know if it succeeded
5. Client retries
6. Server creates order #2

**Result:** Two orders instead of one. This is called a **duplicate side effect**.

### Why PUT Is Idempotent by Design

From `OrderPutController`:
```java
@PutMapping("/orders/{id}")
public Map<String, Object> placeOrder(@PathVariable("id") String id) {
    orders.put(id, "CREATED");  // Same key = same outcome
    return Map.of("orderId", id, ...);
}
```

If a network timeout occurs:
1. Client sends PUT `/orders/abc123`
2. Server processes, creates order `abc123`
3. Response lost in transit
4. Client retries PUT `/orders/abc123`
5. Server overwrites order `abc123` with identical data

**Result:** One order, correctly. The second call doesn't create a duplicate.

### How Duplicate Side Effects Happen

```
   Client                   Server
     |                        |
     |--- POST /orders ------>|
     |                        | (creates order #1)
     |<-- 201 Created --------|
     |                        |
     |--- POST /orders ------>| (network timeout, client retries)
     |                        | (creates order #2)
     |<-- 201 Created --------|
     |                        |
```

The client called once (from its perspective). The server processed twice.

### Why Idempotency Keys Exist

From `PaymentController`:
```java
@PostMapping("/payments")
public Map<String, Object> createPayment(
    @RequestHeader("Idempotency-Key") String idempotencyKey
) {
    if (processKeys.contains(idempotencyKey)) {
        return Map.of("error", "Duplicate request", ...);
    }
    processKeys.add(idempotencyKey);
    // Process payment
}
```

The client generates a unique key (UUID) and sends it with every request. If the client retries, it sends the same key. The server recognizes the duplicate and returns the original result instead of processing again.

**This makes POST idempotent at the application layer.**

### Client Responsibility vs Server Responsibility

| Who | Responsibility |
|-----|----------------|
| Client | Generate and persist idempotency keys before calling |
| Client | Retry with the same key on failure |
| Server | Store processed keys |
| Server | Return original result for duplicate keys |
| Server | Eventually expire old keys |

Neither side can solve this alone. It's a contract.

### Payment and Order System Design Implications

For any operation where:
- Side effects are irreversible (charging money)
- Duplicates are catastrophic (double orders)
- Network reliability is not guaranteed (always)

You need:
1. Client-generated idempotency keys
2. Server-side deduplication
3. Key expiration policy
4. Possibly: At-least-once processing with dedup, not exactly-once

---

## 7. Error Modeling in REST

### 400 vs 422 — Both Client Errors, Different Meanings

From `UserController`:

**400 Bad Request** — Malformed, unparseable:
```java
if (req == null || req.email() == null) {
    return ResponseEntity.badRequest().body(Map.of(
        "error", "BAD_REQUEST",
        "message", "email is required"
    ));
}
```

The request itself is broken. The server couldn't even understand what you wanted.

**422 Unprocessable Entity** — Valid format, invalid content:
```java
if (!req.email().contains("@")) {
    return ResponseEntity.unprocessableEntity().body(Map.of(
        "error", "VALIDATION_FAILED",
        "message", "email format is invalid"
    ));
}
```

The request is well-formed JSON. The server understood it. But the data violates business rules.

**When to use which:**
- JSON parsing failed → 400
- Required field missing → 400
- Email format invalid → 422
- Username taken → 422 (or 409 Conflict)

### 404 vs 204 — Resource Semantics

**404 Not Found:** The resource you asked for doesn't exist.
```
GET /users/999 → 404 (no such user)
```

**204 No Content:** The action succeeded, nothing to return.
```
DELETE /users/123 → 204 (deleted, no body)
```

**Anti-pattern:** Returning 200 with `{"data": null}` for a missing resource. This makes clients parse the body to detect errors instead of checking the status code.

### 4xx vs 5xx — Who Is Responsible?

**4xx:** Client made a mistake. Fix your request.
- 400: Your request is broken
- 401: You're not authenticated
- 403: You're not authorized
- 404: Resource doesn't exist
- 422: Your data is invalid

**5xx:** Server made a mistake. Retry might help.
- 500: Something crashed
- 502: Upstream service failed
- 503: Server overloaded
- 504: Upstream timed out

From `ApiExceptionHandler`:
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<?> handleException(Exception ex) {
    return ResponseEntity.status(500).body(...);
}
```

Unhandled exceptions become 500s. This is correct — an unexpected crash is the server's fault.

### Why Status Codes Matter More Than Messages

```java
// Bad: Message-based error detection
if (response.body.error == "NOT_FOUND") { ... }

// Good: Status-based error detection
if (response.status == 404) { ... }
```

Status codes are:
- Standardized (everyone agrees what 404 means)
- Machine-readable (no parsing needed)
- Language-agnostic (no localization issues)
- Inspectable without body parsing

Messages are for humans. Status codes are for machines.

### Error Contracts and Automation

Consistent error responses enable automation:
- Monitoring dashboards count 5xx rates
- Retry policies trigger on 503, not 422
- API gateways route based on status
- Client SDKs map status to exceptions

From `UserController`, every error includes:
- `error`: Machine-readable code
- `message`: Human-readable explanation
- `field` (when relevant): Which input was wrong

This is a contract. Changing it breaks clients.

---

## 8. API Versioning (Practical, Not Theoretical)

### Why REST Tries to Avoid Versioning

Versioning is a failure mode, not a feature. It means:
- You made a breaking change
- Old clients still exist
- You're maintaining multiple codepaths

Good API design minimizes the need for new versions by:
- Adding fields (backward compatible)
- Using optional parameters
- Deprecating instead of removing

### What Counts as a Breaking Change?

**Breaking (requires new version):**
- Removing a field
- Renaming a field (`email` → `contactEmail`)
- Changing a field type (`id: int` → `id: string`)
- Changing URL structure
- Changing authentication scheme

**Non-breaking (same version):**
- Adding a new optional field
- Adding a new endpoint
- Adding a new HTTP method to existing endpoint
- Making a required field optional

### v1 vs v2 Example from This Project

From `UserV1Controller`:
```java
@RequestMapping("api/v1/users")
// ...
return Map.of("id", id, "email", "a@b.com");
```

From `UserV2Controller`:
```java
@RequestMapping("api/v2/users")
// ...
return Map.of("id", id, "contactEmail", "a@b.com", "createdAt", Instant.now());
```

**The breaking change:** `email` → `contactEmail`

If v2 just added `contactEmail` alongside `email`, no new version would be needed. But renaming forced a version bump.

### URL Versioning vs Header Versioning

**URL versioning (used here):**
```
GET /api/v1/users/123
GET /api/v2/users/123
```

Pros:
- Explicit and visible
- Easy to route at load balancer
- Easy to cache separately
- Easy to document

Cons:
- Pollutes URL space
- Hard to deprecate (URLs live forever)

**Header versioning:**
```
GET /api/users/123
Accept: application/vnd.myapi.v2+json
```

Pros:
- Clean URLs
- Client controls version
- Easier content negotiation

Cons:
- Invisible in logs
- Harder to cache
- Easy to forget

### Why Backward Compatibility Matters in Distributed Systems

In a distributed system:
- Old clients can't all upgrade instantly
- Mobile apps might not update for months
- Third-party integrations have their own release cycles
- Rolling deployments mean old and new servers coexist

If you break the API, you break those clients. They may not even know they're broken until production fails.

**Rule:** Assume clients you don't control are calling your API.

---

## 9. Mental Models & One-Liners

### HTTP Method Rules
> "GET reads. POST creates. PUT replaces. DELETE removes."

> "If it changes state, it's not GET."

> "GET can be cached, prefetched, and retried. POST cannot."

### Idempotency
> "Idempotent means: do it once, do it twice, same result."

> "PUT is idempotent because you're saying 'this is the state', not 'change the state'."

> "POST with an idempotency key is application-level idempotency."

### Statelessness
> "Stateless means: every request is a stranger."

> "The server forgets you between requests. The client must remind."

> "If the server needs to remember, store it in a database, not memory."

### Status Codes
> "Status codes are for machines. Messages are for humans."

> "4xx = client's fault. 5xx = server's fault."

> "200 doesn't mean success. It means HTTP worked."

### REST Design
> "REST is about resources, not actions."

> "URL = noun. Method = verb."

> "If you're putting verbs in URLs, you're doing RPC, not REST."

### Error Handling
> "400 = I can't parse you. 422 = I parsed you but disagree."

> "Never return 200 with an error in the body."

### Networking
> "IP = which machine. Port = which door."

> "localhost is your machine talking to itself."

> "You write HTTP. The OS handles TCP."

### Versioning
> "The best API version is no version."

> "Add fields freely. Remove fields never."

> "Breaking changes require version bumps. Non-breaking changes don't."

### Systems Thinking
> "It worked locally means nothing. Production has retries, caches, and bots."

> "Networks lie. Timeouts don't mean failure."

> "If you can't handle duplicates, your system isn't production-ready."

---

## 10. Interview Readiness Section

### "Explain how a request flows from client to server."

**Clear answer:**

"When a client sends an HTTP request, it first goes through DNS to resolve the hostname to an IP address. The client's OS opens a TCP connection to the server's IP and port — that's the three-way handshake. Once TCP is established, the HTTP request is sent as bytes over that connection.

On the server side, the OS accepts the TCP connection and hands it to the web server — in our case, Tomcat embedded in Spring Boot. Tomcat parses the raw bytes into an HTTP request object: method, URL, headers, body. Spring's dispatcher then routes the request to the appropriate controller based on URL patterns and HTTP method.

My controller code receives a high-level abstraction — I never see TCP packets. I process the request, return a response object, and Spring serializes it back to HTTP format. Tomcat writes the bytes to the TCP connection, and the response travels back to the client.

The key insight is separation of concerns: the OS handles TCP reliability, Tomcat handles HTTP parsing, Spring handles routing, and my code handles business logic."

### "Why is REST stateless?"

**Clear answer:**

"Stateless means each request contains all the information needed to process it. The server doesn't remember anything about previous requests.

This matters for three reasons:

First, scalability. If any server can handle any request, you can add servers freely. With stateful sessions, you need sticky sessions or session replication — both add complexity.

Second, reliability. If a server crashes, no session data is lost. Another server picks up the next request seamlessly.

Third, cacheability. Stateless requests are self-contained, so proxies and CDNs can cache responses without worrying about session context.

The tradeoff is that clients must send authentication tokens and context on every request. But that's a small price for horizontal scalability."

### "What is idempotency and why does it matter?"

**Clear answer:**

"An operation is idempotent if performing it multiple times produces the same server state as performing it once. GET, PUT, and DELETE are idempotent by HTTP specification. POST is not.

It matters because networks are unreliable. When a client sends a request and doesn't get a response, it doesn't know if the server processed it or not. With an idempotent operation, the client can safely retry — worst case, the same thing happens twice with no additional effect.

For non-idempotent operations like POST, retrying can create duplicates. That's why payment systems use idempotency keys — a client-generated identifier that the server uses to detect retries. If the server sees the same key twice, it returns the cached result instead of processing again.

In our project, the `PUT /orders/{id}` endpoint is idempotent because putting the same order twice just overwrites the same key. The `POST /orders` endpoint is not idempotent — each call creates a new order."

### "How do retries break systems?"

**Clear answer:**

"Retries break systems when the retry creates a duplicate side effect.

Consider a payment: the client sends a charge request, the server processes it, but the response is lost to a network timeout. The client doesn't know the payment succeeded, so it retries. Now the customer is charged twice.

Or an order system: retry creates a duplicate order. Or a messaging system: retry sends the same message twice.

The fix is two-fold. First, design idempotent operations where possible — PUT and DELETE naturally support this. Second, for non-idempotent operations, implement application-level idempotency with client-generated keys.

The deeper insight is that exactly-once delivery is impossible in distributed systems. We achieve it by combining at-least-once delivery with deduplication. The server processes the request at least once, but ignores duplicates."

### "How do you design reliable APIs?"

**Clear answer:**

"Reliable APIs are predictable, recoverable, and observable.

Predictable means following HTTP semantics correctly. GET never mutates. Status codes match outcomes. Error responses have consistent structure. Clients can build automation because behavior is standard.

Recoverable means handling failures gracefully. Idempotent endpoints allow safe retries. Non-idempotent endpoints accept idempotency keys. Errors clearly indicate whether retry might help — 503 means try again, 422 means fix your input.

Observable means machines can understand outcomes. Status codes signal success or failure. Health endpoints report service state honestly — returning 503 when sick, not 200 with a sad message. This enables load balancers, monitors, and clients to make correct decisions.

I'd also add: version carefully. Adding fields is safe. Removing fields breaks clients. If you must break, version the URL so old clients keep working.

In essence, reliable APIs assume the worst — networks fail, clients retry, servers scale — and design for that reality from day one."

---

## Closing Thoughts

This project is small, but it encodes deep principles:

1. **Layering works.** You write business logic; frameworks handle HTTP; the OS handles TCP.

2. **Semantics matter.** The difference between GET and POST isn't syntax — it's a contract with every proxy, cache, and client between you and the user.

3. **Production is different.** Local testing hides the retries, caches, and bots that will call your API in production.

4. **Failures are normal.** Networks timeout. Requests retry. Servers crash. Your design must assume this.

5. **Contracts are forever.** Once you publish an API, someone depends on it. Breaking changes break trust.

The journey from networking to REST is a journey from bytes to contracts. The protocols handle the bytes. Your job is to honor the contracts.

---

*NetTrace Project — A learning lab for networking fundamentals.*


