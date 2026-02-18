# Security Incident Response Guide

> **상위 문서:** [CLAUDE.md](../../CLAUDE.md)
> **관련 문서:** [Security Hardening](security-hardening.md) | [Security Testing](security-testing.md) | [Security Checklist](security-checklist.md)
>
> **Last Updated:** 2026-02-11
> **Applicable Versions:** All Production Systems
> **Documentation Version:** 1.0
> **Compliance:** NIST SP 800-61 Rev. 2, GDPR Article 33 (Breach Notification)

이 문서는 MapleExpectation 프로젝트의 보안 사고 대응 절차를 정의합니다.

## Documentation Integrity Statement

This guide is based on **incident response experience** from 2025 P0 incidents:
- P0 #238: Authentication bypass resolved in 2 hours (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- P0 #241: Self-invocation security bypass resolved in 4 hours
- P0 #287: Data loss incident resolved with DLQ implementation
- Average MTTR: 2.5 hours for security incidents (Industry average: 48 hours)

## Terminology

| 용어 | 정의 |
|------|------|
| **MTTR** | Mean Time To Resolve - 사고 해결까지의 평균 시간 |
| **MTTD** | Mean Time To Detect - 사고 감지까지의 평균 시간 |
| **RTO** | Recovery Time Objective - 복구 목표 시간 |
| **RPO** | Recovery Point Objective - 데이터 손실 허용 시점 |
| **Containment** | 영향 범위 제한 (더 이상의 피해 방지) |
| **Eradication** | 근본 원인 제거 |
| **Lessons Learned** | 사고 분석 및 개선 계획 |

---

## 39. Incident Response Lifecycle (NIST SP 800-61)

> **Framework:** NIST Computer Security Incident Handling Guide
> **4 Phases:** Preparation -> Detection/Analysis -> Containment/Eradication/Recovery -> Post-Incident Activity

보안 사고 대응 수명 주기입니다.

### Phase 1: Preparation (준비)

**목표:** 사고 발생 전 대응 체계 구축

```
+---------------------------------------------------------------+
|  1. Incident Response Team (IRT) 구성                          |
|    -> Security Lead, DevOps, Developer, Legal, PR             |
+---------------------------------------------------------------+
|  2. 통신 채널 확보                                             |
|    -> Discord #security-incidents, On-call Slack, Email       |
+---------------------------------------------------------------+
|  3. 모니터링/알림 시스템 구축                                  |
|    -> Prometheus Alert, Discord Webhook, PagerDuty            |
+---------------------------------------------------------------+
|  4. 플레이북 작성                                             |
|    -> 각 유형별 대응 절차 문서화                               |
+---------------------------------------------------------------+
|  5. 정기 훈련                                                  |
|    -> 분기별 Tabletop Exercise, 월간 Chaos Test               |
+---------------------------------------------------------------+
```

### Phase 2: Detection & Analysis (감지 및 분석)

**목표:** 사고 식별 및 심각도 평가

#### 사고 탐지 시그널

| 시그널 | 탐지 방법 | 위험도 |
|--------|----------|--------|
| **비정상적인 로그인 실패** | Prometheus: `auth_login_failed_total` > 100/min | P1 |
| **알 수 없는 IP 접속** | Prometheus: GeoIP mismatch | P2 |
| **API 응답 시간 급증** | Prometheus: `http_request_duration_seconds` > 5s | P1 |
| **에러율 급증** | Prometheus: `http_server_errors_seconds` > 5% | P0 |
| **보안 헤더 누락** | Security scanner alert | P2 |
| **의심스러운 로그** | ELK: `/etc/passwd`, `SELECT * FROM` 등 | P0 |

#### 심각도 분류

| 등급 | 정의 | 예시 | 대응 시간 (MTTR) |
|------|------|------|------------------|
| **P0** | 서비스 중단, 데이터 유출 | 인증 우회, DB 탈취 | 1시간 |
| **P1** | 심각한 기능 장애 | Rate Limit 실패, 서킷 오픈 | 4시간 |
| **P2** | 부분적 기능 저하 | 느린 응답, 간헐적 오류 | 1일 |
| **P3** | 미미한 영향 | 오타, 문서 오류 | 1주 |

### Phase 3: Containment, Eradication, Recovery (억제, 근절, 복구)

**목표:** 영향 제한, 원인 제거, 서비스 복구

```
+---------------------------------------------------------------+
|  Containment (억제)                                           |
|    1. 영향 범위 격리                                          |
|    2. 공격자 차단 (IP Ban, Account 정지)                       |
|    3. 증거 보존 (로그, 메모리 덤프)                           |
+---------------------------------------------------------------+
|  Eradication (근절)                                           |
|    1. 취약점 패치                                             |
|    2. 악성코드 제거                                          |
|    3. 보안 설정 강화                                          |
+---------------------------------------------------------------+
|  Recovery (복구)                                              |
|    1. 시스템 복구                                             |
|    2. 데이터 복원                                             |
|    3. 정상 운영 재개                                          |
+---------------------------------------------------------------+
```

### Phase 4: Post-Incident Activity (사후 활동)

**목표:** 재발 방지 및 개선

```
+---------------------------------------------------------------+
|  1. Root Cause Analysis (RCA) 작성                           |
|    -> 5 Whys, Fishbone Diagram                                |
+---------------------------------------------------------------+
|  2. 개선 계획 수립                                            |
|    -> Short-term (Hotfix), Long-term (Refactoring)            |
+---------------------------------------------------------------+
|  3. 문서 업데이트                                             |
|    -> CLAUDE.md, ADR, Playbook                                |
+---------------------------------------------------------------+
|  4. 지식 공유                                                  |
|    -> Tech Talk, Retro                                        |
+---------------------------------------------------------------+
```

---

## 40. Common Security Incident Playbooks

자주 발생하는 보안 사고별 대응 절차입니다.

### Playbook 1: 인증 우회 (Authentication Bypass)

**관련 사고:** P0 #238

#### 증상
- 인증 없이 보호된 리소스 접근 가능
- JWT 검증이 동작하지 않음
- 401 응답이 나오지 않아야 할 때 나옴

#### 즉시 조치 (0-15분)
```bash
# 1. 영향도 확인
grep "JWT.*valid" /var/log/app.log | tail -100

# 2. 인증 필터 로깅 레벨 상향
curl -X POST http://localhost:8080/actuator/loggers/maple.expectation.global.security \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'

# 3. 공급자 확인
curl http://localhost:8080/actuator/health
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. SecurityConfig 설정
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/protected-endpoint").authenticated()  // 이 설정 누락?

// 2. Filter 등록 순서
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)  // 순서 누락?

// 3. Filter @Component 사용 (CGLIB 문제)
@Component  // 이 어노테이션 제거?
public class JwtAuthenticationFilter extends OncePerRequestFilter
```

#### 해결 및 복구 (60-120분)
```java
// 1. SecurityConfig 수정
.requestMatchers("/api/admin/**").hasRole("ADMIN")  // 명시적 보호

// 2. Filter @Bean 등록으로 변경
@Bean
public JwtAuthenticationFilter jwtAuthenticationFilter(...) {
    return new JwtAuthenticationFilter(...);
}

// 3. 배포 후 검증
./gradlew test --tests "*Security*"
```

### Playbook 2: Rate Limiting 장애 (DoS Vulnerability)

**관련 사고:** Issue #152

#### 증상
- 특정 IP에서 과도한 요청
- 서버 응답 시간 급증
- Redis 연결 실패 시 필터 동작 안 함

#### 즉시 조치 (0-15분)
```bash
# 1. Nginx/WAF에서 임시 차단
nginx-block-ip $ATTACKER_IP

# 2. Rate Limiting 강화
kubectl set env deployment/app RATELIMIT_ENABLED=true

# 3. Health Check
curl http://localhost:8080/actuator/health
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. Fail-Open 여부
boolean allowed = executor.executeOrDefault(
    () -> rateLimitExceeded(),
    false,  // Redis 장애 시 true면 Fail-Open (취약)
    context
);

// 2. IP 기반 Rate Limiting
String key = "ratelimit:" + clientIp;  // IP만 사용하면 우회 가능

// 3. Redis 장애 폴백
@ConditionalOnProperty(prefix = "ratelimit", name = "enabled")
```

#### 해결 및 복구 (60-120분)
```java
// 1. Fail-Closed로 변경 (Redis 장애 시 차단)
boolean allowed = executor.executeOrDefault(
    () -> rateLimitExceeded(),
    false,  // 장애 시 false 반환 = 차단
    context
);

// 2. User-based Rate Limiting 추가
String key = "ratelimit:" + sessionId;  // 세션별 제한

// 3. Token Bucket 알고리즘 구현
public class TokenBucketRateLimiter {
    public boolean tryConsume(String key, int tokens) { ... }
}
```

### Playbook 3: CORS Misconfiguration

**관련 사고:** Issue #21, #172

#### 증상
- 타 도메인에서 API 호출 가능
- 브라우저 콘솔에 CORS 에러 없음
- CSRF 취약점 발생 가능

#### 즉시 조치 (0-15분)
```bash
# 1. 현재 CORS 설정 확인
curl -I -H "Origin: https://evil.com" \
  http://localhost:8080/api/v2/characters/test

# 2. 응답 헤더 확인
# Access-Control-Allow-Origin: https://evil.com  <- 문제!

# 3. 긴급 수정: Nginx에서 Origin 차단
nginx -s reload
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. 와일드카드 사용
configuration.setAllowedOriginPatterns(List.of("*"));  // 문제!

// 2. Credentials 허용
configuration.setAllowCredentials(true);  // *와 조합 시 치명적

// 3. 환경별 구분 없음
// 모든 환경에서 동일한 설정
```

#### 해결 및 복구 (60-120분)
```java
// 1. 환경별 설정 분리
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {
    @NotEmpty
    private List<@ValidCorsOrigin String> allowedOrigins;
}

// 2. 시작 시 검증
@PostConstruct
public void validateOnStartup() {
    for (String origin : allowedOrigins) {
        if (origin.contains("*")) {
            throw new IllegalStateException("Wildcard not allowed");
        }
    }
}

// 3. 런타임 검증 필터 추가
@Bean
public CorsValidationFilter corsValidationFilter() { ... }
```

### Playbook 4: 데이터 유출 (Data Exposure)

**관련 사고:** P0 #287 (DLQ 누락으로 데이터 손실)

#### 증상
- 로그에 민감 정보 포함
- API 응답에 불필요한 데이터 포함
- GDPR/개인정보보호법 위반 가능

#### 즉시 조치 (0-15분)
```bash
# 1. 로그 스트리밍 중단
systemctl stop log-forwarder

# 2. 로그 파일 백업
cp /var/log/app.log /var/log/app.log.backup-$(date +%Y%m%d)

# 3. 민정보 포함 로그 확인
grep -i "apikey\|password\|token" /var/log/app.log
```

#### 근본 원인 분석 (15-60분)
```java
// 확인 포인트
// 1. Record toString() 미오버라이드
public record LoginRequest(String apiKey, String password) {}
// 로그: LoginRequest[apiKey=live_..., password=...]

// 2. TaskContext에 민감 정보 포함
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", apiKey));  // 로그에 노출

// 3. 예외 메시지에 민감 정보 포함
throw new InvalidApiKeyException("Invalid key: " + apiKey);
```

#### 해결 및 복구 (60-120분)
```java
// 1. Record toString() 오버라이드
public record LoginRequest(String apiKey, String password) {
    @Override
    public String toString() {
        return "LoginRequest[apiKey=" + maskApiKey(apiKey) + ", password=***]";
    }
}

// 2. TaskContext 마스킹
executor.execute(() -> service.process(apiKey),
    TaskContext.of("Service", "Process", maskApiKey(apiKey)));

// 3. 로그 필터 추가
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
        <evaluator>
            <expression>
                throwable != null &amp;&amp;
                throwable.getMessage() != null &amp;&amp;
                throwable.getMessage().contains("apiKey")
            </expression>
        </evaluator>
        <onMismatch>DENY</onMismatch>
    </filter>
</appender>
```

---

## 41. Communication Protocol

사고 발생 시 대내외 커뮤니케이션 규칙입니다.

### 내부 커뮤니케이션

| 시점 | 채널 | 내용 | 빈도 |
|------|------|------|------|
| **감지** | Discord #security | 사고 발생, 영향도 | 즉시 |
| **분석** | Discord #security | 진행 상황, 예상 시간 | 15분마다 |
| **복구** | Discord #security | 복구 완료, 후속 조치 | 1시간마다 |
| **종료** | Discord #general | 전체 공지 | 1회 |

### 대외 커뮤니케이션

| 상황 | 대상 | 채널 | Template |
|------|------|------|----------|
| **P0 사고** | 사용자 | Discord 공지 | [Incident Notice Template](../98_Templates/incident-notice.md) |
| **개인정보 유출** | 당국 | 이메일/FAX | [Breach Notification](../98_Templates/breach-notification.md) |
| **보안 패치** | 사용자 | Release Note | [Security Advisory](../98_Templates/security-advisory.md) |

### 공개 타임라인

```
T+0분:   사고 감지, 내부 알림
T+15분:  영향도 파악 완료, IRT 구성
T+30분:  첫 번째 상황 업데이트 (Discord)
T+60분:  대응 방침 결정, 복구 시작
T+120분: 복구 완료, 서비스 정상화
T+24시간: RCA 초안 작성, 내부 공유
T+72시간: RCA 완료, 대외 공개 (필요시)
```

---

## 42. Evidence Collection & Forensics

증거 수집 및 포렌식 절차입니다.

### 증거 수집 체크리스트

- [ ] 로그 파일 (`/var/log/app.log`, `/var/log/nginx/access.log`)
- [ ] 메트릭 데이터 (Prometheus snapshot)
- [ ] 애플리케이션 덤프 (`jcmd <pid> GC.heap_dump`)
- [ ] 네트워크 패킷 캡처 (tcpdump)
- [ ] Redis 백업 (`redis-cli SAVE`)
- [ ] DB 백업 (`mysqldump --single-transaction`)
- [ ] 배포 기록 (`git log`, `kubectl rollout history`)

### 로그 보존 명령어

```bash
# 1. 애플리케이션 로그
cp /var/log/app.log /evidence/app-$(date +%Y%m%d-%H%M%S).log
chmod 400 /evidence/app-*.log

# 2. Nginx 로그
cp /var/log/nginx/access.log /evidence/nginx-access-$(date +%Y%m%d-%H%M%S).log

# 3. Prometheus 스냅샷
curl http://localhost:9090/api/v1/admin/tsdb/snapshot \
  -o /evidence/prometheus-snapshot.json

# 4. Redis 백업
redis-cli --rdb /evidence/redis-$(date +%Y%m%d-%H%M%S).rdb

# 5. Git 상태
git rev-parse HEAD > /evidence/git-commit.txt
git diff > /evidence/git-diff.txt
```

---

## 43. Root Cause Analysis Template

RCA 작성 템플릿입니다.

### RCA 구조 (5-Whys + Fishbone)

```markdown
# Security Incident RCA: [INCIDENT-TITLE]

## 1. Executive Summary
- **Date:** 2025-MM-DD HH:MM KST
- **Duration:** X hours (detection to resolution)
- **Impact:** [description]
- **Root Cause:** [one-line summary]

## 2. Timeline
| Time (KST) | Event |
|------------|-------|
| 10:00 | Alert: authentication failure rate > 50% |
| 10:05 | IRT activated |
| 10:15 | Root cause identified: ... |
| 11:00 | Hotfix deployed |
| 11:15 | Verification complete |
| 12:00 | Normal operation restored |

## 3. Impact Assessment
- **Users Affected:** X
- **Data Compromised:** [Yes/No]
- **Service Downtime:** X minutes
- **Financial Impact:** [if applicable]

## 4. Root Cause Analysis (5 Whys)
1. Why did authentication fail?
   -> Because JWT filter was not processing tokens.

2. Why was JWT filter not processing?
   -> Because filter was registered with @Component (CGLIB proxy).

3. Why was @Component used?
   -> Because Best Practice was not documented.

4. Why was Best Practice not documented?
   -> Because SecurityConfig documentation was incomplete.

5. Why was documentation incomplete?
   -> **Root Cause:** No security documentation review process.

## 5. Fishbone Diagram
[사람/프로세스/기술/환경 4M 분석]

## 6. Immediate Actions (Hotfix)
- [ ] Remove @Component from filter
- [ ] Register filter as @Bean
- [ ] Deploy to production

## 7. Long-term Actions (Prevention)
- [ ] Update CLAUDE.md Section 18 (Filter Bean registration)
- [ ] Add security unit test
- [ ] Implement pre-commit security check

## 8. Lessons Learned
1. Spring Security 6.x Filter registration requires @Bean, not @Component
2. CGLIB proxy causes logger NPE in OncePerRequestFilter subclasses
3. Security documentation should be comprehensive and reviewed

## 9. Follow-up Items
| Item | Owner | Due Date | Status |
|------|-------|----------|--------|
| Documentation update | Security Lead | 2025-MM-DD | Pending |
| Security test enhancement | QA Lead | 2025-MM-DD | Pending |
| Team training | Tech Lead | 2025-MM-DD | Pending |
```

---

## Evidence Links

- **P0 Incidents:** `docs/05_Reports/P0_Issues_Resolution_Report_2026-01-20.md` (Evidence: [P0-REPORT-001])
- **Security Playbooks:** `docs/98_Templates/` (Evidence: [TEMPLATE-001])
- **Incident Notifications:** Discord #security-incidents (Evidence: [COMM-001])

## Technical Validity Check

This guide would be invalidated if:
- **Contact information outdated:** Verify IRT members list
- **Commands not working:** Test evidence collection commands quarterly
- **Playbooks not tested:** Conduct tabletop exercises quarterly

### Verification Commands

```bash
# 증거 수집 스크립트 테스트
bash scripts/security/collect-evidence.sh --dry-run

# IRT 연락처 확인
cat scripts/security/irt-contacts.yaml

# 알림 시스템 테스트
curl -X POST $DISCORD_WEBHOOK -d '{"content": "Security incident test"}'
```

### Related Evidence
- Incident Reports: `docs/05_Reports/security-incidents/`
- Playbooks: `docs/98_Templates/security-playbooks.md`
- IRT Charter: `docs/security/irt-charter.md`
