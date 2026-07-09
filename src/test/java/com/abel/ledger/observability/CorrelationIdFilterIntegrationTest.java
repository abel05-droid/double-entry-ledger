package com.abel.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves {@link CorrelationIdFilter} does what it's for: an incoming
 * {@code X-Correlation-ID} is honored and echoed back, a missing one is
 * generated (as a valid UUIDv7) and still echoed back, and the header is
 * genuinely gone from MDC once the request completes (so it can never leak
 * into a later, unrelated request's log lines on a reused thread).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CorrelationIdFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void echoesBackAClientSuppliedCorrelationId() throws Exception {
        String suppliedCorrelationId = "client-supplied-" + UUID.randomUUID();

        mockMvc.perform(get("/").header(CorrelationIdFilter.CORRELATION_ID_HEADER, suppliedCorrelationId))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, suppliedCorrelationId));
    }

    @Test
    void generatesAUuidV7CorrelationIdWhenNoneIsSupplied() throws Exception {
        MvcResult result = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andReturn();

        String generated = result.getResponse().getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        UUID parsed = UUID.fromString(generated);

        assertThat(parsed.version())
                .as("a generated correlation id must be a UUIDv7")
                .isEqualTo(7);
    }

    @Test
    void mdcCorrelationIdDoesNotLeakPastTheRequestThatSetIt() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());

        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY))
                .as("MDC must be cleared once the request completes, since MockMvc test threads are also "
                        + "reused server-request-handling threads")
                .isNull();
    }
}
