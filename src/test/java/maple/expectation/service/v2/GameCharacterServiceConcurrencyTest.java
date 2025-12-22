package maple.expectation.service.v2;

import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.external.NexonApiClient;
import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
class GameCharacterServiceConcurrencyTest {

    @Autowired
    private GameCharacterService gameCharacterService;

    @Autowired
    private GameCharacterRepository gameCharacterRepository;

    // 넥슨 API 호출 횟수를 감시하기 위해 MockitoBean 사용 (Spring Boot 3.4+)
    @MockitoBean
    private NexonApiClient nexonApiClient;

    @AfterEach
    void tearDown() {
        gameCharacterRepository.deleteAll();
    }

    @Test
    @DisplayName("신규 캐릭터 조회: 동시에 10명이 같은 이름으로 조회해도 캐릭터는 1개만 생성되어야 한다")
    void findCharacterConcurrencyTest() throws InterruptedException {
        // Given
        int threadCount = 10;
        String targetName = "신규유저_" + UUID.randomUUID().toString().substring(0, 8);
        String mockOcid = "mock_ocid_999";

        // API 응답 설정
        when(nexonApiClient.getOcidByCharacterName(anyString()))
                .thenReturn(new CharacterOcidResponse(mockOcid));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    gameCharacterService.findCharacterByUserIgn(targetName);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // Then
        // 1. 넥슨 API(getOcidByCharacterName) 호출은 전체 스레드 중 딱 1번만 발생해야 함
        verify(nexonApiClient, times(1)).getOcidByCharacterName(targetName);

        // 2. DB에 저장된 캐릭터도 딱 1개여야 함
        long dbCount = gameCharacterRepository.findAll().stream()
                .filter(c -> c.getUserIgn().equals(targetName))
                .count();
        assertThat(dbCount).isEqualTo(1);

        // 3. 모든 스레드가 에러 없이 조회를 완료했어야 함
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failCount.get()).isEqualTo(0);
    }
}