# Event Bus V1 — How My Thinking Changed

## Why I’m Writing This

This is not about the code.

This is about how I thought before,
what I misunderstood,
what changed,
and how I should think next time.

If I forget the code, that’s fine.

If I forget how to think, that’s dangerous.

---

# Phase 0 — My Initial Instincts

When we started:

* I wanted to design APIs first.
* I confused offset with consumer offset.
* I thought atomic meant durable.
* I wanted the server to generate eventId.
* I thought rejecting duplicates in the bus was “clean”.
* I thought scalability meant “more topics”.

That was surface-level thinking.

Not wrong — just incomplete.

---

# The First Real Shift

## 1️⃣ Logs Are History, Not State

I used to think of it like:

> “Store events.”

Now I understand:

> “Preserve history.”

That changes everything.

History cannot be:

* Updated
* Deleted
* Reordered

Once I internalized that, append-only made sense.

---

# The Second Real Shift

## 2️⃣ Separation of Responsibility Is Everything

Before, I mixed concerns.

Now I see clearly:

Producer:

* Owns identity
* Must survive retries

Event Bus:

* Owns ordering
* Must stay dumb
* Must stay predictable

Consumer:

* Owns correctness
* Must handle duplicates
* Must track progress

The bus should not:

* Deduplicate
* Track consumers
* Enforce semantics

This separation is the backbone of scalable systems.

---

# The Third Real Shift

## 3️⃣ Atomicity Is Not Durability

This one changed how I see backend systems.

Atomic:

* Thread correctness

Durable:

* Crash survival

You can have:

* Atomic but not durable (our v1)
* Durable but not atomic
* Both
* Neither

They are separate axes.

Before this, I blended them mentally.

Now I don’t.

---

# The Fourth Real Shift

## 4️⃣ Concurrency Must Protect Invariants, Not Just Code

Earlier, I thought:

> “Use a lock because multithreading.”

Now I think:

> “What invariant am I protecting?”

Invariant:

* Offset must be unique and increasing.

That’s why we lock.

Not because “Java needs lock”.

Because invariants must be preserved.

That mindset shift is huge.

---

# The Fifth Real Shift

## 5️⃣ Distributed Problems Are Not REST Problems

When response gets lost:

My instinct:

> HTTP issue.

Correct thinking:

> Distributed systems issue.

REST only defines transport.

Retries, duplicates, crash recovery —
that’s distributed systems behavior.

I now separate transport from system semantics.

---

# What I Now Understand Deeply

* Offset is log position, not consumption state.
* Consumers own their progress.
* Duplicate delivery is normal.
* Exactly-once is expensive.
* Simplicity scales better than cleverness.
* Determinism matters more than features.

---

# My Biggest Mistake

I kept thinking in terms of:

> “How do I code this correctly?”

Instead of:

> “What must always remain true?”

When I switched to invariants-first thinking,
design became clearer.

---

# How I Should Think Next Time

If I design any system now, I should ask:

1. What are the invariants?
2. Who owns identity?
3. Who owns ordering?
4. Who owns correctness?
5. What happens if network fails?
6. What happens if process crashes?
7. What happens if retry happens?
8. Where can race conditions break invariants?

If I cannot answer those,
I don’t understand the system yet.

---

# What Changed in Me

Before:

* I saw distributed systems as complicated tools.
* Kafka felt magical.
* Retry felt scary.
* Idempotency felt abstract.

Now:

* Kafka is just a durable distributed log.
* Offset is just a position in history.
* Retry is normal.
* Idempotency is a responsibility boundary.

That’s a big change.

---

# The Most Important Mental Upgrade

Before:

> “How do I implement this?”

Now:

> “What invariant must never break?”

That is the difference between
someone who writes backend code
and someone who designs backend systems.



