package maple.expectation.support;

import maple.expectation.aop.ConcurrencyStatsExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 🛡️ <strong>동시성 테스트 및 성능 통계 측정을 위한 통합 테스트 환경설정</strong>
 *
 * <h3>📌 필수 사용 규칙</h3>
 * <p>
 * 이 어노테이션이 적용된 테스트 클래스에서 성능을 측정하려면,
 * 측정 대상이 되는 <strong>Service/Component의 메서드</strong>에 반드시
 * {@link LogExecutionTime} 어노테이션을 붙여야 합니다.
 * </p>
 *
 * <h3>⚠️ 비동기/멀티스레드 테스트 시 필수 주의사항</h3>
 * <p>
 * {@link java.util.concurrent.ExecutorService}나 {@link java.util.concurrent.CompletableFuture} 등을 사용하여
 * 멀티스레드 환경을 테스트할 경우, <strong>반드시 메인 테스트 스레드가 모든 비동기 작업이 완료될 때까지 대기(Block/Await)해야 합니다.</strong>
 * </p>
 * <p>
 * 만약 대기하지 않으면, 비동기 작업이 끝나기도 전에 테스트 메서드가 종료되어
 * Extension이 통계를 집계할 시점에 데이터가 없어 <strong>결과가 누락</strong>될 수 있습니다.
 * (예: {@link java.util.concurrent.CountDownLatch#await()} 등을 사용하여 동기화 필수)
 * </p>
 *
 * <h3>📊 동작 방식</h3>
 * <ul>
 * <li><strong>싱글 스레드:</strong> 1회 실행 후 개별 통계 출력</li>
 * <li><strong>멀티 스레드:</strong> 모든 스레드 종료 후(Await 이후) 합산된 통계 리포트 출력</li>
 * </ul>
 *
 * <h3>사용 예시:</h3>
 * <pre>
 * {@code
 * @SpringBootTestWithTimeLogging
 * class LikeConcurrencyTest {
 *
 * @Test
 * void test() throws InterruptedException {
 * // ... ExecutorService 설정 ...
 * CountDownLatch latch = new CountDownLatch(100);
 *
 * // ... 비동기 실행 ...
 *
 * latch.await(); // ⭐️ 필수: 여기서 기다려야 통계가 정상적으로 잡힘
 * }
 * }
 * }
 * </pre>
 *
 * @see LogExecutionTime
 * @see ConcurrencyStatsExtension
 */
@Target(ElementType.TYPE) // 클래스 위에 붙임
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ConcurrencyStatsExtension.class)
public @interface EnableTimeLogging {
}
