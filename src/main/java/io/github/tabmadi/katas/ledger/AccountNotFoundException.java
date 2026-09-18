package io.github.tabmadi.katas.ledger;

/** Thrown when an account id does not resolve. */
public final class AccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("no such account: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
