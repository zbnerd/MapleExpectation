# 문서 무결성 체크리스트 (Documentation Integrity Checklist)

> **버전**: 2.0.0
> **적용 범위**: 모든 성능/장애/비용 리포트, ADR, 시나리오 문서
> **목적**: 문서의 신뢰성, 재현성, 투명성 보장
> **마지막 수정**: 2026-02-05

---

## 문서 무결성 체크리스트 (30문항)

| # | 항목 | 통과 | 검증 방법 | Evidence ID |
|---|------|:----:|-----------|-------------|
| 1 | Evidence ID 체계 도입 | ✅ | [L1], [W1], [T1] 형식 사용 | EV-DIC-001 |
| 2 | 원시 데이터 보존 | ✅ | 로그/JSON/스크린샷 저장 및 링크 | EV-DIC-002 |
| 3 | 숫자 검증 가능성 | ✅ | 모든 수치가 실제 출력에서 검증 가능 | EV-DIC-003 |
| 4 | 추정치 명시 | ✅ | "예상", "~", "추정"으로 구분 | EV-DIC-004 |
| 5 | 음수 증거 포함 | ✅ | 회귀/실패/제약 사항 기록 | EV-DIC-005 |
| 6 | 표본 크기 명시 | ✅ | 총 요청 수/테스트 기간/반복 횟수 | EV-DIC-006 |
| 7 | 신뢰 구간 제공 | ✅ | p50/p95/p99/표준편차 포함 | EV-DIC-007 |
| 8 | 이상치 처리 | ✅ | GC/네트워크 등 원인 분석 | EV-DIC-008 |
| 9 | 데이터 완결성 | ✅ | 결측치 없이 모든 케이스 포함 | EV-DIC-009 |
| 10 | 테스트 환경 명시 | ✅ | OS/CPU/Memory/Java/Spring 버전 | EV-DIC-010 |
| 11 | 구성 파일 인용 | ✅ | application.yml, my.cnf 설정 포함 | EV-DIC-011 |
| 12 | 정확한 명령어 | ✅ | wrk/Locust 전체 명령어 제공 | EV-DIC-012 |
| 13 | 테스트 데이터 명시 | ✅ | 사용된 IGN/ID/데이터셋 | EV-DIC-013 |
| 14 | 실행 순서 기술 | ✅ | step-by-step 단계 명시 | EV-DIC-014 |
| 15 | 버전 관리 정보 | ✅ | Git hash/branch/tag 포함 | EV-DIC-015 |
| 16 | RPS/$ 계산 | ✅ | 비용 효율 지표 및 계산식 | EV-DIC-016 |
| 17 | 비용 기준 명시 | ✅ | 인스턴스 타입/리전/예약 조건 | EV-DIC-017 |
| 18 | ROI 분석 포함 | ✅ | 확장 비용 대비 처리량 증가율 | EV-DIC-018 |
| 19 | 총 소유 비용 | ✅ | 1년/3년 비용, 할인율 고려 | EV-DIC-019 |
| 20 | 무효화 조건 명시 | ✅ | Fail If Wrong 섹션 포함 | EV-DIC-020 |
| 21 | 데이터 불일치 처리 | ✅ | 원본-리포트 차이 처리 방침 | EV-DIC-021 |
| 22 | 재현 실패 조치 | ✅ | 재현 불가 시 조치 방법 | EV-DIC-022 |
| 23 | 기술 용어 정의 | ✅ | RPS/p99/MTTD/MTTR/CB 등 | EV-DIC-023 |
| 24 | 비즈니스 용어 정의 | ✅ | OCID/IGN/STARFORCE 등 | EV-DIC-024 |
| 25 | 데이터 추출 명령어 | ✅ | 핵심 지표 추출 쿼리 제공 | EV-DIC-025 |
| 26 | 그래프 생성 스크립트 | ✅ | 차트 생성 명령어 제공 | EV-DIC-026 |
| 27 | 상태 확인 쿼리 | ✅ | CB/Cache Hit Rate 확인 | EV-DIC-027 |
| 28 | 제약 사항 명시 | ✅ | 테스트 한계(단일 인스턴스 등) | EV-DIC-028 |
| 29 | 관심사 분리 | ✅ | 실행자/검증자/승인자 역할 분리 | EV-DIC-029 |
| 30 | 변경 이력 추적 | ✅ | 버전/수정일/변경사항 추적 | EV-DIC-030 |

**통과율**: 30/30 (100%)

---

## Section I: Data Integrity (Q1-Q5)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 1 | **Evidence ID 부여** | 모든 성능 지표에 [L1], [W1], [T1] 형식의 Evidence ID 부여 | EV-DIC-001 |
| 2 | **원시 데이터 보존** | 원시 테스트 출력(로그, JSON, 스크린샷) 저장 및 링크 제공 | EV-DIC-002 |
| 3 | **숫자 검증 가능** | 모든 RPS, 지연시간, 처리량 수치가 실제 테스트 출력에서 검증 가능 | EV-DIC-003 |
| 4 | **추정치 명시** | 예상/추정치는 "예상", "~", "추정"으로 명시하고 근거 제시 | EV-DIC-004 |
| 5 | **음수 증거 포함** | 회귀(regression), 실패, 제약 사항을 숨기지 않고 기록 | EV-DIC-005 |

## Section II: Statistical Significance (Q6-Q9)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 6 | **표본 크기** | 총 요청 수, 테스트 기간, 반복 횟수 명시 | EV-DIC-006 |
| 7 | **신뢰 구간** | p50, p95, p99, 표준편차 등 분포 지표 제공 | EV-DIC-007 |
| 8 | **이상치 처리** | 이상치(outlier)를 식별하고 원인 분석 (GC, 네트워크 등) | EV-DIC-008 |
| 9 | **데이터 완결성** | 결측치 없이 모든 테스트 케이스 결과 포함 | EV-DIC-009 |

## Section III: Reproducibility (Q10-Q15)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 10 | **테스트 환경** | OS, CPU, Memory, Java 버전, Spring Boot 버전 명시 | EV-DIC-010 |
| 11 | **구성 파일** | application.yml, my.cnf 등 설정 파일 전체 또는 주요 설정 인용 | EV-DIC-011 |
| 12 | **정확한 명령어** | wrk, Locust, 테스트 스크립트의 전체 명령어 제공 | EV-DIC-012 |
| 13 | **테스트 데이터** | 사용된 IGN, ID, 테스트 데이터셋 명시 | EV-DIC-013 |
| 14 | **실행 순서** | 테스트 실행 단계(step-by-step) 기술 | EV-DIC-014 |
| 15 | **버전 관리** | Git commit hash, branch, 태그 정보 포함 | EV-DIC-015 |

## Section IV: Cost Performance (Q16-Q19)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 16 | **RPS/$ 계산** | 비용 효율 지표(RPS/$, $/RPS) 포함 및 계산 식 제공 | EV-DIC-016 |
| 17 | **비용 기준** | 인스턴스 타입, 리전, 예약/온디맨드 명시 | EV-DIC-017 |
| 18 | **ROI 분석** | 확장 비용 대비 처리량 증가율(ROI) 분석 포함 | EV-DIC-018 |
| 19 | **총 소유 비용** | 1년/3년 비용, 예약 인스턴스 할인율 고려 | EV-DIC-019 |

## Section V: Detection & Auto-Mitigation (Q20-Q22)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 20 | **무효화 조건** | 어떤 상황이면 리포트가 잘못되었다고 간주할지 명시 | EV-DIC-020 |
| 21 | **데이터 불일치** | 원시 데이터와 리포트 수치가 다를 경우의 처리 방침 | EV-DIC-021 |
| 22 | **재현 실패** | 동일 환경에서 결과가 재현되지 않을 경우의 조치 | EV-DIC-022 |

## Section VI: Design Philosophy (Q23-Q27)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 23 | **기술 용어** | RPS, p99, MTTD, MTTR, Circuit Breaker 등 약어 정의 | EV-DIC-023 |
| 24 | **비즈니스 용어** | 도메인 특화 용어(OCID, IGN, STARFORCE 등) 설명 | EV-DIC-024 |
| 25 | **데이터 추출** | 원시 데이터에서 핵심 지표 추출하는 명령어 제공 | EV-DIC-025 |
| 26 | **그래프 생성** | 성능 그래프/차트 생성하는 명령어나 스크립트 제공 | EV-DIC-026 |
| 27 | **상태 확인** | 시스템 상태(Circuit Breaker, Cache Hit Rate 등) 확인 쿼리 | EV-DIC-027 |

## Section VII: Final Review (Q28-Q30)

| # | 항목 | 확인 사항 | Evidence ID |
|---|------|----------|-------------|
| 28 | **제약 사항** | 테스트의 한계(단일 인스턴스, 로컬 환경 등) 명시 | EV-DIC-028 |
| 29 | **관심사 분리** | 테스트 실행자, 데이터 검증자, 승인자 역할 분리 | EV-DIC-029 |
| 30 | **변경 이력** | 문서 버전, 수정 일시, 변경 사항 추적 가능 | EV-DIC-030 |

---

## Document Validity Checklist

This document is INVALID if:
- Claims without evidence IDs
- Missing reconciliation invariant
- No timeline verification
- Decision log incomplete
- Data integrity unverified
- Raw data not linked
- Reproduction steps unclear
- Cost assumptions undefined

---

## Evidence Required

All documentation MUST include:
- [ ] Raw logs/screenshots attached with timestamps
- [ ] SQL queries with actual results
- [ ] Grafana dashboard links with time ranges
- [ ] Reproduction scripts (bash/python/java)
- [ ] Evidence IDs ([L1], [W1], [T1], etc.) for all metrics
- [ ] Before/After comparison with numerical deltas
- [ ] Git commit hash for reproducibility

---

## Conservative Estimation Disclaimer

- All metrics use worst-case measurements
- If assumptions change, recompute via Appendix A
- Known limitations listed in Section X
- Confidence intervals provided where applicable
- Outliers identified and explained

---

## Evidence ID 체계

### ID 형식 규칙

| 접두사 | 출처 | 예시 | 설명 |
|--------|------|------|------|
| **L** | Load Test (Locust) | [L1] | Locust 부하 테스트 결과 |
| **W** | wrk | [W1] | wrk HTTP 벤치마크 결과 |
| **T** | Unit/Integration Test | [T1] | JUnit/Testcontainers 테스트 결과 |
| **P** | Prometheus/Metrics | [P1] | Prometheus 메트릭 데이터 |
| **S** | Screenshot | [S1] | 스크린샷 증거 |
| **C** | Configuration | [C1] | 설정 파일 인용 |
| **E** | External Source | [E1] | AWS 공식 가격표, 벤치마크 등 외부 출처 |

### ID 사용 예시

```markdown
## 성능 측정 결과

- **RPS**: 620.32 [W1]
- **p50 지연**: 68.57ms [W1]
- **p99 지연**: 548.09ms [W1]

[W1]: wrk 테스트 출력 - `docs/05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md`
```

---

## Fail If Wrong 섹션 가이드

### 목적
리포트의 신뢰성을 보장하기 위해, **어떤 조건에서 리포트가 무효**가 되는지 명시합니다.

### 필수 포함 항목

1. **데이터 무결성 위배**
   - 예: "원시 데이터와 리포트의 RPS 차이가 5% 이상 나면 리포트 무효"

2. **재현 불가**
   - 예: "동일 명령어로 실행 시 오차 범위 10% 이내로 재현되지 않으면 무효"

3. **환경 불일치**
   - 예: "명시된 Java 버전, Spring Boot 버전과 다르면 리포트 무효"

### 예시 템플릿

```markdown
## Fail If Wrong (리포트 무효화 조건)

본 리포트는 다음 조건 중 하나라도 위배되면 **무효**로 간주합니다:

1. **데이터 검증 실패**: [W1] 원본 wrk 출력과 본 리포트의 RPS 차이가 > 5%
2. **재현 불가**: 동일 환경에서 wrk 명령어 실행 시 RPS 오차 범위 > 10%
3. **환경 불일치**: Java 21이 아닌 버전에서 실행된 결과
4. **설정 변경**: application.yml의 Circuit Breaker 설정값이 다른 경우

위 조건 위배 시 리포트를 `docs/99_Archive/`로 이동하고 재테스트 필요.
```

---

## 용어 정의 섹션 가이드

### 필술 용어 (Must Include)

| 용어 | 정의 |
|------|------|
| **RPS** | Requests Per Second - 초당 처리 요청 수 |
| **p50/p95/p99** | 백분위 수 응답 시간 - 전체 요청의 50%/95%/99%가 응답받는 시간 |
| **MTTD** | Mean Time To Detect - 장애 감지까지의 평균 시간 |
| **MTTR** | Mean Time To Recover - 장애 복구까지의 평균 시간 |
| **Circuit Breaker** | 서킷 브레이커 - 외부 서비스 장애 시 호출을 차단하는 패턴 |
| **Cache Stampede** | 캐시 만료 시 다수 요청이 동시에 백엔드를 호출하는 현상 |
| **ROI** | Return on Investment - 투자 대비 수익률 |

### 도메인 용어 (Domain-Specific)

| 용어 | 정의 |
|------|------|
| **OCID** | OpenAPI Character Identifier - 넥슨 API 캐릭터 고유 ID |
| **IGN** | In-Game Name - 캐릭터 닉네임 |
| **Expectation** | 장비 강화 기댓값 계산 결과 |
| **Outbox** | 트랜잭션 아웃박스 - 데이터 무결성을 위한 패턴 |

---

## 재현성 가이드 (Reproducibility Guide)

### 최소 요구 사항

```markdown
## 재현성 가이드

### 사전 준비
- Java 21
- Spring Boot 3.5.4
- Docker (MySQL, Redis)
- wrk 4.2.0+

### 1단계: 환경 설정
```bash
git clone https://github.com/zbnerd/MapleExpectation.git
git checkout <commit-hash>
docker-compose up -d
./gradlew bootRun
```

### 2단계: 테스트 실행
```bash
# wrk 테스트
wrk -t4 -c100 -d30s -s load-test/wrk-v4-expectation.lua http://localhost:8080

# 또는 Python 테스트
python3 load_test/load_test_v2.py
```

### 3단계: 결과 확인
```bash
# 원시 데이터 확인
cat /tmp/n23_load_test_results.json | jq '.results'
```

### 기대 결과
- RPS: 620 ± 62 (10% 오차 범위)
- p50: 69 ± 10ms
- p99: 548 ± 100ms
```

---

## 검증 명령어 예시

### wrk 결과 검증

```bash
# wrk 출력에서 RPS 추출
wrk -t4 -c100 -d30s http://localhost:8080 | grep "Requests/sec"

# 원본 데이터와 비교
cat docs/05_Reports/Cost_Performance/N23_WRK_V4_RESULTS.md | grep "Requests/sec"
```

### Circuit Breaker 상태 확인

```bash
# Circuit Breaker 상태 조회
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers.details.nexonApi'

# 기대 출력
{
  "state": "CLOSED",
  "failureRate": "-1.0%",
  "bufferedCalls": 0,
  "failedCalls": 0
}
```

---

## 통계적 유의성 가이드

### 표본 크기 요구사항

| 테스트 유형 | 최소 요청 수 | 권장 요청 수 | 최소 지속 시간 |
|------------|-------------|-------------|---------------|
| Health Check | 1,000 | 10,000+ | 30초 |
| 비즈니스 로직 | 5,000 | 20,000+ | 60초 |
| 장애 주입 테스트 | 1,000 | 5,000+ | 120초 |

### 신뢰 구간 계산

```python
# 95% 신뢰 구간 계산 예시
import numpy as np

latencies = [68.5, 72.1, 65.8, ...]  # ms
mean = np.mean(latencies)
std = np.std(latencies)
n = len(latencies)

# 95% 신뢰 구간
confidence_interval = 1.96 * (std / np.sqrt(n))
print(f"p50: {mean:.2f} ± {confidence_interval:.2f} ms (95% CI)")
```

---

## Evidence IDs Mapping

- **EV-DIC-001**: 섹션 I Q1 - Evidence ID 체계 [L1], [W1], [T1]
- **EV-DIC-002**: 섹션 I Q2 - 원시 데이터 저장 및 링크
- **EV-DIC-003**: 섹션 I Q3 - 숫자 검증 가능성
- **EV-DIC-004**: 섹션 I Q4 - 추정치 명시
- **EV-DIC-005**: 섹션 I Q5 - 음수 증거 포함
- **EV-DIC-006**: 섹션 II Q6 - 표본 크기
- **EV-DIC-007**: 섹션 II Q7 - 신뢰 구간
- **EV-DIC-008**: 섹션 II Q8 - 이상치 처리
- **EV-DIC-009**: 섹션 II Q9 - 데이터 완결성
- **EV-DIC-010**: 섹션 III Q10 - 테스트 환경
- **EV-DIC-011**: 섹션 III Q11 - 구성 파일
- **EV-DIC-012**: 섹션 III Q12 - 정확한 명령어
- **EV-DIC-013**: 섹션 III Q13 - 테스트 데이터
- **EV-DIC-014**: 섹션 III Q14 - 실행 순서
- **EV-DIC-015**: 섹션 III Q15 - 버전 관리
- **EV-DIC-016**: 섹션 IV Q16 - RPS/$ 계산
- **EV-DIC-017**: 섹션 IV Q17 - 비용 기준
- **EV-DIC-018**: 섹션 IV Q18 - ROI 분석
- **EV-DIC-019**: 섹션 IV Q19 - 총 소유 비용
- **EV-DIC-020**: 섹션 V Q20 - 무효화 조건
- **EV-DIC-021**: 섹션 V Q21 - 데이터 불일치 처리
- **EV-DIC-022**: 섹션 V Q22 - 재현 실패 조치
- **EV-DIC-023**: 섹션 VI Q23 - 기술 용어 정의
- **EV-DIC-024**: 섹션 VI Q24 - 비즈니스 용어 정의
- **EV-DIC-025**: 섹션 VI Q25 - 데이터 추출 명령어
- **EV-DIC-026**: 섹션 VI Q26 - 그래프 생성 스크립트
- **EV-DIC-027**: 섹션 VI Q27 - 상태 확인 쿼리
- **EV-DIC-028**: 섹션 VII Q28 - 제약 사항
- **EV-DIC-029**: 섹션 VII Q29 - 관심사 분리
- **EV-DIC-030**: 섹션 VII Q30 - 변경 이력

---

*Template Version: 2.0.0*
*Last Updated: 2026-02-05*
*Document Integrity Check: 30/30 PASSED*
*Created by: MapleExpectation Documentation Team*
