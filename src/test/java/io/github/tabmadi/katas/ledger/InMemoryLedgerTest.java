package io.github.tabmadi.katas.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.tabmadi.support.Concurrently;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class InMemoryLedgerTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency EUR = Currency.getInstance("EUR");

    private static final AccountId ALICE = AccountId.of("alice");
    private static final AccountId BOB = AccountId.of("bob");

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        ledger.open(ALICE, GBP);
        ledger.open(BOB, GBP);
    }

    private static Money gbp(String amount) {
        return Money.of(amount, GBP);
    }

    private static TransferId id() {
        return TransferId.of(UUID.randomUUID().toString());
    }

    @Nested
    @DisplayName("Stage 1: accounts hold money")
    class Accounts {

        @Test
        @DisplayName("a new account starts at zero")
        void newAccountIsEmpty() {
            assertThat(ledger.balance(ALICE)).isEqualTo(Money.zero(GBP));
        }

        @Test
        @DisplayName("opening the same id twice is rejected rather than silently resetting a balance")
        void doubleOpenIsRejected() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> ledger.open(ALICE, GBP))
                    .withMessageContaining("alice");
        }

        @Test
        @DisplayName("an unknown account is an error, not an implicit account")
        void unknownAccount() {
            assertThatExceptionOfType(AccountNotFoundException.class)
                    .isThrownBy(() -> ledger.balance(AccountId.of("nobody")));
        }

        @Test
        @DisplayName("a deposit credits, a withdrawal debits")
        void depositAndWithdraw() {
            ledger.deposit(id(), ALICE, gbp("100.00"));
            ledger.withdraw(id(), ALICE, gbp("30.50"));

            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("69.50"));
        }

        @Test
        @DisplayName("a withdrawal beyond the balance is refused and changes nothing")
        void overdraftIsRefused() {
            ledger.deposit(id(), ALICE, gbp("10.00"));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> ledger.withdraw(id(), ALICE, gbp("10.01")));
            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("10.00"));
        }

        @Test
        @DisplayName("zero and negative amounts are rejected at the boundary")
        void amountsMustBePositive() {
            assertThatIllegalArgumentException().isThrownBy(() -> ledger.deposit(id(), ALICE, gbp("0.00")));
            assertThatIllegalArgumentException().isThrownBy(() -> ledger.deposit(id(), ALICE, gbp("-1.00")));
        }
    }

    @Nested
    @DisplayName("Stage 2: transfers move money atomically")
    class Transfers {

        @Test
        @DisplayName("a transfer debits the source and credits the destination")
        void happyPath() {
            ledger.deposit(id(), ALICE, gbp("100.00"));

            ledger.transfer(id(), ALICE, BOB, gbp("40.00"));

            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("60.00"));
            assertThat(ledger.balance(BOB)).isEqualTo(gbp("40.00"));
        }

        @Test
        @DisplayName("a failed transfer leaves both accounts untouched")
        void failureIsAtomic() {
            ledger.deposit(id(), ALICE, gbp("10.00"));

            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> ledger.transfer(id(), ALICE, BOB, gbp("11.00")));

            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("10.00"));
            assertThat(ledger.balance(BOB)).isEqualTo(Money.zero(GBP));
        }

        @Test
        @DisplayName("transferring to yourself is a client error, not a no-op")
        void selfTransferIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> ledger.transfer(id(), ALICE, ALICE, gbp("1.00")));
        }

        @Test
        @DisplayName("currencies never mix implicitly")
        void currenciesDoNotMix() {
            AccountId euros = AccountId.of("euros");
            ledger.open(euros, EUR);
            ledger.deposit(id(), ALICE, gbp("100.00"));

            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> ledger.transfer(id(), ALICE, euros, gbp("10.00")));
        }
    }

    @Nested
    @DisplayName("Stage 3: the same transfer id is applied once")
    class Idempotency {

        @Test
        @DisplayName("a retried transfer moves the money once and replays the original receipt")
        void retryIsANoOp() {
            ledger.deposit(id(), ALICE, gbp("100.00"));
            TransferId once = id();

            Transfer first = ledger.transfer(once, ALICE, BOB, gbp("25.00"));
            Transfer replay = ledger.transfer(once, ALICE, BOB, gbp("25.00"));

            assertThat(replay).isEqualTo(first);
            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("75.00"));
            assertThat(ledger.balance(BOB)).isEqualTo(gbp("25.00"));
        }

        @Test
        @DisplayName("a rejected transfer does not burn its id, so a later retry can still succeed")
        void failureDoesNotConsumeTheId() {
            TransferId attempt = id();
            assertThatExceptionOfType(InsufficientFundsException.class)
                    .isThrownBy(() -> ledger.transfer(attempt, ALICE, BOB, gbp("50.00")));

            ledger.deposit(id(), ALICE, gbp("100.00"));

            assertThatCode(() -> ledger.transfer(attempt, ALICE, BOB, gbp("50.00")))
                    .doesNotThrowAnyException();
            assertThat(ledger.balance(BOB)).isEqualTo(gbp("50.00"));
        }

        @RepeatedTest(5)
        @DisplayName("32 threads submitting the same transfer id move the money exactly once")
        void concurrentDuplicatesCollapse() {
            ledger.deposit(id(), ALICE, gbp("100.00"));
            TransferId once = id();

            List<Transfer> receipts = Concurrently.call(32, () -> ledger.transfer(once, ALICE, BOB, gbp("10.00")));

            assertThat(receipts).hasSize(32).containsOnly(receipts.getFirst());
            assertThat(ledger.balance(ALICE)).isEqualTo(gbp("90.00"));
            assertThat(ledger.balance(BOB)).isEqualTo(gbp("10.00"));
            assertThat(ledger.statement(BOB)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Stage 4: the statement is the audit trail")
    class Statements {

        @Test
        @DisplayName("both sides of a transfer are recorded against the same transfer id")
        void doubleEntry() {
            ledger.deposit(id(), ALICE, gbp("100.00"));
            TransferId movement = id();
            ledger.transfer(movement, ALICE, BOB, gbp("30.00"));

            LedgerEntry debit = ledger.statement(ALICE).getLast();
            LedgerEntry credit = ledger.statement(BOB).getLast();

            assertThat(debit.type()).isEqualTo(EntryType.DEBIT);
            assertThat(credit.type()).isEqualTo(EntryType.CREDIT);
            assertThat(debit.transferId()).isEqualTo(movement).isEqualTo(credit.transferId());
            assertThat(debit.amount()).isEqualTo(credit.amount()).isEqualTo(gbp("30.00"));
            assertThat(debit.balanceAfter()).isEqualTo(gbp("70.00"));
        }

        @Test
        @DisplayName("sequence numbers are gap-free per account")
        void sequenceIsDense() {
            IntStream.rangeClosed(1, 5).forEach(i -> ledger.deposit(id(), ALICE, gbp("1.00")));

            assertThat(ledger.statement(ALICE))
                    .extracting(LedgerEntry::sequence)
                    .containsExactly(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("the statement is a snapshot: mutating the ledger afterwards does not change it")
        void statementIsImmutable() {
            ledger.deposit(id(), ALICE, gbp("1.00"));
            List<LedgerEntry> snapshot = ledger.statement(ALICE);

            ledger.deposit(id(), ALICE, gbp("1.00"));

            assertThat(snapshot).hasSize(1);
        }

        @Test
        @DisplayName("replaying the entries reproduces the balance")
        void entriesFoldToTheBalance() {
            ledger.deposit(id(), ALICE, gbp("100.00"));
            ledger.transfer(id(), ALICE, BOB, gbp("30.00"));
            ledger.withdraw(id(), ALICE, gbp("5.00"));

            Money replayed = ledger.statement(ALICE).stream()
                    .reduce(
                            Money.zero(GBP),
                            (running, entry) -> entry.type() == EntryType.CREDIT
                                    ? running.plus(entry.amount())
                                    : running.minus(entry.amount()),
                            Money::plus);

            assertThat(replayed).isEqualTo(ledger.balance(ALICE));
        }
    }

    @Nested
    @DisplayName("Concurrency: the invariants hold under contention")
    class UnderContention {

        @RepeatedTest(5)
        @DisplayName("concurrent transfers never create or destroy money")
        void moneyIsConserved() {
            int accountCount = 8;
            List<AccountId> ids = IntStream.range(0, accountCount)
                    .mapToObj(i -> AccountId.of("acct-" + i))
                    .toList();
            ids.forEach(account -> {
                ledger.open(account, GBP);
                ledger.deposit(id(), account, gbp("100.00"));
            });
            Money expectedTotal = gbp("800.00");

            // Every thread hammers a random pair, in both directions: the classic deadlock setup.
            Concurrently.run(16, 200, () -> {
                int from = (int) (Math.random() * accountCount);
                int to = (int) (Math.random() * accountCount);
                if (from == to) {
                    return;
                }
                try {
                    ledger.transfer(id(), ids.get(from), ids.get(to), gbp("1.00"));
                } catch (InsufficientFundsException expected) {
                    // A drained account is a legitimate outcome, not a failure.
                }
            });

            Money total = ids.stream().map(ledger::balance).reduce(Money.zero(GBP), Money::plus);
            assertThat(total).isEqualTo(expectedTotal);
            assertThat(ids)
                    .allSatisfy(account -> assertThat(ledger.balance(account).isNegative())
                            .as("account %s went negative", account)
                            .isFalse());
        }

        @RepeatedTest(5)
        @DisplayName("concurrent withdrawals cannot overdraw: only 10 of 50 racing debits succeed")
        void noLostUpdateOnWithdrawal() {
            ledger.deposit(id(), ALICE, gbp("10.00"));
            Set<TransferId> succeeded = ConcurrentHashMap.newKeySet();

            Concurrently.run(50, 1, () -> {
                TransferId attempt = id();
                try {
                    ledger.withdraw(attempt, ALICE, gbp("1.00"));
                    succeeded.add(attempt);
                } catch (InsufficientFundsException expected) {
                    // Exactly 40 of these are expected.
                }
            });

            assertThat(succeeded).hasSize(10);
            assertThat(ledger.balance(ALICE)).isEqualTo(Money.zero(GBP));
        }

        @RepeatedTest(5)
        @DisplayName("A->B and B->A running head-on do not deadlock")
        void oppositeDirectionsDoNotDeadlock() {
            ledger.deposit(id(), ALICE, gbp("1000.00"));
            ledger.deposit(id(), BOB, gbp("1000.00"));

            Concurrently.runIndexed(16, index -> {
                boolean forward = index % 2 == 0;
                for (int i = 0; i < 250; i++) {
                    if (forward) {
                        ledger.transfer(id(), ALICE, BOB, gbp("1.00"));
                    } else {
                        ledger.transfer(id(), BOB, ALICE, gbp("1.00"));
                    }
                }
            });

            assertThat(ledger.balance(ALICE).plus(ledger.balance(BOB))).isEqualTo(gbp("2000.00"));
        }
    }
}
