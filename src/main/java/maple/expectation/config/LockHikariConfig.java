package maple.expectation.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * MySQL Named Lock 전용 HikariCP 설정
 *
 * [목적]
 * - Redis 장애 시 MySQL Named Lock을 사용할 때 메인 커넥션 풀이 고갈되는 것을 방지
 * - 락 전용 작은 커넥션 풀을 별도로 운영하여 애플리케이션 안정성 확보
 *
 * [설계 원칙]
 * - 작은 풀 크기 (10개): 락 작업은 가볍고 빠르므로 많은 커넥션 불필요
 * - 짧은 타임아웃: 락 획득/해제는 빨라야 하므로 짧은 타임아웃 설정
 * - 메인 풀과 분리: 메인 비즈니스 로직과 락 로직을 격리
 */
@Slf4j
@Configuration
@Profile("!test")
public class LockHikariConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean(name = "lockDataSource")
    public DataSource lockDataSource() {
        HikariConfig config = new HikariConfig();

        // 기본 연결 정보
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Lock 전용 풀 설정
        // [수정 1] 주석 의도(작은 풀)에 맞춰 50 -> 10으로 축소
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setPoolName("MySQLLockPool");

        // 검증 설정
        // [수정 2] setConnectionTestQuery("SELECT 1") 제거
        // MySQL Connector/J는 JDBC4 표준 isValid()를 지원하므로 제거하는 것이 성능상 유리합니다.
        config.setValidationTimeout(3000);

        log.info("✅ [Lock Pool] Initialized dedicated MySQL lock connection pool (max: 10)");

        return new HikariDataSource(config);
    }

    @Bean(name = "lockJdbcTemplate")
    public JdbcTemplate lockJdbcTemplate() {
        return new JdbcTemplate(lockDataSource());
    }
}