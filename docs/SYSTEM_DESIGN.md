# System design & DDD, for the discussion round

## Domain-Driven Design, the parts they will actually ask about

- **Ubiquitous language.** The code says `Transfer`, `Ledger`, `Statement` because the business says
  those words. `TransactionManagerServiceImpl` says nothing.
- **Bounded context.** "Account" in Payments, in KYC, and in Support are three different models with
  three different invariants. One shared `Account` class across all three is the failure mode DDD
  exists to name. Contexts integrate through published events or an anti-corruption layer, not a
  shared table.
- **Aggregate.** A consistency boundary: one root, and every invariant that must hold atomically
  lives inside one aggregate. **Design rule: one transaction, one aggregate.** Across aggregates you
  get eventual consistency and a saga, deliberately.
  - Applied to the ledger: is a `Transfer` an aggregate spanning two accounts, or is each `Account`
    an aggregate with the transfer as a saga? Single-database answer: make the transfer the
    aggregate and lock both accounts. Sharded answer: two aggregates, saga, compensations. Being
    able to argue both sides is the point.
- **Entity vs value object.** `Account` has identity and a lifecycle; `Money` and `AccountId` are
  values — immutable, compared by value, safe to share across threads for free.
- **Domain events.** `TransferAccepted` as a fact, in past tense, that other contexts subscribe to.
- **Repository.** Collection-like access to aggregates; the domain does not know about SQL.
- **Keep the domain free of frameworks.** Business rules in plain objects, unit-testable without
  Spring, a database, or a clock you cannot control.

## The patterns worth naming in a payments interview

**Idempotency.** Client-generated key, stored with a unique constraint, in the same transaction as
the effect. Return the *stored original response* on a duplicate, not a fresh one. Distinguish
"retry of the same request" from "a genuinely new request that happens to look alike" — hash the
request body and reject a key reused with different content. See `InMemoryLedger.once` for the
in-memory version of the same shape.

**Exactly-once delivery does not exist.** At-least-once delivery + idempotent consumers = effectively
once. Say it in those words.

**Transactional outbox.** State change and event, one transaction, one table. A relay publishes
at-least-once. Avoids the dual-write problem (DB committed, broker didn't, or vice versa). CDC
(Debezium) is the same idea driven off the WAL.

**Saga.** A multi-step business transaction across aggregates or services, each step with a
compensating action. Choreography (events, no coordinator, easy to start, hard to debug) vs
orchestration (a coordinator owns the state machine, easier to reason about, one more component).
For payments, orchestration usually wins because you need to answer "where is my money" precisely.

**Event sourcing.** Store the events, fold them into state. Perfect audit trail — which a bank needs
anyway — plus temporal queries and rebuildable projections. Costs: schema evolution of events,
snapshotting, and eventual consistency on the read side. Note that **double-entry bookkeeping is
event sourcing**, invented five hundred years earlier.

**CQRS.** Separate the write model (invariants, normalised) from read models (denormalised, fast).
Introduce it when read and write shapes genuinely diverge, not by default.

**Consistency.** Strong where money moves, eventual where it is only observed. Be explicit about
which side of that line each feature sits on. CAP in practice: during a partition a payments system
chooses C over A and refuses the transaction — declining is recoverable, double-spending is not.

## Scaling the kata: the answer to "how would this work at 80M customers"

Walk the stages in this order; it shows you scale by moving bottlenecks, not by naming technologies.

1. **One process, per-key locks.** Where the kata lands. Bottleneck: one machine, no durability.
2. **One Postgres, N app instances.** Locking moves into the database: `SELECT ... FOR UPDATE` in a
   consistent order, `UNIQUE` on the idempotency key, `CHECK (balance >= 0)`. The app becomes
   stateless and horizontally scalable. Bottleneck: single-writer database.
3. **Read replicas + CQRS.** Balance reads and statements go to replicas; writes stay on the primary.
   Bottleneck: replication lag — a user who just paid must not see a stale balance, so route
   read-your-writes back to the primary or version the read.
4. **Partition the write side.** Shard by account id. Now a transfer within a shard is a local
   transaction; a transfer across shards is a saga with a pending/settled state machine — money
   leaves one side, sits in flight, lands on the other. Bottleneck: hot shards.
5. **Hot keys.** One merchant account receiving millions of credits serialises. Fix by splitting it
   into N sub-balances that are summed (per-shard counters), since credits commute — the trick is
   that only *debits* need the full balance.
6. **Operations at this scale:** idempotent retries everywhere, backpressure and load shedding at
   the edge, circuit breakers on dependencies, rate limits per customer, a reconciliation job that
   re-derives balances from entries and alerts on drift, and audit logs that are append-only.

## Non-functionals to raise unprompted

Observability (metrics, structured logs with a correlation id, tracing), graceful degradation,
deployment safety (expand/contract migrations, feature flags), data retention and GDPR, PCI scope,
and testing strategy (unit → integration with Testcontainers → contract → load). Mentioning
Testcontainers against a real Postgres, rather than an in-memory fake, lands well with a team that
cares about "PostgreSQL architecture".
