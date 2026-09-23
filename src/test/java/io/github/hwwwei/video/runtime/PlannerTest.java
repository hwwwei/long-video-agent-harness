package io.github.hwwwei.video.runtime;

import io.github.hwwwei.video.runtime.MockAgents.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {
    @Test void longVideoUsesSmallerChunksAndRetainsTimeRanges() {
        String text = "A grounded sentence. ".repeat(200);
        List<Segment> input = List.of(new Segment("original", 0, 120000, text));
        List<Segment> shortVideo = Planner.split(input, 60000);
        List<Segment> longVideo = Planner.split(input, 3600000);
        assertTrue(longVideo.size() > shortVideo.size());
        assertEquals(0, longVideo.get(0).startMs());
        assertEquals(120000, longVideo.get(longVideo.size() - 1).endMs());
        assertTrue(longVideo.stream().allMatch(s -> s.id().startsWith("original-")));
    }
}
