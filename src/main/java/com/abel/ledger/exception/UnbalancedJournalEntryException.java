package com.abel.ledger.exception;

/**
 * Raised when the total of a journal entry's debit entries does not equal
 * the total of its credit entries.
 */
public class UnbalancedJournalEntryException extends LedgerException {

    public UnbalancedJournalEntryException(String message) {
        super(message);
    }
}
