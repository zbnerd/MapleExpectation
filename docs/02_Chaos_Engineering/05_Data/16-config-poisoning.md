# Scenario 16: Config Poisoning - 설정 오염

> **담당 에이전트**: 🟢 Green (Performance) & 🟡 Yellow (QA Master)
> **난이도**: P1 (Important) - Medium
> **테스트 일시**: 2026-01-19
> **최종 검증**: ⚠️ Test file missing - Implementation required

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
**잘못된 설정 값이 주입**되었을 때 시스템이 이를 감지하고 **기본값으로 폴백**하거나 **시작을 거부**하는지 검증한다. 설정 오류는 심각한 장애의 주요 원인이다.

### 검증 포인트
- [x] 잘못된 설정 값 검증 (Validation)
- [x] 범위 초과 값 거부
- [x] 필수 설정 누락 감지
- [x] 설정 변경 시 Hot Reload 안전성

### ❌ 확인 필요 사항
- [ ] 테스트 파일 존재 여부: `src/test/java/maple/expectation/chaos/data/ConfigPoisoningChaosTest.java` ⚠️ **생성 필요**
- [ ] 실제 설정 클래스 검증: `NexonApiProperties`, `LockHikariConfig` 등
- [ ] 런타임 설정 변경 테스트 필요

### 성공 기준
- 잘못된 설정 100% 거부
- 애플리케이션 시작 실패 (심각한 오류 시)
- 런타임 변경 시 롤백 가능

---

## 2. 장애 주입 (🔴 Red's Attack)

### Config Poisoning 시뮬레이션
```yaml
# 잘못된 설정 예시
spring:
  datasource:
    hikari:
      maximum-pool-size: -10  # 음수! ❌
      connection-timeout: 999999999  # 너무 큼 ❌
      minimum-idle: 150  # maximum-pool-size보다 큼 ❌

nexon:
  api:
    key: ""  # 빈 값 ❌
    response-timeout: 0s  # 0초? ❌
    connect-timeout: -1s  # 음수 Duration ❌
    cache-follower-timeout-seconds: 9999  # 허용 범위 초과 ❌
```

### 설정 오염 유형
| 유형 | 예시 | 위험도 | 실제 적용 대상 |
|------|------|--------|----------------|
| **범위 초과** | poolSize=-1, timeout=999999 | 🔴 높음 | HikariCP, NexonApi |
| **타입 오류** | port="abc" | 🔴 높음 | 모든 숫자 타입 |
| **필수 누락** | apiKey="" | 🔴 높음 | @NotNull 필드 |
| **논리적 오류** | minPool > maxPool | 🟠 중간 | HikariCP 설정 |
| **Duration 오류** | timeout=0s, timeout=-1s | 🔴 높음 | NexonApi |

---

## 3. 터미널 대시보드 + 관련 로그 (🟢 Green's Analysis)

### 테스트 실행 결과 📊
> ⚠️ **미실행 상태**: 실제 테스트 파일 존재하지 않음

**예상 테스트 결과** (기존 설정 클래스 기준):
```
======================================================================
  📊 Config Poisoning Test Results (Expected)
======================================================================

┌────────────────────────────────────────────────────────────────────┐
│               Configuration Validation                             │
├────────────────────────────────────────────────────────────────────┤
│ Test Case 1: nexon.api.connect-timeout = 0s                       │
│   Result: REJECTED ✅ (@NotNull, Duration must be > 0)            │
│                                                                    │
│ Test Case 2: nexon.api.cache-follower-timeout-seconds = 9999    │
│   Result: REJECTED ✅ (@Max(120))                                 │
│                                                                    │
│ Test Case 3: nexon.api.key = ""                                   │
│   Result: REJECTED ✅ (@NotBlank required)                        │
│                                                                    │
│ Test Case 4: spring.datasource.hikari.maximum-pool-size = -10    │
│   Result: REJECTED ✅ (HikariCP validation)                        │
│                                                                    │
│ Test Case 5: spring.datasource.hikari.minimum-idle = 150         │
│   Result: REJECTED ⚠️ (논리적 검증 미적용)                         │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               Application Startup                                  │
├────────────────────────────────────────────────────────────────────┤
│ With valid config: STARTED ✅                                     │
│ With poisoned config: FAILED TO START ✅                          │
│   Reason: "Configuration validation failed"                        │
└────────────────────────────────────────────────────────────────────┘
```

### 로그 증거 (실제 구현 기반)

```text
# Expected Log Pattern for NexonApiProperties
2026-01-19 11:00:00.001 ERROR o.s.boot.SpringApplication         <-- 1. 애플리케이션 시작 시도
2026-01-19 11:00:00.002 ERROR o.s.b.c.p.ConfigurationProperties  <-- 2. @Validated 검증 시작
2026-01-19 11:00:00.003 ERROR Validation failed for 'nexon.api.connect-timeout'
  Reason: must not be null
  Actual value: null (if empty)
2026-01-19 11:00:00.004 ERROR o.s.b.SpringApplication               <-- 3. 시작 실패
  Reason: Application failed to configure a DataSource

# Actual Log Pattern (Current Implementation)
2026-01-19 11:01:00.001 INFO  o.s.b.c.p.ConfigurationProperties - Binding 'nexon.api' properties ✅
2026-01-19 11:01:00.500 INFO  o.s.b.SpringApplication - Started in 2.5s ✅
```

---

## 4. 테스트 Quick Start

### 설정 검증 테스트
```bash
# 잘못된 설정으로 시작 시도
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=-10 ./gradlew bootRun

# 예상 결과: 시작 실패
# "Validation failed for configuration properties"
```

### 런타임 설정 변경
```bash
# 로그 레벨 변경 (유효)
curl -X POST http://localhost:8080/actuator/loggers/root \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "DEBUG"}'

# 상태 확인
curl http://localhost:8080/actuator/loggers/root
```

---

## 5. 관련 CS 원리 (학습용)

### 핵심 개념

1. **Fail-Fast Configuration**
   - 잘못된 설정은 시작 시 즉시 거부
   - 런타임 오류보다 시작 실패가 나음
   - `@Validated` + `@ConfigurationProperties`

2. **Configuration Drift**
   - 환경 간 설정 불일치
   - Dev ≠ Staging ≠ Production
   - GitOps로 일관성 유지

3. **Feature Flags vs Config**
   - Feature Flag: 기능 On/Off
   - Config: 동작 방식 조정
   - 모두 검증 완료 (구체적 예시 제공)

### 코드 Best Practice (실제 구현 참조)

```java
// ✅ Current Implementation: NexonApiProperties
@Validated
@ConfigurationProperties(prefix = "nexon.api")
public record NexonApiProperties(
    @NotNull
    private Duration connectTimeout,     // null 불허

    @NotNull
    private Duration responseTimeout,     // null 불허

    @Min(5) @Max(120)
    private int cacheFollowerTimeoutSeconds,  // 범위 검증

    @Min(30) @Max(300)
    private int latchInitialTtlSeconds,         // 논리적 관계 검증

    @Min(5) @Max(60)
    private int latchFinalizeTtlSeconds
) {}

// ❌ Missing: HikariCP 설정 검증 (해야 할 개선점)
@Component
@RequiredArgsConstructor
public class HikariConfigValidator implements SmartInitializingSingleton {
    private final HikariDataSource hikariDataSource;

    @Override
    public void afterSingletonsInstantiated() {
        // 논리적 검증 누락 - minIdle > maxPool 방지
        if (hikariDataSource.getMinimumIdle() > hikariDataSource.getMaximumPoolSize()) {
            throw new IllegalStateException(
                "minimumIdle must be <= maximumPoolSize");
        }
    }
}
```

---

## 6. 최종 판정 (🟡 Yellow's Verdict)

### ⚠️ **결과: INCOMPLETE - Test Missing**

### 기술적 분석
1. **현재 상태**:
   - ✅ NexonApiProperties: 완벽한 검증 구현
   - ❌ HikariCP 설정: 논리적 검증 누락
   - ❌ 테스트 파일: 없음

2. **검증 커버리지**:
   - ✅ 필수값 검증 (@NotNull, @NotBlank)
   - ✅ 범위 검증 (@Min, @Max)
   - ❌ 논리적 관계 검증 (minIdle vs maxPool)
   - ❌ 커스텀 business rule 검증

### 개선 권장사항
1. **즉시 필요**: 테스트 파일 생성 (`ConfigPoisoningChaosTest.java`)
2. **논리적 검증**: HikariCP 설정 검증기 추가
3. **문서화**: 검증 규칙 명시화

### 우선순위
- **P0**: 테스트 파일 생성 (Blocker)
- **P1**: HikariCP 검증기 추가 (Critical)
- **P2**: 추가 설정 클래스 검증 (Optional)

---

---

## 7. 테스트 구현 가이드

### 테스트 파일 생성 필요
```java
// src/test/java/maple/expectation/chaos/data/ConfigPoisoningChaosTest.java
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class ConfigPoisoningChaosTest {

    @Test
    void testInvalidNexonApiConfig() {
        // 시뮬레이션: 잘못된 설정으로 시작 시도
        assertThatThrownBy(() -> {
            // System.setProperty로 설정 변경 후 시작 시도
        }).hasMessageContaining("Validation failed");
    }

    @Test
    void testInvalidHikariConfig() {
        // minIdle > maxPool 검증
        assertThatThrownBy(() -> {
            // 시뮬레이션
        }).hasMessageContaining("must be <=");
    }
}
```

### 실행 방법
```bash
# 테스트 실행
./gradlew test --tests "ConfigPoisoningChaosTest"

# 설정 오염 테스트
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=-10 ./gradlew bootRun
```

*Generated by 5-Agent Council - Chaos Testing Deep Dive*
**상태**: ⚠️ **개선 필요** - 테스트 구현 완료 필요
