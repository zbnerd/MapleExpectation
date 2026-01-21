package maple.expectation.service.v2;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐릭터 동시 조회 테스트
 *
 * <p>CLAUDE.md Section 23/24 준수:
 * <ul>
 *   <li>CountDownLatch + awaitTermination 조합</li>
 *   <li>latch.await() 타임아웃 설정</li>
 *   <li>테스트 간 상태 격리 (UUID 기반 고유 이름)</li>
 * </ul>
 *
 * <p>5-Agent Council 리뷰 결과:
 * <ul>
 *   <li>Blue: 테스트 목적과 DisplayName 일치 확인</li>
 *   <li>Green: 동시성 패턴 최적화 승인</li>
 *   <li>Yellow: 기존 캐릭터 조회 테스트로 분리 권장</li>
 *   <li>Purple: saveAndFlush로 트랜잭션 격리 보장</li>
 *   <li>Red: 타임아웃 설정 및 리소스 정리 확인</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)  // CLAUDE.md Section 24: DB 공유 상태 충돌 방지
class GameCharacterServiceConcurrencyTest extends IntegrationTestSupport {

    private static final int LATCH_TIMEOUT_SECONDS = 30;
    private static final int TERMINATION_TIMEOUT_SECONDS = 10;

    @Test
    @DisplayName("기존 캐릭터 동시 조회: 10명이 동시에 조회해도 동일한 캐릭터를 반환해야 한다")
    void findExistingCharacterConcurrencyTest() throws InterruptedException {
        // Given: 캐릭터 미리 생성 (5-Agent Council: saveAndFlush 권장)
        String name = "ExistingUser_" + UUID.randomUUID().toString().substring(0, 8);
        GameCharacter existingCharacter = new GameCharacter(name, "mock_ocid_" + UUID.randomUUID());
        gameCharacterRepository.saveAndFlush(existingCharacter);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger(0);
        Set<Long> characterIds = ConcurrentHashMap.newKeySet();

        // When: 동시에 10명이 조회
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    GameCharacter found = gameCharacterFacade.findCharacterByUserIgn(name);
                    characterIds.add(found.getId());
                    success.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Step 1: 모든 작업이 finally 블록까지 도달 대기 (타임아웃 추가)
        boolean latchCompleted = latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(latchCompleted)
                .as("모든 조회 작업이 타임아웃 내에 완료되어야 함")
                .isTrue();

        // Step 2: CLAUDE.md Section 23 - shutdown() 후 awaitTermination() 필수
        executor.shutdown();
        boolean terminated = executor.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(terminated)
                .as("ExecutorService가 정상 종료되어야 함")
                .isTrue();

        // Then: 모든 스레드가 동일한 캐릭터를 반환
        assertThat(success.get())
                .as("모든 스레드가 성공적으로 캐릭터를 조회해야 함")
                .isEqualTo(threadCount);
        assertThat(characterIds)
                .as("모든 스레드가 동일한 캐릭터를 반환해야 함")
                .hasSize(1);
    }
}