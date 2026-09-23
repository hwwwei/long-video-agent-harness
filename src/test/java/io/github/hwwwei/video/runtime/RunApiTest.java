package io.github.hwwwei.video.runtime;

import io.github.hwwwei.video.VideoHarnessApplication;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = VideoHarnessApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:run_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "harness.kafka.enabled=false", "harness.redis.enabled=false", "harness.media.enabled=false"
})
@AutoConfigureMockMvc
class RunApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @Test void createQueryCancelAndRejectResumeOfExhaustedRun() throws Exception {
        String videoId = UUID.randomUUID().toString();
        db.update("INSERT INTO video_assets(id,sha256,size_bytes,storage_path,created_at) VALUES (?,?,?,?,?)",
            videoId, "e".repeat(64), 10L, "test.mp4", Timestamp.from(Instant.now()));
        db.update("INSERT INTO transcripts(video_id,sha256,segments_json,mode,created_at) VALUES (?,?,?,?,?)",
            videoId, "f".repeat(64), "[{\"id\":\"s1\",\"startMs\":0,\"endMs\":1000,\"text\":\"A source fact.\"}]",
            "SIMULATED", Timestamp.from(Instant.now()));
        String body = mvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"videoId\":\"" + videoId + "\",\"budget\":{\"maxCalls\":50,\"maxTokens\":1000,\"maxMillis\":30000}}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.runId").exists()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("runId").asText();
        mvc.perform(get("/api/v1/runs/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/api/v1/runs/" + id + "/cancel")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/v1/runs/unknown")).andExpect(status().isNotFound());
    }
}
