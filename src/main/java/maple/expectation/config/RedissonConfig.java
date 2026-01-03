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

    // 🔥 [Issue #77] Testcontainers NAT 매핑 정보 (테스트 시에만 주입됨)
    @Value("${redis.nat-mapping:}")
    private String natMapping;

    private static final String REDISSON_HOST_PREFIX = "redis://";

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 1. Sentinel 모드 설정
        if (!masterName.isEmpty() && !sentinelNodes.isEmpty()) {
            String[] nodes = sentinelNodes.split(",");
            String[] addresses = Arrays.stream(nodes)
                    .map(node -> REDISSON_HOST_PREFIX + node.trim())
                    .toArray(String[]::new);

            var sentinelConfig = config.useSentinelServers()
                    .setMasterName(masterName)
                    .addSentinelAddress(addresses)
                    .setCheckSentinelsList(false)    // 테스트 환경 안정성
                    .setScanInterval(1000)          // 1초마다 마스터 교체 감지
                    .setReadMode(ReadMode.MASTER)   // READONLY 에러 방지
                    .setDnsMonitoringInterval(5000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500)
                    .setTimeout(3000)
                    .setConnectTimeout(10000)
                    .setMasterConnectionPoolSize(64)
                    .setMasterConnectionMinimumIdleSize(24);

            // 🚀 [핵심 수정] 강력한 NAT 매핑 로직 적용
            if (!natMapping.isEmpty()) {
                Map<String, String> natMap = parseNatMapping(natMapping);

                sentinelConfig.setNatMapper(uri -> {
                    String currentHost = uri.getHost();
                    int currentPort = uri.getPort();
                    String key = currentHost + ":" + currentPort;

                    // CASE 1: 직접 매핑 정보가 있는 경우 (예: "redis-master:6379")
                    if (natMap.containsKey(key)) {
                        String mappedValue = natMap.get(key);
                        String[] parts = mappedValue.split(":");
                        return new RedisURI(uri.getScheme(), "127.0.0.1", Integer.parseInt(parts[1]));
                    }

                    // CASE 2: 호스트명은 맞는데 포트가 명시되지 않았거나 IP로 들어온 경우 우회 로직
                    // UnknownHostException (redis-master) 방지
                    if (currentHost.equals("redis-master")) {
                        String masterEntry = natMap.get("redis-master:6379");
                        if (masterEntry != null) {
                            return new RedisURI(uri.getScheme(), "127.0.0.1", Integer.parseInt(masterEntry.split(":")[1]));
                        }
                    }

                    // CASE 3: 172.x.x.x (Docker 내부 IP) 대역인 경우 (ConnectTimeout 방지)
                    if (currentHost.startsWith("172.")) {
                        // 기본 Redis 포트(6379)라면 마스터 매핑 포트 사용 권장
                        String masterEntry = natMap.get("redis-master:6379");
                        int targetPort = (masterEntry != null && currentPort == 6379)
                                ? Integer.parseInt(masterEntry.split(":")[1])
                                : currentPort;

                        return new RedisURI(uri.getScheme(), "127.0.0.1", targetPort);
                    }

                    return uri;
                });
            }
        } else {
            // 2. Single Server 모드 (로컬 개발용)
            config.useSingleServer()
                    .setAddress(REDISSON_HOST_PREFIX + host + ":" + port)
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
     * 입력 예: "redis-master:6379=127.0.0.1:32768,redis-slave:6379=127.0.0.1:32769"
     */
    private Map<String, String> parseNatMapping(String natMappingStr) {
        Map<String, String> map = new HashMap<>();
        if (natMappingStr == null || natMappingStr.isEmpty()) return map;

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