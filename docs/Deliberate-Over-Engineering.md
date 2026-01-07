
Geek <mps756@gmail.com>
PM 1:11 (0분 전)
나에게

# MapleExpectation — 의도된 상향 설계(Deliberate Over-Engineering) 설명서

> **요약 한 줄**  
> 외부 API에 강하게 의존하는 서비스 특성상, 실제로 장애가 발생한 지점만을 기준으로  
> 동시성 · 캐시 · 장애 격리를 **의도적으로 상향 설계**한 프로젝트입니다.

---

## 1. 프로젝트 성격 정의

- **도메인**: 메이플스토리 Open API 기반 시뮬레이션/조회 서비스
- **핵심 제약**
    - 외부 API 의존도 높음 (Latency / Failure Control 불가)
    - 오픈런 시 특정 유저·장비에 대한 **동시 요청 집중**
    - 조회 트래픽 대비 쓰기 트래픽은 극히 낮음
- **설계 우선순위**
    1. 데이터 정합성
    2. 장애 격리 및 복구 가능성
    3. 성능은 그 다음

---

## 2. 실제로 발생했던 문제 (Problem Driven Design)

### 2.1 동시성 문제
- 동일 `userIgn`에 대해 **동시 생성 요청**
- Check-then-Act 로직으로 인해:
    - 중복 INSERT 시도
    - DB Unique 제약 위반
    - HTTP 500 에러 발생

### 2.2 캐시 스탬피드
- 캐시 만료 시 다수 요청이 동시에 DB/외부 API로 쏠림
- 응답 지연 및 외부 API Rate Limit 위험

### 2.3 외부 API 장애
- API 지연/실패 시:
    - 전체 요청 스레드 대기
    - 서비스 전체 품질 저하

### 2.4 예외 처리 파편화
- IO / Streaming / Aspect / Monitoring 영역에서:
    - Checked Exception → RuntimeException 변환 난립
    - Nested try-catch 다수 존재
- 장애 분석 및 재현 난이도 급증

---

## 3. 핵심 설계 선택 및 이유

### 3.1 동시성 제어 & 멱등성 보장

| 선택 | 이유 |
|---|---|
| DB Unique 제약을 최종 보루로 유지 | 데이터 무결성 100% 보장 |
| 예외 발생 시 재조회(Catch & Retry) | Happy Path 성능 보호 |
| JVM `synchronized` → Redis 분산 락 | Scale-out 대비 |

> **의도**: “락으로 막기”보다 **깨졌을 때 안전하게 회복**

---

### 3.2 다중 계층 캐시 전략

- **L1**: Caffeine (In-Memory)
- **L2**: Redis
- **L3**: MySQL (Cache-Aside)

추가 전략:
- Negative Caching
- Request Collapsing (Pub/Sub 기반)

> **의도**: 조회 폭주 상황에서도 외부 API 보호

---

### 3.3 외부 API 장애 대응 (Resilience4j)

도입 요소:
- Circuit Breaker
- TimeLimiter
- Fail-Fast 전략

시나리오 기반 대응:
- API 실패 + 캐시 존재 → Degrade
- API 실패 + 캐시 없음 → 빠른 실패
- API 지연 → 격리

> **의도**: 외부 장애가 내부 장애로 전파되지 않도록 차단

---

### 3.4 예외 처리 정책 중앙화 (LogicExecutor)

#### 문제
- try-catch 난립
- 예외 타입 / 로그 / 복구 전략 파편화
- 비즈니스 로직 가독성 저하

#### 해결
- `LogicExecutor` 도입
- 예외 처리 패턴 **8종 표준화**
- Checked Exception 처리 정책을 단일 지점에서 관리

> **의도**:  
> 비즈니스 로직은 “무엇을 할 것인가”에만 집중  
> 예외 처리는 “어떻게 보호할 것인가”를 전담 분리

---

## 4. 오버엔지니어링이 아닌 이유

- Kafka / MQ ❌ (필요 없음)
- Redis 사용 목적:
    - 속도 ❌
    - **정합성 · 중복 방지 · 장애 격리** ✅
- 모든 인프라는 **Interface 뒤에 배치**
    - 제거/교체 비용 최소화
    - 필요 시 확장 가능

> “지금 완성”이 아니라 **미래 전환 비용을 줄이는 설계**

---

## 5. 검증 방식

- 동시성 재현 테스트 (CountDownLatch)
- 부하 테스트 (오픈런/큰손유저 시나리오)
- 장애 주입 테스트
    - 외부 API 실패
    - Redis 장애
    - 캐시 미스 연쇄 상황
- 모든 결정은 **Issue 단위로 Decision / Trade-off 문서화**

---

## 6. 한 문장 결론

> **MapleExpectation은 기능 데모가 아니라,  
> “서비스가 실제로 깨지는 지점을 어떻게 방어했는지”를 보여주는 프로젝트입니다.**

---