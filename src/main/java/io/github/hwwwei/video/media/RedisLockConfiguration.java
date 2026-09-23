package io.github.hwwwei.video.media;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisLockConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "harness.redis.enabled", havingValue = "true")
    RedissonClient redisson(@Value("${harness.redis.url:redis://localhost:6379}") String url) {
        Config config = new Config();
        config.useSingleServer().setAddress(url);
        return Redisson.create(config);
    }
}
