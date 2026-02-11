package maple.expectation.infra;

import static org.assertj.core.api.Assertions.assertThat;

import maple.expectation.support.InfraIntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Sample Integration Test - Testcontainers Singleton 패턴 검증
 *
 * <p>이 테스트는 Testcontainers Singleton 패턴이 올바르게 작동하는지 검증합니다. SharedContainers에서 관리하는 MySQL, Redis
 * 컨테이너가 정상적으로 시작되고 동적 프로퍼티 주입이 잘 되는지 확인합니다.
 */
@ContextConfiguration
@ActiveProfiles("integrationTest")
@Tag("integration")
class SampleIntegrationTest extends InfraIntegrationTestSupport {

  static {
    // Start shared containers before Spring context loads
    maple.expectation.support.SharedContainers.class.getClass();
  }

  @Test
  void sharedContainers_should_be_initialized() {
    // SharedContainers static initializer가 실행되어 컨테이너가 시작되었는지 확인
    assertThat(maple.expectation.support.SharedContainers.MYSQL.isRunning()).isTrue();
    assertThat(maple.expectation.support.SharedContainers.REDIS.isRunning()).isTrue();
  }

  @Test
  void mysql_container_should_have_valid_connection_info() {
    // MySQL 컨테이너의 연결 정보가 유효한지 확인
    String jdbcUrl = maple.expectation.support.SharedContainers.MYSQL.getJdbcUrl();
    String username = maple.expectation.support.SharedContainers.MYSQL.getUsername();
    String password = maple.expectation.support.SharedContainers.MYSQL.getPassword();

    assertThat(jdbcUrl).isNotEmpty();
    assertThat(jdbcUrl).contains("mysql");
    assertThat(username).isEqualTo("test");
    assertThat(password).isEqualTo("test");
  }

  @Test
  void redis_container_should_be_accessible() {
    // Redis 컨테이너가 접근 가능한지 확인
    assertThat(maple.expectation.support.SharedContainers.REDIS.isRunning()).isTrue();
    assertThat(maple.expectation.support.SharedContainers.REDIS.getMappedPort(6379)).isPositive();
  }
}
