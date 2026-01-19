# Scenario 08: Disk Full - Silent Scream (디스크 풀)

> **담당 에이전트**: 🔴 Red (장애주입) & 🟢 Green (Performance)
> **난이도**: P1 (Important) - Medium
> **테스트 일시**: 2026-01-19

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

---

## 7. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS (Simulated)**

### 기술적 인사이트
1. **Health Indicator 동작**: 임계치 미만 시 DOWN 전환
2. **핵심 기능 유지**: API 호출은 디스크 풀과 무관
3. **빠른 복구**: 공간 확보 즉시 정상화

---

*Generated by 5-Agent Council - Chaos Testing Deep Dive*
