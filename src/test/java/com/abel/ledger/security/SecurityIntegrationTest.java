package com.abel.ledger.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.abel.ledger.domain.account.Account;
import com.abel.ledger.domain.account.AccountType;
import com.abel.ledger.domain.user.Role;
import com.abel.ledger.repository.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exercises authentication and role-based authorization through real HTTP
 * request/response handling, the same MockMvc approach used by the other
 * controller integration tests in this project (see
 * {@code JournalEntryControllerIntegrationTest}). Lives in
 * {@code com.abel.ledger.security} specifically so it can reach
 * {@link JwtService}'s package-visible
 * {@link JwtService#generateToken(String, Role, Instant)} overload to mint
 * an already-expired token using the application's real signing key,
 * without exposing that key as public API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String VIEWER_USERNAME = "viewer";
    private static final String VIEWER_PASSWORD = "viewer123";

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

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String adminToken() throws Exception {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    private String viewerToken() throws Exception {
        return login(VIEWER_USERNAME, VIEWER_PASSWORD);
    }

    private Map<String, Object> postingRequestBody(UUID debitAccountId, UUID creditAccountId) {
        return Map.of(
                "idempotencyKey", "idem-" + UUID.randomUUID(),
                "referenceId", "REF-" + UUID.randomUUID(),
                "description", "security integration test posting",
                "debitEntries", List.of(Map.of("accountId", debitAccountId, "amount", "10.00", "currency", "USD")),
                "creditEntries", List.of(Map.of("accountId", creditAccountId, "amount", "10.00", "currency", "USD")));
    }

    @Test
    void login_validAdminCredentials_returnsSignedJwt() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void login_invalidPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", ADMIN_USERNAME, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "no-such-user", "password", "irrelevant"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_missingAuthorizationHeader_returns401() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                postingRequestBody(debitAccount.getId(), creditAccount.getId()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_malformedJwt_returns401() throws Exception {
        Account account = saveAccount(AccountType.ASSET);

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedEndpoint_expiredJwt_returns401() throws Exception {
        Account account = saveAccount(AccountType.ASSET);
        String expiredToken = jwtService
                .generateToken(ADMIN_USERNAME, Role.ADMIN, Instant.now().minus(2, ChronoUnit.HOURS))
                .token();

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void postJournalEntry_adminRole_returns201() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                postingRequestBody(debitAccount.getId(), creditAccount.getId()))))
                .andExpect(status().isCreated());
    }

    @Test
    void postJournalEntry_viewerRole_returns403() throws Exception {
        Account debitAccount = saveAccount(AccountType.ASSET);
        Account creditAccount = saveAccount(AccountType.REVENUE);

        mockMvc.perform(post("/api/v1/journal-entries")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                postingRequestBody(debitAccount.getId(), creditAccount.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void getBalance_viewerRole_returns200() throws Exception {
        Account account = saveAccount(AccountType.ASSET);

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken()))
                .andExpect(status().isOk());
    }

    @Test
    void getBalance_adminRole_returns200() throws Exception {
        Account account = saveAccount(AccountType.ASSET);

        mockMvc.perform(get("/api/v1/accounts/{id}/balance", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void getLedger_viewerRole_returns200() throws Exception {
        Account account = saveAccount(AccountType.ASSET);

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken()))
                .andExpect(status().isOk());
    }

    @Test
    void getLedger_adminRole_returns200() throws Exception {
        Account account = saveAccount(AccountType.ASSET);

        mockMvc.perform(get("/api/v1/accounts/{id}/ledger", account.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealth_noAuth_remainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void swaggerUi_noAuth_remainsPublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
