package io.github.hwwwei.video.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hwwwei.video.runtime.MockAgents;
import io.github.hwwwei.video.runtime.MockAgents.Fact;
import io.github.hwwwei.video.runtime.MockAgents.Segment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** Provider-only evaluation. It does not exercise Kafka, FFmpeg, storage or the Run DAG. */
@Service
public class BenchmarkService {
    public record Case(String id, String split, String language, List<String> segments, List<String> keyFacts) {}
    public record Metrics(int cases, double factConsistency, double keyInformationRecall,
            double summaryCompleteness, double taskSuccessRate, double averageMillis,
            double averageCalls, double averageTokens) {}
    private final List<Case> cases;

    public BenchmarkService() {
        try (var stream = getClass().getResourceAsStream("/benchmark/long_transcripts.json")) {
            if (stream == null) throw new IllegalStateException("benchmark fixture missing");
            cases = List.copyOf(new ObjectMapper().readValue(stream, new TypeReference<List<Case>>() {}));
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public List<Case> cases() { return cases; }

    public Metrics evaluate(String split, int maxFacts) {
        if (maxFacts <= 0) throw new IllegalArgumentException("maxFacts must be positive");
        List<Case> selection = cases.stream().filter(c -> c.split().equalsIgnoreCase(split)).toList();
        if (selection.isEmpty()) throw new IllegalArgumentException("unknown benchmark split");
        double consistency = 0, recall = 0, completeness = 0, success = 0, millis = 0, calls = 0, tokens = 0;
        for (Case fixture : selection) {
            long started = System.nanoTime();
            List<Segment> segments = IntStream.range(0, fixture.segments().size())
                .mapToObj(i -> new Segment(fixture.id() + "-s" + (i + 1), i * 60000L, (i + 1) * 60000L, fixture.segments().get(i)))
                .toList();
            List<Fact> facts = MockAgents.analyze(segments, maxFacts);
            String summary = MockAgents.summarize(facts);
            List<MockAgents.Review> reviews = MockAgents.review(MockAgents.anonymize(facts), Math.min(maxFacts, segments.size()));
            consistency += facts.isEmpty() ? 0 : facts.stream().filter(f -> f.evidence().contains(f.claim())).count() / (double) facts.size();
            recall += fixture.keyFacts().stream().filter(key -> summary.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT))).count()
                / (double) fixture.keyFacts().size();
            completeness += facts.stream().map(Fact::segmentId).distinct().count() / (double) segments.size();
            success += !summary.isEmpty() && reviews.stream().allMatch(r -> r.decision().equals("ACCEPT")) ? 1 : 0;
            millis += Math.max(0, (System.nanoTime() - started) / 1_000_000.0);
            calls += facts.size() + 2;
            tokens += summary.length() / 4.0;
        }
        int n = selection.size();
        return new Metrics(n, consistency / n, recall / n, completeness / n, success / n, millis / n, calls / n, tokens / n);
    }
}
