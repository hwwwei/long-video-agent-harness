package io.github.hwwwei.video.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** MCP-ready permission adapter: built-in read-only tools only, no remote MCP transport. */
@Component
public class ToolRegistry {
    private static final Map<String, Set<String>> WHITELIST = Map.of(
        "planner", Set.of("transcript.read", "media.metadata"),
        "analysis", Set.of("transcript.read", "memory.lookup"),
        "summary", Set.of("transcript.read"),
        "critic", Set.of());
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ToolRegistry(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    public static boolean allowed(String agent, String tool) {
        return WHITELIST.getOrDefault(agent, Set.of()).contains(tool);
    }

    public Object call(String agent, String tool, String videoId) {
        if (!allowed(agent, tool)) throw new SecurityException("tool denied: " + agent + " -> " + tool);
        return switch (tool) {
            case "transcript.read" -> parse(db.queryForObject("SELECT segments_json FROM transcripts WHERE video_id=?", String.class, videoId));
            case "media.metadata" -> db.query("SELECT duration_ms,media_json FROM video_assets WHERE id=?",
                (rs, row) -> Map.of("durationMs", rs.getLong(1), "media", rs.getString(2) == null ? "" : rs.getString(2)), videoId);
            case "memory.lookup" -> db.query("SELECT fingerprint,COUNT(*) FROM feedback GROUP BY fingerprint ORDER BY COUNT(*) DESC LIMIT 20",
                (rs, row) -> Map.of("fingerprint", rs.getString(1), "count", rs.getLong(2)));
            default -> throw new SecurityException("unregistered tool");
        };
    }

    private Object parse(String raw) {
        try { return json.readValue(raw, Object.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
