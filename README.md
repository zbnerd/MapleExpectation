# 🍁 MapleExpectation

## 0. Professional Summary
> **"시스템의 가용성과 확장성을 숫자로 증명하고, 장애 대응 시나리오를 설계하는 엔지니어"**
>
> 단순히 기능을 구현하는 것을 넘어, **수평적 확장(Scale-out)**이 가능한 분산 환경을 설계합니다. 
> 외부 의존성 장애가 전체 시스템으로 전이되지 않도록 **회복 탄력성(Resilience)**을 확보하고, 
> 데이터 정합성과 가용성의 최적점을 찾는 엔지니어링 결정에 강점이 있습니다.

---

## 1. 프로젝트 소개
**넥슨 Open API**를 활용하여 유저 장비 데이터를 수집하고, 확률형 아이템(큐브)의 기댓값을 계산하여 **"스펙 완성 비용"을 시뮬레이션해주는 서비스**입니다.

저사양 서버(t3.small) 환경에서 **1,000명 이상의 동시 접속자**를 안정적으로 수용하기 위해 성능 병목을 수치화하고 아키텍처를 고도화했습니다. 특히 분산 환경에서의 **데이터 무결성**과 **외부 API 장애 격리**를 설계로 증명하는 데 집중했습니다.

---

## 2. 프로젝트 아키텍처


> **[System Architecture Key Point]**
> * **Distributed Lock:** Redisson 기반 분산 락을 통한 멀티 인스턴스 환경의 정합성 확보
> * **Circuit Breaker:** 외부 API 장애 전파 차단 및 시나리오 기반 Fallback 전략
> * **Transactional Outbox:** 결제(후원) 트랜잭션과 외부 알림 발행의 원자적 보장 (Reliability)
> * **Write-Behind:** 동시성 처리를 위한 In-Memory Buffering 및 DB I/O 절감

---

## 3. 핵심 기술적 성과 (Key Engineering)

### 🛡️ 장애 격리 및 회복 탄력성 (Resilience)
- **문제 (Problem):** 외부 API(Nexon) 장애 시 내부 워커 스레드가 타임아웃까지 대기하며 연쇄 장애(Cascading Failure) 발생 위험.
- **해결 (Action):** **Resilience4j Circuit Breaker** 도입 및 장애 대응 **Scenario A/B/C** 명세화.
    * **Scenario A (Degrade):** API 실패 시 만료된 로컬 캐시 반환으로 서비스 유지.
    * **Scenario B (Fail-fast):** 캐시 부재 시 **14ms 내 즉시 에러 응답** 및 운영팀 알림.
- **결과 (Result):** 외부 장애 상황에서도 시스템 가용성을 유지하며 불필요한 자원 점유 차단.


### 🔗 분산 환경 동시성 제어 및 확장성 (Scalability)
- **문제 (Problem):** 서버 수평 확장(Scale-out) 시 단일 서버 락 사용 불가 및 스케줄러 중복 실행 이슈.
- **해결 (Action):** **Redisson 분산 락** 도입 및 **DB 원자적 연산(Atomic Update)**으로 리팩토링.
- **결과 (Result):** 멀티 인스턴스 환경의 무결성을 확보하며, 동시 요청 처리 성능 **480% 향상**(5.3s → 1.1s).


### ⚡ 데이터 최적화 및 I/O 효율화 (Efficiency)
- **I/O 병목 95% 해소 (GZIP):** 대용량 JSON(350KB)을 GZIP 압축 저장하여 스토리지 비용 및 네트워크 대역폭 95% 절감.
- **스트리밍 직렬화:** **StreamingResponseBody** 도입으로 힙 메모리 적재 최소화 및 RPS 11배 향상.
- **인덱스 튜닝:** EXPLAIN 분석 기반 복합 인덱스 설계로 조회 성능 **50배 개선**(0.98s → 0.02s).


---

## 4. 협업 기반 개발 프로세스 (Git Flow)
<img width="1000" height="420" alt="image" src="https://github.com/user-attachments/assets/21ff1b7f-e0e6-4656-b9dd-5e292891a22f" />

> **[Engineering Culture]**
> * **Issue-Driven Development:** 모든 작업은 이슈 발행(Problem, Goal, DoD 명세) 후 시작.
> * **Rationale in PR:** 76개의 PR마다 기술적 선택의 근거와 트레이드오프 기록.
> * **DX Optimization:** AOP와 JUnit Extension으로 성능 측정 자동화 및 로그 스팸 해결.

---

## 5. 기술 스택 (Tech Stack)
- **Backend:** Java 17, Spring Boot 3.x, Spring Data JPA
- **Database & Cache:** MySQL, Redis (Redisson), Caffeine Cache
- **Testing & Monitoring:** JUnit 5, Locust (부하 테스트), Micrometer, Actuator
- **Infra & DevOps:** AWS EC2, GitHub Actions, Git Flow

---

## 📈 모니터링 및 운영 가이드 (Operational Checklist)

### 1. 핵심 모니터링 지표 (Core Metrics)

| 분류 | 지표 명칭 (Metric Name) | 위험 임계치 (Threshold) | 비즈니스 의미 및 대응 로직 |
| :--- | :--- | :--- | :--- |
| **Redis** | `redis.connection.error` | **> 0** | **Critical:** 분산 락 및 캐시 불능. **Fallback** 작동 확인. |
| **Resilience** | `circuitbreaker.state` | **"open"** | **Error:** 외부 API 장애로 인한 서킷 개방. 의존성 차단. |
| **App** | `like.buffer.total_pending` | **> 1,000** | **Saturation:** 좋아요 버퍼 유입 속도가 DB 반영보다 빠름. |
| **DB** | `hikaricp.connections.pending` | **> 0** | **Saturation:** DB 커넥션 풀 고갈. 쿼리 성능 점검. |

### 2. 장애 감지 및 알림 Flow (Alerting)
- **임계치 초과 시:** `DiscordAlertService`를 통해 개발팀 채널로 즉시 Critical Alert 전송.
- **추적 ID 활용:** 모든 에러 로그는 **MDC 기반 8자리 `requestId`**와 연결되어 빠른 MTTR 확보.
