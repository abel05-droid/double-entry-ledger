package com.abel.ledger.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exercises the REST layer through real HTTP request/response handling
 * (MockMvc dispatches through the full Spring MVC stack — filters,
 * converters, {@code @Valid} validation, and {@link
 * com.abel.ledger.api.exception.GlobalExceptionHandler} — without opening a
 * real socket), rather than calling controllers or services directly. This
 * is the standard Spring Boot approach for controller-level integration
 * tests: faster and more deterministic than a real HTTP client, while still
 * proving the whole request pipeline behaves correctly.
 *
 * Runs against whatever database the project's test configuration points
 * at (currently: the docker-compose Postgres stack — see
 * LedgerApplicationTests for why Testcontainers isn't used here); this
 * class makes no assumption about that beyond what @SpringBootTest already
 * requires.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class JournalEntryControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.abel.ledger.repository.AccountRepository accountRepository;

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    private Map<String, Object> postingRequestBody(
            String idempotencyKey, String referenceId, UUID debitAccountId, UUID creditAccountId, String amount) {
        return Map.of(
                "idempotencyKey", idempotencyKey,
                "referenceId", referenceId,
                "description", "integration test posting",
                "debitEntries", List.of(Map.of("accountId", debitAccountId, "amount", amount, "currency", "USD")),
                "creditEntries", List.of(Map.of("accountId", creditAccountId, "amount", amount, "currency", "USD")));
    }

    @Test
    void postJournalEntry_returnsCreatedWithLocationHeaderAndBody() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        Map<String, Object> body = postingRequestBody(
                "idem-" + UUID.randomUUID(), "REF-" + UUID.randomUUID(),
                debitAccount.getId(), creditAccount.getId(), "100.00");

        MvcResult result = mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.referenceId").value(body.get("referenceId")))
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.entries", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.entries[0].createdAt").value(org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.entries[1].createdAt").value(org.hamcrest.Matchers.notNullValue()))
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        String journalEntryId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
        assertThat(location).contains("/api/v1/journal-entries/" + journalEntryId);
    }

    @Test
    void postJournalEntry_sameIdempotencyKeySamePayload_returnsOriginalResultWithoutDuplicating() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String idempotencyKey = "idem-" + UUID.randomUUID();
        Map<String, Object> body = postingRequestBody(
                idempotencyKey, "REF-" + UUID.randomUUID(), debitAccount.getId(), creditAccount.getId(), "50.00");
        String payload = objectMapper.writeValueAsString(body);

        MvcResult first = mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void postJournalEntry_sameIdempotencyKeyDifferentPayload_returns409() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        String idempotencyKey = "idem-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postingRequestBody(
                                idempotencyKey, "REF-" + UUID.randomUUID(),
                                debitAccount.getId(), creditAccount.getId(), "50.00"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postingRequestBody(
                                idempotencyKey, "REF-" + UUID.randomUUID(),
                                debitAccount.getId(), creditAccount.getId(), "75.00"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/api/v1/journal-entries"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void postJournalEntry_unbalancedEntries_returns422() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        Map<String, Object> body = Map.of(
                "idempotencyKey", "idem-" + UUID.randomUUID(),
                "referenceId", "REF-" + UUID.randomUUID(),
                "description", "unbalanced",
                "debitEntries", List.of(Map.of(
                        "accountId", debitAccount.getId(), "amount", "100.00", "currency", "USD")),
                "creditEntries", List.of(Map.of(
                        "accountId", creditAccount.getId(), "amount", "90.00", "currency", "USD")));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message", containsString("debits")));
    }

    @Test
    void postJournalEntry_currencyMismatch_returns422() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        Map<String, Object> body = Map.of(
                "idempotencyKey", "idem-" + UUID.randomUUID(),
                "referenceId", "REF-" + UUID.randomUUID(),
                "description", "currency mismatch",
                "debitEntries", List.of(Map.of(
                        "accountId", debitAccount.getId(), "amount", "100.00", "currency", "EUR")),
                "creditEntries", List.of(Map.of(
                        "accountId", creditAccount.getId(), "amount", "100.00", "currency", "USD")));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void postJournalEntry_missingRequiredField_returns400() throws Exception {
        Map<String, Object> body = Map.of(
                "referenceId", "REF-" + UUID.randomUUID(),
                "debitEntries", List.of(),
                "creditEntries", List.of());

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getJournalEntry_found_returnsEntryWithLedgerEntries() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);
        Map<String, Object> body = postingRequestBody(
                "idem-" + UUID.randomUUID(), "REF-" + UUID.randomUUID(),
                debitAccount.getId(), creditAccount.getId(), "40.00");

        MvcResult posted = mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        String journalEntryId = objectMapper.readTree(posted.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/v1/journal-entries/{id}", journalEntryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(journalEntryId))
                .andExpect(jsonPath("$.entries", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void getJournalEntry_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/journal-entries/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
