package maple.expectation;

import lombok.extern.slf4j.Slf4j; // 1. 롬복 어노테이션 임포트
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@Tag("integration")
class EnvironmentIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${discord.webhook-url}")
    private String webhookUrl;

    @Test
    @DisplayName("1. DB 연결이 정상이고 데이터를 읽을 수 있어야 한다")
    void testDbConnection() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);

        log.info("✅ DB Connection Success! Result: {}", result); 
    }

    @Test
    @DisplayName("2. .env에서 로드한 API 키나 설정값이 주입되어야 한다")
    void testEnvLoading() {
        assertThat(webhookUrl).isNotNull();
        assertThat(webhookUrl).isNotEmpty();

        log.info("✅ Env Variable Loaded: {}", webhookUrl);
    }
}