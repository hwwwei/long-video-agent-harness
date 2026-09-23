package io.github.hwwwei.video.evolution;

import io.github.hwwwei.video.benchmark.BenchmarkService.Metrics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionGateTest {
    @Test void requiresAllQualityDimensionsNotToRegressAndOneToImprove() {
        Metrics base = new Metrics(8, .9, .6, .6, .8, 10, 4, 100);
        Metrics better = new Metrics(8, .9, .8, .8, .8, 11, 5, 110);
        Metrics worse = new Metrics(8, .8, .9, .9, .9, 9, 3, 90);
        assertTrue(EvolutionService.passes(base, better));
        assertFalse(EvolutionService.passes(base, worse));
        assertFalse(EvolutionService.passes(base, base));
    }
}
