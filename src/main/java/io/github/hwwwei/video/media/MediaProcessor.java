package io.github.hwwwei.video.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MediaProcessor {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Path root;
    private final ObjectProvider<RedissonClient> locks;

    public MediaProcessor(JdbcTemplate db, ObjectMapper json,
            @Value("${harness.media.enabled:false}") boolean enabled,
            @Value("${harness.storage-root}") String root,
            ObjectProvider<RedissonClient> locks) {
        this.db = db;
        this.json = json;
        this.enabled = enabled;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.locks = locks;
    }

    public Map<String, Object> probe(String videoId) {
        if (!enabled) return Map.of("mode", "DISABLED", "note", "media processing disabled in this profile");
        return locked(videoId, () -> doProbe(videoId));
    }

    private Map<String, Object> doProbe(String videoId) {
        try {
            String raw = execute(Duration.ofSeconds(30), "ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", videoPath(videoId).toString());
            JsonNode info = json.readTree(raw);
            long duration = Math.round(info.path("format").path("duration").asDouble(0) * 1000);
            if (duration <= 0 || !info.path("streams").isArray()) throw new IllegalStateException("invalid FFprobe media output");
            db.update("UPDATE video_assets SET duration_ms=?,media_json=? WHERE id=?", duration, raw, videoId);
            return Map.of("durationMs", duration, "streams", info.path("streams").size(), "mode", "FFPROBE");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("FFprobe failed", error);
        }
    }

    public Map<String, Object> extractAudio(String videoId) {
        if (!enabled) return Map.of("mode", "DISABLED", "note", "audio extraction disabled in this profile");
        return locked(videoId, () -> doExtractAudio(videoId));
    }

    private Map<String, Object> doExtractAudio(String videoId) {
        try {
            Path audio = root.resolve("audio").resolve(videoId + ".wav");
            Files.createDirectories(audio.getParent());
            if (!Files.isRegularFile(audio)) {
                execute(Duration.ofMinutes(5), "ffmpeg", "-nostdin", "-v", "error", "-i", videoPath(videoId).toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-f", "wav", "-y", audio.toString());
            }
            db.update("UPDATE video_assets SET audio_path=? WHERE id=?", audio.toString(), videoId);
            return Map.of("audioPath", audio.toString(), "mode", "FFMPEG");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg failed", error);
        }
    }

    private Map<String, Object> locked(String videoId, Supplier<Map<String, Object>> action) {
        RedissonClient client = locks.getIfAvailable();
        if (client == null) return action.get();
        String hash = db.queryForObject("SELECT sha256 FROM video_assets WHERE id=?", String.class, videoId);
        RLock lock = client.getLock("media:" + hash);
        // No lease time: Redisson's WatchDog renews the lock while this worker is alive.
        lock.lock();
        try { return action.get(); }
        finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private Path videoPath(String videoId) {
        String stored = db.queryForObject("SELECT storage_path FROM video_assets WHERE id=?", String.class, videoId);
        Path path = Path.of(stored).toAbsolutePath().normalize();
        if (!path.startsWith(root.resolve("videos"))) throw new IllegalStateException("video path outside storage root");
        return path;
    }

    private String execute(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean ended;
        try { ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { process.destroyForcibly(); throw interrupted; }
        if (!ended) { process.destroyForcibly(); throw new IllegalStateException("media command timed out"); }
        String output = new String(process.getInputStream().readAllBytes());
        if (process.exitValue() != 0) throw new IllegalStateException("media command failed: " + output.substring(0, Math.min(300, output.length())));
        return output;
    }
}
