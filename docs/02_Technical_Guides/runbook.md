# MapleExpectation 운영 가이드 (Runbook)

> **Last Updated:** 2026-02-05
> **Documentation Version:** 1.0
> **Production Status:** Active (Validated through P0/P1 incident responses)

## Terminology

| 용어 | 정의 |
|------|------|
| **ExternalServiceException** | 넥슨 API 장애 예외 |
| **RateLimitExceededException** | API 호출 한도 초과 예외 |
| **Circuit Breaker** | 장애 확산 방지 패턴 |
| **Graceful Degradation** | 장애 시 서비스 가용성 유지 |

## Documentation Integrity Statement

This runbook is based on **production incident response** from 2025-2026:
- P0 incidents: 23 resolution procedures validated (Evidence: [P0 Report](../04_Reports/P0_Issues_Resolution_Report_2026-01-20.md))
- P1 nightmare issues: 7 distributed system problems resolved (Evidence: [P1 Report](../04_Reports/P1_Nightmare_Issues_Resolution_Report.md))
- Graceful shutdown: 100% data preservation during deployments (Evidence: [ADR-008](../adr/ADR-008-durability-graceful-shutdown.md))

---

## 1. 장애 대응 매뉴얼

### 1.1 ExternalServiceException (Nexon API 장애)

> **Production Frequency:** Average 2-3 incidents/month (Nexon API maintenance)
> **MTTR Target:** < 5 minutes (cache provides 15-minute stale data)
> **Validation:** Scenario A/B/C tested in N05, N06 chaos tests (Evidence: [Chaos Results](../01_Chaos_Engineering/06_Nightmare/Results/)).

**증상:**
- 로그: `[ERROR] ExternalServiceException: Nexon API call failed`
- 영향: 캐릭터 조회, 장비 조회 실패

**조치:**
1. Nexon API 상태 확인: https://openapi.nexon.com/
2. Circuit Breaker 상태 확인: `/actuator/health`
3. 임시 조치: 캐시된 데이터 반환 (Graceful Degradation)

### 1.2 RateLimitExceededException (429)

**증상:**
- HTTP 429 응답
- 헤더: `Retry-After: {seconds}`

**조치:**
1. 클라이언트에게 재시도 안내
2. IP/User별 요청량 모니터링
3. 필요 시 Rate Limit 임계값 조정

### 1.3 Redis 장애

> **Production Incident:** P0 #73 - Redis failover caused 30s outage before Circuit Breaker implementation.
> **Current MTTR:** < 10 seconds (automatic failover + MySQL fallback)
> **Validation:** N01 (redis-death) chaos test passed (Evidence: [N01 Results](../01_Chaos_Engineering/01_Core/01-redis-death.md)).

**증상:**
- 로그: `[WARN] Redis connection failed, using Fail-Open`
- 영향: 캐시 MISS 증가, DB 부하 상승

**조치:**
1. Redis 서버 상태 확인
2. Sentinel failover 확인
3. 임시 조치: 서비스는 Fail-Open으로 계속 동작
4. MySQL Named Lock Pool 모니터링 (`MySQLLockPool` - Fixed Size: 30)

### 1.4 MySQL Named Lock Pool 고갈

**증상:**
- 로그: `HikariPool-MySQLLockPool - Connection is not available`
- 영향: 락 획득 실패, 동시성 제어 불가

**조치:**
1. Redis 복구 우선 시도 (근본 원인)
2. Pool 상태 확인: `/actuator/metrics/hikaricp.connections`
3. 비상시 Pool Size 임시 증설 (환경변수 또는 재배포)

## 2. 모니터링 체크리스트

### 2.1 Health Check 엔드포인트
- [ ] `/actuator/health` - 서비스 상태
- [ ] `/actuator/prometheus` - 메트릭
- [ ] `/actuator/info` - 앱 정보

### 2.2 핵심 메트릭

| 메트릭 | 정상 범위 | 경고 임계값 |
|--------|----------|------------|
| `http_server_requests_seconds` | < 500ms | > 1s |
| `cache.hit{layer=L1}` | > 80% | < 60% |
| `cache.hit{layer=L2}` | > 90% | < 70% |
| `hikaricp.connections.active` | < 80% pool | > 90% pool |
| `resilience4j.circuitbreaker.state` | CLOSED | OPEN |

### 2.3 JaCoCo 커버리지 리포트
- 로컬: `build/reports/jacoco/test/html/index.html`
- 목표: 핵심 서비스 60% 이상

## 3. 배포 체크리스트

### 3.1 배포 전
- [ ] `./gradlew clean test` All Green
- [ ] JaCoCo 커버리지 확인
- [ ] 환경변수 확인 (API Key, DB 연결정보)

### 3.2 배포 후
- [ ] `/actuator/health` 정상 응답
- [ ] 로그 에러 없음 확인
- [ ] 주요 API 응답 시간 정상

## 4. 긴급 연락처

- 운영팀: Discord #ops-alerts
- 온콜: 당직 담당자

## 5. 롤백 절차

### 5.1 Docker 환경
```bash
# 이전 버전으로 롤백
docker-compose down
docker-compose -f docker-compose.rollback.yml up -d
```

### 5.2 Kubernetes 환경
```bash
# 이전 리비전으로 롤백
kubectl rollout undo deployment/maple-expectation
```

## Evidence Links
- **GlobalExceptionHandler:** `src/main/java/maple/expectation/global/error/GlobalExceptionHandler.java` (Evidence: [CODE-ERROR-001])
- **DiscordAlertService:** `src/main/java/maple/expectation/service/v2/alert/DiscordAlertService.java` (Evidence: [CODE-ALERT-001])
- **Actuator Config:** `src/main/resources/application.yml` (management 섹션) (Evidence: [CONF-ACTUATOR-001])
- **P0 Report:** `docs/04_Reports/P0_Issues_Resolution_Report_2026-01-20.md` (Incident response validation)

## Technical Validity Check

This runbook would be invalidated if:
- **Incident response procedures don't work**: Compare with actual production logs
- **Rollback procedures don't match environment**: Verify deployment environment
- **Metrics not collected**: Verify Actuator endpoints
- **Discord alerts not firing**: Test alert endpoint

### Verification Commands
```bash
# Actuator 엔드포인트 확인
curl http://localhost:8080/actuator/health

# 메트릭 확인
curl http://localhost:8080/actuator/metrics

# Discord 알림 설정 확인
grep -A 10 "discord" src/main/resources/application-*.yml

# Circuit Breaker 상태 확인
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

### Related Evidence
- P0 Report: `docs/04_Reports/P0_Issues_Resolution_Report_2026-01-20.md`
- P1 Report: `docs/04_Reports/P1_Nightmare_Issues_Resolution_Report.md`
- ADR-008: `docs/adr/ADR-008-durability-graceful-shutdown.md`
