package com.abel.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.domain.idempotency.IdempotencyKey;
import com.abel.ledger.domain.journal.JournalEntry;
import com.abel.ledger.domain.ledger.LedgerEntry;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.event.JournalEntryPostedEvent;
import com.abel.ledger.exception.AccountNotFoundException;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.IdempotencyKeyConflictException;
import com.abel.ledger.exception.InvalidPostingRequestException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.IdempotencyKeyRepository;
import com.abel.ledger.repository.JournalEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PostingServiceTest {

    @Mock
    private JournalEntryRepository journalEntryRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PostingService postingService;

    private Account cashAccount;
    private Account revenueAccount;

    @BeforeEach
    void setUp() {
        postingService = new PostingService(
                journalEntryRepository, ledgerEntryRepository, accountRepository, idempotencyKeyRepository,
                eventPublisher);

        cashAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("CASH-001")
                .accountName("Cash")
                .accountType(AccountType.ASSET)
                .currency("USD")
                .build();

        revenueAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("REV-001")
                .accountName("Revenue")
                .accountType(AccountType.REVENUE)
                .currency("USD")
                .build();
    }

    private PostingRequest balancedRequest(String amount) {
        return new PostingRequest(
                "idem-key-1",
                "ref-1",
                "test posting",
                List.of(new LedgerEntryRequest(cashAccount.getId(), new BigDecimal(amount), "USD")),
                List.of(new LedgerEntryRequest(revenueAccount.getId(), new BigDecimal(amount), "USD")));
    }

    @Test
    void rejectsUnbalancedEntries() {
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(cashAccount, revenueAccount));

        PostingRequest request = new PostingRequest(
                "idem-key-1",
                "ref-1",
                "unbalanced",
                List.of(new LedgerEntryRequest(cashAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(revenueAccount.getId(), new BigDecimal("90.00"), "USD")));

        assertThatThrownBy(() -> postingService.post(request))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        verifyNoInteractions(journalEntryRepository);
        verify(ledgerEntryRepository, never()).saveAll(anyList());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectsCurrencyMismatchBetweenEntryAndAccount() {
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(cashAccount, revenueAccount));

        PostingRequest request = new PostingRequest(
                "idem-key-1",
                "ref-1",
                "currency mismatch",
                List.of(new LedgerEntryRequest(cashAccount.getId(), new BigDecimal("100.00"), "EUR")),
                List.of(new LedgerEntryRequest(revenueAccount.getId(), new BigDecimal("100.00"), "USD")));

        assertThatThrownBy(() -> postingService.post(request))
                .isInstanceOf(CurrencyMismatchException.class);

        verifyNoInteractions(journalEntryRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectsMixedCurrenciesAcrossEntriesEvenWhenEachMatchesItsAccount() {
        Account eurCashAccount = Account.builder()
                .id(UUID.randomUUID())
                .accountNumber("CASH-EUR")
                .accountName("Cash EUR")
                .accountType(AccountType.ASSET)
                .currency("EUR")
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(eurCashAccount, revenueAccount));

        PostingRequest request = new PostingRequest(
                "idem-key-1",
                "ref-1",
                "mixed currency",
                List.of(new LedgerEntryRequest(eurCashAccount.getId(), new BigDecimal("100.00"), "EUR")),
                List.of(new LedgerEntryRequest(revenueAccount.getId(), new BigDecimal("100.00"), "USD")));

        assertThatThrownBy(() -> postingService.post(request))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void rejectsUnknownAccount() {
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(cashAccount));

        PostingRequest request = balancedRequest("100.00");

        assertThatThrownBy(() -> postingService.post(request))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(journalEntryRepository);
    }

    @Test
    void rejectsStructurallyInvalidRequest() {
        PostingRequest missingCredits = new PostingRequest(
                "idem-key-1", "ref-1", "no credits",
                List.of(new LedgerEntryRequest(cashAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of());

        assertThatThrownBy(() -> postingService.post(missingCredits))
                .isInstanceOf(InvalidPostingRequestException.class);

        verifyNoInteractions(idempotencyKeyRepository);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void postsBalancedEntrySuccessfully() {
        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(accountRepository.findAllById(any())).thenReturn(List.of(cashAccount, revenueAccount));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry input = invocation.getArgument(0);
            return JournalEntry.builder()
                    .id(UUID.randomUUID())
                    .referenceId(input.getReferenceId())
                    .description(input.getDescription())
                    .createdAt(Instant.now())
                    .build();
        });
        when(ledgerEntryRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<LedgerEntry> input = invocation.getArgument(0);
            return input.stream()
                    .map(entry -> LedgerEntry.builder()
                            .id(UUID.randomUUID())
                            .journalEntry(entry.getJournalEntry())
                            .account(entry.getAccount())
                            .entryType(entry.getEntryType())
                            .amount(entry.getAmount())
                            .currency(entry.getCurrency())
                            .createdAt(Instant.now())
                            .build())
                    .toList();
        });

        PostingResult result = postingService.post(balancedRequest("100.00"));

        assertThat(result.journalEntryId()).isNotNull();
        assertThat(result.referenceId()).isEqualTo("ref-1");
        assertThat(result.entries()).hasSize(2);
        verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));

        ArgumentCaptor<JournalEntryPostedEvent> eventCaptor = ArgumentCaptor.forClass(JournalEntryPostedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        JournalEntryPostedEvent event = eventCaptor.getValue();
        assertThat(event.journalEntryId()).isEqualTo(result.journalEntryId());
        assertThat(event.referenceId()).isEqualTo("ref-1");
        assertThat(event.affectedAccountIds()).containsExactlyInAnyOrder(cashAccount.getId(), revenueAccount.getId());
    }

    @Test
    void replaysMatchingPayloadUnderSameIdempotencyKeyWithoutWriting() {
        UUID journalEntryId = UUID.randomUUID();
        PostingRequest request = balancedRequest("100.00");

        IdempotencyKey existingKey = IdempotencyKey.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("idem-key-1")
                .requestFingerprint(invokeFingerprint(request))
                .journalEntryId(journalEntryId)
                .createdAt(Instant.now())
                .build();

        JournalEntry existingJournalEntry = JournalEntry.builder()
                .id(journalEntryId)
                .referenceId("ref-1")
                .description("test posting")
                .createdAt(Instant.now())
                .build();

        LedgerEntry existingDebit = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .journalEntry(existingJournalEntry)
                .account(cashAccount)
                .entryType(com.abel.ledger.domain.ledger.EntryType.DEBIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .createdAt(Instant.now())
                .build();

        LedgerEntry existingCredit = LedgerEntry.builder()
                .id(UUID.randomUUID())
                .journalEntry(existingJournalEntry)
                .account(revenueAccount)
                .entryType(com.abel.ledger.domain.ledger.EntryType.CREDIT)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .createdAt(Instant.now())
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.of(existingKey));
        when(journalEntryRepository.findById(journalEntryId)).thenReturn(Optional.of(existingJournalEntry));
        when(ledgerEntryRepository.findByJournalEntryId(journalEntryId))
                .thenReturn(List.of(existingDebit, existingCredit));

        PostingResult result = postingService.post(request);

        assertThat(result.journalEntryId()).isEqualTo(journalEntryId);
        assertThat(result.entries()).hasSize(2);
        verifyNoInteractions(accountRepository);
        verify(journalEntryRepository, never()).save(any());
        verify(ledgerEntryRepository, never()).saveAll(anyList());
        verify(idempotencyKeyRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentPayload() {
        IdempotencyKey existingKey = IdempotencyKey.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("idem-key-1")
                .requestFingerprint("different-fingerprint")
                .journalEntryId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();

        when(idempotencyKeyRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.of(existingKey));

        assertThatThrownBy(() -> postingService.post(balancedRequest("100.00")))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        verifyNoInteractions(accountRepository);
        verify(journalEntryRepository, never()).save(any());
    }

    /**
     * Invokes PostingService's private fingerprint() method directly via
     * reflection, so this test exercises the real production logic instead
     * of a hand-maintained copy that could silently drift out of sync with it.
     */
    private String invokeFingerprint(PostingRequest request) {
        try {
            Method fingerprint = PostingService.class.getDeclaredMethod("fingerprint", PostingRequest.class);
            fingerprint.setAccessible(true);
            return (String) fingerprint.invoke(postingService, request);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
