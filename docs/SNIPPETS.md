# Snippets

Shapes you do not want to be re-deriving live. Every one of them appears, in context, somewhere in
`src/`.

## Atomic map operations

```java
// Create-if-absent, exactly once across threads.
Account account = accounts.computeIfAbsent(id, key -> new Account(key, GBP));

// Fail if it already exists.
if (accounts.putIfAbsent(id, account) != null) throw new IllegalStateException("exists: " + id);

// Atomic read-modify-write of a value.
counts.merge(key, 1L, Long::sum);
inventory.compute(sku, (k, v) -> v == null || v < qty ? fail() : v - qty);

// Conditional replace (CAS on a map entry).
boolean won = entries.replace(key, expected, updated);
```

Keep the mapping function short and side-effect free: it runs under a bin lock and must not touch
the same map.

## Per-key locking, deadlock-free

```java
private final Map<K, ReentrantLock> locks = new ConcurrentHashMap<>();

private ReentrantLock lockFor(K key) {
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
}

// Two keys: always take them in a total order, so no cycle can form.
K lower = a.compareTo(b) < 0 ? a : b;
K higher = a.compareTo(b) < 0 ? b : a;
ReentrantLock first = lockFor(lower);
ReentrantLock second = lockFor(higher);
first.lock();
try {
    second.lock();
    try {
        // validate BOTH sides, then mutate both -- no rollback path exists after the first write
    } finally { second.unlock(); }
} finally { first.unlock(); }
```

## Single-flight / idempotency reservation

Collapses concurrent duplicates onto one execution, including ones that arrive mid-flight.

```java
private final Map<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

V once(K key, Supplier<V> work) {
    CompletableFuture<V> reservation = new CompletableFuture<>();
    CompletableFuture<V> winner = inFlight.putIfAbsent(key, reservation);
    if (winner != null) return winner.join();          // duplicate: wait for the winner's result
    try {
        V result = work.get();
        reservation.complete(result);
        return result;
    } catch (RuntimeException | Error e) {
        inFlight.remove(key, reservation);             // do not cache a failure
        reservation.completeExceptionally(e);
        throw e;
    }
}
```

## CAS retry loop over immutable state

```java
record State(long tokens, long lastRefillNanos) {}
private final AtomicReference<State> state = new AtomicReference<>(new State(capacity, clock.getAsLong()));

while (true) {
    State current = state.get();
    State next = compute(current);
    if (next == current) return false;
    if (state.compareAndSet(current, next)) return true;
    // lost the race -- re-read and retry
}
```

## Bounded producer/consumer

```java
BlockingQueue<Task> queue = new ArrayBlockingQueue<>(1_000);   // bounded == backpressure
queue.put(task);                                                // blocks when full: the point
Task task = queue.take();
// Shutdown: a poison pill, or poll with a timeout and check a volatile running flag.
```

## Virtual threads and structured concurrency

```java
try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Result>> futures = pool.invokeAll(tasks);   // close() waits for completion
}

// Fan out, fail fast, propagate cancellation to siblings (Java 21+, preview API surface varies).
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<User> user = scope.fork(() -> loadUser(id));
    Subtask<Account> account = scope.fork(() -> loadAccount(id));
    scope.join().throwIfFailed();
    return new Profile(user.get(), account.get());
}
```

## Interruption, handled correctly

```java
try {
    latch.await();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // never swallow: cancellation is a protocol
    throw new IllegalStateException("interrupted while waiting", e);
}
```

## Money

```java
record Money(long minorUnits, Currency currency) { }       // never double
Money sum = a.plus(b);                                     // throws on currency mismatch
long total = Math.addExact(x, y);                          // throws instead of wrapping
```

## JUnit 5 + AssertJ

```java
@Nested @DisplayName("Stage 2: transfers")
class Transfers {
    @Test @DisplayName("a failed transfer leaves both accounts untouched")
    void failureIsAtomic() { ... }
}

assertThat(balance).isEqualTo(gbp("60.00"));
assertThat(statement).hasSize(2).extracting(LedgerEntry::type).containsExactly(DEBIT, CREDIT);
assertThat(accounts).allSatisfy(a -> assertThat(ledger.balance(a).isNegative()).isFalse());
assertThatExceptionOfType(InsufficientFundsException.class)
        .isThrownBy(() -> ledger.withdraw(id(), ALICE, gbp("1.00")))
        .withMessageContaining("alice");
assertThatIllegalArgumentException().isThrownBy(() -> Money.of(-1, GBP));
assertThatCode(() -> ledger.transfer(...)).doesNotThrowAnyException();

@ParameterizedTest
@CsvSource({"100, 40, 60", "100, 100, 0"})
void debits(long start, long amount, long expected) { ... }

@RepeatedTest(10)   // a race that reproduces 20% of the time passes once
```

## Concurrency test, the shape that actually catches bugs

```java
@RepeatedTest(10)
@DisplayName("64 threads racing on one key are granted exactly capacity permits")
void neverOverGrants() {
    AtomicInteger granted = new AtomicInteger();

    Concurrently.run(64, 10, () -> {                 // released together off one latch
        if (limiter.tryAcquire("hot-key")) granted.incrementAndGet();
    });

    assertThat(granted).hasValue(100);                // assert the invariant, never a schedule
}
```

## Awaitility, for genuinely asynchronous effects

```java
await().atMost(Duration.ofSeconds(2))
       .untilAsserted(() -> assertThat(store.get("k")).isEmpty());
```

Prefer an injected `Clock` over waiting. Only reach for Awaitility when something really is
happening on another thread and you cannot control when.
