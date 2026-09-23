package io.github.hwwwei.video;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:harness_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.kafka.bootstrap-servers=localhost:1",
    "harness.kafka.enabled=false",
    "harness.redis.enabled=false",
    "harness.media.enabled=false"
})
class ApplicationContextTest {
    @Test void startsWithMigratedDatabase() {}
}
