# Concurrency in Java, for the interview

The JD leads with "Concurrency & Multithreading (managing race conditions and non-blocking I/O at
scale)". This is the page to reread the morning of.

## The memory model, in the only form that matters live

A race is not "two threads touching a variable". A race is **two accesses, at least one a write, not
ordered by a happens-before edge**. Without an edge, a thread may never see the other's write — not
"eventually sees it", *never*: the JIT is entitled to hoist the read out of the loop.

The edges you can create:

| Edge | Created by |
| --- | --- |
| Program order | Within a single thread |
| Monitor | An `unlock` happens-before every subsequent `lock` of the same monitor |
| Volatile | A write to a `volatile` happens-before every subsequent read of it |
| Final field | A correctly constructed object's `final` fields are visible without synchronisation |
| Thread start/join | `t.start()` sees everything before it; `t.join()` sees everything `t` did |
| Concurrent collections | A put happens-before a get that observes it |
| Futures / latches | `complete`/`countDown` happens-before `join`/`await` returning |

**`volatile` gives visibility and ordering, never atomicity.** `count++` on a `volatile` is still a
lost update: it is a read, an add, and a write. Use `AtomicLong`, or a lock.

**Safe publication** is the thing people get wrong: an object handed to another thread through a
non-final, non-volatile field can be seen *partially constructed*. Publish through a `final` field,
a `volatile` field, a concurrent collection, or a lock — and never let `this` escape a constructor.

## Choosing a mechanism

Pick the weakest tool that is correct.

1. **No shared mutable state.** Immutable records, thread confinement, a copy per thread. Zero
   synchronisation is always the fastest synchronisation.
2. **A concurrent collection.** `ConcurrentHashMap` with `compute`/`merge`/`putIfAbsent` covers a
   remarkable share of real problems atomically and lock-free on reads.
3. **Atomics / CAS.** One word of state, or an immutable state object behind an `AtomicReference`
   swapped in a retry loop — see `TokenBucketRateLimiter`. Lock-free, no deadlock to reason about,
   but livelock under extreme contention and you must be able to re-run the computation.
4. **A lock.** `synchronized` for simple mutual exclusion; `ReentrantLock` when you need `tryLock`,
   a timeout, interruptibility, or multiple `Condition`s. `StampedLock` for read-dominated data
   where an optimistic read can be validated and retried.
5. **A single-threaded executor / actor.** Serialise access by funnelling all mutation through one
   thread. Often the simplest correct answer for complex invariants.

## Rules that prevent the bugs they will look for

- **Never do I/O or call unknown code while holding a lock.** An HTTP call under a lock converts a
  slow dependency into a full stall. Compute under the lock, do I/O outside it.
- **Lock ordering is the whole deadlock story.** Two locks means a total order over them — sort by
  id, hash, or any stable key. `KeyedLocks.withBothLocks` is the pattern.
- **Check-then-act is a race unless it is atomic.** `if (!map.containsKey(k)) map.put(k, v)` is
  broken; `putIfAbsent` is not. `if (balance >= amount) balance -= amount` is broken unless both
  halves are under the same lock.
- **Validate everything, *then* mutate.** Once the first mutation lands there is no rollback path in
  memory. Both `InMemoryLedger.transfer` and `Account.requireCanDebit` exist for this reason.
- **Always `unlock` in a `finally`.**
- **`ConcurrentHashMap` is atomic per operation, not across operations.** A `get` followed by a
  `put` is two operations; `compute` is one.
- **Keep `computeIfAbsent` mapping functions short and side-effect free.** They run while holding a
  bin lock and must not touch the same map. Slow loading belongs in the reservation pattern
  (`ExpiringCache`), not inside `computeIfAbsent`.
- **Interruption is a protocol, not an error.** Catching `InterruptedException` and swallowing it
  breaks cancellation; either propagate it or restore the flag with
  `Thread.currentThread().interrupt()`.

## Virtual threads (Java 21+) — the answer to "non-blocking I/O at scale"

A virtual thread is scheduled by the JVM onto a small pool of carrier threads. A blocking call
*unmounts* it instead of parking an OS thread, so "thread-per-request" becomes viable at a million
concurrent requests. This is the Java answer to "we do this with goroutines in Go".

- `Executors.newVirtualThreadPerTaskExecutor()` — one task, one thread, no pool sizing to tune.
- **Do not pool them.** They are cheap; pooling them reinstates the limit you were escaping.
- **They help blocking I/O, not CPU work.** For CPU-bound parallelism you still want a bounded pool
  sized near the core count.
- **Pinning:** a virtual thread inside a `synchronized` block that blocks used to pin its carrier
  thread. Java 24 removed most of this, but the habit stands: use `ReentrantLock` instead of
  `synchronized` around anything that can block.
- **Backpressure moves, it does not disappear.** With no thread pool bounding concurrency, the
  limiter has to be explicit — a `Semaphore`, a queue, or a rate limiter — or you will simply
  overwhelm the database instead of the thread pool.
- **Structured concurrency** (`StructuredTaskScope`) scopes concurrent subtasks to a block: fan out,
  join, and a failure or cancellation propagates to siblings. This is Go's `errgroup`/`context`
  story, and naming it is a good signal.

## Testing concurrency

A concurrency test that starts threads in a loop usually proves nothing — the first thread finishes
before the last one starts. `Concurrently` in `src/test` exists to fix exactly that:

- All threads park on one latch and are released together.
- Platform threads, not virtual ones: virtual threads multiplex onto few carriers and serialise the
  overlap you are trying to create.
- Failures from every thread are collected and rethrown; a silent worker exception is not a pass.
- A timeout, because a deadlock must fail the build rather than hang it. The Gradle config also sets
  a 30s per-test default timeout for the same reason.
- `@RepeatedTest(5..10)`, because a race that reproduces 20% of the time passes once.
- Assert **invariants**, not schedules: money conserved, never negative, exactly one load, exactly
  `capacity` grants. Never assert an interleaving.

Say out loud: *"this proves the bug didn't occur, not that it can't — `jcstress` is what proves JMM
claims, and a model checker is what proves protocol claims."*

## Performance vocabulary they may probe

- **False sharing** — two hot variables on one 64-byte cache line make independent threads fight.
  `@Contended`, or padding, or restructure so each thread owns its own data.
- **Contention vs. throughput** — a single global lock makes throughput independent of core count.
  Striping (`KeyedLocks`) restores scaling; the limit becomes the hottest single key.
- **Lock-free is not wait-free** — CAS loops retry; under pathological contention throughput can
  *fall* as cores are added. Measure before claiming a win.
- **Read-mostly** — `StampedLock` optimistic reads, `CopyOnWriteArrayList` for tiny rarely-written
  lists, or immutable snapshots swapped atomically.
- **Amdahl** — the serial fraction is the ceiling. Shrinking the critical section beats adding
  threads.
