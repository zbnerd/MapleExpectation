package maple.expectation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * MapleStory Expectation 애플리케이션 메인 클래스
 *
 * <p><b>활성화된 기능:</b>
 *
 * <ul>
 *   <li>{@link EnableScheduling} - 스케줄링 작업 지원
 *   <li>{@link EnableAsync} - 비동기 메서드 실행 지원
 * </ul>
 *
 * <p><b>비동기 실행 설정:</b> Spring의 기본 {@code ThreadPoolTaskExecutor}를 사용하여 {@code @Async} 메서드를 비동기로
 * 실행합니다.
 *
 * <p>Equipment 비동기 저장과 같은 I/O 작업을 효율적으로 처리하며, Graceful Shutdown 시 모든 작업이 완료될 때까지 대기합니다.
 *
 * <p><b>참고:</b> {@code application.yml}의 {@code spring.threads.virtual.enabled=true} 설정은 Java 17에서는
 * 무시됩니다 (Java 21+부터 지원).
 *
 * <p><b>LangChain4j Auto-Configuration Exclusion:</b> OpenAI/Z.ai auto-configuration은 {@code
 * ai.sre.enabled=true}일 때만 활성화되도록 설정에서 제어합니다. 기본적으로 비활성화됩니다.
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class ExpectationApplication {

  public static void main(String[] args) {
    SpringApplication.run(ExpectationApplication.class, args);
  }
}
