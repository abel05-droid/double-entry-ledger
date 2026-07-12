package com.abel.ledger.api.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.domain.user.Role;
import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * See {@link JournalEntryControllerIntegrationTest} for why these tests use
 * MockMvc against the full Spring MVC stack rather than calling
 * BalanceService/LedgerQueryService directly, and why no Testcontainers
 * assumption is baked in here beyond @SpringBootTest itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtService jwtService;

    private Account saveAccount(AccountType type) {
        return accountRepository.save(Account.builder()
                .accountNumber("ACC-" + UUID.randomUUID())
                .accountName("Test " + type)
                .accountType(type)
                .currency("USD")
                .build());
    }

    // Reads are available to any authenticated role, so these tests
    // deliberately use a VIEWER token rather than ADMIN to prove that.
    private String viewerAuthHeader() {
        return "Bearer " + jwtService.generateToken("test-viewer", Role.VIEWER).token();
    }

    private String adminAuthHeader() {
        return "Bearer " + jwtService.generateToken("test-admin", Role.ADMIN).token();
    }

    private void postJournalEntry(String idempotencyKey, UUID debitAccountId, UUID creditAccountId, String amount)
            throws Exception {
        Map<String, Object> body = Map.of(
                "idempotencyKey", idempotencyKey,
                "referenceId", "REF-" + UUID.randomUUID(),
                "description", "seed posting",
                "debitEntries", List.of(Map.of("accountId", debitAccountId, "amount", amount, "currency", "USD")),
                "creditEntries", List.of(Map.of("accountId", creditAccountId, "amount", amount, "currency", "USD")));

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header(HttpHeaders.AUTHORIZATION, adminAuthHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void getBalance_returnsDerivedBalance() throws Exception {
        Account cash = saveAccount(AccountType.ASSET);
        Account revenue = saveAccount(AccountType.REVENUE);

        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "300.00");
        postJournalEntry("idem-" + UUID.randomUUID(), revenue.getId(), cash.getId(), "50.00");

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", cash.getId())
                        .header(HttpHeaders.AUTHORIZATION, viewerAuthHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(cash.getId().toString()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void getBalance_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/balance", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, viewerAuthHeader()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void getLedger_returnsPagedResponseSortedNewestFirstByDefault() throws Exception {
        Account cash = saveAccount(AccountType.ASSET);
        Account revenue = saveAccount(AccountType.REVENUE);

        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "10.00");
        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "20.00");
        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "30.00");

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", cash.getId())
                        .header(HttpHeaders.AUTHORIZATION, viewerAuthHeader())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                // Newest first by default: the last-posted line (30.00) must lead.
                .andExpect(jsonPath("$.content[0].amount").value(30.00))
                .andExpect(jsonPath("$.content[1].amount").value(20.00));
    }

    @Test
    void getLedger_customSortAscendingByAmount() throws Exception {
        Account cash = saveAccount(AccountType.ASSET);
        Account revenue = saveAccount(AccountType.REVENUE);

        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "10.00");
        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "30.00");
        postJournalEntry("idem-" + UUID.randomUUID(), cash.getId(), revenue.getId(), "20.00");

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", cash.getId())
                        .header(HttpHeaders.AUTHORIZATION, viewerAuthHeader())
                        .param("sort", "amount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(10.00))
                .andExpect(jsonPath("$.content[1].amount").value(20.00))
                .andExpect(jsonPath("$.content[2].amount").value(30.00));
    }

    @Test
    void getLedger_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, viewerAuthHeader()))
                .andExpect(status().isNotFound());
    }
}
