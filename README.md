# 🍁 MapleExpectation

## 0. Professional Summary
> [cite_start]**"시스템의 가용성과 확장성을 숫자로 증명하고, 장애 대응 시나리오를 설계하는 엔지니어"** [cite: 516]
>
> [cite_start]단순히 기능을 구현하는 것을 넘어, **수평적 확장(Scale-out)**이 가능한 분산 환경을 설계합니다[cite: 602]. 
> [cite_start]외부 의존성 장애가 전체 시스템으로 전이되지 않도록 **회복 탄력성(Resilience)**을 확보하고[cite: 603], 
> [cite_start]데이터 정합성과 가용성의 최적점을 찾는 엔지니어링 결정에 강점이 있습니다[cite: 601].

---

## 1. 프로젝트 소개
[cite_start]**넥슨 Open API**를 활용하여 유저 장비 데이터를 수집하고, 확률형 아이템(큐브)의 기댓값을 계산하여 **"스펙 완성 비용"을 시뮬레이션해주는 서비스**입니다[cite: 546].

[cite_start]저사양 서버(t3.small) 환경에서 **1,000명 이상의 동시 접속자**를 안정적으로 수용하기 위해 성능 병목을 수치화하고 아키텍처를 고도화했습니다[cite: 549]. [cite_start]특히 분산 환경에서의 **데이터 무결성**과 **외부 API 장애 격리**를 설계로 증명하는 데 집중했습니다[cite: 547].

---

## 2. 프로젝트 아키텍처
<img width="1074" height="771" alt="image" src="https://github.com/user-attachments/assets/1531c4d4-df09-4e58-8263-89c7c2785b3f" />

> **[System Architecture Key Point]**
> [cite_start]* **Distributed Lock:** Redisson 기반 분산 락을 통한 멀티 인스턴스 환경의 정합성 확보 [cite: 521, 617]
> [cite_start]* **Circuit Breaker:** 외부 API 장애 전파 차단 및 시나리오 기반 Fallback 전략 [cite: 524, 621]
> * **Transactional Outbox:** 결제(후원) 트랜잭션과 외부 알림 발행의 원자적 보장 (Reliability)
> [cite_start]* **Write-Behind:** 동시성 처리를 위한 In-Memory Buffering 및 DB I/O 절감 [cite: 522, 619]

---

## 3. 핵심 기술적 성과 (Key Engineering)

### 🛡️ 장애 격리 및 회복 탄력성 (Resilience)
- [cite_start]**문제 (Problem):** 외부 API(Nexon) 장애 시 내부 워커 스레드가 타임아웃까지 대기하며 연쇄 장애(Cascading Failure) 발생 위험[cite: 621].
- [cite_start]**해결 (Action):** **Resilience4j Circuit Breaker** 도입 및 장애 대응 **Scenario A/B/C** 명세화[cite: 524, 623].
    * [cite_start]**Scenario A (Degrade):** API 실패 시 만료된 로컬 캐시 반환으로 서비스 유지[cite: 525].
    * [cite_start]**Scenario B (Fail-fast):** 캐시 부재 시 **14ms 내 즉시 에러 응답** 및 운영팀 알림[cite: 525, 622].
- [cite_start]**결과 (Result):** 외부 장애 상황에서도 시스템 가용성을 유지하며 불필요한 자원 점유 차단[cite: 525, 622].


### 🔗 분산 환경 동시성 제어 및 확장성 (Scalability)
- [cite_start]**문제 (Problem):** 서버 수평 확장(Scale-out) 시 단일 서버 락 사용 불가 및 스케줄러 중복 실행 이슈[cite: 551, 617].
- [cite_start]**해결 (Action):** **Redisson 분산 락** 도입 및 **DB 원자적 연산(Atomic Update)**으로 리팩토링[cite: 521, 552, 617, 618].
- [cite_start]**결과 (Result):** 멀티 인스턴스 환경의 무결성을 확보하며, 동시 요청 처리 성능 **480배 향상**(5.3s → 1.1s)[cite: 555, 618].


### 💰 결제 무결성을 위한 Transactional Outbox 패턴
- **문제 (Problem):** 후원(커피 사주기) 결제 성공 후 외부 알림(Discord) 발행 실패 시 발생하는 데이터 불일치 위험.
- **해결 (Action):** **Outbox 패턴** 적용. 결제와 알림 이벤트를 동일 DB 트랜잭션 내 Outbox 테이블에 저장 후 별도 릴레이어가 발행.
- **결과 (Result):** 외부 시스템 장애와 관계없이 결제 데이터의 신뢰성을 보장하고 **최소 한 번 이상의 배달(At-least-once)** 실현.


### ⚡ 데이터 최적화 및 I/O 효율화 (Efficiency)
- [cite_start]**I/O 병목 95% 해소 (GZIP):** 대용량 JSON(350KB)을 GZIP 압축 저장하여 스토리지 비용 및 네트워크 대역폭 95% 절감[cite: 528, 529, 626].
- [cite_start]**스트리밍 직렬화:** **StreamingResponseBody** 도입으로 힙 메모리 적재 최소화 및 RPS 11배 향상[cite: 528, 561, 627].
- **격리 수준 최적화 (RC):** 빈번한 수정 로직에 **Read Committed** 적용으로 갭 락(Gap Lock) 제거 및 데드락 리스크 감소.
- [cite_start]**인덱스 튜닝:** EXPLAIN 분석 기반 복합 인덱스 설계로 조회 성능 **50배 개선**(0.98s → 0.02s)[cite: 530, 563].


---

## 4. 협업 기반 개발 프로세스 (Git Flow)
<img width="1000" height="420" alt="image" src="https://github.com/user-attachments/assets/21ff1b7f-e0e6-4656-b9dd-5e292891a22f" />

> [cite_start]**[Engineering Culture]** [cite: 568]
> * **Issue-Driven Development:** 모든 작업은 이슈 발행(Problem, Goal, DoD 명세) 후 시작.
> [cite_start]* **Rationale in PR:** 76개의 PR마다 기술적 선택의 근거와 트레이드오프 기록[cite: 567].
> [cite_start]* **DX Optimization:** AOP와 JUnit Extension으로 성능 측정 자동화 및 로그 스팸 해결[cite: 566, 629].

---

## 5. 기술 스택 (Tech Stack)
- [cite_start]**Backend:** Java 17, Spring Boot 3.x, Spring Data JPA [cite: 606]
- [cite_start]**Database & Cache:** MySQL, Redis (Redisson), Caffeine Cache [cite: 521, 522, 607]
- [cite_start]**Testing & Monitoring:** JUnit 5, Locust (부하 테스트), Micrometer, Actuator [cite: 604, 608]
- [cite_start]**Infra & DevOps:** AWS EC2, GitHub Actions, Git Flow [cite: 609]

---

## 📈 모니터링 및 운영 가이드 (Operational Checklist)

[cite_start]본 프로젝트는 **Spring Boot Actuator**와 **Micrometer**를 통해 실시간 가시성(Observability)을 확보합니다[cite: 604, 629].

### [cite_start]1. 핵심 모니터링 지표 (Core Metrics) [cite: 629]

| 분류 | 지표 명칭 (Metric Name) | 위험 임계치 (Threshold) | 비즈니스 의미 및 대응 로직 |
| :--- | :--- | :--- | :--- |
| **Redis** | `redis.connection.error` | **> 0** | **Critical:** 분산 락 및 캐시 불능. **Fallback(DB Named Lock)** 작동 확인. |
| **Resilience** | `circuitbreaker.state` | **"open"** | **Error:** 외부 API 장애로 인한 서킷 개방. [cite_start]사용자 공지 및 의존성 차단[cite: 566]. |
| **App** | `like.buffer.total_pending` | **> 1,000** | **Saturation:** 좋아요 버퍼 유입 속도가 DB 반영보다 빠름. [cite_start]스케줄러 주기 점검[cite: 566]. |
| **DB** | `hikaricp.connections.pending` | **> 0** | **Saturation:** DB 커넥션 풀 고갈. [cite_start]쿼리 성능 점검 또는 풀 사이즈 증설[cite: 566]. |

### 2. 장애 감지 및 알림 Flow (Alerting)
- [cite_start]**임계치 초과 시:** `DiscordAlertService`를 통해 개발팀 채널로 즉시 Critical Alert 전송[cite: 533, 629].
- [cite_start]**추적 ID 활용:** 모든 에러 로그는 **MDC 기반 8자리 `requestId`**와 연결되어 빠른 MTTR 확보[cite: 567, 630].

---
