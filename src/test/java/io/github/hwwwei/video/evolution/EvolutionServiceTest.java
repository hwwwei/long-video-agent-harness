package io.github.hwwwei.video.evolution;

import io.github.hwwwei.video.VideoHarnessApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = VideoHarnessApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:evolution_service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "harness.kafka.enabled=false", "harness.redis.enabled=false", "harness.media.enabled=false"
})
class EvolutionServiceTest {
    @Autowired EvolutionService evolution;
    @Autowired JdbcTemplate db;

    @Test void candidatePassesBothSplitsAndRollbackRestoresPreviousActive() {
        assertEquals("mock-v1", evolution.currentVersion());
        var accepted = evolution.propose(3);
        assertEquals("ACTIVATED", accepted.get("gateDecision"));
        assertEquals(accepted.get("version"), evolution.currentVersion());
        assertEquals(3, evolution.maxFacts(evolution.currentVersion()));
        var rejected = evolution.propose(2);
        assertEquals("REJECTED_VALIDATION", rejected.get("gateDecision"));
        assertEquals(accepted.get("version"), evolution.currentVersion());
        assertEquals("mock-v1", evolution.rollback());
        assertEquals("mock-v1", evolution.currentVersion());
        db.update("INSERT INTO feedback(id,run_id,label,fingerprint,note,created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID().toString(), "example", "OMISSION", "OMISSION:missing-third-point", "missed point", Timestamp.from(Instant.now()));
        assertEquals("OMISSION:missing-third-point", evolution.memoryPatterns().get(0).get("fingerprint"));
        assertEquals("ACTIVATED", evolution.proposeFromFeedback().get("gateDecision"));
    }
}
