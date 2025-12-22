package maple.expectation.config;

import maple.expectation.global.filter.MDCFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import static org.springframework.core.Ordered.*;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<MDCFilter> mdcFilterRegistration(MDCFilter mdcFilter) {
        FilterRegistrationBean<MDCFilter> registrationBean = new FilterRegistrationBean<>(mdcFilter);
        registrationBean.setOrder(-2147483648); // 가장 높은 우선순위로 설정 (0순위)
        return registrationBean;
    }
}