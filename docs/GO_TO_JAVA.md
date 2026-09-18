# Go → Java, the translation table

The recruiter's framing is that the transition is the main consideration. The fastest way to defuse
it is to show you already think in the concepts and only need the vocabulary.

## Concurrency

| Go | Java | Notes |
| --- | --- | --- |
| `go f()` | `Thread.ofVirtual().start(f)` or an executor | Virtual threads are the goroutine analogue; they unmount on blocking I/O |
| `chan T` (buffered) | `ArrayBlockingQueue<T>` / `LinkedBlockingQueue<T>` | `put`/`take` block like a channel send/receive |
| `chan T` (unbuffered) | `SynchronousQueue<T>` | A handoff, no capacity |
| `close(ch)` + range | Poison-pill sentinel, or `BlockingQueue.poll` with timeout | Java queues have no close; this is genuinely clunkier |
| `select` | `CompletableFuture.anyOf`, or `poll` with timeout | No first-class select |
| `sync.Mutex` | `ReentrantLock` / `synchronized` | Java locks are reentrant; Go's are not |
| `sync.RWMutex` | `ReentrantReadWriteLock`, `StampedLock` | `StampedLock` adds optimistic reads |
| `sync.WaitGroup` | `CountDownLatch`, or `StructuredTaskScope` | Latch counts down once; `CyclicBarrier` reuses |
| `sync.Once` | `computeIfAbsent`, a holder class, or a `CompletableFuture` reservation | |
| `sync/atomic` | `AtomicLong`, `AtomicReference`, `VarHandle` | `VarHandle` exposes the memory-order modes |
| `sync.Map` | `ConcurrentHashMap` | `ConcurrentHashMap` is the better data structure of the two |
| `context.Context` cancellation | `Future.cancel`, interruption, `StructuredTaskScope` | Java's cancellation is cooperative via interrupt |
| `context.WithTimeout` | `orTimeout` / `completeOnTimeout` on `CompletableFuture` | |
| `errgroup.Group` | `StructuredTaskScope.ShutdownOnFailure` | Closest structural match |
| Race detector (`-race`) | `jcstress`, and careful invariant tests | Java has no equivalent built-in detector — worth saying |

## Language and idiom

| Go | Java |
| --- | --- |
| `struct` | `record` (immutable data) or a class |
| Interface, implicitly satisfied | `interface`, explicitly implemented |
| `error` return values | Exceptions; unchecked for programming errors, checked for recoverable ones |
| `if err != nil` | `try`/`catch`, or `Optional`, or a sealed result type |
| `defer` | try-with-resources (`AutoCloseable`), or `finally` |
| `nil` | `null` — here, constrained by JSpecify `@Nullable` + NullAway |
| Zero values | No zero values; fields are `null`/`0` and must be initialised deliberately |
| Slices | `List` (`ArrayList`), `List.of` for immutable |
| Maps | `Map` (`HashMap`), `Map.of`, `ConcurrentHashMap` |
| `fmt.Errorf("...: %w", err)` | `new XException("...", cause)` — always pass the cause |
| Table-driven tests | `@ParameterizedTest` + `@MethodSource` / `@CsvSource` |
| `t.Parallel()` | JUnit parallel execution, or just separate test classes |
| Struct embedding | Composition, or `interface` default methods |
| Generics `[T any]` | `<T>` with erasure — no `new T[]`, no primitives as type arguments |
| `iota` enums | `enum` — a real class, with fields and methods |
| `switch` type switch | Pattern matching for `switch` (Java 21) on sealed hierarchies |

## The three things that actually trip up Go engineers

1. **Erasure.** `List<String>` and `List<Integer>` are the same class at runtime. No reflection on
   type arguments, no `T[]` creation, no overload on `List<String>` vs `List<Integer>`.
2. **Everything is a reference, but boxing is not free.** `Long` is an object;
   `long` is not. `Map<String, Long>` boxes on every write. `==` on boxed types compares identity —
   `.equals` compares value. This is a real interview trap.
3. **Checked exceptions and lambdas do not mix.** A lambda that throws a checked exception will not
   fit a standard functional interface. Wrap, or define your own interface that declares `throws`
   (as `Concurrently.IndexedTask` does).

## What to say if they ask directly

Something close to: *"The concepts transfer — goroutines and virtual threads are the same idea, and
`errgroup` and `StructuredTaskScope` are the same shape. What I'd be learning is the ecosystem:
Spring, the build, the JVM's GC and JIT behaviour under load, and the tooling. Language syntax is a
weekend; a codebase is a month; neither is distributed systems, which is what the job actually is."*
Then give an example of a concurrency or database problem you solved in Go and describe it in Java's
vocabulary. That is the whole test.
