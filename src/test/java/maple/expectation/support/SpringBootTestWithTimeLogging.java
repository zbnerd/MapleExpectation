package maple.expectation.support;

import maple.expectation.aop.ConcurrencyStatsExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE) // 클래스 위에 붙임
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest // 1. 스프링 부트 테스트
@ExtendWith(ConcurrencyStatsExtension.class) // 2. 우리가 만든 자동화 도구 장착
@TestPropertySource(properties = {
        "nexon.api.key=dummy-test-key" // 4. API 키 에러 방지
})
public @interface SpringBootTestWithTimeLogging {
}
