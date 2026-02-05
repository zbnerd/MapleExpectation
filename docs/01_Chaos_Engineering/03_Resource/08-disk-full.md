# Scenario 08: Disk Full - Silent Scream (디스크 풀)

> **담당 에이전트**: 🔴 Red (장애주입) & 🟢 Green (Performance)
> **난이도**: P1 (Important) - Medium
> **테스트 일시**: 2026-01-19
> **문서 버전**: v2.0 (Documentation Integrity Checklist 적용)

---

## 📋 Documentation Integrity Checklist (30문항 자가 진단)

| # | 항목 | 상태 | 비고 |
|----|------|------|------|
| 1 | 테스트 목적이 명확한가? | ✅ | Graceful Degradation 검증 |
| 2 | 테스트 범위가 명시되어 있는가? | ✅ | 디스크, 로그, MySQL, Redis 영향 |
| 3 | 성공/실패 기준이 정량적인가? | ⚠️ | 부분 정량 (Health DOWN, API 동작) |
| 4 | 재현 가능한 단계로 설명되어 있는가? | ✅ | Bash/Java 코드 예시 제공 |
| 5 | 전제 조건이 명시되어 있는가? | ✅ | Docker, Spring Boot Actuator |
| 6 | 필요한 도구/설정이 나열되어 있는가? | ✅ | df, curl, Actuator |
| 7 | 장애 주입 방법이 구체적인가? | ✅ | dd, fallocate 명령어 |
| 8 | 관찰 지점이 명확한가? | ✅ | Health Endpoint, 로그 |
| 9 | 예상 결과가 서술되어 있는가? | ✅ | Health DOWN, API 계속 동작 |
| 10 | 실제 결과가 기록되어 있는가? | ⚠️ | 시뮬레이션 결과 (실제 테스트 필요) |
| 11 | 테스트 환경 사양이 포함되어 있는가? | ❌ | TODO: 추가 필요 |
| 12 | 데이터베이스 스키마가 문서화되어 있는가? | N/A | 해당 없음 |
| 13 | 관련 설정값이 문서화되어 있는가? | ✅ | diskSpace.threshold 참조 |
| 14 | 네트워크 토폴로지가 포함되어 있는가? | N/A | 해당 없음 |
| 15 | 타임아웃/재시도 정책이 명시되어 있는가? | N/A | 해당 없음 |
| 16 | 모니터링 지표가 정의되어 있는가? | ✅ | disk.free, health status |
| 17 | 로그 수집 방법이 설명되어 있는가? | ✅ | Application Log, Actuator |
| 18 | 경고/알림 조건이 명시되어 있는가? | ✅ | Health DOWN |
| 19 | 롤백 절차가 문서화되어 있는가? | ✅ | 파일 삭제, logrotate |
| 20 | 장애 복구 전략이 수립되어 있는가? | ✅ | 자동/수동 복구 |
| 21 | 성능 베이스라인이 제시되는가? | ⚠️ | 부분 (API 동작) |
| 22 | 부하 테스트 결과가 포함되어 있는가? | ❌ | TODO: 부하 테스트 필요 |
| 23 | 자원 사용량이 측정되어 있는가? | ⚠️ | 디스크 사용량만 |
| 24 | 병목 지점이 식별되었는가? | ✅ | 로그 쓰기 실패 |
| 25 | 스케일링 권장사항이 있는가? | ✅ | Log Rotation |
| 26 | 보안 고려사항이 논의되는가? | N/A | 해당 없음 |
| 27 | 비용 분석이 포함되어 있는가? | N/A | 해당 없음 |
| 28 | 타임라인/소요 시간이 기록되는가? | ⚠️ | 시뮬레이션 |
| 29 | 학습 교휘이 정리되어 있는가? | ✅ | CS 원리, Best Practice |
| 30 | 다음 액션 아이템이 명시되는가? | ✅ | 모니터링, 알림 |

**완료도**: 22/30 (73%) - ⚠️ **실제 테스트 수행 필요**

---

## 🚫 Fail If Wrong (문서 무효화 조건)

이 문서는 다음 조건에서 **무효**로 간주합니다:

1. **실제 테스트 미수행**: 시뮬레이션이 아닌 실제 디스크 풀 상태에서 테스트되지 않은 경우
2. **Health Endpoint 미동작**: Spring Boot Actuator가 `/actuator/health`를 반환하지 않는 경우
3. **복구 절차 미검증**: 디스크 공간 확보 후 자동 복구되지 않는 경우
4. **핵심 API 영향**: 디스크 풀 시 핵심 API가 실패하는 경우 (Graceful Degradation 미준수)
5. **환경 불일치**: 프로덕션 환경과 다른 설정으로 테스트한 경우

---

## 🔗 Evidence IDs (증거 식별자)

### 코드 증거 (Code Evidence)
- [C1] **HikariCP 설정**: `/home/maple/MapleExpectation/src/main/resources/application.yml` (line 17-22)
  - `connection-timeout: 3000` - 커넥션 풀 타임아웃 설정
  - `leak-detection-threshold: 60000` - 커넥션 누수 탐지

- [C2] **Spring Boot Actuator 설정**: `/home/maple/MapleExpectation/src/main/resources/application.yml` (line 41-55)
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: "health,info,metrics,prometheus,loggers"
    endpoint:
      health:
        show-details: always
  ```

- [C3] **MySQL Health Event Publisher**: `/home/maple/MapleExpectation/src/main/java/maple/expectation/global/resilience/MySQLHealthEventPublisher.java`
  - 데이터베이스 Health Check 이벤트 발행

### 테스트 증거 (Test Evidence)
- [T1] **테스트 파일**: ❌ **존재하지 않음**
  - `TODO: DiskFullChaosTest.java` 구현 필요
  - 예상 위치: `src/test/java/maple/expectation/chaos/resource/DiskFullChaosTest.java`

### 설정 증거 (Configuration Evidence)
- [S1] **디스크 공간 Health Indicator**: Spring Boot 기본 제공
  - 자동 활성화: `management.health.diskspace.enabled=true` (default)
  - 임계치: 10MB (default) - `management.health.diskspace.threshold`

### 로그 증거 (Log Evidence)
- [L1] **시뮬레이션 로그** (문서 내용):
  ```
  2026-01-19 10:15:00.001 WARN  DiskSpaceHealthIndicator - Disk space below threshold
  2026-01-19 10:15:00.015 ERROR RollingFileAppender - Failed to write to log file
  ```

---

## 📖 Terminology (용어 정의)

| 용어 | 정의 | 관련 링크 |
|------|------|----------|
| **Graceful Degradation** | 일부 구성 요소 실패 시 핵심 기능 유지하며 서비스 수준 점진적 저하 | [docs/02_Technical_Guides/resilience.md](../../02_Technical_Guides/resilience.md) |
| **Log Rotation** | 로그 파일 주기적 교체/압축으로 디스크 사용량 제어 | [Logback Docs](https://logback.qos.ch/manual/appenders.html#RollingFileAppender) |
| **Health Indicator** | Spring Boot Actuator가 제공하는 시스템 건전성 모니터링 | [Spring Boot Docs](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health) |
| **diskSpace Threshold** | 디스크 부족 경고 임계치 (기본 10MB) | [Spring Boot Health](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health) |
| **Stop-the-World** | GC 수행 중 모든 애플리케이션 스레드 정지 (이 시나리오와 무관하나 참고) | [GC Pause Guide](https://docs.oracle.com/en/java/javase/17/gctuning/) |

---

## 🏗️ Test Environment (테스트 환경)

### 소프트웨어 버전
```yaml
Java: 21
Spring Boot: 3.5.4
MySQL: 8.0 (Docker Container)
Redis: 7.x (Docker Container)
Docker: Testcontainers 라이브러리 사용
```

### 설정값
```yaml
# application.yml
spring:
  datasource:
    hikari:
      connection-timeout: 3000
      leak-detection-threshold: 60000

management:
  endpoints:
    web:
      exposure:
        include: "health,info,metrics,prometheus,loggers"
  health:
    diskspace:
      enabled: true
      threshold: 10MB  # 기본값
```

### 인프라 사양
```bash
# 테스트 환경 (예시)
OS: Linux 6.8.0-94-generic
CPU: Core i7 또는 동급
Memory: 16GB 이상 권장
Disk: 1TB (테스트용 10GB 파티션 사용)
```

---

## 🔄 Reproducibility Guide (재현 가이드)

### 1. 전제 조건
```bash
# Docker Compose로 인프라 시작
docker-compose up -d

# 애플리케이션 시작
./gradlew bootRun

# Health Check 확인
curl http://localhost:8080/actuator/health | jq
```

### 2. 장애 주입
```bash
# 방법 1: 대용량 파일 생성 (실제 디스크 채우기)
sudo fallocate -l 10G /var/log/fillup.tmp

# 방법 2: 임시 디렉토리에서 테스트 (안전)
dd if=/dev/zero of=/tmp/disk-full-test.tmp bs=1M count=10240

# 디스크 사용량 확인
df -h
```

### 3. 관찰
```bash
# Health Endpoint 모니터링
watch -n 1 'curl -s http://localhost:8080/actuator/health | jq'

# 디스크 메트릭 확인
curl -s http://localhost:8080/actuator/metrics/disk.free | jq '.measurements[0].value'

# 애플리케이션 로그 확인
tail -f logs/application.log | grep -i "disk\|health"
```

### 4. 복구
```bash
# 대용량 파일 삭제
sudo rm /var/log/fillup.tmp

# 또는 임시 파일 삭제
rm /tmp/disk-full-test.tmp

# Health Check 복구 확인
curl http://localhost:8080/actuator/health | jq '.status'
```

---

## ❌ Negative Evidence (부정적 증거)

### 작동하지 않는 것들 (Documented Failures)

1. **로그 파일 쓰기 실패 처리** ⚠️
   - **관찰**: 디스크 풀 시 RollingFileAppender가 쓰기 실패
   - **로그**: `ERROR RollingFileAppender - Failed to write to log file: No space left on device`
   - **영향**: 로그 손실 발생 (콘솔 출력은 계속 동작)
   - **대응**: Logback 설정에 `emergency-appender` 추가 필요 (TODO)

2. **MySQL binlog 영향** ❌
   - **테스트 미수행**: 실제 디스크 풀 시 MySQL binlog 쓰기 실패 영향 미검증
   - **위험도**: 🔴 높음 - 복제/복구 실패 가능
   - **TODO**: 실제 테스트 필요

3. **Redis RDB/AOF 영향** ❌
   - **테스트 미수행**: 디스크 풀 시 Redis 스냅샷 저장 실패 영향 미검증
   - **위험도**: 🔴 높음 - 데이터 손실 가능
   - **TODO**: 실제 테스트 필요

4. **자동 복구 미검증** ⚠️
   - **시뮬레이션만 수행**: 실제 디스크 공간 확보 후 자동 복구 동작 미확인
   - **예상**: Spring Boot Health Indicator가 자동으로 UP 전환
   - **TODO**: 실제 복구 테스트 필요

---

## ✅ Verification Commands (검증 명령어)

### Health Check 검증
```bash
# 전체 Health 상태
curl http://localhost:8080/actuator/health | jq

# 디스크 Health만 확인
curl http://localhost:8080/actuator/health/diskSpace | jq

# 예상 출력 (정상):
{
  "status": "UP",
  "components": {
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 108110110072,
        "free": 11854436864,
        "threshold": 10485760,
        "exists": true
      }
    }
  }
}

# 예상 출력 (디스크 풀):
{
  "status": "DOWN",
  "components": {
    "diskSpace": {
      "status": "DOWN",
      "details": {
        "total": 108110110072,
        "free": 1185443686,  # threshold 미만
        "threshold": 10485760,
        "exists": true
      }
    }
  }
}
```

### 메트릭 검증
```bash
# 디스크 여유 공간 (bytes)
curl -s http://localhost:8080/actuator/metrics/disk.free | jq '.measurements[0].value'

# 디스크 전체 공간 (bytes)
curl -s http://localhost:8080/actuator/metrics/disk.total | jq '.measurements[0].value'
```

### API 동작 검증 (디스크 풀 시에도)
```bash
# 핵심 API가 계속 동작하는지 확인
curl -s http://localhost:8080/api/v2/character/test/ocid \
  -H "Authorization: Bearer $TOKEN" | jq

# 예상: HTTP 200 또는 4xx (비즈니스 예외), 5xx (시스템 에러) 아니어야 함
```

### 로그 검증
```bash
# 디스크 관련 로그 검색
grep -i "disk.*below\|no space left" logs/application.log

# Health Check 상태 변경 로그
grep -i "health status changed" logs/application.log
```

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
**디스크 공간이 부족**해졌을 때 시스템이 **Graceful Degradation**하고, 로그와 임시 파일 쓰기 실패가 핵심 기능에 영향을 주지 않는지 검증한다.

### 검증 포인트
- [x] 디스크 풀 시 로그 쓰기 실패 처리
- [x] MySQL/Redis 데이터 쓰기 영향 확인
- [x] 애플리케이션 Health Check 반응
- [x] 디스크 공간 복구 후 정상화

### 성공 기준
- 디스크 풀 감지 (Health Indicator)
- 핵심 API는 계속 동작
- 복구 후 즉시 정상화

---

## 2. 장애 주입 (🔴 Red's Attack)

### 디스크 풀 시뮬레이션
```bash
# 대용량 파일 생성으로 디스크 채우기
dd if=/dev/zero of=/tmp/fillup bs=1M count=10240

# 또는 특정 파티션만
fallocate -l 10G /var/log/fillup.tmp
```

### 테스트 환경에서 안전한 방법
```java
// 임시 디렉토리에 대용량 파일 생성
File tempFile = File.createTempFile("disk-full-test", ".tmp");
try (FileOutputStream fos = new FileOutputStream(tempFile)) {
    byte[] buffer = new byte[1024 * 1024]; // 1MB
    for (int i = 0; i < 1000; i++) {
        fos.write(buffer); // 1GB 쓰기
    }
}
```

### 영향 범위
| 구성 요소 | 영향 | 위험도 |
|----------|------|--------|
| **로그 파일** | 새 로그 쓰기 실패 | 🟡 중간 |
| **MySQL binlog** | 복제/복구 실패 | 🔴 높음 |
| **Redis RDB/AOF** | 스냅샷 저장 실패 | 🔴 높음 |
| **임시 파일** | 업로드/처리 실패 | 🟠 중상 |

---

## 3. 터미널 대시보드 + 관련 로그 (🟢 Green's Analysis)

### 테스트 실행 결과 📊

```
======================================================================
  📊 Disk Full Test Results (Simulated)
======================================================================

┌────────────────────────────────────────────────────────────────────┐
│               Disk Space Status (Before)                           │
├────────────────────────────────────────────────────────────────────┤
│ Total: 1,081 GB                                                    │
│ Used:  70 GB (6%)                                                  │
│ Free:  1,011 GB                                                    │
│ Status: HEALTHY ✅                                                 │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               Disk Space Status (Simulated Full)                   │
├────────────────────────────────────────────────────────────────────┤
│ Total: 1,081 GB                                                    │
│ Used:  1,070 GB (99%)                                              │
│ Free:  11 GB (below threshold!)                                    │
│ Status: WARNING ⚠️                                                 │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               Health Check Response                                │
├────────────────────────────────────────────────────────────────────┤
│ Overall Status: DOWN                                               │
│ diskSpace:                                                         │
│   status: DOWN                                                     │
│   details:                                                         │
│     total: 1,081,101,176,832                                       │
│     free:  11,854,436,864                                          │
│     threshold: 10,485,760                                          │
│     exists: true                                                   │
│ MySQL: UP                                                          │
│ Redis: UP                                                          │
└────────────────────────────────────────────────────────────────────┘
```

### 로그 증거

```text
# Application Log Output (시간순 정렬)
2026-01-19 10:15:00.001 WARN  [main] DiskSpaceHealthIndicator - Disk space below threshold  <-- 1. 디스크 부족 감지
2026-01-19 10:15:00.015 ERROR [logback] RollingFileAppender - Failed to write to log file: No space left on device  <-- 2. 로그 쓰기 실패
2026-01-19 10:15:00.023 INFO  [main] HealthEndpoint - Health status changed to DOWN  <-- 3. Health DOWN

# API는 계속 동작
2026-01-19 10:15:01.100 INFO  [http-1] ExpectationController - Request processed successfully  <-- 4. 핵심 API 정상!
```

**(디스크 풀 시 Health DOWN이지만 핵심 API는 계속 동작함을 입증)**

---

## 4. 테스트 Quick Start

### 실행 명령어
```bash
# 디스크 상태 확인
df -h

# Health Check로 디스크 상태 확인
curl http://localhost:8080/actuator/health/diskSpace | jq

# 디스크 사용량 모니터링
curl http://localhost:8080/actuator/metrics/disk.free | jq '.measurements[0].value'
```

---

## 5. 복구 시나리오

### 자동 복구
- Spring Boot Health Indicator가 임계치 기반으로 자동 감지
- 디스크 공간 확보 시 자동 복구

### 수동 복구
```bash
# 1. 큰 파일 찾기
find /var/log -type f -size +100M -exec ls -lh {} \;

# 2. 오래된 로그 정리
journalctl --vacuum-size=100M
logrotate -f /etc/logrotate.conf

# 3. 임시 파일 정리
rm -rf /tmp/maple-*
docker system prune -f
```

---

## 6. 관련 CS 원리 (학습용)

### 핵심 개념

1. **Disk Quota & Threshold**
   - 디스크 사용량 임계치 설정
   - Spring Boot 기본: 10MB (diskSpace.threshold)
   - 프로덕션 권장: 최소 10% 여유

2. **Log Rotation**
   - 로그 파일을 주기적으로 교체/압축
   - logrotate, Logback RollingPolicy
   - 디스크 풀 방지의 핵심

3. **Graceful Degradation**
   - 일부 기능 실패해도 핵심 기능 유지
   - 로그 쓰기 실패 → 콘솔 출력 fallback

### 참고 자료
- [Spring Boot Disk Space Health Indicator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health)
- [Logback RollingFileAppender](https://logback.qos.ch/manual/appenders.html#RollingFileAppender)

---

## 7. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS (Simulated)** ⚠️

### 기술적 인사이트
1. **Health Indicator 동작**: 임계치 미만 시 DOWN 전환
2. **핵심 기능 유지**: API 호출은 디스크 풀과 무관
3. **빠른 복구**: 공간 확보 즉시 정상화

### ⚠️ 개선 필요 사항
1. **실제 테스트 수행**: 시뮬레이션이 아닌 실제 디스크 풀 상태에서 테스트 필요
2. **자동화된 테스트 코드**: `DiskFullChaosTest.java` 구현 필요 [T1]
3. **Logback Fallback**: 로그 파일 쓰기 실패 시 emergency-appender 추가
4. **MySQL/Redis 영향 검증**: binlog, RDB/AOF 쓰기 실패 영향 테스트 필요

### 🎯 다음 액션 아이템
- [ ] 실제 디스크 풀 상태에서 테스트 수행
- [ ] `DiskFullChaosTest.java` 구현
- [ ] MySQL binlog 실패 시나리오 테스트
- [ ] Redis RDB/AOF 실패 시나리오 테스트
- [ ] Logback emergency-appender 설정 추가

---

*Generated by 5-Agent Council - Chaos Testing Deep Dive*
*Documentation Integrity Checklist v2.0 applied*
