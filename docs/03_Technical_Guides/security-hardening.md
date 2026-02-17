# Security Hardening Guide

> **상위 문서:** [CLAUDE.md](../../CLAUDE.md)
> **관련 문서:** [Security Testing](security-testing.md) | [Incident Response](security-incident-response.md) | [Security Checklist](security-checklist.md) | [Infrastructure](infrastructure.md#19-security-best-practices)
>
> **Last Updated:** 2026-02-11
> **Applicable Versions:** Java 21, Spring Boot 3.5.4, Spring Security 6.x, JJWT 0.12.x
> **Documentation Version:** 1.0
> **Compliance:** GDPR Article 32, OWASP Top 10 2021

이 문서는 MapleExpectation 프로젝트의 보안 강화 규칙을 정의합니다.

## Documentation Integrity Statement

This guide is based on **security audit findings** from 2025 Q4:
- Security hardening: 25+ vulnerabilities addressed (Evidence: [Security Review](../04_Reports/security-audit-2025-Q4.md))
- Production incidents resolved: P0 #238, #241, #287 (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- OWASP Top 10 alignment: All 10 categories addressed (Evidence: [OWASP Checklist](../04_Reports/owasp-compliance.md))

## Terminology

| 용어 | 정의 |
|------|------|
| **Defense in Depth** | 다층 보안: 하나의 장애가 전체 보안을 무너뜨리지 않도록 중복 방어 계층 구성 |
| **Fail-Fast** | 보안 설정 오류 시 즉시 시작 실패 (기본값 사용 방지) |
| **Security Context** | Spring Security의 인증/인가 정보 저장소 (ThreadLocal) |
| **Fingerprint** | `HMAC-SHA256(serverSecret, apiKey)`으로 생성한 API Key 식별자 |
| **CSP** | Content Security Policy: XSS 방지를 위한 브라우저 보안 헤더 |

---

## 26. Defense in Depth: Multi-Layer Security Architecture

> **Design Rationale:** Single security controls can fail. Multiple independent layers ensure that a breach in one layer does not compromise the system.
> **Why NOT alternatives:** Single-layer security requires perfect implementation; defense in depth acknowledges human error and system failures.
> **Known Limitations:** Added latency (~5-10ms per request) and operational complexity.
> **Rollback Plan:** Remove individual layers if latency exceeds SLA, maintaining at least 3 layers.

다층 보안 아키텍처 구현을 위한 필수 규칙입니다.

### 보안 계층 구조

```
+---------------------------------------------------------------+
|  Layer 1: Network Security (VPC, Security Groups, WAF)        |
|    -> IP Whitelist, DDoS Protection, Geo-blocking             |
+---------------------------------------------------------------+
|  Layer 2: Edge Security (Nginx/ALB)                          |
|    -> TLS Termination, HTTP/2, Request Size Limits            |
+---------------------------------------------------------------+
|  Layer 3: Application Security (Spring Security)             |
|    -> Authentication, Authorization, CSRF, CORS              |
+---------------------------------------------------------------+
|  Layer 4: Business Logic Security                             |
|    -> Rate Limiting, Input Validation, Business Rules         |
+---------------------------------------------------------------+
|  Layer 5: Data Security                                       |
|    -> Encryption at Rest, Encryption in Transit, PII Masking  |
+---------------------------------------------------------------+
```

### Prometheus 엔드포인트 다층 보안 예시

**Evidence:** `PrometheusSecurityFilter.java` (Issue #20, #34)

```java
// Layer 1: IP Whitelist (Network Level)
if (!isInternalNetwork(clientIp)) {
    return FORBIDDEN;
}

// Layer 2: X-Forwarded-For Validation (Edge Level)
if (isSpoofedHeader(xForwardedFor)) {
    return FORBIDDEN;
}

// Layer 3: Spring Security Role Check (Application Level)
.hasRole("ADMIN")

// Layer 4: Rate Limiting (Business Logic Level)
if (rateLimitExceeded(clientIp)) {
    return TOO_MANY_REQUESTS;
}
```

### JWT 인증 다층 보안

**Evidence:** `JwtTokenProvider.java`, `JwtAuthenticationFilter.java`

| 계층 | 보안 조치 | 코드 위치 |
|------|----------|----------|
| **Validation** | Secret Key 길이 검증 (>= 32 chars) | `validateSecretKeyForProduction()` |
| **Environment** | Production 환경에서 기본 secret 사용 거부 | `validateSecretKeyForProduction()` |
| **Signature** | HS256 알고리즘 서명 검증 | `parseSignedClaims()` |
| **Expiration** | 토큰 만료 시간 검증 | `Jwts.parser().verifyWith()` |
| **Session** | Redis 세션 검증 (토큰 폐기) | `SessionService.validate()` |
| **Fingerprint** | HMAC-SHA256 기반 키 무결성 검증 | `FingerprintGenerator.verify()` |

---

## 27. JWT Security Best Practices

> **Production Incident:** P0 #238 (2025-12) - Weak JWT secret caused authentication bypass.
> **Fix Validated:** Fail-fast validation prevents startup with weak secrets (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md) Section 4.3).
> **Algorithm Choice:** HS256 chosen over RS256 for simplicity; symmetric key sufficient for single-service architecture.

JWT 토큰 생성, 검증, 관리를 위한 필수 규칙입니다.

### Secret Key 관리 (Critical)

```java
// Bad (하드코딩된 비밀키)
String secret = "my-secret-key";  // 절대 금지

// Bad (환경별 구분 없음)
@Value("${jwt.secret}")
private String secret;  // 모든 환경에서 같은 값

// Good (환경 변수 + Fail-Fast 검증)
public JwtTokenProvider(
    @Value("${auth.jwt.secret}") String secret,
    Environment environment) {

    // 1. 환경변수 placeholder 감지
    if (secret.contains("${")) {
        throw new IllegalStateException("JWT_SECRET not set");
    }

    // 2. 빈 값 감지
    if (secret.isBlank()) {
        throw new IllegalStateException("JWT_SECRET is blank");
    }

    // 3. Production 환경에서 기본 값 사용 거부
    if (isProduction && secret.startsWith("dev-secret")) {
        throw new IllegalStateException("Default secret not allowed in prod");
    }

    // 4. 최소 길이 검증 (HS256 = HMAC-SHA256)
    if (secret.length() < 32) {
        throw new IllegalStateException("Secret must be >= 32 chars");
    }
}
```

### API Key 저장 규칙 (Critical)

> **Why NOT in JWT:** JWT는 클라이언트에 노출되므로 apiKey를 포함하면 유출됩니다.
> **Alternative:** Redis 세션에 저장 + Fingerprint로 식별

```java
// Bad (JWT에 API Key 포함)
String token = Jwts.builder()
    .claim("apiKey", userApiKey)  // 유출 위험
    .compact();

// Good (Fingerprint만 포함)
String fingerprint = hmacSha256(serverSecret, userApiKey);
String token = Jwts.builder()
    .claim("fgp", fingerprint)  // 복원 불가능한 해시만 포함
    .compact();

// API Key는 Redis 세션에만 저장
redisTemplate.opsForHash().put(sessionId, "apiKey", userApiKey);
```

### JJWT 0.12.x Best Practice

```java
// Bad (deprecated)
Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);

// Good (최신 API)
Jws<Claims> jws = Jwts.parser()
    .verifyWith(secretKey)           // 공개키 설정
    .build()                         // Parser 빌드
    .parseSignedClaims(token);       // 서명 검증 + 파싱
```

### 토큰 만료 정책

| 환경 | Access Token | Refresh Token | 이유 |
|------|-------------|---------------|------|
| **Production** | 15분 | 7일 | 유출 시 영향 제한 |
| **Staging** | 1시간 | 1일 | 테스트 편의성 |
| **Development** | 24시간 | 30일 | 개발 편의성 |

### Token Reuse Detection

```java
// Redis 세션에서 토큰별 UUID 관리
String tokenId = UUID.randomUUID().toString();
redisTemplate.opsForValue().set("session:" + sessionId + ":token", tokenId, expiration);

// 토큰 재사용 감지
String storedTokenId = redisTemplate.opsForValue().get("session:" + sessionId + ":token");
if (!tokenId.equals(storedTokenId)) {
    throw new TokenReusedException("Token already used");
}
```

---

## 28. Content Security Policy (CSP) Configuration

> **Design Rationale:** CSP is the primary defense against XSS attacks in modern browsers.
> **Why NOT alternatives:** Input sanitization alone is insufficient; CSP provides defense in depth.
> **Known Limitations:** 'unsafe-inline' required for inline scripts/styles; mitigate with nonce/SHA in future.

XSS 방지를 위한 CSP 헤더 설정 규칙입니다.

### Spring Security 6.x CSP 설정

**Evidence:** `SecurityConfig.java` lines 234-246

```java
http.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp
        .policyDirectives(
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: https:; " +
            "font-src 'self'; " +
            "connect-src 'self'; " +
            "frame-ancestors 'none'; " +
            "form-action 'self'; " +
            "base-uri 'self';"
        )
    )
);
```

### CSP Directive 설명

| 지시어 | 값 | 위협 방지 |
|--------|-----|----------|
| `default-src` | `'self'` | 모든 리소스의 기본 출처 제한 |
| `script-src` | `'self' 'unsafe-inline' 'unsafe-eval'` | XSS 스크립트 주입 방지 |
| `style-src` | `'self' 'unsafe-inline'` | CSS 주입 방지 |
| `img-src` | `'self' data: https:` | 이미지 XSS 방지 |
| `connect-src` | `'self'` | CSRF/Ajax 요청 제한 |
| `frame-ancestors` | `'none'` | Clickjacking 방지 |
| `form-action` | `'self'` | 폼 제출 위변조 방지 |
| `base-uri` | `'self'` | Base 태그 주입 방지 |

### CSP 위반 모니터링 (Future Enhancement)

```java
// 개발 환경에서만 CSP Report-Only 모드
if (isDevelopment) {
    .contentSecurityPolicy(csp -> csp
        .policyDirectives("...")
        .reportOnly(true)  // 위반 시 차단하지 않고 리포트만
    )
}

// Production에서는 Enforce 모드
.contentSecurityPolicy(csp -> csp
    .policyDirectives("...")
    // reportOnly 미설정 = Enforce 모드
)
```

---

## 29. CORS Security Hardening

> **Production Issue:** Wildcard CORS configuration allowed CSRF attacks (Issue #172).
> **Fix Validated:** Environment-specific origin validation with startup audit (Evidence: [Issue #21](https://github.com/your-repo/issues/21)).

CORS(Cross-Origin Resource Sharing) 보안 강화 규칙입니다.

### 와일드카드 금지 (Critical)

```java
// Bad (CSRF 취약점)
configuration.setAllowedOriginPatterns(List.of("*"));
configuration.setAllowCredentials(true);  // 치명적 조합

// Good (환경별 명시적 오리진)
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
    @NotEmpty
    private List<@ValidCorsOrigin String> allowedOrigins;
    private boolean allowCredentials = true;
    private long maxAge = 3600;
}
```

### 오리진 검증 (3단계)

**Evidence:** `CorsOriginValidator.java`

```java
// 1단계: 시작 시 포맷 검증
@PostConstruct
public void validateOnStartup() {
    for (String origin : allowedOrigins) {
        // URL 형식 검증
        if (!isValidUrl(origin)) {
            throw new IllegalStateException("Invalid origin: " + origin);
        }
        // 프로토콜 검증 (HTTPS 권장)
        if (isProduction && !origin.startsWith("https://")) {
            log.warn("HTTP origin in production: {}", origin);
        }
        // 금지 패턴 검증
        if (origin.contains("*") || origin.contains("..")) {
            throw new IllegalStateException("Dangerous origin pattern: " + origin);
        }
    }
}

// 2단계: 감사 로그
log.info("[CORS-Config] Allowed origins: {}", allowedOrigins);

// 3단계: 런타임 헤더 검증
@Bean
public CorsValidationFilter corsValidationFilter() {
    return new CorsValidationFilter(validator, executor, allowedOrigins);
}
```

### CORS vs CSP

| 특성 | CORS | CSP |
|------|------|-----|
| **목적** | Cross-Origin 요청 제어 | 리소스 로딩 제어 |
| **서버 설정** | HTTP 응답 헤더 | HTTP 응답 헤더 |
| **클라이언트** | 브라우저 자동 처리 | 브라우저 자동 처리 |
| **우회 가능성** | 없음 (브라우저 강제) | 없음 (브라우저 강제) |

---

## 30. Input Validation & Sanitization

> **OWASP Category:** A03:2021 - Injection (Top 1)
> **Why NOT client-side only:** Client validation can be bypassed; server validation is authoritative.

입력값 검증과 이스케이프 처리 규칙입니다.

### Path Variable Injection 방지

**Evidence:** `PathVariableValidationFilter.java`

```java
// Bad (Path Traversal 취약)
@GetMapping("/files/{path}")
public ResponseEntity<?> getFile(@PathVariable String path) {
    return ResponseEntity.ok(Files.read(Paths.get(path)));
}

// Good (정규식 검증)
@GetMapping("/characters/{ign}")
public ResponseEntity<?> getCharacter(
    @PathVariable @Pattern(regexp = "^[a-zA-Z0-9가-힣]{1,12}$") String ign) {
    return ResponseEntity.ok(service.findByIgn(ign));
}
```

### SQL Injection 방지 (JPA)

```java
// Bad (JPQL Injection)
String jpql = "SELECT c FROM Character c WHERE c.ign = '" + ign + "'";

// Good (Parameterized Query)
@Query("SELECT c FROM Character c WHERE c.ign = :ign")
Optional<Character> findByIgn(@Param("ign") String ign);

// Good (JPA Criteria Builder)
CriteriaBuilder cb = entityManager.getCriteriaBuilder();
CriteriaQuery<Character> query = cb.createQuery(Character.class);
Root<Character> root = query.from(Character.class);
query.where(cb.equal(root.get("ign"), ign));
```

### Log Injection 방지 (CRLF)

```java
// Bad (CRLF Injection)
log.info("User input: " + userInput);  // \n\n\n 으로 로그 조작 가능

// Good (자동 이스케이프)
log.info("User input: {}", userInput);  // SLF4J가 자동 처리

// 수동 이스케이프 (필요 시)
String sanitized = userInput.replace("\n", "\\n").replace("\r", "\\r");
log.info("User input: {}", sanitized);
```

---

## 31. Sensitive Data Logging Rules

> **Compliance:** GDPR Article 32 - Security of Processing requires logging access control and data masking.
> **Incident Evidence:** API key exposure in logs detected during security audit 2025-11 (Evidence: [Security Review](../04_Reports/security-audit-2025-Q4.md)).

민감 정보 로깅 규칙입니다.

### 마스킹 대상 데이터

| 데이터 종류 | 예시 | 마스킹 패턴 |
|-------------|------|------------|
| **API Key** | `live_abcd1234efgh5678` | `live****5678` |
| **JWT Token** | `eyJhbGciOiJIUzI1Ni...` | `eyJhbG...` |
| **비밀번호** | `MyP@ssw0rd!` | `********` |
| **주민번호** | `901231-1234567` | `901231-*******` |
| **전화번호** | `010-1234-5678` | `010-****-5678` |
| **신용카드** | `1234-5678-9012-3456` | `1234-****-****-3456` |

### Record toString() 오버라이드 (Critical)

```java
// Bad (기본 toString() -> 모든 필드 노출)
public record LoginRequest(String apiKey, String password) {}
// 로그: LoginRequest[apiKey=live_abcd1234efgh5678, password=MyP@ssw0rd!]

// Good (마스킹 적용)
public record LoginRequest(String apiKey, String password) {
    @Override
    public String toString() {
        return "LoginRequest[" +
            "apiKey=" + maskApiKey(apiKey) +
            ", password=***]";
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
```

### LogicExecutor 내부 마스킹

```java
// Bad (민감 정보가 트레이스에 남음)
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", apiKey));  // API Key 노출

// Good (마스킹된 컨텍스트)
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", maskApiKey(apiKey)));
```

---

## 32. Secrets Management

> **Related:** [Secrets Management Documentation](security-secrets.md)
> **Compliance:** NIST SP 800-53 - SC-12: Cryptographic Key Establishment and Management

비밀 정보 관리 규칙입니다.

### 환경 변수 우선순위 (12-Factor App)

```
1. 환경 변수 (최우선)
2. Docker Secrets (/run/secrets/)
3. External Secrets Manager (AWS Secrets Manager, HashiCorp Vault)
4. application-{profile}.yml (암호화된 값만)
5. application.yml (기본값만, 개발용)
```

### application.yml 암호화 (Jasypt)

```yaml
# Bad (평문 비밀키)
spring:
  datasource:
    password: MySecretPassword123!

# Good (Jasypt 암호화)
spring:
  datasource:
    password: ENC(encrypted_password_here)
jasypt:
  encryptor:
    password: ${JASYPT_ENCRYPTOR_PASSWORD}  # 환경 변수에서 복호화 키
```

### Docker Compose Secrets

```yaml
# docker-compose.yml
services:
  app:
    secrets:
      - db_password
      - jwt_secret
secrets:
  db_password:
    file: ./secrets/db_password.txt
  jwt_secret:
    file: ./secrets/jwt_secret.txt
```

### Rotate Secret 전략

| 자산 | 주기 | 절차 |
|------|------|------|
| **JWT Secret** | 90일 | 1. 새 secret 배포, 2. 이중 기간(7일) 운영, 3. 이전 secret 만료 |
| **Database Password** | 180일 | 1. DB 비밀번호 변경, 2. 앱 배포, 3. 재시작 |
| **API Keys** | 365일 | 1. 새 키 발급, 2. 롤오버 기간, 3. 이전 키 폐기 |

---

## Evidence Links

- **Security Config:** `module-app/src/main/java/maple/expectation/config/SecurityConfig.java` (Evidence: [CODE-SECURITY-001])
- **JWT Provider:** `module-app/src/main/java/maple/expectation/global/security/jwt/JwtTokenProvider.java` (Evidence: [CODE-JWT-001])
- **Prometheus Filter:** `module-app/src/main/java/maple/expectation/global/security/filter/PrometheusSecurityFilter.java` (Evidence: [CODE-PROM-SEC-001])
- **CORS Validator:** `module-app/src/main/java/maple/expectation/global/security/cors/CorsOriginValidator.java` (Evidence: [CODE-CORS-001])
- **Exception Base:** `module-common/src/main/java/maple/expectation/global/error/exception/base/` (Evidence: [CODE-EXC-001])

## Technical Validity Check

This guide would be invalidated if:
- **JWT secret in git**: Search repository for hardcoded secrets
- **Wildcard CORS in production**: Check SecurityConfig.java for setAllowedOriginPatterns("*")
- **CSP disabled**: Verify headers() configuration in SecurityConfig
- **API keys in logs**: Search logs for `live_` pattern (should be masked)

### Verification Commands

```bash
# 하드코딩된 시크릿 검색
grep -r "secret.*=" --include="*.java" --include="*.yml" | grep -v "env\|placeholder"

# 와일드카드 CORS 검색
grep -r "setAllowedOriginPatterns" --include="*.java" | grep "\\\*"

# CSP 설정 확인
grep -A 5 "contentSecurityPolicy" src/main/java/**/SecurityConfig.java

# 민감 정보 로그 검색
grep -r "log.info.*apiKey\|log.info.*password" --include="*.java"
```

### Related Evidence
- Security Audit: `docs/04_Reports/security-audit-2025-Q4.md`
- P0 Resolution: `docs/04_Reports/P0_Issues_Resolution_Report_2026-01-20.md`
- OWASP Compliance: `docs/04_Reports/owasp-compliance.md`
