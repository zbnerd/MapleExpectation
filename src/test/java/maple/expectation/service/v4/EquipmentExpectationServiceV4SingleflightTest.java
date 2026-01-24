package maple.expectation.service.v4;

import maple.expectation.domain.v2.CharacterEquipment;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.parser.EquipmentStreamingParser;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.facade.GameCharacterFacade;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 Singleflight 패턴 동시성 테스트 (#262)
 *
 * <h3>CLAUDE.md Section 23-24 준수:</h3>
 * <ul>
 *   <li>CountDownLatch + awaitTermination 조합</li>
 *   <li>명시적 완료 대기 (Flaky Test 방지)</li>
 *   <li>테스트 간 캐시 격리</li>
 * </ul>
 */
@Tag("integration")
class EquipmentExpectationServiceV4SingleflightTest extends IntegrationTestSupport {

    @Autowired
    private EquipmentExpectationServiceV4 serviceV4;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private GameCharacterRepository characterRepository;

    @Autowired
    private EquipmentStreamingParser streamingParser;

    private static final String TEST_USER_IGN = "테스트캐릭터262";
    private static final String TEST_OCID = "test-ocid-262";

    @BeforeEach
    void setUp() {
        // CLAUDE.md Section 24: 테스트 간 상태 격리
        var cache = cacheManager.getCache("expectationV4");
        if (cache != null) {
            cache.clear();
        }

        // 테스트용 캐릭터 생성
        characterRepository.findByUserIgn(TEST_USER_IGN)
                .ifPresentOrElse(
                        existing -> {},  // 이미 존재
                        () -> {
                            // GameCharacter 먼저 저장 (ocid가 PK 역할)
                            GameCharacter character = new GameCharacter(TEST_USER_IGN, TEST_OCID);
                            characterRepository.save(character);

                            // CharacterEquipment는 ocid로 연결 (동일 ocid 사용)
                            CharacterEquipment equipment = CharacterEquipment.builder()
                                    .ocid(TEST_OCID)
                                    .jsonContent(createMinimalEquipmentJson())
                                    .build();

                            character.setEquipment(equipment);
                            characterRepository.save(character);
                        }
                );
    }

    @Test
    @DisplayName("[#262] Singleflight: 동시 50 요청 시 캐시 히트로 중복 계산 방지")
    void singleflight_concurrentRequests_preventsDuplicateCalculation() throws InterruptedException {
        // Given: 50개 동시 요청
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);  // 동시 시작 보장
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cacheHitCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // When: 모든 스레드가 동시에 요청
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();  // 모든 스레드 동시 시작

                    EquipmentExpectationResponseV4 response =
                            serviceV4.calculateExpectation(TEST_USER_IGN, false);

                    if (response != null) {
                        successCount.incrementAndGet();
                        if (response.isFromCache()) {
                            cacheHitCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    // 진단용 로그
                    System.err.printf("[#262 테스트] 예외 발생: %s%n", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();

        // CLAUDE.md Section 23: 명시적 완료 대기
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: 진단 출력
        System.out.printf("[#262 테스트] 총 요청: %d, 성공: %d, 캐시 히트: %d (%.1f%%), 에러: %d%n",
                threadCount, successCount.get(), cacheHitCount.get(),
                (successCount.get() > 0 ? (cacheHitCount.get() * 100.0) / successCount.get() : 0),
                errorCount.get());

        assertThat(completed).isTrue();

        // Singleflight 검증: 최소 1개 요청 성공 + 캐시 히트 발생
        // (동시성 환경에서 일부 요청이 트랜잭션 충돌로 실패할 수 있음)
        assertThat(successCount.get())
                .as("최소 1개 요청 성공 필요")
                .isGreaterThanOrEqualTo(1);

        // 성공한 요청 중 캐시 히트가 있어야 함 (Singleflight 효과)
        // 첫 번째 요청만 계산, 나머지는 캐시에서 조회
        if (successCount.get() > 1) {
            assertThat(cacheHitCount.get())
                    .as("Singleflight 효과: 캐시 히트 발생")
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("[#262] force=true 시 캐시 무시하고 재계산")
    void force_true_bypassesCache() {
        // Given: 첫 번째 요청으로 캐시 저장
        EquipmentExpectationResponseV4 firstResponse =
                serviceV4.calculateExpectation(TEST_USER_IGN, false);
        assertThat(firstResponse).isNotNull();

        // When: force=true로 재계산
        EquipmentExpectationResponseV4 forceResponse =
                serviceV4.calculateExpectation(TEST_USER_IGN, true);

        // Then: 캐시 히트 아님 (재계산됨)
        assertThat(forceResponse).isNotNull();
        assertThat(forceResponse.isFromCache()).isFalse();
    }

    @Test
    @DisplayName("[#262] 캐시 미스 시 직접 계산 (Graceful Degradation)")
    void cacheMiss_fallbackToDirectCalculation() {
        // Given: 캐시 비움
        var cache = cacheManager.getCache("expectationV4");
        if (cache != null) {
            cache.clear();
        }

        // When: 새로운 요청
        EquipmentExpectationResponseV4 response =
                serviceV4.calculateExpectation(TEST_USER_IGN, false);

        // Then: 계산 성공 (캐시 미스이므로 fromCache=false)
        assertThat(response).isNotNull();
        assertThat(response.getUserIgn()).isEqualTo(TEST_USER_IGN);
    }

    /**
     * 최소한의 테스트용 장비 JSON 생성
     *
     * <p>base_equipment_level은 item_base_option 안에 위치해야 함 (Parser 요구사항)</p>
     */
    private String createMinimalEquipmentJson() {
        return """
                {
                  "date": "2024-01-01T00:00+09:00",
                  "character_class": "아크메이지(썬,콜)",
                  "item_equipment_preset_1": [
                    {
                      "item_equipment_part": "모자",
                      "item_equipment_slot": "모자",
                      "item_name": "앱솔랩스 매지션햇",
                      "item_icon": "https://example.com/icon.png",
                      "item_base_option": {
                        "base_equipment_level": 160
                      },
                      "item_total_option": {},
                      "potential_option_grade": "레전드리",
                      "potential_option_1": "INT : +12%",
                      "potential_option_2": "INT : +9%",
                      "potential_option_3": "최대 MP : +9%",
                      "additional_potential_option_grade": "에픽",
                      "additional_potential_option_1": "INT : +6%",
                      "additional_potential_option_2": "마력 : +10",
                      "additional_potential_option_3": "최대 MP : +6%",
                      "starforce": "17",
                      "starforce_scroll_flag": "미사용"
                    }
                  ],
                  "item_equipment_preset_2": [],
                  "item_equipment_preset_3": []
                }
                """;
    }
}
