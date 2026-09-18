# PostgreSQL, for the follow-up discussion

The JD asks for "deep query optimization, locking mechanisms, and data integrity". The live coding
will be in-memory; this is what the conversation afterwards turns into.

## MVCC — the model everything else follows from

Every row version carries `xmin` (creating transaction) and `xmax` (deleting transaction). A reader
sees the versions visible to its snapshot, so **readers never block writers and writers never block
readers**. Consequences you should be able to state:

- An `UPDATE` writes a *new row version* and marks the old one dead. Updating one column rewrites
  the whole row, and every index on the table unless the update is HOT (heap-only tuple: no indexed
  column changed and the new version fits on the same page).
- Dead versions accumulate → **bloat**. `VACUUM` reclaims them for reuse; `VACUUM FULL` rewrites the
  table and takes an `ACCESS EXCLUSIVE` lock — never on a live table.
- `SELECT count(*)` has to scan, because visibility is per-snapshot. The visibility map makes
  index-only scans possible when pages are all-visible.
- **Long-running transactions are the classic production killer**: they hold back the xmin horizon,
  so vacuum cannot remove anything newer, and the whole cluster bloats. An idle-in-transaction
  connection is an outage waiting to happen.
- Transaction ID wraparound: 32-bit xids, frozen by autovacuum. If autovacuum cannot keep up the
  database shuts down to protect itself.

## Isolation levels

| Level | Prevents | Still allows |
| --- | --- | --- |
| Read Committed (default) | Dirty reads | Non-repeatable reads, phantoms, lost updates across statements |
| Repeatable Read | + non-repeatable reads, phantoms (Postgres is snapshot isolation) | Write skew |
| Serializable (SSI) | Everything, including write skew | Nothing — but transactions abort with `40001` and must be retried |

Two things to know that people usually miss:

- **In Read Committed, each statement takes a new snapshot.** An `UPDATE` that blocks on a lock
  re-evaluates its `WHERE` against the *new* version when it unblocks. So
  `UPDATE accounts SET balance = balance - 100 WHERE id = 1 AND balance >= 100` is safe under
  concurrency, while `SELECT balance` then `UPDATE ... SET balance = :computed` is a lost update.
- **Repeatable Read does not prevent write skew.** Two transactions each check "the total stays
  positive", each sees the other's pre-state, both commit, invariant violated. This is the
  double-spend shape. Fix with `SERIALIZABLE`, with `SELECT ... FOR UPDATE` on a common row, or by
  encoding the invariant as a constraint.
- **`SERIALIZABLE` requires a retry loop.** If you claim it without mentioning retry on `40001`, you
  have not used it.

## Locking

**Row locks:**

- `SELECT ... FOR UPDATE` — exclusive; blocks other `FOR UPDATE` and writes. The standard "read,
  decide, write" pattern. **Acquire in a consistent order** (e.g. `ORDER BY id`) or you get
  deadlocks exactly as in application code; Postgres will detect and abort one side with `40P01`.
- `FOR NO KEY UPDATE` — what a plain `UPDATE` takes; weaker, allows `FOR KEY SHARE`.
- `FOR SHARE` / `FOR KEY SHARE` — the latter is what an FK check takes, which is why a child insert
  can block a parent update.
- `SKIP LOCKED` — **the queue pattern.** `SELECT ... WHERE status = 'PENDING' ORDER BY id LIMIT 10
  FOR UPDATE SKIP LOCKED` lets N workers pull disjoint batches with no coordination and no
  convoying. Know this one cold; it is the standard job-queue answer.
- `NOWAIT` — fail immediately instead of queueing, when latency matters more than the work.

**Table locks** matter mostly for migrations. `ACCESS EXCLUSIVE` (taken by most `ALTER TABLE`,
`VACUUM FULL`, `CREATE INDEX` without `CONCURRENTLY`) blocks *everything*, and it queues — one
blocked DDL statement blocks every subsequent reader behind it. Always `SET lock_timeout` before
DDL, and `CREATE INDEX CONCURRENTLY` / `ADD CONSTRAINT ... NOT VALID` then `VALIDATE`.

**Advisory locks** (`pg_advisory_xact_lock(key)`) give you a mutex keyed by an arbitrary bigint,
scoped to the transaction. Use for leader election or serialising a non-row-shaped critical section.
This is how you replace an in-process `KeyedLocks` when you go multi-instance.

## Data integrity — push invariants into the database

The application-layer version of every guarantee is lost the moment a second instance starts.

- `UNIQUE (idempotency_key)` — the durable version of the in-memory idempotency map. Insert first,
  catch `23505`, return the original result. This is exactly how a `payments` table deduplicates
  retries.
- `CHECK (balance >= 0)` — the invariant, enforced by the engine, immune to a buggy code path.
- Foreign keys, `NOT NULL`, and domain types. Cheap, and they turn a data-corruption incident into a
  failed insert.
- **Double-entry, not a mutable balance.** Append immutable entries; the balance is `SUM(amount)`,
  optionally materialised into a balances row updated in the same transaction. An append-only table
  has no lost-update problem and is auditable. This is the model the `ledger` kata follows.
- **The transactional outbox.** You cannot atomically write to Postgres and publish to Kafka. Write
  the event into an `outbox` table in the *same transaction* as the state change, and have a relay
  poll it (`FOR UPDATE SKIP LOCKED`) and publish at-least-once. Consumers must be idempotent. This
  is the single most useful pattern to name in a payments interview.

## Query optimisation

**Indexes:**

- B-tree for equality and range; **column order matters** — `(a, b)` serves `WHERE a = ?` and
  `WHERE a = ? AND b = ?` and `ORDER BY a, b`, but not `WHERE b = ?`.
- **Covering / index-only scans:** `INCLUDE (col)` or putting the column in the key lets Postgres
  answer from the index alone — but only when the visibility map says the pages are all-visible, so
  a heavily-updated table still hits the heap.
- **Partial indexes:** `WHERE status = 'PENDING'` — tiny index over a hot slice of a huge table. The
  best single optimisation for a jobs/outbox table.
- BRIN for naturally ordered giant tables (append-only time series); GIN for `jsonb`, arrays, and
  full text; GiST for ranges and geometry.
- Every index slows writes and blocks HOT updates. Unused indexes are pure cost —
  `pg_stat_user_indexes`.

**Reading a plan:** `EXPLAIN (ANALYZE, BUFFERS)`. What to look at, in order:

1. **Estimated vs actual rows.** A 1000× misestimate is the root cause; the bad join order is the
   symptom. Fix with `ANALYZE`, raised statistics targets, or extended statistics for correlated
   columns.
2. **`Rows Removed by Filter`** — the index fetched rows the filter then threw away. Wrong or
   missing index.
3. **Nested Loop with a large outer** — fine for a handful of rows, catastrophic for a million.
4. **Buffers**: `shared read` vs `hit` tells you whether you are I/O bound or cache resident.
5. **Sort with `external merge Disk`** — `work_mem` is too low for this query.
6. Seq scan is not automatically bad: for a large fraction of a table it is the right plan.

**Other levers:** keyset pagination (`WHERE (created_at, id) < (?, ?)`) instead of `OFFSET`;
partitioning by range for time-series and for cheap retention drops; `pg_stat_statements` to find
the query that actually costs you; batching writes; and `COPY` for bulk load.

## Connection management

Postgres is process-per-connection: a few hundred connections is a lot. Application pools
(HikariCP) must be **small** — roughly `cores × 2 + spindles`, not 500. Put PgBouncer in transaction
mode in front of many app instances, and know the consequence: transaction-mode pooling breaks
session state, prepared statements, and advisory session locks.

Two connection-pool traps worth naming: holding a connection while doing an HTTP call, and
virtual threads letting 10,000 requests in flight all queue for a 20-connection pool. The fix is
explicit backpressure, not a bigger pool.
