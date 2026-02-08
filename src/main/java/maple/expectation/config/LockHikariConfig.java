package maple.expectation.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL Named Lock 전용 HikariCP 설정
 *
 * <p>[목적] - Redis 장애 시 MySQL Named Lock을 사용할 때 메인 커넥션 풀이 고갈되는 것을 방지 - 락 전용 커넥션 풀을 별도로 운영하여 애플리케이션
 * 안정성 확보
 *
 * <p>[설계 원칙] - Fixed Pool Size: Min과 Max를 동일하게 설정하여 연결 비용(Handshake) 제거 - Size 30: RPS 235 트래픽이
 * Redis 장애로 넘어왔을 때 병목 없이 처리하기 위한 최소 용량 (Little's Law: 235 req/s * 0.1s latency + buffer ≈ 30
 * connections) - Fail-fast: 락 획득 타임아웃을 짧게 가져가서 스레드 고갈 방지
 *
 * <p>[P0-6 Fix] Circular Dependency Deadlock: MicrometerMetricsTrackerFactory 제거로 Spring Boot 초기화 시
 * 데드락 방지 - 원인: MeterRegistry → HikariDataSource → MicrometerMetrics → MeterRegistry 순환 참조 - 해결:
 * Lock Pool은 Micrometer 메트릭 비활성화 (주요 DataSource만 메트릭 수집)
 */
@Slf4j
@Configuration
@Profile({"!test", "container"})
public class LockHikariConfig {

  @Value("${spring.datasource.url}")
  private String jdbcUrl;

  @Value("${spring.datasource.username}")
  private String username;

  @Value("${spring.datasource.password}")
  private String password;

  // Issue #284 DoD: Pool Size 외부화 (기본 40, prod에서 150으로 오버라이드)
  // AI SRE 제안 (INC-29506518): Lock Pool 병목 방지를 위해 최댓값 증가
  @Value("${lock.datasource.pool-size:40}")
  private int poolSize;

  @Bean(name = "lockDataSource")
  public DataSource lockDataSource() {
    HikariConfig config = new HikariConfig();

    // 기본 연결 정보
    config.setJdbcUrl(jdbcUrl);
    config.setUsername(username);
    config.setPassword(password);
    config.setDriverClassName("com.mysql.cj.jdbc.Driver");

    // [핵심 수정 1] Pool Size 증설 (10 -> 30) 및 고정 (Fixed Pool)
    // Redis가 죽으면 트래픽이 몰리므로 10개로는 부족함.
    // MinIdle = MaxPoolSize로 설정하여 불필요한 연결/해제 비용 제거.
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(poolSize);

    // [핵심 수정 2] Fail-fast 전략 유지
    config.setConnectionTimeout(5000); // 5초 안에 연결 못 얻으면 에러 (스레드 보호)
    config.setIdleTimeout(300000);
    config.setMaxLifetime(600000);
    config.setPoolName("MySQLLockPool");

    // 검증 설정 (JDBC4 isValid 사용으로 쿼리 비용 절감)
    config.setValidationTimeout(3000);

    // P0-6 Fix: Micrometer 메트릭 비활성화 (순환 참조 데드락 방지)
    // 주요 HikariPool 메트릭은 기본 DataSource에서 충분히 수집 가능

    log.info(
        "[Lock Pool] Initialized dedicated MySQL lock connection pool (Fixed Size: {}, Metrics: disabled)",
        poolSize);

    return new HikariDataSource(config);
  }

  @Bean(name = "lockJdbcTemplate")
  public JdbcTemplate lockJdbcTemplate() {
    return new JdbcTemplate(lockDataSource());
  }
}
