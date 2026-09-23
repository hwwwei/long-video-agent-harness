package io.github.hwwwei.video.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hwwwei.video.media.MediaProcessor;
import io.github.hwwwei.video.evolution.EvolutionService;
import io.github.hwwwei.video.tools.ToolRegistry;
import io.github.hwwwei.video.runtime.MockAgents.Fact;
import io.github.hwwwei.video.runtime.MockAgents.Review;
import io.github.hwwwei.video.runtime.MockAgents.Segment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunService {
    public record Budget(int maxCalls, int maxTokens, long maxMillis, int usedCalls, int usedTokens, long usedMillis) {
        public Budget(int maxCalls, int maxTokens, long maxMillis) { this(maxCalls, maxTokens, maxMillis, 0, 0, 0); }
        public Budget {
            if (maxCalls <= 0 || maxTokens <= 0 || maxMillis <= 0) throw new IllegalArgumentException("budget limits must be positive");
        }
        Budget consume(int calls, int tokens, long elapsed) {
            Budget next = spent(calls, tokens, elapsed);
            if (next.usedCalls > maxCalls || next.usedTokens > maxTokens || next.usedMillis > maxMillis)
                throw new BudgetExceededException();
            return next;
        }
        Budget spent(int calls, int tokens, long elapsed) {
            return new Budget(maxCalls, maxTokens, maxMillis, usedCalls + calls, usedTokens + tokens, usedMillis + elapsed);
        }
        public boolean exhausted() { return usedCalls >= maxCalls || usedTokens >= maxTokens || usedMillis >= maxMillis; }
    }
    public static final class BudgetExceededException extends RuntimeException {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final MediaProcessor media;
    private final EvolutionService evolution;
    private final ToolRegistry tools;
    private final ContentProvider provider;

    public RunService(JdbcTemplate db, ObjectMapper json, org.springframework.transaction.PlatformTransactionManager tx,
            MediaProcessor media, EvolutionService evolution, ToolRegistry tools, ContentProvider provider) {
        this.db = db;
        this.json = json;
        this.transactions = new TransactionTemplate(tx);
        this.media = media;
        this.evolution = evolution;
        this.tools = tools;
        this.provider = provider;
    }

    public String create(String videoId, Budget budget) {
        Integer hasTranscript = db.queryForObject("SELECT COUNT(*) FROM transcripts WHERE video_id=?", Integer.class, videoId);
        if (hasTranscript == null || hasTranscript == 0) throw new IllegalArgumentException("Mock mode requires an uploaded transcript");
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        transactions.executeWithoutResult(status -> {
            db.update("INSERT INTO runs(id,video_id,status,budget_json,checkpoint_json,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                id, videoId, "QUEUED", encode(budget), "{}", evolution.currentVersion(), Timestamp.from(now), Timestamp.from(now));
            db.update("INSERT INTO outbox_events(id,event_key,topic,payload_json,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), "run:" + id, "video-runs", encode(Map.of("runId", id)), Timestamp.from(now));
            trace(id, null, "RUN_CREATED", Map.of("videoId", videoId));
        });
        return id;
    }

    /** Atomic compare-and-set in PostgreSQL; a Redis/Kafka redelivery cannot claim an active lease. */
    public boolean claim(String runId, String owner) {
        Instant now = Instant.now();
        int claimed = db.update("UPDATE runs SET status='RUNNING',owner=?,lease_until=?,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING') AND (lease_until IS NULL OR lease_until<?)",
            owner, Timestamp.from(now.plusSeconds(60)), Timestamp.from(now), runId, Timestamp.from(now));
        return claimed == 1;
    }

    public boolean execute(String runId, String owner) {
        if (!claim(runId, owner)) return false;
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lease-heartbeat-" + runId);
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> db.update(
            "UPDATE runs SET lease_until=? WHERE id=? AND status='RUNNING' AND owner=?",
            Timestamp.from(Instant.now().plusSeconds(60)), runId, owner), 15, 15, TimeUnit.SECONDS);
        try {
            String videoId = db.queryForObject("SELECT video_id FROM runs WHERE id=?", String.class, runId);
            String version = db.queryForObject("SELECT version FROM runs WHERE id=?", String.class, runId);
            int maxFacts = evolution.maxFacts(version);
            String versionPrompt = evolution.prompt(version);
            List<Segment> originalSegments = json.convertValue(tools.call("planner", "transcript.read", videoId), new TypeReference<List<Segment>>() {});
            node(runId, owner, "media_parse", 0, () -> media.probe(videoId));
            node(runId, owner, "audio_extract", 0, () -> media.extractAudio(videoId));
            node(runId, owner, "transcript_load", 0, () -> Map.of("mode", "SIMULATED", "segments", originalSegments.size()));
            Long measuredDuration = db.queryForObject("SELECT duration_ms FROM video_assets WHERE id=?", Long.class, videoId);
            long planningDuration = measuredDuration == null ? originalSegments.get(originalSegments.size() - 1).endMs() : measuredDuration;
            List<Segment> segments = json.convertValue(node(runId, owner, "plan", 0,
                () -> Planner.split(originalSegments, planningDuration)), new TypeReference<List<Segment>>() {});
            List<Fact> facts = new ArrayList<>();
            ExecutorService analysisPool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, segments.size())));
            try {
                List<Future<List<Fact>>> results = new ArrayList<>();
                for (int i = 0; i < segments.size(); i++) {
                    Segment segment = segments.get(i);
                    final int position = i;
                    results.add(analysisPool.submit(() -> json.convertValue(node(runId, owner, "analyze:" + segment.id(), position < maxFacts ? 1 : 0,
                        () -> position < maxFacts ? provider.analyze(segment, 1, versionPrompt) : List.of()),
                        new TypeReference<List<Fact>>() {})));
                }
                for (Future<List<Fact>> result : results) facts.addAll(result.get());
            } finally {
                analysisPool.shutdownNow();
            }
            String summary = (String) node(runId, owner, "summarize", 1, () -> provider.summarize(facts, versionPrompt));
            String initialSummary = summary;
            node(runId, owner, "aggregate", 0, () -> Map.of("facts", facts, "summary", initialSummary));
            List<MockAgents.AnonymousCandidate> anonymous = MockAgents.anonymize(facts);
            List<MockAgents.AnonymousCandidate> firstReviewInput = anonymous;
            List<Review> reviews = json.convertValue(node(runId, owner, "critic:0", 1,
                () -> MockAgents.review(firstReviewInput, segments.size())), new TypeReference<List<Review>>() {});
            for (int round = 1; round <= 2 && reviews.stream().anyMatch(r -> r.decision().equals("REWORK")); round++) {
                Map<String, Fact> routing = new LinkedHashMap<>();
                for (int i = 0; i < anonymous.size(); i++) routing.put(anonymous.get(i).reviewId(), facts.get(i));
                for (Review review : reviews) {
                    if (!review.decision().equals("REWORK")) continue;
                    List<Segment> targets;
                    if (review.reviewId().equals("coverage")) {
                        targets = segments.stream().filter(s -> facts.stream().noneMatch(f -> f.segmentId().equals(s.id()))).toList();
                    } else {
                        Fact source = routing.get(review.reviewId());
                        targets = source == null ? List.of() : segments.stream().filter(s -> s.id().equals(source.segmentId())).toList();
                    }
                    for (Segment segment : targets) {
                        final int pass = round;
                        List<Fact> replacement = json.convertValue(node(runId, owner, "rework:" + segment.id() + ":" + pass, 1,
                            () -> provider.analyze(segment, 1, versionPrompt + " Revise to address: " + review.reason())),
                            new TypeReference<List<Fact>>() {});
                        facts.removeIf(f -> f.segmentId().equals(segment.id()));
                        facts.addAll(replacement);
                    }
                }
                final int pass = round;
                summary = (String) node(runId, owner, "summarize:rework:" + pass, 1,
                    () -> provider.summarize(facts, versionPrompt));
                anonymous = MockAgents.anonymize(facts);
                List<MockAgents.AnonymousCandidate> reviewInput = anonymous;
                reviews = json.convertValue(node(runId, owner, "critic:" + pass, 1,
                    () -> MockAgents.review(reviewInput, segments.size())), new TypeReference<List<Review>>() {});
            }
            List<Review> finalReviews = reviews;
            Map<String, Object> report = Map.of("summary", summary, "facts", facts,
                "chapters", segments.stream().map(s -> Map.of("segmentId", s.id(), "startMs", s.startMs(), "endMs", s.endMs())).toList(),
                "critic", finalReviews, "transcriptMode", "SIMULATED", "traceId", runId);
            node(runId, owner, "finalize", 0, () -> report);
            int completed = db.update("UPDATE runs SET status='COMPLETED',report_json=?,owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND owner=?",
                encode(report), Timestamp.from(Instant.now()), runId, owner);
            if (completed == 1) trace(runId, null, "RUN_COMPLETED", Map.of());
        } catch (BudgetExceededException error) {
            fail(runId, owner, "BUDGET_EXHAUSTED");
        } catch (Exception error) {
            fail(runId, owner, error.toString());
        } finally {
            heartbeat.shutdownNow();
        }
        return true;
    }

    private Object node(String runId, String owner, String name, int calls, Supplier<Object> operation) {
        List<String> previous = db.query("SELECT output_json FROM node_executions WHERE run_id=? AND node=? AND status='COMPLETED' ORDER BY attempt DESC",
            (rs, row) -> rs.getString(1), runId, name);
        if (!previous.isEmpty()) return decode(previous.get(0));
        for (int retry = 0; retry < 3; retry++) {
            int attempt = db.queryForObject("SELECT COUNT(*) FROM node_executions WHERE run_id=? AND node=?", Integer.class, runId, name) + 1;
            Budget reserved = transactions.execute(tx -> {
                Budget current = lockedBudget(runId);
                String state = db.queryForObject("SELECT status FROM runs WHERE id=? AND owner=?", String.class, runId, owner);
                if (!"RUNNING".equals(state)) throw new IllegalStateException("run cancelled or lease lost");
                Budget next = current.consume(calls, 0, 0);
                db.update("INSERT INTO node_executions(run_id,node,attempt,status) VALUES (?,?,?,'RUNNING')", runId, name, attempt);
                db.update("UPDATE runs SET budget_json=? WHERE id=? AND owner=? AND status='RUNNING'", encode(next), runId, owner);
                trace(runId, name, "NODE_STARTED", Map.of("attempt", attempt));
                return next;
            });
            long started = System.nanoTime();
            int tokenCost = 0;
            try {
                long remaining = reserved.maxMillis() - reserved.usedMillis();
                Object output = timed(operation, remaining);
                long elapsed = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                tokenCost = calls == 0 ? 0 : Math.max(1, encode(output).length() / 4);
                int tokens = tokenCost;
                transactions.executeWithoutResult(tx -> {
                    Budget updated = lockedBudget(runId).consume(0, tokens, elapsed);
                    db.update("UPDATE node_executions SET status='COMPLETED',duration_ms=?,output_json=? WHERE run_id=? AND node=? AND attempt=?",
                        elapsed, encode(output), runId, name, attempt);
                    Map<String, Object> checkpoint = new LinkedHashMap<>(json.convertValue(decode(
                        db.queryForObject("SELECT checkpoint_json FROM runs WHERE id=?", String.class, runId)), new TypeReference<Map<String, Object>>() {}));
                    checkpoint.put(name, Map.of("attempt", attempt, "status", "COMPLETED"));
                    int changed = db.update("UPDATE runs SET budget_json=?,checkpoint_json=?,lease_until=?,updated_at=? WHERE id=? AND owner=? AND status='RUNNING'",
                        encode(updated), encode(checkpoint), Timestamp.from(Instant.now().plusSeconds(60)), Timestamp.from(Instant.now()), runId, owner);
                    if (changed != 1) throw new IllegalStateException("run cancelled or lease lost");
                    trace(runId, name, "NODE_COMPLETED", Map.of("attempt", attempt, "durationMs", elapsed, "calls", calls, "tokens", tokens));
                });
                return output;
            } catch (Exception error) {
                long elapsed = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                int failedTokens = tokenCost;
                Budget spent = transactions.execute(tx -> {
                    Budget charged = lockedBudget(runId).spent(0, failedTokens, elapsed);
                    db.update("UPDATE node_executions SET status='FAILED',duration_ms=?,error=? WHERE run_id=? AND node=? AND attempt=?",
                        elapsed, error.toString(), runId, name, attempt);
                    db.update("UPDATE runs SET budget_json=? WHERE id=? AND owner=? AND status='RUNNING'", encode(charged), runId, owner);
                    trace(runId, name, "NODE_FAILED", Map.of("attempt", attempt, "error", error.toString(), "durationMs", elapsed));
                    return charged;
                });
                if (error instanceof BudgetExceededException || spent.exhausted()) throw new BudgetExceededException();
                if (retry == 2) throw new IllegalStateException("node exhausted retries: " + name, error);
                try { Thread.sleep(200L << retry); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
        throw new IllegalStateException("node did not complete");
    }

    private Budget lockedBudget(String runId) {
        String raw = db.queryForObject("SELECT budget_json FROM runs WHERE id=? FOR UPDATE", String.class, runId);
        try { return json.readValue(raw, Budget.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private Object timed(Supplier<Object> operation, long remainingMillis) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "agent-node");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Object> future = executor.submit(operation::get);
            try { return future.get(Math.max(1, remainingMillis), TimeUnit.MILLISECONDS); }
            catch (TimeoutException timeout) { future.cancel(true); throw timeout; }
        } finally { executor.shutdownNow(); }
    }

    public Map<String, Object> view(String runId) {
        List<Map<String, Object>> found = db.query("SELECT video_id,status,budget_json,checkpoint_json,report_json,version FROM runs WHERE id=?", (rs, row) -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", runId); value.put("videoId", rs.getString(1)); value.put("status", rs.getString(2));
            value.put("budget", decode(rs.getString(3))); value.put("checkpoint", decode(rs.getString(4)));
            value.put("report", rs.getString(5) == null ? null : decode(rs.getString(5)));
            value.put("version", rs.getString(6));
            return value;
        }, runId);
        if (found.isEmpty()) throw new IllegalArgumentException("unknown run");
        Map<String, Object> view = found.get(0);
        view.put("nodes", db.query("SELECT node,attempt,status,duration_ms,error FROM node_executions WHERE run_id=? ORDER BY id",
            (rs, row) -> Map.of("node", rs.getString(1), "attempt", rs.getInt(2), "status", rs.getString(3),
                "durationMs", rs.getLong(4), "error", rs.getString(5) == null ? "" : rs.getString(5)), runId));
        view.put("trace", events(runId, 0));
        return view;
    }

    public List<Map<String, Object>> recent() {
        return db.query("SELECT id,video_id,status,version,created_at FROM runs ORDER BY created_at DESC LIMIT 30",
            (rs, row) -> Map.of("id", rs.getString(1), "videoId", rs.getString(2),
                "status", rs.getString(3), "version", rs.getString(4), "createdAt", rs.getTimestamp(5).toInstant().toString()));
    }

    public List<Map<String, Object>> events(String runId, long after) {
        return db.query("SELECT id,node,event_type,payload_json,created_at FROM trace_events WHERE run_id=? AND id>? ORDER BY id",
            (rs, row) -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("id", rs.getLong(1)); event.put("node", rs.getString(2)); event.put("type", rs.getString(3));
                event.put("payload", decode(rs.getString(4))); event.put("at", rs.getTimestamp(5).toInstant().toString());
                return event;
            }, runId, after);
    }

    public boolean cancel(String runId) {
        int updated = db.update("UPDATE runs SET status='CANCELLED',owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING','FAILED')",
            Timestamp.from(Instant.now()), runId);
        if (updated == 1) trace(runId, null, "RUN_CANCELLED", Map.of());
        return updated == 1;
    }

    public boolean resume(String runId, Budget increase) {
        Budget current = budget(runId);
        Budget revised = increase == null ? current : new Budget(current.maxCalls + increase.maxCalls,
            current.maxTokens + increase.maxTokens, current.maxMillis + increase.maxMillis,
            current.usedCalls, current.usedTokens, current.usedMillis);
        if (revised.exhausted()) return false;
        int updated = db.update("UPDATE runs SET status='QUEUED',owner=NULL,lease_until=NULL,budget_json=?,updated_at=? WHERE id=? AND status='FAILED'",
            encode(revised), Timestamp.from(Instant.now()), runId);
        if (updated == 1) {
            db.update("INSERT INTO outbox_events(id,event_key,topic,payload_json,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID().toString(), "resume:" + runId + ":" + UUID.randomUUID(), "video-runs", encode(Map.of("runId", runId)), Timestamp.from(Instant.now()));
            trace(runId, null, "RUN_RESUMED", Map.of());
        }
        return updated == 1;
    }

    private void fail(String runId, String owner, String reason) {
        db.update("UPDATE runs SET status='FAILED',owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND owner=?",
            Timestamp.from(Instant.now()), runId, owner);
        trace(runId, null, "RUN_FAILED", Map.of("reason", reason));
    }

    private Budget budget(String runId) {
        String raw = db.queryForObject("SELECT budget_json FROM runs WHERE id=?", String.class, runId);
        try { return json.readValue(raw, Budget.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private void trace(String runId, String node, String type, Object payload) {
        db.update("INSERT INTO trace_events(run_id,node,event_type,payload_json,created_at) VALUES (?,?,?,?,?)",
            runId, node, type, encode(payload), Timestamp.from(Instant.now()));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private Object decode(String value) {
        try { return json.readValue(value, Object.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
