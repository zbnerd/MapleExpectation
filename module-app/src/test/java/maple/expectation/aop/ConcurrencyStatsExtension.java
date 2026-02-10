package maple.expectation.aop;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.aspect.LoggingAspect;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@Slf4j
public class ConcurrencyStatsExtension implements BeforeEachCallback, AfterEachCallback {

  private LoggingAspect getLoggingAspect(ExtensionContext context) {
    ApplicationContext springContext = SpringExtension.getApplicationContext(context);
    return springContext.getBean(LoggingAspect.class);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    // 1. 테스트 시작 전 데이터 초기화
    getLoggingAspect(context).resetStatistics();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    // 2. 테스트 종료 후 결과 출력
    LoggingAspect aspect = getLoggingAspect(context);
    String testName = context.getDisplayName();

    for (String stat : aspect.getStatistics(testName)) {
      log.info(stat);
    }
  }
}
