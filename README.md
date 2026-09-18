# Revolut "Build it" interview prep

A ready-to-type Java 25 project for a live-coding session, plus the reference material behind it.
Two things it gives you: a build that will not get in your way at minute two, and three worked
katas covering the exact ground the role advertises — concurrency, correctness under contention, and
money that never goes missing.

## Right now, before anything else

```sh
mise run setup    # installs the toolchain and git hooks, warms the dependency cache
mise run test     # should be green in a few seconds
```

Do this the day before the interview as well as an hour before it.

## During the interview

Write in `src/main/java/io/github/tabmadi/interview/` — it is empty on purpose. Its test counterpart
`StarterTest` is a template to type over: nested classes per stage, `@DisplayName`s that state
behaviours, and a working concurrency test.

```sh
mise run tdd      # continuous test loop -- leave it running in a split pane
```

Read [docs/PLAYBOOK.md](docs/PLAYBOOK.md) first. It is the minute-by-minute: what to ask in the first
five minutes, how to stage the build, and what to have ready for the discussion afterwards.

## What is here

### Katas — read these, do not copy them

Reference implementations of the three problems this interview keeps circling. Each is small,
heavily commented with the *reasoning* rather than the mechanics, and covered by tests that fail if
the concurrency is wrong.

| Kata | The thing it teaches |
| --- | --- |
| [`katas/ledger`](src/main/java/io/github/tabmadi/katas/ledger) | Double-entry accounts, atomic transfers, deadlock-free two-key locking, idempotency keys, an audit trail. The canonical staged exercise. |
| [`katas/ratelimiter`](src/main/java/io/github/tabmadi/katas/ratelimiter) | A lock-free token bucket: CAS over immutable state, lazy refill without drift, no background threads. |
| [`katas/ttlcache`](src/main/java/io/github/tabmadi/katas/ttlcache) | TTL expiry and single-flight loading — how a cache avoids turning one miss into a thousand queries. |

Shared building block: [`concurrent/KeyedLocks`](src/main/java/io/github/tabmadi/concurrent/KeyedLocks.java)
— one lock per key, with ordered acquisition for the two-key case.

Test harness: [`support/Concurrently`](src/test/java/io/github/tabmadi/support/Concurrently.java) —
parks every worker on one latch and releases them together, so the contended window is real. A
concurrency test without this usually proves nothing.

### Notes

| Document | Use it for |
| --- | --- |
| [PLAYBOOK.md](docs/PLAYBOOK.md) | How to spend the hour: clarifying questions, staging, closing |
| [CONCURRENCY.md](docs/CONCURRENCY.md) | JMM, choosing a mechanism, virtual threads, testing races |
| [POSTGRES.md](docs/POSTGRES.md) | MVCC, isolation, locking, `SKIP LOCKED`, indexes, reading a plan |
| [SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md) | DDD, idempotency, outbox, sagas, scaling the kata to 80M users |
| [GO_TO_JAVA.md](docs/GO_TO_JAVA.md) | The translation table, and what to say about the switch |
| [SNIPPETS.md](docs/SNIPPETS.md) | Shapes you should not be re-deriving live |

## The build

Java 25 (toolchain-pinned, so `JAVA_HOME` is irrelevant), Gradle with the Kotlin DSL, JUnit 5,
AssertJ, Awaitility.

**Static analysis is deliberately relaxed by default.** Spotless, Error Prone and NullAway all run,
but warnings stay warnings: nothing derails a timed exercise faster than a build refusing to compile
over a missing annotation. The full gate is one flag away.

| Command | What it does |
| --- | --- |
| `mise run test` | Fast test run |
| `mise run tdd` | Continuous test loop |
| `mise run kata ledger` | Run one kata's tests |
| `mise run format` | Spotless (palantir-java-format) |
| `mise run check` | format + compile + test |
| `mise run verify` | The full gate: `-Werror`, NullAway as errors, 80% coverage floor |

Details of layout and conventions live in [AGENTS.md](AGENTS.md).
