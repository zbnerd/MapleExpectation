package maple.expectation.support;

import maple.expectation.external.impl.RealNexonApiClient;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.repository.v2.RedisBufferRepository;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

/**
 * 경량 통합 테스트 베이스 클래스 (Issue #207 최적화)
 *
 * <p>SimpleRedisContainerBase를 상속하여 MySQL + Redis(단일 노드)만 사용.
 * 컨테이너 시작 시간: ~3초 (기존 7개 컨테이너 대비 90% 단축)
 *
 * <h4>CLAUDE.md Section 25 준수:</h4>
 * <ul>
 *   <li>경량 테스트 우선: Sentinel/Toxiproxy 불필요 시 이 클래스 상속</li>
 *   <li>장애 주입 필요 시: AbstractContainerBaseTest 상속</li>
 *   <li>Sentinel HA 필요 시: SentinelContainerBase 상속</li>
 * </ul>
 *
 * <h4>포함된 Mock Bean (ApplicationContext 캐싱용):</h4>
 * <ul>
 *   <li>RealNexonApiClient - 외부 API 호출 Mock</li>
 *   <li>DiscordAlertService - 알림 서비스 Mock</li>
 * </ul>
 *
 * <h4>포함된 Real Bean:</h4>
 * <ul>
 *   <li>GameCharacterFacade, GameCharacterRepository</li>
 *   <li>CharacterEquipmentRepository</li>
 *   <li>LockStrategy, RedisBufferRepository</li>
 * </ul>
 *
 * @see SimpleRedisContainerBase 컨테이너 정의
 * @see AbstractContainerBaseTest Toxiproxy 필요 시 (P0 Chaos 테스트)
 * @see SentinelContainerBase Sentinel HA 필요 시
 */
@SpringBootTest
@ActiveProfiles({"test", "container"})
@TestPropertySource(properties = {
        "nexon.api.key=dummy-test-key"
})
@Tag("integration")
public abstract class IntegrationTestSupport extends SimpleRedisContainerBase {

    // -------------------------------------------------------------------------
    // [Infrastructure] JdbcTemplate for test data cleanup
    // -------------------------------------------------------------------------
    @Autowired protected JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // [Mock 구역] 외부 연동 및 알림만 Mock으로 유지 (ApplicationContext 캐싱용)
    // -------------------------------------------------------------------------
    @MockitoBean protected RealNexonApiClient nexonApiClient;
    @MockitoBean protected DiscordAlertService discordAlertService;

    // -------------------------------------------------------------------------
    // [Real Bean 구역] 실제 DB/Redis 작동 확인용
    // -------------------------------------------------------------------------
    @Autowired protected GameCharacterFacade gameCharacterFacade;
    @Autowired protected GameCharacterRepository gameCharacterRepository;
    @Autowired protected CharacterEquipmentRepository equipmentRepository;
    @Autowired protected LockStrategy lockStrategy;
    @Autowired protected RedisBufferRepository redisBufferRepository;

    /**
     * 테스트 간 데이터 격리를 위한 Redis + DB 초기화
     *
     * <p>Singleton Container 패턴에서 테스트 간 데이터 누수를 방지합니다.
     * <ul>
     *   <li>Redis: 부모 클래스의 FLUSHDB 실행</li>
     *   <li>DB: INFORMATION_SCHEMA 기반 전체 테이블 TRUNCATE</li>
     * </ul>
     */
    @Override
    @AfterEach
    protected void cleanupTestData() {
        // 1. Redis cleanup (inherited)
        super.cleanupTestData();

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
            System.err.println("[cleanupTestData] DB cleanup failed (best-effort): " + e.getMessage());
        }
    }
}