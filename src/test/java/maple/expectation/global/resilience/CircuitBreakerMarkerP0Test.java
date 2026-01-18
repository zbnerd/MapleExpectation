package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import maple.expectation.global.error.exception.ExternalServiceException;
import maple.expectation.global.error.exception.auth.DuplicateLikeException;
import maple.expectation.global.error.exception.marker.CircuitBreakerIgnoreMarker;
import maple.expectation.global.error.exception.marker.CircuitBreakerRecordMarker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0 테스트: CircuitBreaker Marker Interface 검증
 *
 * <h4>검증 대상</h4>
 * <ul>
 *   <li>CircuitBreakerIgnoreMarker: 비즈니스 예외가 실패 카운트에 포함되지 않음</li>
 *   <li>CircuitBreakerRecordMarker: 시스템 예외가 실패 카운트에 포함됨</li>
 *   <li>전체 상태 전이: CLOSED → OPEN → HALF_OPEN → CLOSED</li>
 * </ul>
 *
 * @see CircuitBreakerIgnoreMarker
 * @see CircuitBreakerRecordMarker
 */
@Tag("unit")
@DisplayName("[P0] CircuitBreaker Marker Interface 테스트")
class CircuitBreakerMarkerP0Test {

    private CircuitBreaker testCb;
    private String uniqueCbName;

    @BeforeEach
    void setUp() {
        // 테스트마다 고유한 이름으로 새 CircuitBreaker 생성 (테스트 격리)
        uniqueCbName = "p0-test-cb-" + System.nanoTime();

        // 테스트 전용 CircuitBreaker 생성 (minimumNumberOfCalls=2로 빠른 검증)
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(2)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(2)
                // IgnoreMarker 구현체들은 실패로 카운트하지 않음
                .ignoreExceptions(DuplicateLikeException.class)
                // RecordMarker 구현체들은 실패로 카운트함
                .recordExceptions(ExternalServiceException.class, RuntimeException.class)
                .build();

        // CircuitBreakerRegistry.of()로 독립 레지스트리 생성
        CircuitBreakerRegistry localRegistry = CircuitBreakerRegistry.of(config);
        testCb = localRegistry.circuitBreaker(uniqueCbName);
    }

    @Nested
    @DisplayName("CB-P01: CircuitBreakerIgnoreMarker 테스트")
    class IgnoreMarkerTests {

        @Test
        @DisplayName("비즈니스 예외(IgnoreMarker)는 실패 카운트에 포함되지 않아야 한다")
        void shouldNotCountFailure_whenIgnoreMarkerException() {
            // given: 초기 상태 CLOSED
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // when: IgnoreMarker 예외 10회 발생
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() ->
                        testCb.executeSupplier(() -> {
                            throw new DuplicateLikeException();
                        })
                ).isInstanceOf(DuplicateLikeException.class);
            }

            // then: 여전히 CLOSED 상태 (IgnoreMarker는 실패로 카운트 안됨)
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // 메트릭 확인: 실패 횟수 0 (IgnoreMarker 예외는 카운트 안됨)
            CircuitBreaker.Metrics metrics = testCb.getMetrics();
            assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(0);
        }

        @Test
        @DisplayName("DuplicateLikeException이 CircuitBreakerIgnoreMarker를 구현해야 한다")
        void shouldImplementIgnoreMarker() {
            assertThat(CircuitBreakerIgnoreMarker.class)
                    .isAssignableFrom(DuplicateLikeException.class);
        }
    }

    @Nested
    @DisplayName("CB-P02: CircuitBreakerRecordMarker 테스트")
    class RecordMarkerTests {

        @Test
        @DisplayName("시스템 예외(RecordMarker)는 실패 카운트에 포함되어야 한다")
        void shouldCountFailure_whenRecordMarkerException() {
            // given: 초기 상태 CLOSED
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // when: RecordMarker 예외 2회 발생 (minimumNumberOfCalls=2 충족)
            for (int i = 0; i < 2; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new ExternalServiceException("TestService");
                    });
                } catch (ExternalServiceException ignored) {}
            }

            // then: OPEN 상태로 전환됨 (100% 실패율 >= 50% 임계값)
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 메트릭 확인
            CircuitBreaker.Metrics metrics = testCb.getMetrics();
            assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(2);
        }

        @Test
        @DisplayName("ExternalServiceException이 CircuitBreakerRecordMarker를 구현해야 한다")
        void shouldImplementRecordMarker() {
            assertThat(CircuitBreakerRecordMarker.class)
                    .isAssignableFrom(ExternalServiceException.class);
        }
    }

    @Nested
    @DisplayName("CB-P03: 전체 상태 전이 검증")
    class FullCycleTests {

        @Test
        @DisplayName("CLOSED → OPEN → HALF_OPEN → CLOSED 전체 사이클이 정상 동작해야 한다")
        void shouldTransitionThroughAllStates() {
            // 1. CLOSED 상태 확인
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // 2. 실패를 발생시켜 OPEN으로 전환 (minimumNumberOfCalls=2)
            for (int i = 0; i < 2; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new ExternalServiceException("TestService");
                    });
                } catch (ExternalServiceException ignored) {}
            }
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 3. 수동으로 HALF_OPEN 전환 (테스트 안정성)
            testCb.transitionToHalfOpenState();
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // 4. HALF_OPEN에서 성공 호출로 CLOSED로 복귀
            // permittedNumberOfCallsInHalfOpenState=2 이므로 2회 성공 필요
            testCb.executeSupplier(() -> "success-1");
            testCb.executeSupplier(() -> "success-2");

            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("HALF_OPEN에서 실패율이 임계값 이상이면 다시 OPEN으로 전환되어야 한다")
        void shouldTransitionBackToOpen_whenFailInHalfOpen() {
            // 1. OPEN으로 전환
            for (int i = 0; i < 2; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new ExternalServiceException("TestService");
                    });
                } catch (ExternalServiceException ignored) {}
            }
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            // 2. 수동으로 HALF_OPEN 전환
            testCb.transitionToHalfOpenState();
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

            // 3. HALF_OPEN에서 2회 모두 실패 (permittedNumberOfCallsInHalfOpenState=2)
            // 실패율 100% >= failureRateThreshold 50%
            for (int i = 0; i < 2; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new ExternalServiceException("TestService");
                    });
                } catch (ExternalServiceException ignored) {}
            }

            // 4. 다시 OPEN으로 전환
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    @Nested
    @DisplayName("CB-P04: 혼합 시나리오 테스트")
    class MixedScenarioTests {

        @Test
        @DisplayName("IgnoreMarker와 RecordMarker가 혼합되면 RecordMarker만 카운트되어야 한다")
        void shouldOnlyCountRecordMarker_whenMixed() {
            // given: 초기 상태
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            // when: IgnoreMarker 5회 발생
            for (int i = 0; i < 5; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new DuplicateLikeException();
                    });
                } catch (DuplicateLikeException ignored) {}
            }

            // then: 아직 CLOSED (IgnoreMarker는 카운트 안됨)
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(testCb.getMetrics().getNumberOfFailedCalls()).isEqualTo(0);

            // when: RecordMarker 예외로 실패 유발 (2회 → OPEN)
            for (int i = 0; i < 2; i++) {
                try {
                    testCb.executeSupplier(() -> {
                        throw new ExternalServiceException("TestService");
                    });
                } catch (ExternalServiceException ignored) {}
            }

            // then: OPEN 상태 (RecordMarker만 카운트됨)
            assertThat(testCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(testCb.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        }
    }
}
