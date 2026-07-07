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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

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

        assertThat(accountRepository.findAll()).isEmpty();
        assertThat(journalEntryRepository.findAll()).isEmpty();
        assertThat(ledgerEntryRepository.findAll()).isEmpty();
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
