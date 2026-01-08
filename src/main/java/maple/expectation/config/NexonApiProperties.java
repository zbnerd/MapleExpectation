package maple.expectation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Nexon API 클라이언트 타임아웃 설정 프로퍼티
 *
 * <p>application.yml에서 다음과 같이 설정:
 * <pre>
 * nexon:
 *   api:
 *     connect-timeout: 3s
 *     response-timeout: 5s
 *     cache-follower-timeout-seconds: 32
 *     latch-initial-ttl-seconds: 60
 *     latch-finalize-ttl-seconds: 10
 * </pre>
 *
 * <h4>타임아웃 계층 설계 (상한 보장)</h4>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                 TimeLimiter (28s)                           │
 * │  ┌───────────────────────────────────────────────────────┐  │
 * │  │                 Retry (3 attempts × 500ms)            │  │
 * │  │  ┌─────────────────────────────────────────────────┐  │  │
 * │  │  │           HTTP Client                           │  │  │
 * │  │  │  - connectTimeout: 3s                           │  │  │
 * │  │  │  - responseTimeout: 5s                          │  │  │
 * │  │  └─────────────────────────────────────────────────┘  │  │
 * │  └───────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────┘
 * 상한 예산: 3*(3s+5s) + 2*0.5s + 3s = 24 + 1 + 3 = 28s
 * cacheFollowerTimeout: 32s (TimeLimiter 28s + 여유 4s)
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "nexon.api")
public class NexonApiProperties {

    /**
     * TCP 연결 타임아웃
     * <p>서버와의 TCP 연결(핸드셰이크)이 완료되어야 하는 최대 시간
     * <p>기본값: 3초
     */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * HTTP 응답 타임아웃 (읽기 타임아웃 성격)
     * <p>요청 전송 후 응답을 수신하는 과정에서 허용되는 최대 대기 시간
     * <p>Reactor Netty HttpClient.responseTimeout()에 적용됨
     * <p>기본값: 5초
     */
    @NotNull
    private Duration responseTimeout = Duration.ofSeconds(5);

    /**
     * 캐시 Follower 대기 타임아웃 (초)
     * <p>Leader가 API 호출을 완료할 때까지 Follower가 대기하는 최대 시간
     * <p>TimeLimiter보다 약간 길게 설정 권장 (안전 마진)
     * <p>기본값: 32초 (TimeLimiter 28초 + 여유 4초)
     * <p>허용 범위: 5 ~ 120초
     */
    @Min(5)
    @Max(120)
    private int cacheFollowerTimeoutSeconds = 32;

    /**
     * 래치 초기 TTL (초)
     * <p>리더가 래치를 생성할 때 설정하는 TTL
     * <p>리더 크래시 시 팔로워가 영원히 대기하는 것을 방지
     * <p>기본값: 60초 (cacheFollowerTimeout보다 충분히 길게)
     */
    @Min(30)
    @Max(300)
    private int latchInitialTtlSeconds = 60;

    /**
     * 래치 정리 후 TTL (초)
     * <p>리더가 작업 완료 후 래치에 설정하는 짧은 TTL
     * <p>팔로워가 캐시 조회할 시간만큼 유지 후 자동 정리
     * <p>기본값: 10초
     */
    @Min(5)
    @Max(60)
    private int latchFinalizeTtlSeconds = 10;

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public int getCacheFollowerTimeoutSeconds() {
        return cacheFollowerTimeoutSeconds;
    }

    public void setCacheFollowerTimeoutSeconds(int cacheFollowerTimeoutSeconds) {
        this.cacheFollowerTimeoutSeconds = cacheFollowerTimeoutSeconds;
    }

    public int getLatchInitialTtlSeconds() {
        return latchInitialTtlSeconds;
    }

    public void setLatchInitialTtlSeconds(int latchInitialTtlSeconds) {
        this.latchInitialTtlSeconds = latchInitialTtlSeconds;
    }

    public int getLatchFinalizeTtlSeconds() {
        return latchFinalizeTtlSeconds;
    }

    public void setLatchFinalizeTtlSeconds(int latchFinalizeTtlSeconds) {
        this.latchFinalizeTtlSeconds = latchFinalizeTtlSeconds;
    }
}
