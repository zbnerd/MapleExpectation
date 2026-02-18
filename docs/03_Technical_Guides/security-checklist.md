# Security Audit Checklist

> **상위 문서:** [CLAUDE.md](../../CLAUDE.md)
> **관련 문서:** [Security Hardening](security-hardening.md) | [Security Testing](security-testing.md) | [Incident Response](security-incident-response.md)
>
> **Last Updated:** 2026-02-11
> **Applicable Versions:** All Production Systems
> **Documentation Version:** 1.0
> **Compliance:** OWASP ASVS v4.0, PCI DSS, SOC 2

이 문서는 MapleExpectation 프로젝트의 보안 감사 체크리스트를 정의합니다.

## Documentation Integrity Statement

This checklist is based on:
- OWASP Application Security Verification Standard (ASVS) v4.0
- Production security audit findings from 2025 Q4
- Best practices from Spring Security 6.x documentation

## How to Use This Checklist

1. **Frequency:** Quarterly security audits
2. **Responsibility:** Security Lead + Development Team
3. **Evidence:** Each item requires evidence (code, config, or screenshot)
4. **Scoring:** Pass/Fail for each item; 100% Pass required for production release

---

## Section 1: Authentication & Authorization

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **A-001** | JWT Secret이 환경 변수로 설정됨 | `grep JWT_SECRET .env` | 환경 변수에 있음, 코드에 없음 | `.env` 파일 |
| **A-002** | JWT Secret 길이 >= 32자 | `echo $JWT_SECRET \| wc -c` | 32 이상 | 스크린샷 |
| **A-003** | Production 환경에서 기본 secret 미사용 | `grep "dev-secret" application-prod.yml` | 없음 | `application-prod.yml` |
| **A-004** | JWT HS256 알고리즘 사용 | 코드 확인 `Jwts.SIG.HS256` | HS256 사용 | `JwtTokenProvider.java` |
| **A-005** | 토큰 만료 시간 설정됨 | `grep expiration application.yml` | 15분 이하 (Production) | `application.yml` |
| **A-006** | Refresh Token 만료 시간 설정됨 | `grep refresh-expiry application.yml` | 7일 이하 (Production) | `application.yml` |
| **A-007** | API Key가 JWT에 포함되지 않음 | `grep "claim.*apiKey" JwtTokenProvider.java` | 없음 | 코드 검색 |
| **A-008** | Fingerprint로 API Key 식별 | `grep "claim.*fgp" JwtTokenProvider.java` | 있음 | `JwtTokenProvider.java` |
| **A-009** | Redis 세션에 API Key 저장됨 | `redis-cli HGETALL session:*` | apiKey 필드 존재 | Redis CLI |
| **A-010** | 보호된 엔드포인트에 인증 필요 | `SecurityConfig.java` 확인 | `.authenticated()` 설정 | `SecurityConfig.java` |
| **A-011** | Admin 엔드포인트에 ROLE_ADMIN 필요 | `SecurityConfig.java` 확인 | `.hasRole("ADMIN")` 설정 | `SecurityConfig.java` |
| **A-012** | Prometheus 엔드포인트 보호됨 | curl without auth | 403 응답 | curl 결과 |
| **A-013** | Actuator 엔드포인트 제한됨 | `grep requestMatchers.*actuator` | health/info만 허용 | `SecurityConfig.java` |

---

## Section 2: Input Validation & Sanitization

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **I-001** | Path Variable 정규식 검증 | `@Pattern` 어노테이션 확인 | 정규식 패턴 존재 | Controller 코드 |
| **I-002** | SQL Injection 방지 (JPA) | Parameterized Query 사용 | `:param` 또는 `?` 사용 | Repository 코드 |
| **I-003** | XSS 필터 설정됨 | CSP 헤더 확인 | `script-src 'self'` | HTTP 헤더 |
| **I-004** | Path Traversal 방지 | 입력값에 `..` 금지 | 검증 로직 존재 | `PathVariableValidationFilter` |
| **I-005** | Null Byte Injection 방지 | `\u0000` 필터링 | 필터링 로직 존재 | Input validation |
| **I-006** | Log4Shell mitigation | Log4j 버전 확인 | 2.17.1 이상 | `gradle dependencies` |
| **I-007** | 요청 크기 제한됨 | `max-file-size` 확인 | 10MB 이하 | `application.yml` |
| **I-008** | 파일 업로드 확장자 검증 | 허용 목록 방식 | `.jpg, .png` 등만 허용 | Upload handler |

---

## Section 3: Output Encoding & Data Protection

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **O-001** | 민감 데이터 로그 마스킹 | Record `toString()` 확인 | 마스킹된 값 출력 | DTO 코드 |
| **O-002** | API Key 로그 마스킹 | 로그 확인 | `****` 패턴 | Log 파일 |
| **O-003** | 비밀번호 로그 안남음 | `log.*password` 검색 | 없음 | `grep` 결과 |
| **O-004** | JWT 토큰 로그 마스킹 | 로그 확인 | 앞부분만 노출 | Log 파일 |
| **O-005** | 응답에 민정보 미포함 | API response 확인 | password 등 없음 | API 테스트 |
| **O-006** | 예외 메시지에 민감 정보 없음 | `throw new ...Exception` 확인 | 구체적 ID만 포함 | Exception 코드 |
| **O-007** | 에러 페이지 스택 트레이스 숨김 | Production 에러 페이지 | 일반 메시지만 | 브라우저 |
| **O-008** | Gson/Jackson 순환 참조 방지 | `@JsonIgnore` 확인 | 순환 참조 방지 | Entity 코드 |

---

## Section 4: Communication Security

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **C-001** | HTTPS enforced (Production) | https:// 접속 | 리다이렉트됨 | 브라우저 |
| **C-002** | HSTS 헤더 설정됨 | curl -I 확인 | `Strict-Transport-Security` | HTTP 헤더 |
| **C-003** | TLS 1.2+만 허용 | Nginx/ALB 설정 | TLSv1.2, TLSv1.3 | Nginx config |
| **C-004** | 강력한 Cipher Suite | SSL Labs Test | A+ 등급 | SSL Labs |
| **C-005** | HTTP 메서드 제한 | OPTIONS, TRACE 금지 | 405 응답 | curl -X OPTIONS |
| **C-006** | CORS 와일드카드 없음 | `setAllowedOriginPatterns` 확인 | 구체적 오리진만 | `SecurityConfig.java` |
| **C-007** | CORS credentials 제한됨 | `allowCredentials` 확인 | 특정 오리진만 | `SecurityConfig.java` |
| **C-008** | WebSocket 보안 설정됨 | STOMP over TLS | TLS 사용 | WebSocket config |

---

## Section 5: Cryptography

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **CR-001** | 암호화 알고리즘 강함 | AES-256, RSA-2048+ | 최신 알고리즘 | 코드 확인 |
| **CR-002** | IV/Nonce 랜덤 생성 | `SecureRandom` 사용 | 매번 다름 | 테스트 결과 |
| **CR-003** | 해시 함수 적절함 | bcrypt, Argon2, PBKDF2 | 솔트 사용 | Password encoder |
| **CR-004** | 키 저장 안전함 | 환경 변수/Secrets Manager | 코드에 없음 | `.env` 파일 |
| **CR-005** | 난수 생성기 안전함 | `SecureRandom` 사용 | `java.util.Random` 미사용 | 코드 검색 |
| **CR-006** | 타임스탬프 위변조 방지 | 서버 시간 사용 | 클라이언트 신뢰 안함 | Timestamp logic |

---

## Section 6: Session Management

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **S-001** | Session ID 랜덤 생성 | UUID 사용 | 예측 불가능 | Session code |
| **S-002** | Session 만료 설정됨 | Redis TTL 확인 | 15분 이하 | Redis CLI |
| **S-003** | Session 재생성 방지 | 로그아웃 시 삭제 | Redis에서 삭제됨 | Redis CLI |
| **S-004** | 고정 Session 방지 | 로그인 시 새 ID | 이전 ID 무효화 | 테스트 결과 |
| **S-005** | 동시 세션 제한 | Redis 세션 수 | 제한 있음 | Session service |
| **S-006** | Session 쿠키 설정 | Secure, HttpOnly, SameSite | 모두 설정 | Cookie 헤더 |
| **S-007** | CSRF 비활성화 (Stateless) | `.csrf().disable()` | JWT 사용 중 | `SecurityConfig.java` |
| **S-008** | Token 재사용 감지 | Redis token ID | 재사용 시 거부 | Session code |

---

## Section 7: Access Control

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **AC-001** | 최소 권한 원칙 | Role 확인 | 필요한 권한만 | `SecurityConfig.java` |
| **AC-002** | IDOR 방지 | 소유권 검증 | 자원 소유자만 | Service 코드 |
| **AC-003** | 권한 상승 방지 | Role 확인 불변 | 변경 불가 | Auth logic |
| **AC-004** | 열거 방지 | 에러 메시지 일반화 | 존재여부 노출 안함 | Error response |
| **AC-005** | Rate Limiting 설정됨 | 요청 제한 확인 | IP/User별 제한 | `RateLimitingFilter` |
| **AC-006** | IP Whitelist (Prometheus) | `PrometheusSecurityFilter` | 내부 IP만 | Filter 코드 |
| **AC-007** | X-Forwarded-For 검증 | 스푸핑 방지 | 신뢰 프록시만 | Filter 코드 |

---

## Section 8: Security Headers

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **H-001** | X-Frame-Options: DENY | curl -I 확인 | DENY | HTTP 헤더 |
| **H-002** | X-Content-Type-Options: nosniff | curl -I 확인 | nosniff | HTTP 헤더 |
| **H-003** | Strict-Transport-Security | curl -I 확인 | max-age=31536000 | HTTP 헤더 |
| **H-004** | Content-Security-Policy | curl -I 확인 | policyDirectives 존재 | HTTP 헤더 |
| **H-005** | X-XSS-Protection (legacy) | curl -I 확인 | 1; mode=block | HTTP 헤더 |
| **H-006** | Referrer-Policy | curl -I 확인 | strict-origin-when-cross-origin | HTTP 헤더 |
| **H-007** | Permissions-Policy | curl -I 확인 | 필요한 기능만 | HTTP 헤더 |

---

## Section 9: Error Handling & Logging

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **E-001** | 예외 처리 LogicExecutor 사용 | `try-catch` 검색 | 대부분 LogicExecutor | 코드 검색 |
| **E-002** | 보안 예외 Marker 구현 | CircuitBreakerIgnoreMarker | 비즈니스 예외에 있음 | Exception 코드 |
| **E-003** | 에러 로그에 스택 트레이스 | 5xx 에러 확인 | 스택 트레이스 존재 | Log 파일 |
| **E-004** | 4xx 에러는 WARN 레벨 | `log.warn` 확인 | 비즈니스 예외 | 코드 확인 |
| **E-005** | 보안 이벤트 로그됨 | 로그인, 권한거부 | 감사 로그 존재 | Log 파일 |
| **E-006** | TaskContext 사용 | 로그에 구조화 정보 | 도메인, 오퍼레이션 | Log 형식 |

---

## Section 10: Dependencies & Supply Chain

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **D-001** | 취약 라이브러리 없음 | `./gradlew dependencyCheck` | CVSS 7.0 이상 없음 | Report |
| **D-002** | 의존성 최신 버전 | `./gradlew versions` | 주요 업데이트 적용 | Gradle output |
| **D-003** | 라이선스 호환성 | `./gradlew licenseReport` | 허용 라이선스만 | Report |
| **D-004** | 서명된 빌드 | Gradle signing | 서명 검증됨 | Build output |
| **D-005** | SBOM 생성 | CycloneDX | SBOM 존재 | `sbom.json` |

---

## Section 11: Infrastructure Security

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **INF-001** | Docker 최신 베이스 이미지 | Dockerfile | Alpine/Debian 최신 | Dockerfile |
| **INF-002** | Container rootless 실행 | docker-compose.yml | User 지정 | Compose file |
| **INF-003** | Kubernetes RBAC | kubectl get rolebinding | 최소 권한 | kubectl output |
| **INF-004** | Network Policy 설정 | kubectl get networkpolicy | Pod 간 통신 제한 | NetworkPolicy |
| **INF-005** | Secret 암호화 | kubectl get secret | Encrypted 상태 | kubectl output |
| **INF-006** | AWS/Azure Security Hub | Dashboard | Critical 없음 | Dashboard |
| **INF-007** | WAF 설정됨 | AWS WAF | SQLi/XSS 규칙 | WAF config |

---

## Section 12: Data Protection

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **DP-001** | DB 암호화 at rest | RDS/Aurora 설정 | AES-256 | AWS Console |
| **DP-002** | 백업 암호화됨 | 백업 파일 | 암호화됨 | File test |
| **DP-003** | 개인정보 접근 로그 | DB audit log | 접근 기록됨 | Audit log |
| **DP-004** | 데이터 수명 주기 | TTL 설정 | 자동 삭제 | Redis/DB config |
| **DP-005** | GDPR 삭제 권한 | DELETE API | 데이터 완전 삭제 | API 테스트 |

---

## Section 13: Monitoring & Alerting

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **M-001** | 보안 메트릭 수집 | Prometheus | security_* 메트릭 | Prometheus UI |
| **M-002** | 인증 실패 알림 | AlertManager | Slack/Discord | Alert rule |
| **M-003** | Rate Limit 알림 | AlertManager | 임계값 초과 시 | Alert rule |
| **M-004** | 5xx 에러 알림 | AlertManager | 에러율 > 5% | Alert rule |
| **M-005** | 이상 징후 탐지 | 로그 분석 | 불가능한 패턴 탐지 | Dashboard |

---

## Section 14: Development Security

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **DEV-001** | Code Review 필수 | PR 규칙 | 1인 이상 승인 | PR history |
| **DEV-002** | 보안 테스트 통과 | CI Pipeline | 실패 시 머지 안됨 | CI log |
| **DEV-003** | SAST 실행됨 | SonarQube | Hotspot 없음 | SonarQube |
| **DEV-004** | Secret scanning | git-secrets | Commit 차단 | Git log |
| **DEV-005** | Pre-commit hook | husky/pre-commit | 테스트 실행 | `.git/hooks` |

---

## Section 15: Documentation & Training

| ID | 항목 | 검증 방법 | 기대 결과 | Evidence |
|----|------|----------|----------|----------|
| **DOC-001** | 보안 정책 문서화 | CLAUDE.md | 최신화됨 | Git log |
| **DOC-002** | 인시던트 플레이북 | Playbook 문서 | 시나리오별 정의 | Playbook file |
| **DOC-003** | 개발자 보안 교육 | Training record | 분기별 교육 | Training log |
| **DOC-004** | 온보딩 체크리스트 | Onboarding doc | 보안 항목 포함 | Onboarding guide |

---

## Scoring Summary

### Production Release Criteria

| 섹션 | 항목수 | 통과기준 | 실제 |
|------|--------|----------|------|
| Authentication & Authorization | 13 | 100% | __/13 |
| Input Validation | 8 | 100% | __/8 |
| Output Encoding | 8 | 100% | __/8 |
| Communication Security | 8 | 100% | __/8 |
| Cryptography | 6 | 100% | __/6 |
| Session Management | 8 | 100% | __/8 |
| Access Control | 7 | 100% | __/7 |
| Security Headers | 7 | 100% | __/7 |
| Error Handling | 6 | 100% | __/6 |
| Dependencies | 5 | 100% | __/5 |
| Infrastructure Security | 7 | 100% | __/7 |
| Data Protection | 5 | 100% | __/5 |
| Monitoring | 5 | 100% | __/5 |
| Development Security | 5 | 100% | __/5 |
| Documentation | 4 | 100% | __/4 |
| **TOTAL** | **107** | **100%** | **__/107** |

### Release Decision

- [ ] **APPROVED** - All items passed, ready for production
- [ ] **CONDITIONAL** - Minor deviations documented, exception approved
- [ ] **REJECTED** - Critical items failed, remediation required

---

## Automation Scripts

### Automated Check Script

```bash
#!/bin/bash
# scripts/security/audit-check.sh

echo "=== Security Audit Check ==="

# A-001: JWT Secret in environment
if grep -q "JWT_SECRET" .env 2>/dev/null; then
    echo "[PASS] A-001: JWT_SECRET in .env"
else
    echo "[FAIL] A-001: JWT_SECRET not found in .env"
fi

# A-004: HS256 Algorithm
if grep -q "Jwts.SIG.HS256" module-app/src/main/java/**/JwtTokenProvider.java; then
    echo "[PASS] A-004: HS256 algorithm used"
else
    echo "[FAIL] A-004: HS256 algorithm not found"
fi

# C-006: No wildcard CORS
if grep -r "setAllowedOriginPatterns.*\*" module-app/src/main/java/ | grep -v "^Binary"; then
    echo "[FAIL] C-006: Wildcard CORS found"
else
    echo "[PASS] C-006: No wildcard CORS"
fi

# I-002: SQL Injection prevention
if grep -r "Query.*\+.*'" module-app/src/main/java/ | grep -v "^Binary"; then
    echo "[WARN] I-002: Potential SQL injection (string concatenation)"
else
    echo "[PASS] I-002: No obvious SQL injection patterns"
fi

# O-001: Sensitive data masking
if grep -r "toString" module-app/src/main/java/**/dto/ | grep -v "mask"; then
    echo "[WARN] O-001: DTOs may have unmasked toString()"
else
    echo "[PASS] O-001: DTOs likely have masked toString()"
fi

echo "=== Check Complete ==="
```

### OWASP Dependency Check

```bash
# Run dependency vulnerability scan
./gradlew dependencyCheckAnalyze

# Check report
cat build/reports/dependency-check-report.html
```

---

## Evidence Links

- **Checklist History:** `docs/security/audit-history/` (Evidence: [AUDIT-HISTORY-001])
- **Automated Scans:** CI/CD Pipeline results (Evidence: [CI-SCAN-001])
- **Manual Review:** Security Lead approval (Evidence: [REVIEW-001])

## Technical Validity Check

This checklist is validated quarterly:
- **Next Review:** 2026-05-11
- **Last Review:** 2026-02-11
- **Validator:** Security Lead

### Verification Commands

```bash
# 전체 체크리스트 실행
bash scripts/security/audit-check.sh

# 의존성 취약점 스캔
./gradlew dependencyCheckAnalyze

# 보안 헤더 확인
curl -I http://localhost:8080/api/v2/characters/test

# OWASP ZAP 스캔
docker run -t --network=host owasp/zap2docker-stable zap-baseline.py \
  -t http://localhost:8080 -r zap-report.html
```

### Related Evidence
- Security Audit Report: `docs/05_Reports/security-audit-2025-Q4.md`
- OWASP Compliance: `docs/05_Reports/owasp-compliance.md`
