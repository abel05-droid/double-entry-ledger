# Posting Flow

Ordered steps `PostingService.post(PostingRequest)` takes from receiving a
request to returning a result. Everything below runs inside a single
`@Transactional` boundary — the method either completes every step or
rolls back all of them.

1. **Structural validation.** Reject the request if `idempotencyKey`,
   `referenceId`, or either entry list is missing/empty, or if any entry
   has a null account id, a non-positive amount, an amount with more than
   4 decimal places, or a malformed (non-3-letter) currency code. No
   database access has happened yet.
2. **Idempotency lookup.** Compute a SHA-256 fingerprint of the request
   (everything except `idempotencyKey` itself — see
   `PostingService.fingerprint`), then look up `idempotency_keys` by
   `idempotencyKey`.
   - **Key found, fingerprint matches:** the request is a retry of an
     already-processed transaction. Load the existing `JournalEntry` and
     its `LedgerEntries` and return them as the result. No new writes.
   - **Key found, fingerprint differs:** reject with
     `IdempotencyKeyConflictException`. No writes.
   - **Key not found:** continue to step 3.
3. **Account lookup.** Load every `Account` referenced by the request's
   entries in one query. Any missing account id fails with
   `AccountNotFoundException`.
4. **Currency validation.** Each entry's currency must equal its account's
   currency (`CurrencyMismatchException` otherwise), and every entry in
   the request must share the same currency — multi-currency journal
   entries are out of scope for this phase.
5. **Balance validation.** Sum the debit entries and the credit entries;
   they must be equal (`UnbalancedJournalEntryException` otherwise).
6. **Persist the JournalEntry.** Insert one row.
7. **Persist the LedgerEntries.** Insert one row per debit and credit
   entry, each referencing the JournalEntry and its Account.
8. **Persist the IdempotencyKey.** Record the key, its fingerprint, and
   the new JournalEntry's id, so future retries under the same key are
   recognized in step 2.
9. **Return the result.** Map the persisted JournalEntry and LedgerEntries
   to a `PostingResult` DTO.

If any step from 3 onward throws, the transaction rolls back completely:
no JournalEntry, LedgerEntry, or IdempotencyKey row from the failed
attempt is left behind, including rows that would otherwise have been
inserted before the failure (e.g. the JournalEntry row when a later
LedgerEntry insert fails, or in the case of a database-level constraint
violation such as a duplicate `reference_id`).
