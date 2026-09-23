package io.github.hwwwei.video.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hwwwei.video.VideoHarnessApplication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = VideoHarnessApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:run_service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "harness.kafka.enabled=false", "harness.redis.enabled=false", "harness.media.enabled=false"
})
class RunServiceTest {
    @Autowired JdbcTemplate db;
    @Autowired RunService runs;
    @Autowired ObjectMapper json;

    @Test void producesEvidenceGroundedSummaryAndCheckpointDoesNotReplay() throws Exception {
        String videoId = UUID.randomUUID().toString();
        db.update("INSERT INTO video_assets(id,sha256,size_bytes,storage_path,created_at) VALUES (?,?,?,?,?)",
            videoId, "a".repeat(64), 10L, "test.mp4", Timestamp.from(Instant.now()));
        db.update("INSERT INTO transcripts(video_id,sha256,segments_json,mode,created_at) VALUES (?,?,?,?,?)",
            videoId, "b".repeat(64), "[{\"id\":\"s1\",\"startMs\":0,\"endMs\":1000,\"text\":\"The team shipped version two on Monday.\"}]",
            "SIMULATED", Timestamp.from(Instant.now()));
        String runId = runs.create(videoId, new RunService.Budget(100, 10000, 60000));
        assertTrue(runs.execute(runId, "worker-one"));
        JsonNode view = json.valueToTree(runs.view(runId));
        assertEquals("COMPLETED", view.get("status").asText());
        assertEquals("s1", view.at("/report/facts/0/segmentId").asText());
        assertTrue(view.at("/report/summary").asText().contains("version two"));
        int nodes = db.queryForObject("SELECT COUNT(*) FROM node_executions WHERE run_id=?", Integer.class, runId);
        assertFalse(runs.execute(runId, "worker-two"));
        assertEquals(nodes, db.queryForObject("SELECT COUNT(*) FROM node_executions WHERE run_id=?", Integer.class, runId));
    }

    @Test void databaseLeaseAllowsOnlyOneWorker() throws Exception {
        String videoId = UUID.randomUUID().toString();
        db.update("INSERT INTO video_assets(id,sha256,size_bytes,storage_path,created_at) VALUES (?,?,?,?,?)",
            videoId, "c".repeat(64), 10L, "test.mp4", Timestamp.from(Instant.now()));
        db.update("INSERT INTO transcripts(video_id,sha256,segments_json,mode,created_at) VALUES (?,?,?,?,?)",
            videoId, "d".repeat(64), "[{\"id\":\"s1\",\"startMs\":0,\"endMs\":1000,\"text\":\"A measured result.\"}]",
            "SIMULATED", Timestamp.from(Instant.now()));
        String runId = runs.create(videoId, new RunService.Budget(100, 10000, 60000));
        assertTrue(runs.claim(runId, "worker-a"));
        assertFalse(runs.claim(runId, "worker-b"));
    }

    @Test void blindCriticRequestsTargetedCoverageReworkAtMostTwice() throws Exception {
        String videoId = UUID.randomUUID().toString();
        db.update("INSERT INTO video_assets(id,sha256,size_bytes,storage_path,created_at) VALUES (?,?,?,?,?)",
            videoId, "1".repeat(64), 10L, "test.mp4", Timestamp.from(Instant.now()));
        db.update("INSERT INTO transcripts(video_id,sha256,segments_json,mode,created_at) VALUES (?,?,?,?,?)",
            videoId, "2".repeat(64), "[{\"id\":\"s1\",\"startMs\":0,\"endMs\":1000,\"text\":\"First fact.\"},{\"id\":\"s2\",\"startMs\":1000,\"endMs\":2000,\"text\":\"Second fact.\"},{\"id\":\"s3\",\"startMs\":2000,\"endMs\":3000,\"text\":\"Third fact.\"}]",
            "SIMULATED", Timestamp.from(Instant.now()));
        String id = runs.create(videoId, new RunService.Budget(100, 10000, 60000));
        runs.execute(id, "critic-worker");
        JsonNode run = json.valueToTree(runs.view(id));
        assertEquals("COMPLETED", run.path("status").asText());
        assertEquals(3, run.at("/report/facts").size());
        assertTrue(run.at("/report/summary").asText().contains("Third fact"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM node_executions WHERE run_id=? AND node='rework:s3:1'", Integer.class, id));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM node_executions WHERE run_id=? AND node LIKE 'rework:%:3'", Integer.class, id));
    }
}
