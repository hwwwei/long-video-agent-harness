package io.github.hwwwei.video.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hwwwei.video.benchmark.BenchmarkService;
import io.github.hwwwei.video.benchmark.BenchmarkService.Metrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EvolutionService {
    private final JdbcTemplate db;
    private final BenchmarkService benchmark;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public EvolutionService(JdbcTemplate db, BenchmarkService benchmark, ObjectMapper json,
            org.springframework.transaction.PlatformTransactionManager tx) {
        this.db = db; this.benchmark = benchmark; this.json = json; this.transactions = new TransactionTemplate(tx);
    }

    public static boolean passes(Metrics baseline, Metrics candidate) {
        double epsilon = 1e-9;
        boolean nondegrading = candidate.factConsistency() + epsilon >= baseline.factConsistency()
            && candidate.keyInformationRecall() + epsilon >= baseline.keyInformationRecall()
            && candidate.summaryCompleteness() + epsilon >= baseline.summaryCompleteness()
            && candidate.taskSuccessRate() + epsilon >= baseline.taskSuccessRate();
        boolean improvement = candidate.factConsistency() > baseline.factConsistency() + epsilon
            || candidate.keyInformationRecall() > baseline.keyInformationRecall() + epsilon
            || candidate.summaryCompleteness() > baseline.summaryCompleteness() + epsilon
            || candidate.taskSuccessRate() > baseline.taskSuccessRate() + epsilon;
        return nondegrading && improvement;
    }

    public String currentVersion() {
        ensureBaseline();
        return db.queryForObject("SELECT version FROM evolution_versions WHERE status='ACTIVE' ORDER BY activated_at DESC LIMIT 1", String.class);
    }

    public int maxFacts(String version) {
        ensureBaseline();
        String raw = db.queryForObject("SELECT candidate_json FROM evolution_versions WHERE version=?", String.class, version);
        try { return json.readTree(raw).path("maxFacts").asInt(2); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    public String prompt(String version) {
        ensureBaseline();
        String raw = db.queryForObject("SELECT candidate_json FROM evolution_versions WHERE version=?", String.class, version);
        try { return json.readTree(raw).path("prompt").asText("Extract concise facts grounded in the exact source text."); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    public Map<String, Object> propose(int maxFacts) {
        return propose(maxFacts, "manual");
    }

    public Map<String, Object> proposeFromFeedback() {
        List<String> patterns = db.query("SELECT fingerprint FROM feedback GROUP BY fingerprint ORDER BY COUNT(*) DESC LIMIT 1",
            (rs, row) -> rs.getString(1));
        if (patterns.isEmpty()) throw new IllegalStateException("no feedback pattern is available");
        return propose(Math.min(100, maxFacts(currentVersion()) + 1), patterns.get(0));
    }

    public List<Map<String, Object>> memoryPatterns() {
        return db.query("SELECT fingerprint,label,COUNT(*) FROM feedback GROUP BY fingerprint,label ORDER BY COUNT(*) DESC LIMIT 30",
            (rs, row) -> Map.of("fingerprint", rs.getString(1), "label", rs.getString(2), "count", rs.getLong(3)));
    }

    private Map<String, Object> propose(int maxFacts, String sourceFingerprint) {
        if (maxFacts < 1 || maxFacts > 100) throw new IllegalArgumentException("maxFacts must be 1..100");
        String baselineVersion = currentVersion();
        int baselineFacts = maxFacts(baselineVersion);
        Metrics baseValidation = benchmark.evaluate("VALIDATION", baselineFacts);
        Metrics candidateValidation = benchmark.evaluate("VALIDATION", maxFacts);
        boolean validationPassed = passes(baseValidation, candidateValidation);
        Metrics baseHoldout = benchmark.evaluate("HOLDOUT", baselineFacts);
        Metrics candidateHoldout = validationPassed ? benchmark.evaluate("HOLDOUT", maxFacts) : null;
        boolean accepted = validationPassed && passes(baseHoldout, candidateHoldout);
        String version = "mock-" + UUID.randomUUID().toString().substring(0, 8);
        String decision = !validationPassed ? "REJECTED_VALIDATION" : accepted ? "ACTIVATED" : "REJECTED_HOLDOUT";
        transactions.executeWithoutResult(tx -> {
            if (accepted) db.update("UPDATE evolution_versions SET status='PREVIOUS' WHERE status='ACTIVE'");
            db.update("INSERT INTO evolution_versions(id,version,status,predecessor,candidate_json,validation_json,holdout_json,gate_decision,created_at,baseline_version,activated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), version, accepted ? "ACTIVE" : "REJECTED", baselineVersion,
                encode(Map.of("maxFacts", maxFacts, "prompt", "Extract up to " + maxFacts + " concise source-grounded facts per analysis.",
                    "sourceFingerprint", sourceFingerprint)),
                encode(Map.of("baseline", baseValidation, "candidate", candidateValidation)),
                candidateHoldout == null ? "{}" : encode(Map.of("baseline", baseHoldout, "candidate", candidateHoldout)),
                decision, Timestamp.from(Instant.now()), baselineVersion, accepted ? Timestamp.from(Instant.now()) : null);
        });
        return Map.of("version", version, "baselineVersion", baselineVersion, "gateDecision", decision,
            "validation", candidateValidation, "holdout", candidateHoldout == null ? Map.of() : candidateHoldout);
    }

    public String rollback() {
        String active = currentVersion();
        List<String> predecessors = db.query("SELECT predecessor FROM evolution_versions WHERE version=?", (rs, row) -> rs.getString(1), active);
        if (predecessors.isEmpty() || predecessors.get(0) == null) throw new IllegalStateException("no previous active version");
        String predecessor = predecessors.get(0);
        transactions.executeWithoutResult(tx -> {
            db.update("UPDATE evolution_versions SET status='ROLLED_BACK' WHERE version=? AND status='ACTIVE'", active);
            db.update("UPDATE evolution_versions SET status='ACTIVE',activated_at=? WHERE version=? AND status='PREVIOUS'",
                Timestamp.from(Instant.now()), predecessor);
        });
        return predecessor;
    }

    public List<Map<String, Object>> versions() {
        ensureBaseline();
        return db.query("SELECT version,status,predecessor,baseline_version,gate_decision,candidate_json,validation_json,holdout_json FROM evolution_versions ORDER BY created_at DESC",
            (rs, row) -> Map.of("version", rs.getString(1), "status", rs.getString(2),
                "predecessor", rs.getString(3) == null ? "" : rs.getString(3),
                "baselineVersion", rs.getString(4) == null ? "" : rs.getString(4),
                "gateDecision", rs.getString(5), "candidate", decode(rs.getString(6)),
                "validation", decode(rs.getString(7)), "holdout", decode(rs.getString(8))));
    }

    private void ensureBaseline() {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM evolution_versions", Integer.class);
        if (count != null && count > 0) return;
        try {
            db.update("INSERT INTO evolution_versions(id,version,status,candidate_json,validation_json,holdout_json,gate_decision,created_at,activated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "mock-v1", "ACTIVE", encode(Map.of("maxFacts", 2,
                    "prompt", "Extract up to two concise source-grounded facts per analysis.")),
                encode(benchmark.evaluate("VALIDATION", 2)), encode(benchmark.evaluate("HOLDOUT", 2)),
                "BASELINE", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ignored) { /* another API node created the baseline */ }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private JsonNode decode(String value) {
        try { return json.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
