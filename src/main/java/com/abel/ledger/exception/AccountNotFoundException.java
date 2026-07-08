package com.abel.ledger.exception;

/**
 * Raised when a request references an account id that does not exist.
 */
public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
