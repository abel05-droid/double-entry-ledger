package com.abel.ledger.exception;

/**
 * Raised when a {@code PostingRequest} is structurally invalid — missing
 * required fields, empty entry lists, non-positive amounts, or malformed
 * currency codes — before any account lookup or persistence is attempted.
 */
public class InvalidPostingRequestException extends LedgerException {

    public InvalidPostingRequestException(String message) {
        super(message);
    }
}
