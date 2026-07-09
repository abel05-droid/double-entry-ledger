package com.abel.ledger.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.dto.LedgerEntryRequest;
import com.abel.ledger.dto.PostingRequest;
import com.abel.ledger.dto.PostingResult;
import com.abel.ledger.exception.CurrencyMismatchException;
import com.abel.ledger.exception.UnbalancedJournalEntryException;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.service.PostingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the correctness requirements from docs/architecture.md,
 * "Event Publishing": an event is published if and only if the posting
 * transaction actually committed, publish failures can't fail a successful
 * post, and an idempotent replay never publishes a duplicate event.
 *
 * <p>Uses {@code @EmbeddedKafka} (an in-process broker from
 * {@code spring-kafka-test}, already a dependency since Phase 0 but unused
 * until now) rather than Testcontainers: this environment's Docker Desktop
 * negotiates API v1.55, which Testcontainers 1.20.3's bundled
 * {@code docker-java} still rejects (re-verified while building this
 * phase — same {@code BadRequestException} documented in
 * {@code LedgerApplicationTests} for Postgres/Kafka Testcontainers). Rather
 * than fall back to the shared, long-lived docker-compose Kafka container
 * (the workaround used for Postgres, since no embedded-Postgres dependency
 * is available), {@code @EmbeddedKafka} sidesteps the Docker question
 * entirely: it's in-process, fresh per test class, and doesn't require
 * {@code docker compose up} to be running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(partitions = 1, topics = {LedgerKafkaTopics.JOURNAL_ENTRY_POSTED})
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class LedgerEventPublishingIntegrationTest {

    @Autowired
    private PostingService postingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Consumer<String, String> consumer;

    @BeforeEach
    void setUpConsumer() {
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("test-" + UUID.randomUUID(), "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        consumer = consumerFactory.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, LedgerKafkaTopics.JOURNAL_ENTRY_POSTED);

        // A fresh consumer group reads from the topic's beginning, which
        // includes messages published by earlier test methods in this
        // class (the embedded broker's topic persists for the class's
        // lifetime). Draining them here means each test's own assertions
        // only see records produced by that test.
        drainAnyExistingRecords();
    }

    @AfterEach
    void tearDownConsumer() {
        consumer.close();
    }

    private void drainAnyExistingRecords() {
        ConsumerRecords<String, String> records;
        do {
            records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
        } while (!records.isEmpty());
    }

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Event publishing test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    @Test
    void publishesEventAfterSuccessfulPost() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String referenceId = "REF-" + UUID.randomUUID();

        PostingRequest request = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                referenceId,
                "event publishing test",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("100.00"), "USD")));

        PostingResult result = postingService.post(request);

        ConsumerRecord<String, String> record =
                KafkaTestUtils.getSingleRecord(consumer, LedgerKafkaTopics.JOURNAL_ENTRY_POSTED, Duration.ofSeconds(10));

        assertThat(record.key()).isEqualTo(result.journalEntryId().toString());

        JournalEntryPostedMessage message = objectMapper.readValue(record.value(), JournalEntryPostedMessage.class);
        assertThat(message.eventId()).isNotNull();
        assertThat(message.eventVersion()).isEqualTo(1);
        assertThat(message.journalEntryId()).isEqualTo(result.journalEntryId());
        assertThat(message.referenceId()).isEqualTo(referenceId);
        assertThat(message.postedAt()).isNotNull();
        assertThat(message.affectedAccountIds())
                .containsExactlyInAnyOrder(debitAccount.getId(), creditAccount.getId());
    }

    @Test
    void publishesNoEventWhenPostingFailsBecauseEntriesAreUnbalanced() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        PostingRequest unbalanced = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "unbalanced",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("90.00"), "USD")));

        assertThatThrownBy(() -> postingService.post(unbalanced))
                .isInstanceOf(UnbalancedJournalEntryException.class);

        // This is the direct proof that a rolled-back transaction never
        // reaches the after-commit listener: postWithinTransaction throws
        // (and its transaction rolls back) before the domain event is ever
        // raised, so nothing arrives here within a bounded wait.
        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));
        assertThat(records.count()).isZero();
    }

    @Test
    void publishesNoEventWhenPostingFailsBecauseOfCurrencyMismatch() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account eurCreditAccount = accountRepository.save(Account.builder()
                .accountNumber("ACC-EUR-" + UUID.randomUUID())
                .accountName("EUR revenue account")
                .accountType(AccountType.REVENUE)
                .currency("EUR")
                .build());

        PostingRequest mismatched = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "currency mismatch",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("100.00"), "USD")),
                List.of(new LedgerEntryRequest(eurCreditAccount.getId(), new BigDecimal("100.00"), "USD")));

        assertThatThrownBy(() -> postingService.post(mismatched))
                .isInstanceOf(CurrencyMismatchException.class);

        ConsumerRecords<String, String> records =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));
        assertThat(records.count()).isZero();
    }

    @Test
    void publishesExactlyOneEventAcrossAnIdempotentReplay() {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        PostingRequest request = new PostingRequest(
                "idem-" + UUID.randomUUID(),
                "REF-" + UUID.randomUUID(),
                "replay test",
                List.of(new LedgerEntryRequest(debitAccount.getId(), new BigDecimal("50.00"), "USD")),
                List.of(new LedgerEntryRequest(creditAccount.getId(), new BigDecimal("50.00"), "USD")));

        PostingResult first = postingService.post(request);
        ConsumerRecord<String, String> firstRecord =
                KafkaTestUtils.getSingleRecord(consumer, LedgerKafkaTopics.JOURNAL_ENTRY_POSTED, Duration.ofSeconds(10));
        assertThat(firstRecord.key()).isEqualTo(first.journalEntryId().toString());

        PostingResult replay = postingService.post(request);
        assertThat(replay.journalEntryId()).isEqualTo(first.journalEntryId());

        ConsumerRecords<String, String> recordsAfterReplay =
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3));
        assertThat(recordsAfterReplay.count())
                .as("a same-payload replay under the same idempotencyKey must not publish a second event")
                .isZero();
    }
}
