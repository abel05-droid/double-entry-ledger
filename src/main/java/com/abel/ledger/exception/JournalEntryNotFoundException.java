package com.abel.ledger.exception;

/**
 * Raised when a request references a journal entry id that does not exist.
 */
public class JournalEntryNotFoundException extends LedgerException {

    public JournalEntryNotFoundException(String message) {
        super(message);
    }
}
