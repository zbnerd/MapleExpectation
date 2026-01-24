package maple.expectation.config;

import lombok.RequiredArgsConstructor;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.ratelimit.RateLimitingFacade;
import maple.expectation.global.ratelimit.config.RateLimitProperties;
import maple.expectation.global.ratelimit.filter.RateLimitingFilter;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.filter.JwtAuthenticationFilter;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import maple.expectation.service.v2.auth.SessionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
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
 *
 * <p>Issue #172: CORS 와일드카드 제거 - CorsProperties로 환경별 설정 분리</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({CorsProperties.class, RateLimitProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorsProperties corsProperties;

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

    /**
     * Rate Limiting 필터 Bean 등록 (Issue #152)
     *
     * <p>CRITICAL: @Component 대신 @Bean으로 등록하여 CGLIB 프록시 문제 방지</p>
     * <p>P0-2 FIX: LogicExecutor 주입으로 Fail-Open 에러 처리 보장</p>
     */
    @Bean
    public RateLimitingFilter rateLimitingFilter(
            RateLimitingFacade rateLimitingFacade,
            RateLimitProperties rateLimitProperties,
            LogicExecutor logicExecutor) {
        return new RateLimitingFilter(rateLimitingFacade, rateLimitProperties, logicExecutor);
    }

    /**
     * Rate Limiting 필터 서블릿 컨테이너 중복 등록 방지 (Issue #152)
     */
    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitFilterRegistration(
            RateLimitingFilter filter) {
        FilterRegistrationBean<RateLimitingFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtAuthenticationFilter,
                                            RateLimitingFilter rateLimitingFilter) throws Exception {
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
                // Issue #209: Prometheus 메트릭 (Docker 네트워크/localhost만 허용)
                .requestMatchers("/actuator/prometheus").access(
                    (authentication, context) -> {
                        String ip = context.getRequest().getRemoteAddr();
                        boolean isInternalNetwork = ip.startsWith("172.") ||
                                                    ip.startsWith("127.") ||
                                                    ip.equals("::1");
                        return new AuthorizationDecision(isInternalNetwork);
                    }
                )

                // Swagger UI (개발용)
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // Admin endpoints (Issue #146: /api/admin/** 보호)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasRole("ADMIN")

                // ★ 좋아요 API는 인증 필요 (더 구체적인 규칙이 먼저!)
                // POST /api/v2/characters/{userIgn}/like - 좋아요
                // GET  /api/v2/characters/{userIgn}/like/status - 좋아요 여부
                .requestMatchers("/api/v2/characters/*/like/**").authenticated()
                .requestMatchers("/api/v2/characters/*/like").authenticated()

                // ★ v3 API (인증 없이 접근 가능)
                // GET /api/v3/characters/{userIgn}/expectation - 기대값 계산
                .requestMatchers(HttpMethod.GET, "/api/v3/characters/**").permitAll()

                // ★ v4 API (인증 없이 접근 가능) (#240)
                // GET /api/v4/characters/{userIgn}/expectation - 기대값 계산
                // POST /api/v4/characters/{userIgn}/expectation/recalculate - 재계산
                .requestMatchers(HttpMethod.GET, "/api/v4/characters/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v4/characters/**/recalculate").permitAll()

                // 기존 v1/v2 API (인증 없이 접근 가능 - 점진적 마이그레이션)
                .requestMatchers(HttpMethod.GET, "/api/v1/characters/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v2/cubes/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v2/characters/**").permitAll()

                // 그 외 모든 요청은 인증 필요
                .anyRequest().authenticated()
            )

            // #262 Fix: Spring Security 6.x 필터 순서 문제 해결
            // 커스텀 필터는 표준 필터 기준으로만 순서 지정 가능
            // addFilterBefore 호출 순서 = 실행 순서
            // 실행 순서: JWT → RateLimit → UsernamePassword
            // (JWT가 먼저 인증 후, RateLimit이 user-based rate limiting 수행)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)

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

    /**
     * CORS 설정 (환경별 분리)
     *
     * <h4>Issue #172: 와일드카드 제거</h4>
     * <ul>
     *   <li><b>변경 전</b>: setAllowedOriginPatterns(List.of("*")) - CSRF 취약점</li>
     *   <li><b>변경 후</b>: CorsProperties 주입 - 환경별 명시적 오리진</li>
     * </ul>
     *
     * <h4>5-Agent Council Round 2 결정</h4>
     * <ul>
     *   <li><b>Red Agent</b>: 빈 리스트 시 앱 시작 실패 (fail-fast)</li>
     *   <li><b>Purple Agent</b>: 와일드카드 + credentials 조합 금지</li>
     *   <li><b>Blue Agent</b>: @ConfigurationProperties로 DIP 준수</li>
     * </ul>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // ✅ Issue #172: 환경별 허용 오리진 (하드코딩 제거)
        // CorsProperties.allowedOrigins는 @NotEmpty로 검증됨 (fail-fast)
        configuration.setAllowedOrigins(corsProperties.getAllowedOrigins());

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

        // ✅ credentials 설정 (CorsProperties에서 주입)
        configuration.setAllowCredentials(corsProperties.getAllowCredentials());

        // ✅ 캐시 시간 (CorsProperties에서 주입)
        configuration.setMaxAge(corsProperties.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
