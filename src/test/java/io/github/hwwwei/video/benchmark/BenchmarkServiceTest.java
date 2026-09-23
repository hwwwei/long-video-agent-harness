package io.github.hwwwei.video.benchmark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkServiceTest {
    @Test void fixtureSplitsAndActualMockRuleChangeMetrics() {
        BenchmarkService service = new BenchmarkService();
        assertEquals(20, service.cases().size());
        assertEquals(12, service.cases().stream().filter(c -> c.split().equals("VALIDATION")).count());
        assertEquals(8, service.cases().stream().filter(c -> c.split().equals("HOLDOUT")).count());
        BenchmarkService.Metrics baseline = service.evaluate("VALIDATION", 2);
        BenchmarkService.Metrics improved = service.evaluate("VALIDATION", 3);
        assertTrue(improved.keyInformationRecall() > baseline.keyInformationRecall());
        assertTrue(improved.summaryCompleteness() > baseline.summaryCompleteness());
        assertEquals(1.0, improved.factConsistency());
        assertTrue(improved.averageMillis() >= 0);
    }
}
