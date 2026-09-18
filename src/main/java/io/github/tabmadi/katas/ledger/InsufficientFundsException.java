package io.github.tabmadi.katas.ledger;

/**
 * Thrown when a debit would take an account below its floor.
 *
 * <p>Carries the balance and the requested amount because "insufficient funds" alone is unusable in
 * a support ticket six weeks later.
 */
public final class InsufficientFundsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AccountId accountId;

    public InsufficientFundsException(AccountId accountId, Money balance, Money requested) {
        super("account %s has %s, cannot debit %s".formatted(accountId, balance, requested));
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
