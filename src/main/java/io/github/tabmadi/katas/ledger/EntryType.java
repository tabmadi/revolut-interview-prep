package io.github.tabmadi.katas.ledger;

/** Which side of the double entry a line represents. */
public enum EntryType {
    /** Money leaving the account. */
    DEBIT,
    /** Money arriving in the account. */
    CREDIT
}
