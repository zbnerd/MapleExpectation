package maple.expectation.service.v2;

import maple.expectation.external.dto.v2.CharacterOcidResponse;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GameCharacterServiceConcurrencyTest extends IntegrationTestSupport {

    @Test
    @DisplayName("신규 캐릭터 조회: 동시에 10명이 조회해도 캐릭터는 1개만 생성되어야 한다")
    void findCharacterConcurrencyTest() throws InterruptedException {
        int threadCount = 10;
        String name = "NewUser_" + UUID.randomUUID().toString().substring(0, 8);
        // Issue #195: CompletableFuture 반환으로 변경
        when(nexonApiClient.getOcidByCharacterName(anyString()))
                .thenReturn(CompletableFuture.completedFuture(new CharacterOcidResponse("mock_ocid")));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try { gameCharacterFacade.findCharacterByUserIgn(name); success.incrementAndGet(); }
                finally { latch.countDown(); }
            });
        }

        latch.await();
        executor.shutdown();

        verify(nexonApiClient, times(1)).getOcidByCharacterName(name);
        assertThat(success.get()).isEqualTo(threadCount);
    }
}