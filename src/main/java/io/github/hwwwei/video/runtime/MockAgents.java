package io.github.hwwwei.video.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic, evidence-preserving demo provider. It does not transcribe audio. */
public final class MockAgents {
    private MockAgents() {}

    public record Segment(String id, long startMs, long endMs, String text) {}
    public record Fact(String segmentId, long startMs, long endMs, String claim, String evidence) {}
    public record AnonymousCandidate(String reviewId, String claim, String evidence) {}
    public record Review(String reviewId, String decision, String reason) {}

    public static List<Fact> analyze(List<Segment> segments) {
        return analyze(segments, Integer.MAX_VALUE);
    }

    public static List<Fact> analyze(List<Segment> segments, int maxFacts) {
        List<Fact> facts = new ArrayList<>();
        for (Segment segment : segments) {
            if (facts.size() >= maxFacts) break;
            String text = segment.text().trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) continue;
            String claim = text.length() > 280 ? text.substring(0, 280) : text;
            facts.add(new Fact(segment.id(), segment.startMs(), segment.endMs(), claim, text));
        }
        return facts;
    }

    public static String summarize(List<Fact> facts) {
        return facts.stream().map(Fact::claim).limit(8).reduce((left, right) -> left + " " + right).orElse("");
    }

    public static List<AnonymousCandidate> anonymize(List<Fact> facts) {
        return java.util.stream.IntStream.range(0, facts.size())
            .mapToObj(i -> new AnonymousCandidate("review-" + (i + 1), facts.get(i).claim(), facts.get(i).evidence())).toList();
    }

    public static List<Review> review(List<AnonymousCandidate> anonymous, int expectedCoverage) {
        List<Review> result = new ArrayList<>();
        for (AnonymousCandidate candidate : anonymous) {
            boolean supported = candidate.evidence().toLowerCase(Locale.ROOT)
                .contains(candidate.claim().toLowerCase(Locale.ROOT));
            result.add(new Review(candidate.reviewId(), supported ? "ACCEPT" : "REWORK",
                supported ? "claim appears verbatim in source evidence" : "claim lacks source evidence"));
        }
        if (anonymous.size() < expectedCoverage) result.add(new Review("coverage", "REWORK", "transcript segments are missing"));
        return result;
    }
}
