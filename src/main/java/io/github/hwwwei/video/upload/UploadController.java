package io.github.hwwwei.video.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class UploadController {
    private final JdbcTemplate db;
    private final ChunkStorage storage;
    private final ObjectMapper json;
    private final int chunkSize;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final boolean redisEnabled;
    private final UploadRateLimiter rateLimiter;

    public UploadController(JdbcTemplate db, ObjectMapper json,
            @Value("${harness.storage-root}") String root,
            @Value("${harness.chunk-size:8388608}") int chunkSize,
            @Value("${harness.redis.enabled:false}") boolean redisEnabled,
            ObjectProvider<StringRedisTemplate> redis, UploadRateLimiter rateLimiter) {
        this.db = db;
        this.json = json;
        this.storage = new ChunkStorage(Path.of(root));
        this.chunkSize = chunkSize;
        this.redis = redis;
        this.redisEnabled = redisEnabled;
        this.rateLimiter = rateLimiter;
    }

    public record CreateUpload(@NotBlank String filename, @NotBlank String contentType, @Positive long sizeBytes) {}
    public record CompleteUpload(@NotBlank String sha256) {}
    public record TranscriptSegment(@NotBlank String id, long startMs, long endMs, @NotBlank String text) {}
    public record PutTranscript(@NotEmpty List<@Valid TranscriptSegment> segments) {}
    private record Session(String id, long sizeBytes, int chunkSize, String status, String videoId) {
        ChunkLayout layout() { return new ChunkLayout(sizeBytes, chunkSize); }
    }

    @PostMapping("/uploads")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateUpload request) {
        try {
            ChunkLayout layout = new ChunkLayout(request.sizeBytes(), chunkSize);
            if (request.filename().contains("/") || request.filename().contains("\\") || request.filename().contains("..")) {
                throw new IllegalArgumentException("filename must not contain a path");
            }
            String id = UUID.randomUUID().toString();
            db.update("INSERT INTO upload_sessions(id, filename, content_type, size_bytes, chunk_size, status, created_at) VALUES (?,?,?,?,?,?,?)",
                id, request.filename(), request.contentType(), request.sizeBytes(), chunkSize, "UPLOADING", Timestamp.from(Instant.now()));
            return Map.of("uploadId", id, "chunkSize", chunkSize, "chunkCount", layout.count(), "status", "UPLOADING");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }

    @PutMapping("/uploads/{id}/chunks/{index}")
    public Map<String, Object> putChunk(@PathVariable String id, @PathVariable int index,
            @RequestHeader("X-Chunk-SHA256") String sha256, HttpServletRequest request) throws IOException {
        Session session = session(id);
        if (!session.status().equals("UPLOADING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "upload already complete");
        if (!rateLimiter.acquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "upload token bucket exhausted");
        try {
            boolean stored = storage.put(id, session.layout(), index, request.getInputStream(), sha256);
            List<String> manifest = db.query("SELECT sha256 FROM upload_chunks WHERE upload_id=? AND chunk_index=?",
                (rs, row) -> rs.getString(1), id, index);
            if (manifest.isEmpty()) {
                db.update("INSERT INTO upload_chunks(upload_id,chunk_index,sha256,size_bytes,created_at) VALUES (?,?,?,?,?)",
                    id, index, sha256.toLowerCase(), session.layout().expectedLength(index), Timestamp.from(Instant.now()));
            } else if (!manifest.get(0).equalsIgnoreCase(sha256)) {
                throw new IllegalStateException("manifest conflicts with uploaded chunk");
            }
            if (redisEnabled && redis.getIfAvailable() != null) {
                try { redis.getObject().opsForSet().add("upload:" + id + ":received", Integer.toString(index)); }
                catch (RuntimeException ignored) { /* PostgreSQL manifest and disk are authoritative */ }
            }
            return Map.of("index", index, "stored", stored, "idempotent", !stored);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @GetMapping("/uploads/{id}")
    public Map<String, Object> status(@PathVariable String id) throws IOException {
        Session session = session(id);
        List<Integer> received = new ArrayList<>();
        for (int i = 0; i < session.layout().count(); i++) {
            if (storage.hasChunk(id, session.layout(), i)
                && db.queryForObject("SELECT COUNT(*) FROM upload_chunks WHERE upload_id=? AND chunk_index=?", Integer.class, id, i) == 1) received.add(i);
        }
        return Map.of("uploadId", id, "status", session.status(), "chunkCount", session.layout().count(),
            "received", received, "sizeBytes", session.sizeBytes());
    }

    @PostMapping("/uploads/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id, @Valid @RequestBody CompleteUpload request) throws IOException {
        Session session = session(id);
        if (session.status().equals("COMPLETE")) {
            String original = db.queryForObject("SELECT sha256 FROM video_assets WHERE id=?", String.class, session.videoId());
            if (!original.equalsIgnoreCase(request.sha256())) throw new ResponseStatusException(HttpStatus.CONFLICT, "completed upload hash differs");
            return Map.of("videoId", session.videoId(), "deduplicated", true);
        }
        try {
            Path path = storage.complete(id, session.layout(), request.sha256());
            String sha = request.sha256().toLowerCase();
            List<String> existing = db.query("SELECT id FROM video_assets WHERE sha256=?", (rs, row) -> rs.getString(1), sha);
            String videoId = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
            if (existing.isEmpty()) {
                try {
                    db.update("INSERT INTO video_assets(id,sha256,size_bytes,storage_path,created_at) VALUES (?,?,?,?,?)",
                        videoId, sha, session.sizeBytes(), path.toString(), Timestamp.from(Instant.now()));
                } catch (DuplicateKeyException race) {
                    videoId = db.queryForObject("SELECT id FROM video_assets WHERE sha256=?", String.class, sha);
                    existing = List.of(videoId);
                }
            }
            db.update("UPDATE upload_sessions SET status='COMPLETE',video_id=? WHERE id=? AND status='UPLOADING'", videoId, id);
            return Map.of("videoId", videoId, "deduplicated", !existing.isEmpty());
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage());
        }
    }

    @PostMapping("/videos/{id}/transcript")
    public Map<String, Object> transcript(@PathVariable String id, @Valid @RequestBody PutTranscript request) throws JsonProcessingException {
        if (db.queryForObject("SELECT COUNT(*) FROM video_assets WHERE id=?", Integer.class, id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        }
        long lastEnd = -1;
        for (TranscriptSegment segment : request.segments()) {
            if (segment.startMs() < 0 || segment.endMs() <= segment.startMs() || segment.startMs() < lastEnd) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "segments must be ordered and non-overlapping");
            }
            lastEnd = segment.endMs();
        }
        String body = json.writeValueAsString(request.segments());
        String sha = digest(body);
        db.update("DELETE FROM transcripts WHERE video_id=?", id);
        db.update("INSERT INTO transcripts(video_id,sha256,segments_json,mode,created_at) VALUES (?,?,?,?,?)",
            id, sha, body, "SIMULATED", Timestamp.from(Instant.now()));
        return Map.of("videoId", id, "mode", "SIMULATED", "segmentCount", request.segments().size(), "sha256", sha);
    }

    private Session session(String id) {
        List<Session> found = db.query("SELECT id,size_bytes,chunk_size,status,video_id FROM upload_sessions WHERE id=?",
            (rs, row) -> new Session(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)), id);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "upload not found");
        return found.get(0);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
