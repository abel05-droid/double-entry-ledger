package com.abel.ledger.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@code /actuator/health} distinguishes application, database, and
 * Kafka status as separate components (per docs/architecture.md, "Health
 * Checks") rather than collapsing everything into one flag — run against
 * the real docker-compose Postgres + Kafka stack, both healthy, so this
 * covers the wiring end to end. DOWN-state behavior for each indicator is
 * covered in isolation elsewhere ({@link KafkaHealthIndicatorTest}) rather
 * than by actually stopping shared infrastructure here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HealthEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsOverallAndPerComponentStatusForDatabaseAndKafka() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.db.details.database").exists())
                .andExpect(jsonPath("$.components.kafka.status").value("UP"))
                .andExpect(jsonPath("$.components.kafka.details.clusterId").exists());
    }
}
