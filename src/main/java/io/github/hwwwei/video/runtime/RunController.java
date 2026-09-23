package io.github.hwwwei.video.runtime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {
    public record BudgetInput(@Positive int maxCalls, @Positive int maxTokens, @Positive long maxMillis) {
        RunService.Budget toBudget() { return new RunService.Budget(maxCalls, maxTokens, maxMillis); }
    }
    public record CreateRun(@NotBlank String videoId, @Valid BudgetInput budget) {}
    private final RunService runs;

    public RunController(RunService runs) { this.runs = runs; }

    @GetMapping
    public List<Map<String, Object>> list() { return runs.recent(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateRun request) {
        try {
            RunService.Budget budget = request.budget() == null ? new RunService.Budget(100, 20000, 120000) : request.budget().toBudget();
            return Map.of("runId", runs.create(request.videoId(), budget), "status", "QUEUED");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        try { return runs.view(id); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage()); }
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        get(id);
        if (!runs.cancel(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "run cannot be cancelled in its current state");
        return Map.of("runId", id, "status", "CANCELLED", "cooperative", true);
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id, @Valid @RequestBody(required = false) BudgetInput increase) {
        get(id);
        if (!runs.resume(id, increase == null ? null : increase.toBudget()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "run is not failed or remaining budget is exhausted");
        return Map.of("runId", id, "status", "QUEUED");
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        get(id);
        SseEmitter emitter = new SseEmitter(65000L);
        CompletableFuture.runAsync(() -> {
            long cursor = after;
            try {
                for (int cycle = 0; cycle < 60; cycle++) {
                    for (Map<String, Object> event : runs.events(id, cursor)) {
                        cursor = ((Number) event.get("id")).longValue();
                        emitter.send(SseEmitter.event().id(Long.toString(cursor)).name("trace").data(event));
                    }
                    String state = (String) runs.view(id).get("status");
                    if (state.equals("COMPLETED") || state.equals("FAILED") || state.equals("CANCELLED")) break;
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                emitter.completeWithError(error);
            } catch (RuntimeException error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }
}
