package io.github.hwwwei.video.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hwwwei.video.runtime.MockAgents.Fact;
import io.github.hwwwei.video.runtime.MockAgents.Segment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** OpenAI-compatible chat completions are experimental; Mock is the default. */
@Service
public class ContentProvider {
    private final String mode;
    private final String url;
    private final String model;
    private final String key;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ContentProvider(@Value("${harness.provider:mock}") String mode,
            @Value("${harness.model.url:}") String url,
            @Value("${harness.model.name:}") String model,
            @Value("${harness.model.key:}") String key,
            ObjectMapper json) {
        this.mode = mode; this.url = url; this.model = model; this.key = key; this.json = json;
    }

    public List<Fact> analyze(Segment segment, int maxFacts, String versionPrompt) {
        if (mode.equals("mock")) return MockAgents.analyze(List.of(segment), maxFacts);
        if (!mode.equals("openai")) throw new IllegalArgumentException("unsupported provider: " + mode);
        JsonNode result = complete("Return one JSON object with a facts array. Each item has claim and evidence. "
            + "Evidence must be an exact substring of the transcript; do not invent facts. " + versionPrompt,
            "Transcript: " + segment.text());
        JsonNode facts = result.path("facts");
        if (!facts.isArray()) throw new IllegalStateException("provider facts must be an array");
        return java.util.stream.StreamSupport.stream(facts.spliterator(), false).limit(maxFacts).map(item -> {
            String evidence = item.path("evidence").asText("").trim();
            String claim = item.path("claim").asText("").trim();
            if (claim.isBlank() || evidence.isBlank() || !segment.text().contains(evidence))
                throw new IllegalStateException("provider fact failed evidence validation");
            return new Fact(segment.id(), segment.startMs(), segment.endMs(), claim, evidence);
        }).toList();
    }

    public String summarize(List<Fact> facts, String versionPrompt) {
        if (mode.equals("mock")) return MockAgents.summarize(facts);
        JsonNode result = complete("Return one JSON object with a summary string. Use only the supplied evidence-backed facts. " + versionPrompt,
            "Facts: " + encode(facts));
        String summary = result.path("summary").asText("").trim();
        if (summary.isBlank()) throw new IllegalStateException("provider summary missing");
        return summary;
    }

    private JsonNode complete(String system, String user) {
        if (url.isBlank() || model.isBlank() || key.isBlank()) throw new IllegalStateException("OpenAI-compatible provider is not configured");
        String body = encode(Map.of("model", model, "temperature", 0, "response_format", Map.of("type", "json_object"),
            "messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user))));
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IllegalStateException("provider HTTP " + response.statusCode());
                JsonNode envelope = json.readTree(response.body());
                String content = envelope.path("choices").path(0).path("message").path("content").asText("");
                if (content.isBlank()) throw new IllegalStateException("provider returned empty content");
                return json.readTree(content);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider interrupted", interrupted);
            } catch (Exception error) {
                last = new IllegalStateException("provider request failed", error);
            }
        }
        throw last;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
