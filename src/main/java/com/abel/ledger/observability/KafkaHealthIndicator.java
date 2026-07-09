package com.abel.ledger.observability;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reports Kafka broker reachability as its own {@code kafka} component
 * under {@code /actuator/health}, independent of the {@code db} component
 * that Spring Boot's auto-configured {@code DataSourceHealthIndicator}
 * already provides (see docs/architecture.md, "Health Checks", for why no
 * custom database indicator was written — Boot's existing one is already
 * correct and this phase would only be duplicating it).
 *
 * <p>Replaces Spring Boot's own auto-configured Kafka health indicator
 * (kept disabled via {@code management.health.kafka.enabled: false} in
 * {@code application.yml}, originally disabled in Phase 3 when no Kafka
 * publishing existed at all) with this purpose-built one, which reuses the
 * same {@link KafkaAdmin} bean the rest of the app's Kafka configuration is
 * built from rather than a generic, separately-configured check.
 *
 * <p>Uses a short, bounded timeout so a completely unreachable broker
 * reports DOWN quickly rather than hanging the health endpoint.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final KafkaAdmin kafkaAdmin;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public Health health() {
        Object bootstrapServers = kafkaAdmin.getConfigurationProperties()
                .get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);

        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            DescribeClusterResult describeClusterResult = adminClient.describeCluster(
                    new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));

            String clusterId = describeClusterResult.clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Collection<Node> nodes =
                    describeClusterResult.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            return Health.up()
                    .withDetail("bootstrapServers", bootstrapServers)
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodes.size())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("bootstrapServers", bootstrapServers)
                    .build();
        }
    }

    /**
     * Exposed for tests that need to point a {@code KafkaHealthIndicator}
     * at an unreachable broker without touching the shared docker-compose
     * Kafka container the rest of the suite depends on.
     */
    static KafkaHealthIndicator forBootstrapServers(String bootstrapServers) {
        return new KafkaHealthIndicator(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)));
    }
}
