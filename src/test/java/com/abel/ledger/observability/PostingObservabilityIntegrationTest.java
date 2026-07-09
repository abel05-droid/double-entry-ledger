package com.abel.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.service.PostingService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves the observability requirements for the posting path: metrics
 * increment with the correct tags, and structured log entries carry the
 * expected fields — verified as structured event objects (via
 * {@link StructuredArgument#writeTo}, the exact method the real JSON
 * encoder calls), not by parsing rendered log text, per docs/architecture.md,
 * "Observability" / "Logging Verification".
 *
 * Runs against the docker-compose Postgres stack, same convention as
 * {@code PostingServiceIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostingObservabilityIntegrationTest {

    @Autowired
    private PostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private ListAppender<ILoggingEvent> listAppender;
    private ch.qos.logback.classic.Logger postingLogger;

    @BeforeEach
    void attachListAppender() {
        postingLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("com.abel.ledger.observability.posting");
        listAppender = new ListAppender<>();
        listAppender.start();
        postingLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachListAppender() {
        postingLogger.detachAppender(listAppender);
    }

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Observability test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    private PostingRequest balancedRequest(Account debitAccount, Account creditAccount) {
        return new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "observability test",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("100.00"), "USD")));
    }

    /**
     * Renders every {@link StructuredArgument} in an event's argument array
     * into a flat field map, exactly as the real JSON encoder would, by
     * calling the same {@code writeTo(JsonGenerator)} contract it uses.
     */
    private Map<String, Object> structuredFields(ILoggingEvent event) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = mapper.getFactory().createGenerator(writer)) {
            generator.writeStartObject();
            for (Object arg : event.getArgumentArray()) {
                if (arg instanceof StructuredArgument structuredArgument) {
                    structuredArgument.writeTo(generator);
                }
            }
            generator.writeEndObject();
        }
        return mapper.readValue(writer.toString(), new TypeReference<Map<String, Object>>() {});
    }

    @Test
    void successfulPostingIncrementsSuccessMetricsAndLogsStructuredFields() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        PostingRequest request = balancedRequest(debitAccount, creditAccount);

        // meterRegistry.counter(...)/timer(...) is a register-or-get lookup
        // — the same idiom PostingObservabilityAspect itself uses — unlike
        // meterRegistry.get(...).tags(...).counter(), which throws
        // MeterNotFoundException if this exact tag combination has never
        // been recorded yet. Capturing the same Counter/Timer reference
        // before and after means the delta assertion is safe even the very
        // first time this tag combination is ever seen in the registry.
        var requestsCounter = meterRegistry.counter("ledger.posting.requests", "outcome", "success");
        var durationTimer = meterRegistry.timer("ledger.posting.duration", "outcome", "success");
        double requestsBefore = requestsCounter.count();
        long durationCountBefore = durationTimer.count();

        PostingResult result = postingService.post(request);

        assertThat(requestsCounter.count()).isEqualTo(requestsBefore + 1);
        assertThat(durationTimer.count()).isEqualTo(durationCountBefore + 1);

        ILoggingEvent successEvent = listAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Journal entry posted"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no 'Journal entry posted' log event captured"));

        Map<String, Object> fields = structuredFields(successEvent);
        assertThat(fields).containsEntry("journalEntryId", result.journalEntryId().toString());
        assertThat(fields).containsEntry("idempotencyKey", request.idempotencyKey());
        assertThat(fields).containsEntry("referenceId", request.referenceId());
    }

    @Test
    void unbalancedPostingIncrementsFailureMetricWithReasonTagAndLogsStructuredFields() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        PostingRequest unbalanced = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "unbalanced",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("90.00"), "USD")));

        var counter = meterRegistry.counter(
                "ledger.posting.requests", "outcome", "failure", "failure_reason", "unbalanced");
        double before = counter.count();

        assertThatThrownBy(() -> postingService.post(unbalanced))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        assertThat(counter.count()).isEqualTo(before + 1);

        ILoggingEvent failureEvent = listAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("Journal entry posting failed"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no 'Journal entry posting failed' log event captured"));

        Map<String, Object> fields = structuredFields(failureEvent);
        assertThat(fields).containsEntry("idempotencyKey", unbalanced.idempotencyKey());
        assertThat(fields).containsEntry("failureReason", "unbalanced");
        assertThat(fields).containsEntry("exceptionClass", UnbalancedJournalEntryException.class.getName());
        assertThat(fields).containsKey("exceptionMessage");
    }

    @Test
    void currencyMismatchIsClassifiedAndTaggedCorrectly() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account eurCreditAccount = accountRepository.save(Account.builder()
                .accountNumber("ACC-EUR-" + UUID.randomUUID())
                .accountName("EUR account")
                .accountType(AccountType.REVENUE)
                .currency("EUR")
                .build());

        PostingRequest mismatched = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "currency mismatch",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(eurCreditAccount.getId(), new BigDecimal("100.00"), "USD")));

        var counter = meterRegistry.counter(
                "ledger.posting.requests", "outcome", "failure", "failure_reason", "currency_mismatch");
        double before = counter.count();

        assertThatThrownBy(() -> postingService.post(mismatched)).isInstanceOf(CurrencyMismatchException.class);

        assertThat(counter.count()).isEqualTo(before + 1);
    }

    @Test
    void idempotencyConflictIncrementsDedicatedCounterAndTagsFailureReason() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String idempotencyKey = "idem-" + UUID.randomUUID();

        postingService.post(new PostingRequest(
                idempotencyKey,
                "REF-" + UUID.randomUUID(),
                "original",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("100.00"), "USD"))));

        PostingRequest conflicting = new PostingRequest(
                idempotencyKey,
                "REF-" + UUID.randomUUID(),
                "conflicting",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("250.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("250.00"), "USD")));

        var conflictsCounter = meterRegistry.counter("ledger.idempotency.conflicts");
        var failuresCounter = meterRegistry.counter(
                "ledger.posting.requests", "outcome", "failure", "failure_reason", "idempotency_conflict");
        double conflictsBefore = conflictsCounter.count();
        double failuresBefore = failuresCounter.count();

        assertThatThrownBy(() -> postingService.post(conflicting))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(conflictsCounter.count()).isEqualTo(conflictsBefore + 1);
        assertThat(failuresCounter.count()).isEqualTo(failuresBefore + 1);
    }
}
