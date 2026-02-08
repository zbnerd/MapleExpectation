# Flaky Test Management Guide

> **버전**: 1.0.0
> **마지막 수정**: 2026-02-08
> **상위 문서**: [CLAUDE.md](../../CLAUDE.md)

## 개요

이 문서는 MapleExpectation 프로젝트에서 **Flaky Test(간헐적 실패 테스트)**를 식별, 격리, 관리하는 절차를 정의합니다.

---

## Table of Contents

1. [개요](#개요)
2. [정의](#정의)
3. [젯지 원칙](#젯지-원칙)
4. [Flaky Test 식별](#flaky-test-식별)
5. [격리 절차](#격리-절차)
6. [GitHub 이슈 템플릿](#github-이슈-템플릿)
7. [build.gradle 설정](#buildgradle-설정)
8. [현재 Flaky Test 목록](#현재-flaky-test-목록)
9. [해결 방안](#해결-방안)

---

## 정의

### Flaky Test란?

**Flaky Test(플래키 테스트)**는 코드 변경 없이도 실행 시마다 성공/실패가 번갈아 가며 나타나는 테스트를 말합니다.

### 일반적인 원인

1. **Race Condition**: 동시성 테스트에서 비결정적 타이밍
2. **타이밍 의존성**: 비동기 작업 완료 대기 부족
3. **리소스 경합**: DB/Redis 커넥션 풀 고갈
4. **환경 의존성**: Testcontainers 성능, 로컬/CI 환경 차이

---

## 젯지 원칙

> "Flaky Test는 엄밀히 말하면 버그입니다. 특히 동시성 부분에서 생긴다면 그건 진짜 Race Condition 버그입니다."
> — MapleExpectation 프로젝트 정책

### 원칙 1: 즉시 격리
- Flaky 테스트 발견 즉시 `@Tag("flaky")`로 마킹
- CI 파이프라인에서 제외하여 개발 속도 저하 방지

### 원칙 2: 기술 부채로 등록
- GitHub 이슈 생성으로 근본 원인 분석 계획 수립
- 단순히 @Disabled 하지 말고 "왜 플래키한지" 문서화

### 원칙 3: 우선순위 부여
- **P0 (Critical)**: 데이터 무결성 위험 (Race Condition, 동시성 버그)
- **P1 (High)**: 테스트 신뢰성 저하 (Redis 타이밍 이슈)
- **P2 (Medium)**: 성능 저하 (Testcontainers 지연)

---

## Flaky Test 식별

### 증상

```bash
# 첫 번째 실행: 성공
./gradlew test --tests "SomeTest"
# BUILD SUCCESSFUL

# 두 번째 실행: 실패 (코드 변경 없음)
./gradlew test --tests "SomeTest"
# FAILED

# 세 번째 실행: 성공 (???)
./gradlew test --tests "SomeTest"
# BUILD SUCCESSFUL
```

### CI에서의 패턴

```yaml
# GitHub Actions 실행 로그
Run: ./gradlew test -PfastTest
Test Suite 1: PASSED
Test Suite 2: FAILED (DonationTest.concurrencyTest)
Test Suite 3: PASSED

# Re-run: 자동으로 성공 (코드 변경 없음)
Test Suite 2: PASSED (동일 테스트)
```

---

## 격리 절차

### Step 1: @Tag("flaky") 추가

```java
// Before
@Test
@DisplayName("동시성 테스트")
void concurrencyTest() { ... }

// After
@Test
@Tag("flaky")  // 플래키 테스트 마킹
@DisplayName("동시성 테스트")
void concurrencyTest() { ... }
```

### Step 2: Import 확인

```java
import org.junit.jupiter.api.Tag;  // 필수 import
```

### Step 3: build.gradle 설정

```groovy
test {
    useJUnitPlatform {
        if (project.hasProperty('fastTest')) {
            // CI에서 플래키 테스트 제외
            excludeTags 'sentinel', 'slow', 'quarantine', 'chaos', 'nightmare', 'integration', 'flaky'
        } else {
            // Nightly에서도 제외
            excludeTags 'sentinel', 'quarantine', 'flaky'
        }
    }
}
```

### Step 4: GitHub 이슈 생성

```bash
gh issue create \
  --title "[Flaky Test] DonationTest 동시성 테스트 Race Condition" \
  --label "bug,concurrency,data-integrity,priority:high" \
  --body "@docs/02_Technical_Guides/flaky-test-management.md"
```

---

## GitHub 이슈 템플릿

### 필수 항목

```markdown
## 문제 개요

### 테스트 정보
- **테스트 클래스**: `DonationTest`
- **테스트 메서드**: `concurrencyTest()`, `hotspotTest()`
- **베이스 클래스**: `IntegrationTestSupport`
- **Git Commit**: [현재 커밋 해시]

### 실패 메시지
```
[실제 에러 메시지]
```

## 예상 vs 실제 동작

### 예상 동작
[기대했던 동작 설명]

### 실제 동작
[실제 발생한 동작]

## 근본 원인
1. 원인 1
2. 원인 2
3. 원인 3

## 재현 단계
```bash
./gradlew test --tests "SomeTest.methodName"
```

## 우선순위
- [ ] **P0 (Critical)**: 서비스 장애 발생
- [x] **P1 (High)**: 데이터 무결성 위험
- [ ] **P2 (Medium)**: 성능 저하

## 영향 범위

| 영역 | 영향 | 심각도 |
|------|------|--------|
| 사용자 API | Yes/No | High/Medium/Low |
| 데이터 정합성 | Yes/No | High/Medium/Low |
| 시스템 안정성 | Yes/No | High/Medium/Low |

## 해결 방안 (제안)

### 단기 (Hotfix)
- [x] `@Tag("flaky")`로 테스트 격리 완료
- [x] build.gradle에서 CI 파이프라인에서 제외

### 장기 (Architecture)
- [ ] 근본적 해결 방안 1
- [ ] 근본적 해결 방안 2

## 관련 문서
- 테스트 코드: `src/test/java/...`
- 비즈니스 로직: `src/main/java/...`

## 체크리스트
- [x] 실패 원인 분석 완료
- [x] 재현 가능 여부 확인 (간헐적)
- [x] 영향 범위 파악
- [x] 해결 방안 수립
- [x] 테스트 코드 수정 (격리 완료)
- [ ] 장기적 리팩토링 대기
```

---

## build.gradle 설정

### CI 환경 감지

```groovy
// CI 환경 감지 (Context7 Best Practice)
boolean isCiServer = System.getenv().containsKey("CI")
```

### 테스트 필터링

```groovy
test {
    useJUnitPlatform {
        // @Tag 기반 테스트 필터링
        // ./gradlew test -PfastTest → CI Gate (1-2분)
        // ./gradlew test → Nightly 전체 테스트
        if (project.hasProperty('fastTest')) {
            // CI Gate: integration, chaos, nightmare, flaky 제외
            excludeTags 'sentinel', 'slow', 'quarantine', 'chaos', 'nightmare', 'integration', 'flaky'
        } else {
            // Nightly: sentinel, quarantine, flaky 제외
            excludeTags 'sentinel', 'quarantine', 'flaky'
        }
    }
}
```

### Flaky 테스트 포함 실행 (Nightly용)

```groovy
// Quarantine + Flaky 테스트 포함 실행 (Nightly용)
tasks.register('testWithQuarantine', Test) {
    useJUnitPlatform()  // quarantine, flaky 태그 포함
    retry {
        maxRetries = 1
        maxFailures = 10
        failOnPassedAfterRetry = false
    }
    systemProperty 'flaky.logging.enabled', 'true'
    systemProperty 'flaky.log.dir', "${buildDir}/flaky"
    environment "DOCKER_HOST", "unix:///var/run/docker.sock"
}
```

---

## 현재 Flaky Test 목록

| # | 테스트 클래스 | 메서드 | 원인 | 우선순위 | 이슈 | 상태 |
|---|--------------|--------|------|----------|------|------|
| 1 | `DonationTest` | `concurrencyTest()` | Race Condition (트랜잭션 경계) | P1 | [#328](https://github.com/zbnerd/probabilistic-valuation-engine/issues/328) | 격리 완료 |
| 2 | `DonationTest` | `hotspotTest()` | Race Condition (분산 락 경합) | P1 | [#328](https://github.com/zbnerd/probabilistic-valuation-engine/issues/328) | 격리 완료 |
| 3 | `RefreshTokenIntegrationTest` | 전체 | Redis 저장 타이밍 (Thread.sleep 안티패턴) | P2 | [#329](https://github.com/zbnerd/probabilistic-valuation-engine/issues/329) | 격리 완료 |
| 4 | `LikeSyncCompensationIntegrationTest` | `syncSuccess_TempKeyDeleted()` | Redis Key Deletion Issue (Lua Script RENAME) | P1 | [#330](https://github.com/zbnerd/probabilistic-valuation-engine/issues/330) | 격리 완료 |
| 5 | `LikeSyncCompensationIntegrationTest` | `consecutiveFailuresThenSuccess_WorksCorrectly()` | Redis Key Deletion Issue (Lua Script RENAME) | P1 | [#330](https://github.com/zbnerd/probabilistic-valuation-engine/issues/330) | 격리 완료 |

---

## 해결 방안

### Race Condition (DonationTest)

#### 문제점
```java
@Test
@Transactional  // ❌ 테스트 레벨 트랜잭션
void concurrencyTest() {
    // saveAndFlush()로 즉시 플러시해도
    Member guest = saveAndTrack(Member.createGuest(1000L));

    // 다른 스레드에서는 트랜잭션 커밋 전까지 보이지 않음
    executorService.submit(() -> donationService.sendCoffee(...));
}
```

#### 해결 방법

**Option 1: 트랜잭션 경계 재설계**
```java
@Test  // ❌ @Transactional 제거
void concurrencyTest() {
    Member guest = saveAndTrack(Member.createGuest(1000L));
    // 각 요청이 독립적인 트랜잭션을 사용하도록 비즈니스 로직 수정
}
```

**Option 2: 낙관적 락 (Optimistic Locking)**
```java
@Entity
public class Member {
    @Version  // JPA 낙관적 락
    private Long version;
}

// Service에서
@Transactional
public void sendCoffee(...) {
    Member member = repository.findById(id).orElseThrow();
    member.decreasePoint(amount);  // @Version이 자동으로 충돌 감지
    repository.save(member);
}
```

**Option 3: 비관적 락 (Pessimistic Locking)**
```java
@Transactional
public void sendCoffee(...) {
    // SELECT FOR UPDATE
    Member member = repository.findByIdWithLock(id).orElseThrow();
    member.decreasePoint(amount);
    repository.save(member);
}
```

### Redis 타이밍 이슈 (RefreshTokenIntegrationTest)

#### 문제점
```java
// ❌ 안티패턴
refreshTokenService.createRefreshToken(sessionId, fingerprint);
Thread.sleep(200);  // 환경에 따라 부족하거나 낭비
RefreshToken newToken = refreshTokenService.rotateRefreshToken(tokenId);
```

#### 해결 방법

**Option 1: Awaitility 도입**
```java
// build.gradle
testImplementation 'org.awaitility:awaitility:4.2.0'

// Good
refreshTokenService.createRefreshToken(sessionId, fingerprint);
await().atMost(5, TimeUnit.SECONDS)
    .untilAsserted(() ->
        assertThat(refreshTokenRepository.findById(tokenId)).isPresent()
    );
RefreshToken newToken = refreshTokenService.rotateRefreshToken(tokenId);
```

**Option 2: CountDownLatch 패턴**
```java
@Test
void shouldCreateTokenSuccessfully() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);

    // Redis 저장 완료 콜백
    refreshTokenService.createRefreshToken(sessionId, fingerprint,
        () -> latch.countDown());

    // 저장 완료 대기 (타임아웃 5초)
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    RefreshToken newToken = refreshTokenService.rotateRefreshToken(tokenId);
}
```

---

## Best Practices

### 1. 테스트 격리
- 동시성 테스트는 `@Execution(ExecutionMode.SAME_THREAD)`로 직렬 실행
- Testcontainers 사용 시 `maxParallelForks = 1`로 컨테이너 경합 방지

### 2. 타임아웃 설정
```java
@Test
@Timeout(value = 30, unit = TimeUnit.SECONDS)
void shouldCompleteWithinTimeout() { ... }
```

### 3. 재시도 정책 (build.gradle)
```groovy
retry {
    if (isCiServer) {
        maxRetries = 1          // 보수적 재시도
        maxFailures = 5         // 5개 초과 실패 시 빌드 중단
        failOnPassedAfterRetry = false  // 재시도 성공 시 빌드 통과
    }
}
```

### 4. 로깅
```groovy
systemProperty 'flaky.logging.enabled', 'true'
systemProperty 'flaky.log.dir', "${buildDir}/flaky"
```

---

## 관련 문서

- [CLAUDE.md](../../CLAUDE.md) - 프로젝트 핵심 규칙
- [ISSUE_TEMPLATE.md](../98_Templates/ISSUE_TEMPLATE.md) - 카오스 테스트 실패 보고서
- [testing-guide.md](testing-guide.md) - 테스트 작성 가이드
- [adr/ADR-014-multi-module-cross-cutting-concerns.md](../../adr/ADR-014-multi-module-cross-cutting-concerns.md) - ADR-014 멀티 모듈 전환

---

## Changelog

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| 1.0.0 | 2026-02-08 | 최초 작성 (Issue #328, #329 반영) |

---

*Template Version: 1.0.0*
*Last Updated: 2026-02-08*
*Created from MapleExpectation Flaky Test Management Project*
