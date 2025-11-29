package maple.expectation.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ⏱️ <strong>메서드 실행 시간 측정 AOP 어노테이션</strong>
 * <p>
 * 이 어노테이션이 붙은 메서드는 AOP에 의해 실행 시간이 측정됩니다.
 * 측정된 시간은 로그로 출력되거나, 통계용으로 수집됩니다.
 * </p>
 *
 * <h3>⚠️ 테스트 환경 사용 시 주의사항</h3>
 * <p>
 * 테스트 코드에서 이 어노테이션이 정상적으로 작동하고 통계가 출력되려면,
 * 테스트 클래스에 반드시 <strong><code>@SpringBootTestWithTimeLogging</code></strong> 어노테이션을 붙여야 합니다.
 * (해당 어노테이션이 AOP 설정과 통계 익스텐션을 자동으로 로드합니다.)
 * </p>
 *
 * <h3>사용 예시:</h3>
 * <pre>
 * {@code
 * // 1. 메서드에 적용
 * @LogExecutionTime
 * public void heavyLogic() { ... }
 *
 * // 2. 테스트 클래스 설정 (Test 경로)
 * @SpringBootTestWithTimeLogging
 * class MyServiceTest { ... }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {
}
