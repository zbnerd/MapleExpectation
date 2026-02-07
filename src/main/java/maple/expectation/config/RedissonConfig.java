package maple.expectation.config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.redisson.Redisson;
import org.redisson.api.NatMapper;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.misc.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    if (isSentinelMode()) {
      configureSentinel(config);
    } else {
      configureSingleServer(config);
    }

    return Redisson.create(config);
  }

  private boolean isSentinelMode() {
    return !masterName.isEmpty() && !sentinelNodes.isEmpty();
  }

  private void configureSentinel(Config config) {
    String[] nodes = sentinelNodes.split(",");
    String[] addresses =
        Arrays.stream(nodes).map(node -> REDISSON_HOST_PREFIX + node.trim()).toArray(String[]::new);

    var sentinelConfig =
        config
            .useSentinelServers()
            .setMasterName(masterName)
            .addSentinelAddress(addresses)
            .setCheckSentinelsList(false)
            .setScanInterval(1000)
            .setReadMode(ReadMode.MASTER)
            .setDnsMonitoringInterval(5000)
            .setRetryAttempts(3)
            .setRetryInterval(1500)
            .setTimeout(8000) // Issue #225: 3s → 8s (Timeout Hierarchy 정렬)
            .setConnectTimeout(5000) // Issue #225: 10s → 5s (빠른 연결 실패 감지)
            .setMasterConnectionPoolSize(64)
            .setMasterConnectionMinimumIdleSize(24);

    // 🚀 [해결] NAT 매핑 및 Docker 내부 IP 자동 우회 로직
    sentinelConfig.setNatMapper(createNatMapper());
  }

  private NatMapper createNatMapper() {
    Map<String, String> natMap = parseNatMapping(natMapping);

    return uri -> {
      String currentHost = uri.getHost();
      int currentPort = uri.getPort();
      String key = currentHost + ":" + currentPort;

      // 1. 명시적 매핑 정보가 있는 경우 (Testcontainers 등)
      if (natMap.containsKey(key)) {
        return mapToLocalhost(uri, natMap.get(key));
      }

      // 2. 호스트명(redis-master)으로 들어온 경우
      if ("redis-master".equals(currentHost)) {
        return resolveFromMapOrFallback(uri, natMap, "redis-master:6379");
      }

      // 3. 🚨 [중요] Docker 내부 IP(172.x.x.x)인 경우 자동 localhost 매핑
      if (currentHost.startsWith("172.")) {
        return new RedisURI(uri.getScheme(), "127.0.0.1", currentPort);
      }

      return uri;
    };
  }

  private RedisURI mapToLocalhost(RedisURI uri, String mappedValue) {
    String[] parts = mappedValue.split(":");
    return new RedisURI(uri.getScheme(), "127.0.0.1", Integer.parseInt(parts[1]));
  }

  private RedisURI resolveFromMapOrFallback(RedisURI uri, Map<String, String> natMap, String key) {
    String mapped = natMap.get(key);
    if (mapped != null) return mapToLocalhost(uri, mapped);
    return new RedisURI(uri.getScheme(), "127.0.0.1", uri.getPort());
  }

  private void configureSingleServer(Config config) {
    config
        .useSingleServer()
        .setAddress(REDISSON_HOST_PREFIX + host + ":" + port)
        .setRetryAttempts(3)
        .setRetryInterval(1500)
        .setTimeout(8000) // Issue #225: 3s → 8s (Timeout Hierarchy 정렬)
        .setConnectTimeout(5000) // Issue #225: 10s → 5s (빠른 연결 실패 감지)
        .setConnectionPoolSize(64)
        .setConnectionMinimumIdleSize(24);
  }

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
