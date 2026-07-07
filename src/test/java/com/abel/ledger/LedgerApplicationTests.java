package com.abel.ledger;

import com.abel.ledger.repository.AccountRepository;
import com.abel.ledger.repository.JournalEntryRepository;
import com.abel.ledger.repository.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// TODO: Testcontainers-managed Postgres/Kafka containers are temporarily
// disabled. In this environment, the bundled docker-java client (via
// Testcontainers 1.20.3) negotiates Docker Engine API v1.32, which this
// Docker Desktop installation (API 1.55, minimum supported 1.40) rejects
// with HTTP 400 before any container is created — unrelated to the
// application or test code. Until Testcontainers/docker-java is upgraded
// to a version that negotiates a compatible API version, these tests run
// against the Postgres/Kafka stack started via `docker compose up`, which
// must be running locally on the default ports for this class to pass.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void repositoryBeansLoadAndFlywayMigrationsHaveRun() {
        assertThat(accountRepository).isNotNull();
        assertThat(journalEntryRepository).isNotNull();
        assertThat(ledgerEntryRepository).isNotNull();
    }

    @Test
    void statusEndpointReturnsRunningMessage() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo("Ledger Service Running");
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
