package maple.expectation.mornitering;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.global.lock.LockStrategy;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.service.v2.alert.DiscordAlertService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean; // ✅ Spring Boot 3.4+

import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest // ✅ Spring Context를 로드하여 @MockitoBean 사용 가능하게 함
class MonitoringAlertServiceTest {

    @Autowired
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean // ✅ 기존 @MockBean 대신 사용 (Spring Boot 3.4 신규 어노테이션)
    private LikeBufferStorage likeBufferStorage;

    @MockitoBean
    private DiscordAlertService discordAlertService;

    @MockitoBean
    private LockStrategy lockStrategy;

    private Cache<String, AtomicLong> testCache;

    @BeforeEach
    void setUp() {
        testCache = Caffeine.newBuilder().build();
        given(likeBufferStorage.getCache()).willReturn(testCache);
    }

    @Test
    @DisplayName("리더 권한을 획득하고 임계치를 초과하면 알림을 발송한다")
    void leaderSuccess_OverThreshold_SendAlert() throws Throwable {
        // given: 락 획득 성공 시뮬레이션 (인자로 전달된 람다를 실행하도록 설정)
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> supplier = invocation.getArgument(3);
                    return supplier.get(); // 실제 내부 로직 실행
                });

        testCache.put("UserA", new AtomicLong(2500));

        // when
        monitoringAlertService.checkBufferSaturation();

        // then
        verify(discordAlertService, times(1)).sendCriticalAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("리더 권한 획득에 실패하면 임계치를 초과해도 알림을 보내지 않는다")
    void leaderFail_NoAlert() throws Throwable {
        // given: 락 획득 실패 시뮬레이션 (예외 발생)
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willThrow(new RuntimeException("Lock acquisition failed"));

        testCache.put("UserA", new AtomicLong(2500));

        // when
        monitoringAlertService.checkBufferSaturation();

        // then
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("임계치 미만이면 리더 권한이 있어도 알림을 보내지 않는다")
    void leaderSuccess_UnderThreshold_NoAlert() throws Throwable {
        // given: 락 획득 성공
        given(lockStrategy.executeWithLock(anyString(), anyLong(), anyLong(), any(ThrowingSupplier.class)))
                .willAnswer(invocation -> ((ThrowingSupplier<?>) invocation.getArgument(3)).get());

        testCache.put("UserA", new AtomicLong(1000));

        // when
        monitoringAlertService.checkBufferSaturation();

        // then
        verify(discordAlertService, never()).sendCriticalAlert(anyString(), anyString(), any());
    }
}