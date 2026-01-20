# Chaos Test 실패 이슈 템플릿

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

*Created from Chaos Test Deep Dive Project*
*5-Agent Council: Yellow QA Master coordinating*
