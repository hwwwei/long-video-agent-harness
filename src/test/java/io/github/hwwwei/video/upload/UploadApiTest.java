package io.github.hwwwei.video.upload;

import io.github.hwwwei.video.VideoHarnessApplication;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = VideoHarnessApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:upload_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.kafka.bootstrap-servers=localhost:1",
    "harness.kafka.enabled=false", "harness.redis.enabled=false", "harness.media.enabled=false",
    "harness.storage-root=target/upload-api", "harness.chunk-size=3"
})
@AutoConfigureMockMvc
class UploadApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void uploadsTwoChunksAndAttachesMockTranscript() throws Exception {
        String init = mvc.perform(post("/api/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"demo.mp4\",\"contentType\":\"video/mp4\",\"sizeBytes\":5}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode session = mapper.readTree(init);
        String id = session.get("uploadId").asText();
        assertEquals(2, session.get("chunkCount").asInt());
        mvc.perform(put("/api/v1/uploads/" + id + "/chunks/0").header("X-Chunk-SHA256", sha("abc")).content("abc"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/uploads/" + id + "/chunks/0").header("X-Chunk-SHA256", sha("xyz")).content("xyz"))
            .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/uploads/" + id + "/chunks/1").header("X-Chunk-SHA256", sha("de")).content("de"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/uploads/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.received[0]").value(0)).andExpect(jsonPath("$.received[1]").value(1));
        String complete = mvc.perform(post("/api/v1/uploads/" + id + "/complete").contentType(MediaType.APPLICATION_JSON)
            .content("{\"sha256\":\"" + sha("abcde") + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String videoId = mapper.readTree(complete).get("videoId").asText();
        mvc.perform(post("/api/v1/videos/" + videoId + "/transcript").contentType(MediaType.APPLICATION_JSON)
            .content("{\"segments\":[{\"id\":\"s1\",\"startMs\":0,\"endMs\":1000,\"text\":\"Hello world.\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SIMULATED"));
    }

    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }
}
