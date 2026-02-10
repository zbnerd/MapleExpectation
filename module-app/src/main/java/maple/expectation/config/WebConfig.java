package maple.expectation.config;

import maple.expectation.global.filter.MDCFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebConfig {

  @Bean
  public FilterRegistrationBean<MDCFilter> mdcFilterRegistration(MDCFilter mdcFilter) {
    FilterRegistrationBean<MDCFilter> registrationBean = new FilterRegistrationBean<>(mdcFilter);

    // 💡 핵심: 모든 필터 중 가장 먼저 실행되도록 순서를 최우선(0순위)으로 설정합니다.
    // 그래야 보안 필터나 API 로직에서 발생하는 모든 로그에 requestId가 찍힙니다.
    registrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE);

    // 모든 URL 패턴에 대해 필터 적용
    registrationBean.addUrlPatterns("/*");

    return registrationBean;
  }
}
