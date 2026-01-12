package maple.expectation.config;

import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.filter.JwtAuthenticationFilter;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import maple.expectation.service.v2.auth.SessionService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 설정 (6.x Lambda DSL)
 *
 * <p>보안 구성:
 * <ul>
 *   <li>STATELESS 세션 정책 (JWT 사용)</li>
 *   <li>CSRF 비활성화 (REST API)</li>
 *   <li>CORS 설정</li>
 *   <li>보안 헤더 (X-Frame-Options, HSTS)</li>
 *   <li>JWT 인증 필터</li>
 * </ul>
 * </p>
 *
 * <p>CRITICAL (Spring Security 6.x Best Practice - Context7):
 * <ul>
 *   <li>Filter는 @Component 사용 금지 (CGLIB 프록시 → logger NPE)</li>
 *   <li>@Bean으로 수동 등록 + FilterRegistrationBean으로 서블릿 컨테이너 중복 등록 방지</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * JWT 인증 필터 Bean 등록
     *
     * <p>CRITICAL: @Component 대신 @Bean으로 등록하여 CGLIB 프록시 문제 방지</p>
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            SessionService sessionService,
            FingerprintGenerator fingerprintGenerator) {
        return new JwtAuthenticationFilter(jwtTokenProvider, sessionService, fingerprintGenerator);
    }

    /**
     * 서블릿 컨테이너 중복 등록 방지 (Context7 Best Practice)
     *
     * <p>Spring Boot가 Filter Bean을 서블릿 컨테이너에도 자동 등록하므로
     * FilterRegistrationBean으로 비활성화하여 Spring Security만 필터 관리</p>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
            // CSRF 비활성화 (REST API)
            .csrf(AbstractHttpConfigurer::disable)

            // CORS 설정
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // 세션 정책: STATELESS (JWT 사용)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 보안 헤더 설정
            .headers(headers -> headers
                // Clickjacking 방지
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                // Content-Type 스니핑 방지
                .contentTypeOptions(Customizer.withDefaults())
                // HSTS (HTTPS 강제)
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000)
                )
            )

            // 요청 권한 설정
            // IMPORTANT: 더 구체적인 규칙이 먼저 와야 함 (Spring Security 6.x 규칙)
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/auth/login").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/info").permitAll()

                // Swagger UI (개발용)
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Admin endpoints
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // ★ 좋아요 API는 인증 필요 (더 구체적인 규칙이 먼저!)
                // POST /api/v2/characters/{userIgn}/like - 좋아요
                // GET  /api/v2/characters/{userIgn}/like/status - 좋아요 여부
                .requestMatchers("/api/v2/characters/*/like/**").authenticated()
                .requestMatchers("/api/v2/characters/*/like").authenticated()

                // ★ v3 API (인증 없이 접근 가능)
                // GET /api/v3/characters/{userIgn}/expectation - 기대값 계산
                .requestMatchers(HttpMethod.GET, "/api/v3/characters/**").permitAll()

                // 기존 v1/v2 API (인증 없이 접근 가능 - 점진적 마이그레이션)
                .requestMatchers(HttpMethod.GET, "/api/v1/characters/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v2/cubes/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v2/characters/**").permitAll()

                // 그 외 모든 요청은 인증 필요
                .anyRequest().authenticated()
            )

            // JWT 인증 필터 추가
            .addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class)

            // 인증 실패 핸들링
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                        "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}"
                    );
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                        "{\"code\":\"FORBIDDEN\",\"message\":\"접근 권한이 없습니다.\"}"
                    );
                })
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 허용 오리진 (프로덕션에서는 환경변수로 관리)
        configuration.setAllowedOriginPatterns(List.of("*"));

        // 허용 메서드
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // 허용 헤더
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin"
        ));

        // 자격 증명 허용
        configuration.setAllowCredentials(true);

        // 캐시 시간
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
