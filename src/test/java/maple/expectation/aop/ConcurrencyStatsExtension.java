package maple.expectation.aop;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.aspect.LoggingAspect;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
// ...

@Slf4j
public class ConcurrencyStatsExtension implements BeforeEachCallback, AfterEachCallback {

    private LoggingAspect getLoggingAspect(ExtensionContext context) {
        ApplicationContext springContext = SpringExtension.getApplicationContext(context);
        return springContext.getBean(LoggingAspect.class);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        // 1. 테스트 시작 전: 데이터 초기화 (청소)
        getLoggingAspect(context).getAndClearExecutionTimes();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        // 2. 테스트 종료 후: 데이터 가져와서 통계 출력 (자동화!)
        LoggingAspect aspect = getLoggingAspect(context);
        List<Long> times = aspect.getAndClearExecutionTimes();

        String testName = context.getDisplayName(); // 테스트 메서드의 이름(@DisplayName)을 가져옵니다.

        // 3. 로그 출력 (이제 테스트 코드에 log.info 안 써도 됨)
        for (String stat : aspect.calculateStatistics(times, testName)) {
            log.info(stat);
        }

    }

}
