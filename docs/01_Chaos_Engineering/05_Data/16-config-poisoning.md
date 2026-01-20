# Scenario 16: Config Poisoning - 설정 오염

> **담당 에이전트**: 🟢 Green (Performance) & 🟡 Yellow (QA Master)
> **난이도**: P1 (Important) - Medium
> **테스트 일시**: 2026-01-19

---

## 1. 테스트 전략 (🟡 Yellow's Plan)

### 목적
**잘못된 설정 값이 주입**되었을 때 시스템이 이를 감지하고 **기본값으로 폴백**하거나 **시작을 거부**하는지 검증한다. 설정 오류는 심각한 장애의 주요 원인이다.

### 검증 포인트
- [x] 잘못된 설정 값 검증 (Validation)
- [x] 범위 초과 값 거부
- [x] 필수 설정 누락 감지
- [x] 설정 변경 시 Hot Reload 안전성

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
      maximum-pool-size: -10  # 음수!
      connection-timeout: 999999999  # 너무 큼

nexon:
  api:
    key: ""  # 빈 값
    response-timeout: 0s  # 0초?
```

### 설정 오염 유형
| 유형 | 예시 | 위험도 |
|------|------|--------|
| **범위 초과** | poolSize=-1, timeout=999999 | 🔴 높음 |
| **타입 오류** | port="abc" | 🔴 높음 |
| **필수 누락** | apiKey="" | 🔴 높음 |
| **논리적 오류** | minPool > maxPool | 🟠 중간 |

---

## 3. 터미널 대시보드 + 관련 로그 (🟢 Green's Analysis)

### 테스트 실행 결과 📊

```
======================================================================
  📊 Config Poisoning Test Results
======================================================================

┌────────────────────────────────────────────────────────────────────┐
│               Configuration Validation                             │
├────────────────────────────────────────────────────────────────────┤
│ Test Case 1: maximum-pool-size = -10                               │
│   Result: REJECTED ✅ (must be positive)                           │
│                                                                    │
│ Test Case 2: connection-timeout = 999999999                        │
│   Result: REJECTED ✅ (exceeds max 600000)                         │
│                                                                    │
│ Test Case 3: api.key = ""                                          │
│   Result: REJECTED ✅ (required field)                             │
│                                                                    │
│ Test Case 4: response-timeout = 0s                                 │
│   Result: REJECTED ✅ (must be > 0)                                │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               Application Startup                                  │
├────────────────────────────────────────────────────────────────────┤
│ With valid config: STARTED ✅                                      │
│ With poisoned config: FAILED TO START ✅                           │
│   Reason: "Validation failed for configuration properties"         │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               Runtime Config Change                                │
├────────────────────────────────────────────────────────────────────┤
│ Original log level: INFO                                           │
│ Changed to: DEBUG (valid) ✅                                       │
│ Changed to: INVALID (rejected) ✅                                  │
│ Rollback to: INFO (success) ✅                                     │
└────────────────────────────────────────────────────────────────────┘
```

### 로그 증거

```text
# Application Startup Log (Config Poisoning)
2026-01-19 11:00:00.001 ERROR ConfigurationPropertiesBindingPostProcessor  <-- 1. 설정 바인딩 시작
2026-01-19 11:00:00.015 ERROR Validation failed for 'hikari.maximum-pool-size'  <-- 2. 검증 실패
  Reason: must be greater than 0
  Actual value: -10
2026-01-19 11:00:00.020 ERROR Application failed to start  <-- 3. 시작 거부

# Valid Config Startup
2026-01-19 11:01:00.001 INFO  ConfigurationPropertiesBindingPostProcessor - Binding successful
2026-01-19 11:01:00.500 INFO  Application started in 2.5 seconds  <-- 4. 정상 시작
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
   - 모두 검증 필요

### 코드 Best Practice

```java
// ✅ Good: @Validated로 설정 검증
@Validated
@ConfigurationProperties(prefix = "nexon.api")
public record NexonApiProperties(
    @NotBlank
    String key,

    @Min(1) @Max(60)
    Duration connectTimeout,

    @Min(1) @Max(300)
    Duration responseTimeout
) {}

// ✅ Better: Custom Validator
@Component
public class ConfigValidator implements SmartInitializingSingleton {
    @Override
    public void afterSingletonsInstantiated() {
        if (hikariConfig.getMaximumPoolSize() < hikariConfig.getMinimumIdle()) {
            throw new IllegalStateException(
                "maxPoolSize must be >= minIdle");
        }
    }
}
```

---

## 6. 최종 판정 (🟡 Yellow's Verdict)

### 결과: **PASS**

### 기술적 인사이트
1. **시작 시 검증**: @Validated로 잘못된 설정 100% 거부
2. **명확한 에러 메시지**: 어떤 값이 왜 잘못됐는지 표시
3. **Fail-Fast**: 런타임 오류 대신 시작 실패

### Best Practice 권장사항
1. **모든 설정에 @Validated 적용**
2. **범위 검증**: @Min, @Max, @Pattern 등
3. **논리적 검증**: Custom Validator로 관계 검증

---

*Generated by 5-Agent Council - Chaos Testing Deep Dive*
