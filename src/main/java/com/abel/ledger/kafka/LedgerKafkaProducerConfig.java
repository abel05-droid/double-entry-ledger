package com.abel.ledger.kafka;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * A dedicated producer for ledger domain events, deliberately not the
 * Spring Boot auto-configured {@code KafkaTemplate<Object, Object>} bean
 * that {@code spring.kafka.producer.*} in {@code application.yml}
 * describes (that bean remains present, but unused by ledger event
 * publishing — it's Phase 0 scaffold, and harmless to leave for any future
 * producer). Every setting below is explicit and independently justified,
 * since this producer carries a public event contract:
 *
 * <ul>
 *   <li>{@code BOOTSTRAP_SERVERS_CONFIG} — sourced from the same
 *       {@code spring.kafka.bootstrap-servers} property (and therefore the
 *       same {@code KAFKA_BOOTSTRAP_SERVERS} env var) already wired for the
 *       rest of the app, so this producer always points at the same
 *       cluster without a second place to configure it.</li>
 *   <li>{@code CLIENT_ID_CONFIG} — a distinct, descriptive client id so
 *       this producer is identifiable in broker-side logs/metrics/quotas,
 *       separate from any future producer or consumer this app adds.</li>
 *   <li>{@code KEY_SERIALIZER_CLASS_CONFIG} — {@link StringSerializer}: the
 *       message key is {@code journalEntryId.toString()}, a plain string,
 *       so no richer serializer is needed.</li>
 *   <li>{@code VALUE_SERIALIZER_CLASS_CONFIG} — {@link JsonSerializer}: the
 *       payload is a structured object read by downstream consumers outside
 *       this JVM; JSON is broadly interoperable, human-readable, and
 *       evolves compatibly (see {@link JournalEntryPostedMessage}).</li>
 *   <li>{@code ACKS_CONFIG = "all"} — the producer waits for the message to
 *       be acknowledged by every in-sync replica, not just the partition
 *       leader, before considering the send successful: the strongest
 *       durability guarantee the Kafka protocol offers, appropriate for a
 *       financial-domain event stream. (This environment's docker-compose
 *       Kafka runs with replication factor 1, so {@code acks=all} behaves
 *       like {@code acks=1} locally — the setting is what a real,
 *       replicated production cluster needs, and costs nothing to set now.)</li>
 *   <li>{@code RETRIES_CONFIG = 3} — a small number of client-level retries
 *       absorbs transient network blips or leader elections without any
 *       custom retry code.</li>
 *   <li>{@code ENABLE_IDEMPOTENCE_CONFIG = true} — pairs with retries: a
 *       retried send is guaranteed not to be written to the partition
 *       twice, so producer-level retries can never themselves cause a
 *       duplicate event on the broker.</li>
 *   <li>{@code JsonSerializer.ADD_TYPE_INFO_HEADERS = false} — by default
 *       Spring's {@code JsonSerializer} stamps a {@code __TypeId__} header
 *       naming this JVM's Java class. External, non-Java consumers should
 *       depend only on the JSON body and {@code eventVersion}, not on an
 *       implementation detail of how the producer happens to be written in
 *       Java, so that header is turned off.</li>
 * </ul>
 */
@Configuration
public class LedgerKafkaProducerConfig {

    private static final String CLIENT_ID = "double-entry-ledger-events-producer";
    private static final int RETRIES = 3;

    private final String bootstrapServers;

    public LedgerKafkaProducerConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    public ProducerFactory<String, JournalEntryPostedMessage> ledgerEventProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.CLIENT_ID_CONFIG, CLIENT_ID,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.RETRIES_CONFIG, RETRIES,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, JournalEntryPostedMessage> ledgerEventKafkaTemplate(
            ProducerFactory<String, JournalEntryPostedMessage> ledgerEventProducerFactory) {
        return new KafkaTemplate<>(ledgerEventProducerFactory);
    }
}
