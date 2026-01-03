package maple.expectation.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.sentinel.master:}")
    private String masterName;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    private static final String REDISSON_HOST_PREFIX = "redis://";

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // Sentinel 모드 우선 사용, 설정이 없으면 Single Server로 Fallback
        if (!masterName.isEmpty() && !sentinelNodes.isEmpty()) {
            String[] nodes = sentinelNodes.split(",");
            String[] addresses = Arrays.stream(nodes)
                .map(node -> REDISSON_HOST_PREFIX + node.trim())
                .toArray(String[]::new);

            config.useSentinelServers()
                  .setMasterName(masterName)
                  .addSentinelAddress(addresses)
                  .setCheckSentinelsList(false); // 로컬 개발 시 필요
        } else {
            // Fallback: Single Server (로컬 개발, 테스트용)
            config.useSingleServer()
                  .setAddress(REDISSON_HOST_PREFIX + host + ":" + port);
        }

        return Redisson.create(config);
    }
}