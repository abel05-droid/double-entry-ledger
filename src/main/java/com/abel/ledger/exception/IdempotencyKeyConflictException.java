package com.abel.ledger.exception;

/**
 * Raised when a client reuses an {@code idempotencyKey} with a request
 * payload that differs from the one originally processed under that key.
 * This is almost always a client bug (accidental key reuse across distinct
 * transactions) and must be surfaced rather than silently resolved by
 * returning either request's result.
 */
public class IdempotencyKeyConflictException extends LedgerException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
