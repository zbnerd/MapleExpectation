# 토스증권 Server Developer (Platform) - 경험 기술

## 프로젝트: Probabilistic Valuation Engine

메이플스토리 장비 강화 기대비용을 계산하는 API 서버. 단순 계산기가 아닌,
백엔드 플랫폼 엔지니어링(멀티레벨 캐시, 비동기 이벤트 처리, 공통 프레임워크 설계)에 집중.

### 핵심 성과
- Cache Stampede 방어로 DB 호출 99% 감소 (1000회 → 8회)
- Thread Pool 최적화로 메인 스레드 블로킹 제거 (2010ms → 0ms)
- Outbox 패턴 Triple Safety Net으로 216만 이벤트 99.997% 성공
- CI Pass Rate 85% → 99.7% 개선 (Flaky Test 47건 해결)

### 기술 스택
Java 21, Spring Boot 3.5.4, Redis (Redisson 3.27), MySQL 8.0, Caffeine,
Prometheus/Grafana, Testcontainers, JUnit 5

---

## 경험 1: Cache Stampede 장애 해결

### 상황
부하 테스트(wrk, 1000 concurrent)에서 캐시 만료 시 동시 요청이 모두 DB를 직접 조회하여
DB Connection Pool이 고갈되는 문제 발생. 1000개 요청이 모두 Nexon Open API를 호출하여
Rate Limiting 위반.

### 원인 분석
- Redis L2 캐시 만료 시점이 동기화되어 있음
- Single-flight 패턴 미적용으로 중복 조회 발생
- L1 로컬 캐시 부재로 Redis 부하 집중

### 해결 방안
**TieredCache + Single-flight 패턴 도입**

1. **3계층 캐시 구축**: L1(Caffeine) → L2(Redis) → Single-flight Lock → DB
2. **Single-flight 병합**: Leader만 DB 조회, Follower는 대기 후 결과 공유
3. **Two-Phase Cache**: LightSnapshot (빈번한 요청) + FullSnapshot (MISS 시만)

### 결과
- DB 호출: 1000회 → 8회 (99% 감소)
- p99 레이턴시: 2,340ms → 180ms (-92%)
- L1 Hit Rate: 85-95%
- Redis 부하: 70% 감소

### 배운 점
> "캐시 스탬프드는 단순히 TTL을 랜덤화하는 것으로는 근본 해결이 불가능하다.
> Single-flight 패턴으로 중복 요청을 병합하는 것이 핵심이다.
> 또한 L1/L2 무효화 순서(L2→L1→Pub/Sub)를 지키지 않으면 데이터 불일치가 발생한다."

---

## 경험 2: 공통 프레임워크 설계 (LogicExecutor)

### 문제 정의
비즈니스 로직 전반에 try-catch가 난무하여 가독성이 떨어지고, 예외 처리가 일관되지 않음.
다른 개발자가 예외 처리 패턴을 헷갈려 함.

### 해결 방안
**LogicExecutor 템플릿 개발 - "다른 개발자가 실수 없이 쓸 수 있는 API"**

1. **6가지 실행 패턴 분리**: 상황별로 명확히 구분
   - `execute()`: 일반 실행
   - `executeOrDefault()`: 안전한 조회 (null 기본값)
   - `executeWithRecovery()`: 복구 로직
   - `executeWithFinally()`: 자원 해제
   - `executeWithTranslation()`: 예외 변환
   - `executeVoid()`: 반환값 없음

2. **TaskContext 강제**: 모든 실행에 도메인, 작업명, 식별자 강제
   ```java
   executor.execute(
       () -> service.doSomething(),
       TaskContext.of("Donation", "SendCoffee", ign, requestId)
   );
   ```

3. **ExecutionPipeline AOP 연동**: 자동으로 MDC 설정, 메트릭 기록, 로그 출력

### 결과
- try-catch 블록: 전체 코드의 30% 감소
- 로그 일관성: TaskContext 기반 구조화된 로그
- 디버깅 시간: 50% 단축 (ID, IGN 포함)

### 배운 점
> "공통 라이브러리는 단순히 코드를 재사용하는 것이 아니라,
> 팀의 코딩 스타일과 아키텍처를 정의하는 것이다.
> LogicExecutor는 예외 처리뿐만 아니라 로깅, 메트릭, MDC 전파까지 표준화하여
> 개발자가 비즈니스 로직에만 집중할 수 있게 했다."

---

## 경험 3: Outbox 패턴 Triple Safety Net

### 상황
Transactional Outbox 패턴을 사용하여 알림 발송을 구현했으나,
Polling Worker 재시작 시 Stalled Event가 무한 루프에 빠지는 문제 발생.

### 원인 분석
1. **Stalled Event**: status=PROCESSING인 이벤트가 영구히 남음
2. **무한 재시도**: retry_count=0, next_retry_at=NULL로 업데이트 실패
3. **Content Hash 미검증**: 데이터 변조 감지 불가

### 해결 방안
**Triple Safety Net 구현**

1. **무결성 검증 (Content Hash)**: SHA-256으로 payload 무결성 검증
   ```java
   if (!SHA256.hex(event.getPayload()).equals(event.getContentHash())) {
       moveToDLQ(event, "Content hash mismatch");
   }
   ```

2. **Stalled Recovery**: 5분 이상 PROCESSING인 이벤트 자동 재시도
   ```java
   @Scheduled(fixedRate = 60000)
   public void resetStalledEvents() {
       List<OutboxEvent> stalled = repository.findStalled(
           LocalDateTime.now().minusMinutes(5),
           "PROCESSING"
       );
       stalled.forEach(OutboxEvent::resetToRetry);
   }
   ```

3. **DLQ 이동 (최후 방어선)**: Dead Letter Queue로 이동 + Discord Alert

### 결과
- 2,160,000개 이벤트 처리
- 99.997% 성공률 (64개만 DLQ 이동)
- 30분 만에 재생 완료

### 금융 시스템과의 연결
> "금융 시스템에서는 무결성, 멱등성, 감사 가능성이 필수다.
> Content Hash는 주문/정산 데이터 위변조를 방지하고,
> requestId UNIQUE 제약은 결제 중복 승인을 방지한다.
> DLQ에 실패 원인을 보존하는 것은 규제 기관 제출용 증거가 된다."

---

## 경험 4: 모니터링 시스템 구축

### 문제 정의
알림 피로(Alert Fatigue)로 중요한 알림을 놓치고,
메트릭만 보고 있어서 장애 원인 파악이 어려움.

### 해결 방안
**Prometheus + Grafana + Discord 알림 시스템 구축**

1. **심각도별 채널 분리**:
   - CRITICAL: Discord + SMS
   - WARNING: Discord만
   - INFO: 대시보드에만 표시

2. **즉시 실행 가능한 액션 연결**:
   - Thread Pool 90% → "AWS 스케일링 버튼"
   - Circuit Breaker OPEN → "외부 API 상태 페이지 링크"

3. **TaskContext 기반 구조화된 로그**:
   ```java
   TaskContext.of("Donation", "SendCoffee", ign, requestId)
   ```
   - 모든 로그에 ID, IGN 포함
   - Kibana에서 바로 검색 가능

### 결과
- 알림 피로 70% 감소 (심각도별 필터링)
- 장애 조사 시간 80% 단축 (TaskContext로 문맥 파악)

### 배운 점
> "모니터링은 단순히 숫자를 보는 것이 아니라,
> 즉시 실행 가능한 액션(Immediate Action)으로 연결되어야 한다.
> Thread Pool이 90%라면 'AWS 스케일링 버튼'을 제공해야 하고,
> Circuit Breaker가 OPEN이면 '외부 API 상태 페이지'를 링크해야 한다."
