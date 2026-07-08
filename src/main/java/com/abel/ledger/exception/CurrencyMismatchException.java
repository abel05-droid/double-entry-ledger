package com.abel.ledger.exception;

/**
 * Raised when a ledger entry's currency does not match its account's
 * currency, or when a single journal entry mixes more than one currency
 * across its entries (multi-currency/FX postings are out of scope for this
 * phase).
 */
public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
