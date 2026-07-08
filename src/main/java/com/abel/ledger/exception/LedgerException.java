package com.abel.ledger.exception;

/**
 * Base type for all domain-level failures raised by the ledger's posting
 * and balance-derivation logic. Callers that want to handle any ledger
 * failure generically can catch this; callers that need to distinguish
 * failure modes can catch one of the specific subtypes instead.
 */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }
}
