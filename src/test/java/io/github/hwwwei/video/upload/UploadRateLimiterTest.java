package io.github.hwwwei.video.upload;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadRateLimiterTest {
    @Test void tokenBucketRefillsWithoutExceedingBurst() {
        assertEquals(5.0, UploadRateLimiter.refill(0, 500, 10, 5), .0001);
        assertEquals(5.0, UploadRateLimiter.refill(4, 500, 10, 5), .0001);
        assertEquals(2.0, UploadRateLimiter.refill(2, -100, 10, 5), .0001);
    }
}
