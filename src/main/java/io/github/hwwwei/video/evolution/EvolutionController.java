package io.github.hwwwei.video.evolution;

import io.github.hwwwei.video.benchmark.BenchmarkService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class EvolutionController {
    public record Propose(int maxFacts) {}
    public record FeedbackInput(String label, String note) {}
    private final EvolutionService evolution;
    private final BenchmarkService benchmark;
    private final JdbcTemplate db;

    public EvolutionController(EvolutionService evolution, BenchmarkService benchmark, JdbcTemplate db) {
        this.evolution = evolution; this.benchmark = benchmark; this.db = db;
    }

    @PostMapping("/benchmark")
    public Map<String, Object> benchmark() {
        int rules = evolution.maxFacts(evolution.currentVersion());
        return Map.of("scope", "PROVIDER_ONLY", "validation", benchmark.evaluate("VALIDATION", rules),
            "holdout", benchmark.evaluate("HOLDOUT", rules));
    }

    @GetMapping("/evolution/versions")
    public List<Map<String, Object>> versions() { return evolution.versions(); }

    @GetMapping("/evolution/memory")
    public List<Map<String, Object>> memory() { return evolution.memoryPatterns(); }

    @PostMapping("/evolution/candidates")
    public Map<String, Object> propose(@RequestBody Propose proposal) {
        try { return evolution.propose(proposal.maxFacts()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage()); }
    }

    @PostMapping("/evolution/candidates/from-feedback")
    public Map<String, Object> proposeFromFeedback() {
        try { return evolution.proposeFromFeedback(); }
        catch (IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage()); }
    }

    @PostMapping("/evolution/rollback")
    public Map<String, String> rollback() {
        try { return Map.of("activeVersion", evolution.rollback()); }
        catch (IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage()); }
    }

    @PostMapping("/runs/{id}/feedback")
    public Map<String, String> feedback(@PathVariable String id, @RequestBody FeedbackInput input) {
        if (db.queryForObject("SELECT COUNT(*) FROM runs WHERE id=?", Integer.class, id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found");
        if (input.label() == null || !List.of("FACT_ERROR", "OMISSION", "CRITIC_REJECT", "NEGATIVE").contains(input.label()))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported label");
        String note = input.note() == null ? "" : input.note().trim();
        String fingerprint = input.label() + ":" + note.toLowerCase().replaceAll("\\d+", "#").replaceAll("\\s+", " ");
        if (fingerprint.length() > 128) fingerprint = fingerprint.substring(0, 128);
        String feedbackId = UUID.randomUUID().toString();
        db.update("INSERT INTO feedback(id,run_id,label,fingerprint,note,created_at) VALUES (?,?,?,?,?,?)",
            feedbackId, id, input.label(), fingerprint, note, Timestamp.from(Instant.now()));
        return Map.of("feedbackId", feedbackId, "fingerprint", fingerprint);
    }
}
