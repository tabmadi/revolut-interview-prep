package io.github.tabmadi.katas.ledger;

import java.util.Currency;
import java.util.List;

/**
 * A double-entry ledger of accounts and the movements between them.
 *
 * <p>The contract, stated as invariants rather than as methods:
 *
 * <ol>
 *   <li>No account balance ever goes negative.
 *   <li>The sum of all balances in a currency is unchanged by any transfer — money is moved, never
 *       created or destroyed.
 *   <li>A {@link TransferId} is applied at most once, however many times it is submitted and from
 *       however many threads.
 *   <li>Every balance change has a corresponding statement entry, and vice versa.
 * </ol>
 */
public interface Ledger {

    /** Opens an account with a zero balance. Fails if the id is already in use. */
    void open(AccountId id, Currency currency);

    /** Current balance. Throws if the account does not exist. */
    Money balance(AccountId id);

    /** Credits an account from outside the ledger (a top-up). Idempotent on {@code transferId}. */
    Transfer deposit(TransferId transferId, AccountId to, Money amount);

    /** Debits an account to outside the ledger (a payout). Idempotent on {@code transferId}. */
    Transfer withdraw(TransferId transferId, AccountId from, Money amount);

    /**
     * Moves {@code amount} from one account to another, atomically.
     *
     * <p>Either both entries are written or neither is; no observer sees the money in one account
     * and not the other once the call returns.
     *
     * @throws InsufficientFundsException if the source cannot cover the amount
     * @throws AccountNotFoundException if either account is unknown
     * @throws CurrencyMismatchException if the accounts or the amount disagree on currency
     */
    Transfer transfer(TransferId transferId, AccountId from, AccountId to, Money amount);

    /** Immutable statement for an account, oldest entry first. */
    List<LedgerEntry> statement(AccountId id);
}
