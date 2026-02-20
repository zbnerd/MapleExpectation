# ADR-061: Flaky Test 추적 및 Quarantine 운영 체계

## Status
**Accepted** (2026-02-20)

## Context

### 1장: 문제의 발견 (Problem)

#### 1.1 CI 신뢰도 붕괴

**PR #212**와 **Issue #207**에서 **Flaky Test(불안정한 테스트) 누적으로 CI 파이프라인 신뢰도가 85%로 급락**하는 심각한 문제가 발견되었습니다.

**문제의 심각성**:
```
월간 Flaky Test 발생 횟수: 47회
CI 통과율: 85% (목표: 99.7%)
개발자 경험: "테스트 실패 시 코드 버그인지 Flaky인지 구분 불가"
```

#### 1.2 Flaky Test의 원인 분석

**PR #211, #205, #204, #203**에서 주요 원인이 파악되었습니다:

| 원인 | 사례 | 발생 빈도 |
|------|------|----------|
| **타이밍 경쟁 (Race Condition)** | EquipmentServiceTest에서 캐시 만료 대기 시간 불충분 | 30% |
| **테스트 격리 부족** | ShutdownDataRecoveryIntegrationTest에서 Redis/파일시스템 공유 | 25% |
| **외부 의존성** | ResilientNexonApiClientTest에서 Mock 불완전 | 20% |
| **환경 차이** | 로컬에서는 통과, CI에서는 실패 (Timezone, Locale) | 15% |
| **기타** | 10% | |

#### 1.3 누적되는 Flaky Test

**문제**:
1. 실패한 테스트를 **코드 버그로 착각**하여 디버깅에 시간 낭비
2. **Rebuild 반복**으로 CI 리소스 낭비
3. **"테스트를 믿을 수 없어 무시"** 문화 퍼짐
4. 새로운 테스트를 추가할 때마다 Flaky Test가 누적됨

---

### 2장: 선택지 탐색 (Options)

#### 2.1 선택지 1: 재시도(Retry) 전략

**방식**: JUnit Retry Rule로 실패한 테스트 자동 재시도

```java
@Retry(3)
@Test
void someFlakyTest() {
    // 3번까지 재시도
}
```

**장점**:
- 구현이 간단함
- 일시적 Flaky를 흡수

**단점**:
- **근본적 해결 아님**: 진짜 버그도 재시도로 통과 가능
- **CI 시간 증가**: 재시도로 인해 전체 테스트 시간 증가
- **Flaky Test 은폐**: 문제를 봉투씌우는 것에 불과

**결론**: **임시 방편일 뿐 근본 해결책 아님**

---

#### 2.2 선택지 2: Quarantine (격리) 전략

**방식**: Flaky Test를 별도 `@Tag("quarantine")`으로 분류하여 Main Suite에서 제외

```java
@Tag("quarantine")
@Test
void flakyTest() {
    // Main Suite에서 제외, 별도 실행
}
```

**장점**:
- **CI 신뢰도 보호**: Main Suite는 Flaky Test 오염 방지
- **명시적 관리**: Flaky Test 목록이 가시화됨
- **수정 우선순위 부여**: Quarantine된 테스트는 명확한 수정 대상

**단점**:
- **테스트 커버리지 감소**: Quarantine된 테스트는 실제로 실행되지 않음
- **관리 오버헤드**: Quarantine 목록을 주기적으로 정리해야 함

**결론**: **CI 신뢰도 확보를 위한 필수 전략**

---

#### 2.3 선택지 3: 근본적 수정 + Quarantine 병행 (선택)

**방식**:
1. **단기**: Flaky Test를 Quarantine으로 분류하여 CI 신뢰도 보호
2. **장기**: Flaky Test의 근본적 원인을 분석하여 수정

**Quarantine 운영 체계**:
```
1. Flaky Test 발견 시 즉시 Quarantine 추가
2. Issue 등록 (#207-flaky-test-이름)
3. 원인 분석 (Race Condition, 격리 부족, Mock 불완전)
4. 수정 후 Quarantine 해제
5. Quarantine 기한: 2주 (초과 시 삭제 검토)
```

**추적 시스템**:
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Quarantine(
    issue = "207",
    reason = "Redis Key 만료 타이밍 경쟁",
    since = "2026-02-01",
    assignee = "worker-3"
)
public @interface Quarantine {}
```

**장점**:
- **CI 신뢰도 즉시 개선**: Quarantine으로 즉시 효과
- **장기적 해결**: 원인 분석으로 근본적 수정
- **책임 소재 명확**: Assignee가 명확하여 수정 유도

**단점**:
- **운영 비용**: Quarantine 목록 관리가 필요

**결론**: **가장 현실적이고 효과적인 해결책**

---

### 3장: 결정의 근거 (Decision)

#### 3.1 선택: 근본적 수정 + Quarantine 병행

MapleExpectation 프로젝트는 **선택지 3: 근본적 수정 + Quarantine 병행**을 채택했습니다.

**결정 근거**:
1. **CI 신뢰도는 생존 문제**: Issue #207의 "CI 통과율 85%"는 개발 생산성에 치명적
2. **즉각적인 개선 필요**: 근본적 수정은 시간이 걸리므로 Quarantine으로 즉시 효과
3. **지속적 개선**: Quarantine을 수정 Backlog로 활용

---

### 4장: 구현의 여정 (Action)

#### 4.1 @Quarantine Annotation 정의

**파일**: `maple/expectation/test/annotation/Quarantine.java`

```java
package maple.expectation.test.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Tag("quarantine")
public @interface Quarantine {

    /**
     * 관련 Issue 번호
     */
    String issue();

    /**
     * Quarantine 사유
     */
    String reason();

    /**
     * Quarantine 시작일
     */
    String since();

    /**
     * 담당자
     */
    String assignee() default "unassigned";
}
```

#### 4.2 JUnit Configuration에서 Quarantine 제외

**파일**: `build.gradle`

```gradle
tasks.named('test') {
    useJUnitPlatform {
        // Quarantine 태그 제외
        excludeTags 'quarantine'
    }
}

// Quarantine 전용 Test Task
tasks.register('testQuarantine', Test) {
    useJUnitPlatform {
        includeTags 'quarantine'
    }
    description = 'Run quarantined flaky tests'
}
```

#### 4.3 Flaky Test 추적 대시보드

**파일**: `docs/02_Chaos_Engineering/FLAKY_TEST_TRACKER.md`

```markdown
# Flaky Test 추적 대시보드

## Quarantine 목록 (2026-02-20 기준)

| Test | Issue | Reason | Since | Assignee |
|------|-------|--------|-------|----------|
| LikeSyncCompensationIntegrationTest | #330 | Redis Key D 경합 | 2026-02-10 | worker-3 |
| RefreshTokenIntegrationTest | #329 | Redis 저장 대기 시간 | 2026-02-08 | worker-1 |
| DonationTest | #328 | 동시성 테스트 Race Condition | 2026-02-05 | worker-2 |
| ResilientNexonApiClientTest | #202 | Retry 로직 타이밍 | 2026-02-01 | worker-3 |

## 통계

- **Quarantine된 테스트**: 4개
- **전체 테스트**: 1,250개
- **Quarantine 비율**: 0.3%
- **CI 통과율**: 99.7% (개선 전: 85%)

## 만료 기준

Quarantine 시작일로부터 **2주** 경과 시:
1. 수정 완료 후 Quarantine 해제
2. 수정 불가 시 테스트 삭제 검토
```

#### 4.4 Flaky Test 수정 가이드

**Race Condition 해결 예시**:

**Before (Flaky)**:
```java
@Test
void testCacheExpiry() {
    cache.put("key", "value", 1);  // 1초 TTL
    // 즉시 확인 → Flaky 가능성
    assertTrue(cache.get("key").isPresent());
}
```

**After (Stable)**:
```java
@Test
void testCacheExpiry() {
    cache.put("key", "value", 1);

    // Awaitility로 명시적 대기
    await().atMost(2, TimeUnit.SECONDS)
        .until(() -> cache.get("key").isPresent());

    assertTrue(cache.get("key").isPresent());
}
```

**테스트 격리 강화 예시**:

**Before (Flaky)**:
```java
@SpringBootTest
class IntegrationTest {
    // 여러 테스트가 Redis를 공유하여 간섭
}
```

**After (Isolated)**:
```java
@SpringBootTest
class IntegrationTest {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        // 각 테스트마다 고유한 Redis Key Prefix 사용
        registry.add("test.key.prefix",
            () -> UUID.randomUUID().toString());
    }
}
```

---

### 5장: 결과와 학습 (Result)

#### 5.1 성과

1. **CI 통과율 개선**: 85% → 99.7% (목표 달성)
2. **개발 생산성 향상**: "테스트 실패 = 코드 버그" 확신
3. **Flaky Test 가시화**: Quarantine 목록으로 명확한 수정 대상

#### 5.2 학습한 점

1. **Quarantine은 수술이 아닌 붕대**: 즉시 증상 완화용이지 근본적 해결책은 아님
2. **테스트 격리的重要性**: 각 테스트는 독립적이어야 함
3. **명시적 대기**: Thread.sleep() 대신 Awaitility로 의도를 명확히

#### 5.3 향후 개선 방향

- **자동 Flaky Test 탐지**: 실패 패턴 분석으로 자동 Quarantine 제안
- **Flaky Test 수정 타임라인**: Quarantine 2주内 수정 목표

---

## Consequences

### 긍정적 영향
- **CI 신뢰도 복구**: 개발자가 다시 테스트를 신뢰
- **수정 우선순위 명확**: Quarantine 목록이 Backlog 역할

### 부정적 영향
- **커버리지 일시 감소**: Quarantine된 테스트는 Main Suite에서 제외
- **관리 오버헤드**: Quarantine 목록을 주기적으로 정리해야 함

### 위험 완화
- **2주 만료 정책**: Quarantine을 방치하지 않도록 시간 제한
- **Assignee 지정**: 책임 소재를 명확히 하여 수정 유도

---

## References

- **PR #212**: feat(#210): Flaky Test 추적 및 Quarantine 운영 체계 구축
- **PR #211**: fix(#207): CI 테스트 안정성 개선 및 Flaky 테스트 제거
- **Issue #207**: [CI] 통합테스트 10분+ 장기화 및 플래키 테스트 지속으로 인한 파이프라인 신뢰도/속도 저하
- **Issue #210**: # [test-reliability]] Flaky Test 누적 추적/로그화 및 Quarantine 운영 체계
- **ADR-062**: Testcontainers 기반 통합 테스트 격리 전략
