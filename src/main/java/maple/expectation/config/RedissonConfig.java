package maple.expectation.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.misc.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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

    // 🔥 [Issue #77] Testcontainers NAT 매핑 정보
    @Value("${redis.nat-mapping:}")
    private String natMapping;

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

            var sentinelConfig = config.useSentinelServers()
                  .setMasterName(masterName)
                  .addSentinelAddress(addresses)
                  .setCheckSentinelsList(false) // 로컬 개발 시 필요

                  // 🔥 [Issue #77] Failover 시 즉시 Topology 업데이트
                  .setScanInterval(1000)           // 1초마다 Master/Slave 구성 스캔

                  // 🔥 [Issue #77] READONLY 에러 방지: 모든 읽기를 Master에서 수행
                  .setReadMode(ReadMode.MASTER)    // Slave 읽기 비활성화

                  // 🔥 [Issue #77] DNS 안정성 강화
                  .setDnsMonitoringInterval(5000)  // 5초마다 DNS 갱신

                  // 🔥 [Issue #77] 재연결 및 타임아웃 설정
                  .setRetryAttempts(3)             // 재시도 3회
                  .setRetryInterval(1500)          // 재시도 간격 1.5초
                  .setTimeout(3000)                // 명령 타임아웃 3초
                  .setConnectTimeout(10000)        // 연결 타임아웃 10초

                  // 🔥 [Issue #77] Connection Pool 설정
                  .setMasterConnectionPoolSize(64)     // Master 연결 풀 크기
                  .setMasterConnectionMinimumIdleSize(24) // 최소 유휴 연결
                  .setSlaveConnectionPoolSize(64)      // Slave 연결 풀 크기
                  .setSlaveConnectionMinimumIdleSize(24)
                  .setFailedSlaveCheckInterval(3000);  // 실패한 Slave 재확인 간격 3초

            // 🔥 [Issue #77] Testcontainers NAT 매핑: Docker 네트워크 내부 주소 → 외부 매핑 주소
            if (!natMapping.isEmpty()) {
                Map<String, String> natMap = parseNatMapping(natMapping);
                sentinelConfig.setNatMapper(uri -> {
                    String key = uri.getHost() + ":" + uri.getPort();
                    String mapped = natMap.get(key);
                    if (mapped != null) {
                        String[] parts = mapped.split(":");
                        return new RedisURI(uri.getScheme(), parts[0], Integer.parseInt(parts[1]));
                    }
                    return uri;
                });
            }
        } else {
            // Fallback: Single Server (로컬 개발, 테스트용)
            config.useSingleServer()
                  .setAddress(REDISSON_HOST_PREFIX + host + ":" + port)

                  // Single Server 모드에도 기본 재연결 설정 추가
                  .setRetryAttempts(3)
                  .setRetryInterval(1500)
                  .setTimeout(3000)
                  .setConnectTimeout(10000)
                  .setConnectionPoolSize(64)
                  .setConnectionMinimumIdleSize(24);
        }

        return Redisson.create(config);
    }

    /**
     * NAT 매핑 문자열 파싱
     * 형식: "redis-master:6379=localhost:32768,redis-slave:6379=localhost:32769"
     */
    private Map<String, String> parseNatMapping(String natMappingStr) {
        Map<String, String> map = new HashMap<>();
        String[] mappings = natMappingStr.split(",");
        for (String mapping : mappings) {
            String[] parts = mapping.trim().split("=");
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
    }
}