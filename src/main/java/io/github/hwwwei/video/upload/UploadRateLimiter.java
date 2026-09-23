package io.github.hwwwei.video.upload;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Single-tenant Redis token bucket: 20 chunks/s, burst 40, atomic across API replicas. */
@Component
public class UploadRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
        local now = tonumber(ARGV[1])
        local rate = tonumber(ARGV[2])
        local capacity = tonumber(ARGV[3])
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(state[1]) or capacity
        local previous = tonumber(state[2]) or now
        tokens = math.min(capacity, tokens + math.max(0, now - previous) * rate / 1000)
        if tokens < 1 then
          redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
          redis.call('PEXPIRE', KEYS[1], 60000)
          return 0
        end
        redis.call('HSET', KEYS[1], 'tokens', tokens - 1, 'updated', now)
        redis.call('PEXPIRE', KEYS[1], 60000)
        return 1
        """, Long.class);
    private final boolean enabled;
    private final ObjectProvider<StringRedisTemplate> redis;

    public UploadRateLimiter(@Value("${harness.redis.enabled:false}") boolean enabled,
            ObjectProvider<StringRedisTemplate> redis) {
        this.enabled = enabled; this.redis = redis;
    }

    public boolean acquire() {
        if (!enabled) return true;
        StringRedisTemplate client = redis.getIfAvailable();
        if (client == null) throw new IllegalStateException("Redis rate limiter is unavailable");
        Long allowed = client.execute(SCRIPT, List.of("harness:upload:bucket"),
            Long.toString(System.currentTimeMillis()), "20", "40");
        return Long.valueOf(1).equals(allowed);
    }

    static double refill(double tokens, long elapsedMillis, double perSecond, double capacity) {
        return Math.min(capacity, tokens + Math.max(0, elapsedMillis) * perSecond / 1000);
    }
}
