# The "Build it" playbook

How to spend the hour. The exercise is staged: they add a requirement, you extend the design.
**Finishing three stages with clean, tested code beats finishing five with a mess.** The brief says
so explicitly — "keep your solution simple in order to complete the necessary amount of stages".

## Before the call

- `mise run setup`, then `mise run test`. Confirm green. Do this the day before *and* an hour
  before — a toolchain download at minute two is the worst possible start.
- Open `docs/SNIPPETS.md` in a second window. Not to copy blindly; to avoid burning ninety seconds
  remembering `CompletableFuture` reservation syntax.
- Have `src/main/java/io/github/tabmadi/interview/` and `StarterTest` open and ready to type into.
- Screen share, camera, headset tested. They asked; they mean it.

## Minutes 0–5: clarify, do not code

You are graded on this. Ask, then restate the answer back as a one-line summary before typing.

**The questions that actually change the design:**

1. **Single process or distributed?** Almost always "assume one JVM, in-memory" for stage 1. Get
   this said out loud — it is what licenses you to use a `ConcurrentHashMap` instead of apologising
   for not using Postgres.
2. **What are the concurrency expectations?** How many concurrent callers? Is this a library called
   by many threads, or a service with a request loop? Read-heavy or write-heavy?
3. **What is the consistency requirement?** Must a read see the immediately preceding write? Is a
   stale read acceptable? Can an operation be rejected, or must it queue?
4. **Duplicates and retries.** If a caller submits the same request twice, what should happen?
   (Ask this *early*, even if idempotency is a later stage. It shapes the API — an idempotency key
   in the signature from minute one costs nothing; retrofitting it costs a rewrite.)
5. **Failure semantics.** Exception or a result type? What is the expected error on an invalid
   input — throw, or return empty?
6. **Scale envelope.** How many keys/accounts/entries? Bounded or unbounded? This decides whether
   "never evict" is acceptable.
7. **What is out of scope?** Persistence, networking, auth, currency conversion. Say "I'll assume
   X is out of scope unless you want it" and let them correct you.

**Then state your plan in two sentences** before you write a line: "I'll start with the interface
and a test for the happy path, use a `ConcurrentHashMap` keyed by account with a per-key lock, and
we can talk about sharding or persistence when we get there."

## Minutes 5–45: build in stages

**The loop, per stage:**

1. Write the failing test with a `@DisplayName` that states the behaviour.
2. Simplest implementation that passes.
3. Say what you deliberately did not do and why. ("I'm not handling eviction yet — unbounded growth
   is fine for the keyspace we agreed on, and I'd add a size cap if that changed.")

**Rules that keep you out of trouble:**

- **Start single-threaded and correct.** Add thread safety when the stage asks for it or when you
  have five spare minutes. A correct serial solution scores far above a broken concurrent one.
- **Push validation to the boundary.** Constructors and public entry points reject bad input;
  everything downstream assumes valid state. Say this once, then it is invisible.
- **Immutable value objects, mutable state in one place.** Records for data, one class that owns the
  mutation and its locking.
- **No `synchronized` on `this`, no locking on a public object.** If the lock is reachable by
  callers, the locking policy is not yours to reason about.
- **Never `double` for money.** `long` minor units. If they hand you a `double` in the spec, say why
  you are changing it.
- **Inject the clock.** `Clock` or a `LongSupplier` for `nanoTime`. It costs one parameter and makes
  time-dependent behaviour testable without `Thread.sleep`.
- **Say "I'd extract this, but not in this timebox" out loud** rather than doing it. Narrating the
  trade-off gets the credit; doing it costs a stage.

**When you get stuck,** say what you are considering and ask. Silence looks like confusion; thinking
aloud looks like reasoning. They said they are "interested in understanding your reasoning."

## Minutes 45–55: prove it

If you have tests, you have this already. Otherwise add, in priority order:

1. The invariant test. Whatever the system must never do — go negative, double-charge, lose an item.
2. The concurrency test. `Concurrently.run(...)` with `@RepeatedTest`, asserting the invariant still
   holds after contention. This is the single most differentiating thing you can put on screen for
   this specific role.
3. The boundary tests. Empty, zero, negative, missing key, same key twice.

Run the suite on screen. A green bar at the end of a live-coding session is the close.

## Minutes 55+: the discussion

They move to "broader system considerations". Have these ready as short answers, not lectures.

- **"How would you scale this?"** Name the bottleneck first: the single lock, or the single process.
  Then: shard by key so state is partitioned rather than shared → each shard owns its keys → routing
  by consistent hash → the cross-shard operation (a transfer between two shards) becomes a
  distributed transaction, which is where you introduce a saga or an outbox.
- **"How would you persist this?"** Postgres, the table layout, the index, and the isolation level —
  see `docs/POSTGRES.md`. The key sentence: *move the invariant into the database* — a unique index
  on the idempotency key, a `CHECK (balance >= 0)`, `SELECT ... FOR UPDATE` in a consistent order.
  Application-level locks do not survive a second instance.
- **"What breaks at 80M users?"** Hot keys (one account everyone pays), unbounded maps, `synchronized`
  around I/O, connection pool starvation, and anything that is O(n) in the number of accounts.
- **"How do you test concurrency?"** Barrier-released threads + invariant assertions + repetition;
  deterministic tests with an injected clock; `jcstress` for JMM-level claims; and honesty that a
  passing concurrency test proves the bug did not appear, not that it cannot.
- **"What would you do differently with more time?"** Have two real answers ready. Both should be
  things you consciously skipped, not things you missed.

## Using GenAI during the interview

They explicitly allow it. Use it the way a senior engineer uses it: to type out a shape you have
already decided on, not to decide the shape. Read every line before it lands, and be able to defend
it — the follow-up discussion is where a pasted answer you cannot explain becomes fatal.
