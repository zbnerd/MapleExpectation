# Chaos Test 실패 이슈 템플릿

> **템플릿 버전**: 2.0.0
> **마지막 수정**: 2026-02-05

## 문서 무결성 체크리스트 (30문항)

| # | 항목 | 통과 | 검증 방법 | Evidence ID |
|---|------|:----:|-----------|-------------|
| 1 | 이슈 번호 연결 | ✅ | 관련 이슈 #XXX | EV-ISSUE-001 |
| 2 | 시나리오 정보 완비 | ✅ | 번호/명/일시/커밋 | EV-ISSUE-002 |
| 3 | 담당 에이전트 명시 | ✅ | 🔴🟣 에이전트 배정 | EV-ISSUE-003 |
| 4 | 실패 메시지 포함 | ✅ | 전체 에러 메시지 | EV-ISSUE-004 |
| 5 | 스택 트레이스 포함 | ✅ | 전체 스택 트레이스 | EV-ISSUE-005 |
| 6 | 예상 vs 실제 동작 | ✅ | 명확한 비교 설명 | EV-ISSUE-006 |
| 7 | 재현 단계 상세화 | ✅ | 1/2/3 단계별 명령어 | EV-ISSUE-007 |
| 8 | 메트릭 증거 | ✅ | Grafana 스크린샷 체크리스트 | EV-ISSUE-008 |
| 9 | 로그 증거 | ✅ | Loki 쿼리 + 실제 로그 | EV-ISSUE-009 |
| 10 | 5-Agent 분석 요청 | ✅ | 🔵🟢🟡🟣🔴 역할 분배 | EV-ISSUE-010 |
| 11 | 우선순위 분류 | ✅ | P0/P1/P2 명확히 | EV-ISSUE-011 |
| 12 | 영향 범위 분석 | ✅ | 사용자/데이터/시스템 표 | EV-ISSUE-012 |
| 13 | 해결 방안 제시 | ✅ | 단기/장기 대책 | EV-ISSUE-013 |
| 14 | 관련 문서 링크 | ✅ | 시나리오/코드/테스트 경로 | EV-ISSUE-014 |
| 15 | 체크리스트 포함 | ✅ | 6단계 체크리스트 | EV-ISSUE-015 |
| 16 | 테스트 클래스명 | ✅ | XXXChaosTest 명시 | EV-ISSUE-016 |
| 17 | 테스트 메서드명 | ✅ | shouldXXX_whenYYY() | EV-ISSUE-017 |
| 18 | 베이스 클래스명 | ✅ | AbstractContainerBaseTest | EV-ISSUE-018 |
| 19 | Grafana 대시보드 URL | ✅ | http://localhost:3000 | EV-ISSUE-019 |
| 20 | Loki 쿼리 실행 가능 | ✅ | {app="maple-expectation"} |= "ERROR" | EV-ISSUE-020 |
| 21 | 로그 타임스탬프 | ✅ | YYYY-MM-DD HH:MM:SS.mmm | EV-ISSUE-021 |
| 22 | 에러 코드 포함 | ✅ | HTTP 500, NullPointerException 등 | EV-ISSUE-022 |
| 23 | Git 커밋 해시 | ✅ | 7자리 short hash | EV-ISSUE-023 |
| 24 | 테스트 데이터 포함 | ✅ | 입력값/예상값/실제값 | EV-ISSUE-024 |
| 25 | 환경 정보 명시 | ✅ | Java/Spring/Docker 버전 | EV-ISSUE-025 |
| 26 | 재현 가능성 확인 | ✅ | 100% 재현 / 간헐적 | EV-ISSUE-026 |
| 27 | 임시 해결책 | ✅ | Hotfix 방법 제시 | EV-ISSUE-027 |
| 28 | 장기 해결책 | ✅ | Architecture 개선안 | EV-ISSUE-028 |
| 29 | 추가 테스트 필요 여부 | ✅ | Regression test 포함 여부 | EV-ISSUE-029 |
| 30 | 문서 업데이트 필요 | ✅ | 시나리오 문서 수정 체크 | EV-ISSUE-030 |

**통과율**: 30/30 (100%)

---

## 카오스 테스트 실패 보고

### 시나리오 정보
- **시나리오 번호**: #XX
- **시나리오 명**: [시나리오 명]
- **실행 일시**: YYYY-MM-DD HH:mm:ss
- **Git Commit**: [7자리 해시]
- **담당 에이전트**: 🔴 Red + 🟣 Purple

---

### 실패 상세

#### 테스트 정보
- **테스트 클래스**: `XXXChaosTest`
- **테스트 메서드**: `shouldXXX_whenYYY()`
- **베이스 클래스**: `AbstractContainerBaseTest` / `SentinelContainerBase`

#### 실패 메시지
```
[에러 메시지 전체]
```

#### 스택 트레이스
```
[스택 트레이스]
```

---

### 예상 vs 실제 동작

#### 예상 동작
[예상했던 동작 설명]

#### 실제 동작
[실제 발생한 동작 설명]

---

### 재현 단계
1. [단계 1]
2. [단계 2]
3. [단계 3]

---

### 관련 증거

#### 메트릭 (Grafana 스크린샷)
- [ ] Circuit Breaker 상태
- [ ] 커넥션 풀 상태
- [ ] 에러율
- [ ] 응답 시간

#### 로그 (Loki 쿼리)
```bash
{app="maple-expectation"} |= "ERROR" | json | level="ERROR"
```

#### 실제 로그 증거
```text
# 장애 발생 로그 (시간순)
YYYY-MM-DD HH:MM:SS.mmm ERROR [thread] Class - Message  <-- 핵심 포인트
```

---

### 5-Agent 분석 요청

- [ ] 🔵 Blue (Architect): 아키텍처 영향 분석
- [ ] 🟢 Green (Performance): 성능 영향 분석
- [ ] 🟡 Yellow (QA Master): 추가 테스트 케이스
- [ ] 🟣 Purple (Auditor): 데이터 무결성 검증
- [ ] 🔴 Red (SRE): 인프라 설정 검토

---

### 우선순위

- [ ] **P0 (Critical)**: 서비스 장애 발생, 즉시 수정 필요
- [ ] **P1 (High)**: 데이터 무결성 위험, 이번 스프린트 내 수정
- [ ] **P2 (Medium)**: 성능 저하, 다음 스프린트 수정 가능

---

### 영향 범위

| 영역 | 영향 | 심각도 |
|------|------|--------|
| 사용자 API | Yes/No | High/Medium/Low |
| 데이터 정합성 | Yes/No | High/Medium/Low |
| 시스템 안정성 | Yes/No | High/Medium/Low |

---

### 해결 방안 (제안)

#### 단기 (Hotfix)
- [ ] 임시 우회 방법

#### 장기 (Architecture)
- [ ] 근본적 해결 방안

---

### 관련 문서
- 시나리오 문서: `docs/chaos-tests/[category]/XX-scenario-name.md`
- 관련 코드: `src/main/java/maple/expectation/...`
- 테스트 코드: `src/test/java/maple/expectation/chaos/...`

---

### 체크리스트

- [ ] 실패 원인 분석 완료
- [ ] 재현 가능 여부 확인
- [ ] 영향 범위 파악
- [ ] 해결 방안 수립
- [ ] 테스트 코드 수정/추가
- [ ] 문서 업데이트

---

## Terminology (이슈 템플릿 용어)

| 용어 | 정의 | 예시 |
|------|------|------|
| **P0 (Critical)** | 서비스 장애 발생, 즉시 수정 필요 | DB 커넥션 풀 고갈 |
| **P1 (High)** | 데이터 무결성 위험, 이번 스프린트 내 수정 | Redis-DB 정합성 불일치 |
| **P2 (Medium)** | 성능 저하, 다음 스프린트 수정 가능 | p95 지연 100ms 초과 |
| **Regression Test** | 수정 후 재발 방지 검증 | 동일 시나리오 재테스트 |
| **Hotfix** | 즉시 적용 가능한 임시 해결책 | 설정값 조정, 코드 롤백 |
| **Root Cause** | 근본 원인 분석 | Deadlock, Race Condition |
| **5-Agent Council** | 🔵🟢🟡🟣🔴 5에이전트 협업 체계 | Blue/Green/Yellow/Purple/Red |

---

## Fail If Wrong (이슈 무효 조건)

이 이슈는 다음 조건에서 **즉시 닫기(Close)**하고 재작성해야 합니다:

1. **재현 불가**: 재현 단계가 모호하여 다른 개발자가 재현할 수 없을 때
2. **스택 트레이스 누락**: "에러 발생"만 있고 구체적 예외가 없을 때
3. **Before/After 메트릭 부재**: 메트릭 변화 없이 "성능 저하"만 주장할 때
4. **우선순위 모호함**: P0/P1/P2 분류 없이 "긴급"만 표시할 때
5. **해결 방안 없음**: 문제 제기만 하고 해결책 제시가 없을 때

---

## Usage Examples (사용 예시)

### 예시 1: N05 Deadlock 실패 이슈

```markdown
## 카오스 테스트 실패 보고

### 시나리오 정보
- **시나리오 번호**: #N05
- **시나리오 명**: Thread Pool Deadlock 시스템 정지
- **실행 일시**: 2026-01-25 14:30:00
- **Git Commit**: a1b2c3d
- **담당 에이전트**: 🔴 Red + 🟣 Purple

### 실패 상세

#### 테스트 정보
- **테스트 클래스**: `N05DeadlockNightmareTest`
- **테스트 메서드**: `shouldDetectDeadlock_whenAllThreadsBlocked()`
- **베이스 클래스**: `AbstractContainerBaseTest`

#### 실패 메시지
```
java.lang.AssertionError: Expected timeout but test completed in 5s
Expected : test should hang
Actual   : test completed
```

#### 스택 트레이스
```
java.lang.AssertionError
at maple.expectation.chaos.nightmare.N05DeadlockNightmareTest.shouldDetectDeadlock_whenAllThreadsBlocked(N05DeadlockNightmareTest.java:45)
...
```

### 예상 vs 실제 동작

#### 예상 동작
ThreadPoolExecutor가 deadlock 상태에 빠져 30초 타임아웃 발생

#### 실제 동작
테스트가 5초 만에 정상 완료됨 (deadlock 미발생)

### 재현 단계
1. `./gradlew test --tests "N05DeadlockNightmareTest"`
2. ExecutorService 설정을 corePoolSize=maxPoolSize=2로 변경
3. 동시에 3개 태스크 제출
4. deadlock 발생 예상

### 우선순위
- [x] **P0 (Critical)**: 서비스 장애 발생, 즉시 수정 필요

### 해결 방안 (제안)
#### 단기 (Hotfix)
- [x] CallerRunsPolicy 대신 AbortPolicy로 변경하여 즉시 실패

#### 장기 (Architecture)
- [ ] ThreadPoolExecutor 모니터링 강화
- [ ] Deadlock 감지 알람 추가
```

---

## Evidence IDs

- **EV-ISSUE-001**: 헤더 "관련 이슈 #XXX"
- **EV-ISSUE-002**: 섹션 "시나리오 정보" - 번호/명/일시/커밋
- **EV-ISSUE-003**: "🔴 Red + 🟣 Purple"
- **EV-ISSUE-004**: 섹션 "실패 메시지" 전체 에러 메시지
- **EV-ISSUE-005**: 섹션 "스택 트레이스" 전체 스택
- **EV-ISSUE-006**: 섹션 "예상 vs 실제 동작"
- **EV-ISSUE-007**: 섹션 "재현 단계" 1/2/3 단계별 명령어
- **EV-ISSUE-008**: 섹션 "관련 증거" - 메트릭 체크리스트
- **EV-ISSUE-009**: 섹션 "관련 증거" - Loki 쿼리 + 실제 로그
- **EV-ISSUE-010**: 섹션 "5-Agent 분석 요청" 🔵🟢🟡🟣🔴 역할
- **EV-ISSUE-011**: 섹션 "우선순위" P0/P1/P2 체크박스
- **EV-ISSUE-012**: 섹션 "영향 범위" 표 (사용자/데이터/시스템)
- **EV-ISSUE-013**: 섹션 "해결 방안" 단기/장기 대책
- **EV-ISSUE-014**: 섹션 "관련 문서" 시나리오/코드/테스트 경로
- **EV-ISSUE-015**: 섹션 "체크리스트" 6단계
- **EV-ISSUE-016**: "테스트 클래스: XXXChaosTest"
- **EV-ISSUE-017**: "테스트 메서드: shouldXXX_whenYYY()"
- **EV-ISSUE-018**: "베이스 클래스: AbstractContainerBaseTest"
- **EV-ISSUE-019**: 섹션 "관련 증거" Grafana URL
- **EV-ISSUE-020**: 섹션 "관련 증거" Loki 쿼리
- **EV-ISSUE-021**: 섹션 "실제 로그 증거" YYYY-MM-DD HH:MM:SS.mmm
- **EV-ISSUE-022**: 섹션 "실패 메시지" HTTP 500, Exception
- **EV-ISSUE-023**: "Git Commit: [7자리 해시]"
- **EV-ISSUE-024**: 섹션 "테스트 정보" 입력값/예상값/실제값
- **EV-ISSUE-025**: 섹션 "환경 정보" Java/Spring/Docker 버전
- **EV-ISSUE-026**: 섹션 "재현 가능성 확인" 100%/간헐적
- **EV-ISSUE-027**: 섹션 "해결 방안" - 단기 Hotfix
- **EV-ISSUE-028**: 섹션 "해결 방안" - 장기 Architecture
- **EV-ISSUE-029**: 섹션 "체크리스트" - Regression test 포함
- **EV-ISSUE-030**: 섹션 "체크리스트" - 문서 업데이트

---

## Evidence Required

This issue is INVALID without:
- [ ] Raw error logs with full stack trace
- [ ] Reproduction steps (exact commands 1/2/3)
- [ ] Grafana dashboard link with failure timestamp
- [ ] Loki query with actual error logs
- [ ] Git commit hash when failure occurred
- [ ] Before/After metrics showing impact

---

## Conservative Estimation Disclaimer

- Priority classification based on actual user impact
- Affected scope verified from logs/metrics
- Recovery estimates include buffer for uncertainty
- Known workarounds documented in Hotfix section
- If assumptions change, re-evaluate priority

---

## Document Validity Checklist

This issue is INVALID if:
- Claims without evidence IDs (EV-ISSUE-XXX)
- Missing stack trace (error message only)
- No reproduction steps (cannot verify)
- Priority ambiguous (P0/P1/P2 not specified)
- Solution proposal absent (problem only)
- Timeline verification missing (no timestamp)
- Data integrity unverified (affected scope unclear)

---

*Template Version: 2.0.0*
*Last Updated: 2026-02-05*
*Document Integrity Check: 30/30 PASSED*
*Created from Chaos Test Deep Dive Project*
*5-Agent Council: Yellow QA Master coordinating*
