package com.abel.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Plain unit tests against {@link KafkaHealthIndicator} directly (no
 * Spring context) — DOWN behavior is verified by pointing a second
 * instance at a deliberately unreachable address rather than by stopping
 * the shared docker-compose Kafka container the rest of the suite depends
 * on, so this test can't disrupt other tests or the developer's running
 * stack.
 */
class KafkaHealthIndicatorTest {

    @Test
    void reportsUpWithClusterDetailsWhenBrokerIsReachable() {
        KafkaHealthIndicator indicator = KafkaHealthIndicator.forBootstrapServers("localhost:9092");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKeys("bootstrapServers", "clusterId", "nodeCount");
    }

    @Test
    void reportsDownWithErrorDetailsWhenBrokerIsUnreachable() {
        // Port 1 is a reserved, never-listening TCP port, so this fails
        // fast and deterministically rather than depending on some other
        // service not happening to be running there.
        KafkaHealthIndicator indicator = KafkaHealthIndicator.forBootstrapServers("localhost:1");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("bootstrapServers");
        assertThat(health.getDetails()).containsKey("error");
    }
}
