package io.github.hwwwei.video.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableKafka
@ConditionalOnProperty(name = "harness.kafka.enabled", havingValue = "true")
class KafkaConfiguration {
    @Bean NewTopic runsTopic() { return new NewTopic("video-runs", 1, (short) 1); }
    @Bean NewTopic deadLetterTopic() { return new NewTopic("video-runs-dlq", 1, (short) 1); }
}

@Component
@ConditionalOnProperty(name = "harness.kafka.enabled", havingValue = "true")
class OutboxPublisher {
    private final JdbcTemplate db;
    private final KafkaTemplate<String, String> kafka;

    OutboxPublisher(JdbcTemplate db, KafkaTemplate<String, String> kafka) {
        this.db = db; this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        List<String[]> pending = db.query("SELECT id,topic,payload_json FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100",
            (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
        for (String[] event : pending) {
            try {
                kafka.send(event[1], event[0], event[2]).get(10, TimeUnit.SECONDS);
                db.update("UPDATE outbox_events SET published_at=? WHERE id=? AND published_at IS NULL", Timestamp.from(Instant.now()), event[0]);
            } catch (Exception failure) {
                break; // leave in outbox for next poll; a duplicate delivery is harmless
            }
        }
    }
}

@Component
@ConditionalOnProperty(name = {"harness.kafka.enabled", "harness.worker.enabled"}, havingValue = "true")
class RunConsumer {
    private final RunService runs;
    private final ObjectMapper json;
    private final KafkaTemplate<String, String> kafka;
    private final String owner = "worker-" + UUID.randomUUID();

    RunConsumer(RunService runs, ObjectMapper json, KafkaTemplate<String, String> kafka) {
        this.runs = runs; this.json = json; this.kafka = kafka;
    }

    @KafkaListener(topics = "video-runs", groupId = "video-harness-workers")
    public void consume(ConsumerRecord<String, String> message) throws Exception {
        JsonNode payload = json.readTree(message.value());
        String runId = payload.path("runId").asText();
        if (runId.isBlank()) throw new IllegalArgumentException("event missing runId");
        runs.execute(runId, owner);
        if ("FAILED".equals(runs.view(runId).get("status"))) {
            kafka.send("video-runs-dlq", runId, message.value());
        }
    }
}

@Component
@ConditionalOnProperty(name = "harness.local-worker.enabled", havingValue = "true")
class LocalRunPoller {
    private final JdbcTemplate db;
    private final RunService runs;
    LocalRunPoller(JdbcTemplate db, RunService runs) { this.db = db; this.runs = runs; }
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        for (String runId : db.query("SELECT id FROM runs WHERE status='QUEUED' ORDER BY created_at LIMIT 10", (rs, row) -> rs.getString(1))) {
            runs.execute(runId, "local-worker");
        }
    }
}
