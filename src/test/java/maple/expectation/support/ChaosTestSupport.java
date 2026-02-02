package maple.expectation.support;

import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

/**
 * Chaos/Nightmare 테스트 베이스 클래스 (Toxiproxy + Spring Context 통합)
 *
 * <p>Issue #303: Spring ApplicationContext 캐싱 최적화
 * <ul>
 *   <li>AbstractContainerBaseTest의 Testcontainers(MySQL + Redis + Toxiproxy) 상속</li>
 *   <li>@SpringBootTest + MockBeans를 한 곳에 통합하여 Context 캐시 키 일관성 확보</li>
 *   <li>모든 Chaos/Nightmare 테스트가 이 클래스를 상속하면 단일 Context 공유</li>
 * </ul>
 *
 * <h4>Context 캐시 키 구성 요소</h4>
 * <ul>
 *   <li>@SpringBootTest (기본 설정)</li>
 *   <li>@ActiveProfiles({"test", "container"}) — AbstractContainerBaseTest에서 상속</li>
 *   <li>@TestPropertySource(nexon.api.key=dummy-test-key)</li>
 *   <li>@MockitoBean: RealNexonApiClient, DiscordAlertService</li>
 * </ul>
 *
 * <h4>사용 대상</h4>
 * <ul>
 *   <li>Chaos 테스트: Redis 장애 주입이 필요한 테스트</li>
 *   <li>Nightmare 테스트: 극한 조건 검증 테스트</li>
 *   <li>Toxiproxy가 필요 없는 통합 테스트는 {@link IntegrationTestSupport} 사용</li>
 * </ul>
 *
 * @see AbstractContainerBaseTest Testcontainers 정의 (MySQL + Redis + Toxiproxy)
 * @see IntegrationTestSupport 경량 통합 테스트 (Toxiproxy 불필요 시)
 */
@SpringBootTest
@TestPropertySource(properties = {"nexon.api.key=dummy-test-key"})
@Tag("chaos")
public abstract class ChaosTestSupport extends AbstractContainerBaseTest {

    // -------------------------------------------------------------------------
    // [Infrastructure] JdbcTemplate for test data cleanup
    // -------------------------------------------------------------------------
    @Autowired protected JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // [Mock 구역] 외부 연동만 Mock (ApplicationContext 캐싱 일관성)
    // -------------------------------------------------------------------------
    @MockitoBean protected RealNexonApiClient nexonApiClient;
    @MockitoBean protected DiscordAlertService discordAlertService;

    /**
     * 테스트 간 데이터 격리를 위한 DB + Redis + Toxiproxy 초기화
     *
     * <p>실행 순서:
     * <ol>
     *   <li>Toxiproxy 상태 초기화 (부모 클래스)</li>
     *   <li>DB: INFORMATION_SCHEMA 기반 전체 테이블 TRUNCATE</li>
     * </ol>
     */
    @Override
    @AfterEach
    protected void globalProxyReset() {
        // 1. Toxiproxy + Redis cleanup (inherited)
        super.globalProxyReset();

        // 2. DB cleanup via JdbcTemplate - TRUNCATE all tables
        try {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
            List<String> tables = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                    String.class
            );
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE " + table);
            }
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (Exception e) {
            System.err.println("[ChaosTestSupport] DB cleanup failed (best-effort): " + e.getMessage());
        }
    }
}
