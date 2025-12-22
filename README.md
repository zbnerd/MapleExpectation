# 🍁 MapleExpectation

## 0. Professional Summary
> **"숫자로 문제를 정의하고, 아키텍처로 증명하는 엔지니어"**
>
> 대규모 트래픽 환경의 병목을 직접 진단하고,
> 시스템 아키텍처 개선을 통해 **RPS 7배, 성능 대폭 향상**을 달성한
> Java 백엔드 개발자입니다.

## 1. 프로젝트 소개
**넥슨 Open API**를 활용하여 유저 장비 데이터를 수집하고, 확률형 아이템(큐브)의 기댓값을 계산하여 **"스펙 완성 비용"을 시뮬레이션해주는 서비스**입니다.

단순한 기능 구현을 넘어, **대용량 데이터(400KB/User) 처리와 동시성 제어** 상황에서 발생하는 성능 문제를 집요하게 파고들어 해결했습니다.

## 2. 프로젝트 아키텍처
<img width="1074" height="771" alt="image" src="https://github.com/user-attachments/assets/1531c4d4-df09-4e58-8263-89c7c2785b3f" />





> **[System Architecture Key Point]**
> * **Write-Behind:** 동시성 처리를 위한 In-Memory Buffering
> * **Cache-Aside:** 넥슨 API 15분 만료 정책 준수 및 비용 절감
> * **GZIP Compression:** 대용량 JSON I/O 병목 해결 (Blob Storage)

## 3. 핵심 기술적 성과 (Key Engineering)
이 프로젝트는 단순 기능 구현과 추가 인프라 도입(Redis, Kafka 등)보다 **대용량 데이터 처리, 대규모 트래픽 처리와 성능 최적화**에 집중했습니다.

### 동시성 이슈 해결: DB 락의 한계를 넘는 In-Memory 버퍼링 ###
- **문제 (Problem):**
  - 인기 캐릭터 '좋아요' 요청 폭주 상황(동접 1,000명)을 가정하여 **Locust** 부하 테스트 수행.
  - **MySQL 비관적 락(Pessimistic Lock)** 적용 시, DB Row Lock 대기로 인해 **RPS가 107**에서 병목 발생 및 평균 응답 지연 **8.8초** 기록.
  - [📉 V1 테스트 결과 그래프 보기](https://velog.velcdn.com/images/zbnerd/post/181e0bab-5e05-41cf-9945-f217fd900754/image.png)

- **해결 (Action):**
  - **Write-Behind(지연 쓰기)** 패턴 도입: 요청을 **Caffeine Cache(AtomicLong)**에서 즉시 처리(Non-Blocking)하고, 3초마다 DB에 **Bulk Update** 수행.
  - **JPA Dirty Checking 우회:** JPA의 변경 감지 오버헤드를 제거하기 위해 `JPQL Bulk Update`를 사용하여 DB I/O를 극한으로 절감.

- **결과 (Result):**
  - **Throughput:** RPS 107.4 → **763.1 (약 7.1배 향상)**
  - **Latency:** 평균 응답 속도 8.8s → **0.037s**
  - **DB Efficiency:** 21만 건의 요청을 처리하는 동안 JPA `Version`이 증가하지 않음을 검증(Version: 0), **DB 쓰기 부하 99% 절감**.

- [👉 **[기술 블로그] Locust 부하 테스트 & 성능 튜닝 과정 상세 보기**](https://velog.io/@zbnerd/%EB%8F%99%EC%8B%9C%EC%A0%91%EC%86%8D%EC%9E%90-1000%EB%AA%85%EC%9D%98-%EC%A2%8B%EC%95%84%EC%9A%94-%ED%8A%B8%EB%9E%98%ED%94%BD-DB-%EB%9D%BD%EC%9D%84-%EB%B2%84%EB%A6%AC%EA%B3%A0-RPS%EB%A5%BC-7%EB%B0%B0-%EC%98%AC%EB%A6%B0-%EA%B3%BC%EC%A0%95)
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/%EC%84%B1%EB%8A%A5-%ED%8A%9C%EB%8B%9D-0.03%EC%B4%88%EC%9D%98-%EA%B8%B0%EC%A0%81-DB-%EB%9D%BD%EC%9D%98-%ED%95%9C%EA%B3%84%EB%A5%BC-%EB%84%98%EC%96%B4-%EC%9D%B8%EB%A9%94%EB%AA%A8%EB%A6%AC-%EB%B2%84%ED%8D%BC%EB%A7%81Write-Behind%EC%9C%BC%EB%A1%9C-%EC%84%B1%EB%8A%A5-70%EB%A7%8C-%ED%96%A5%EC%83%81%ED%95%98%EA%B8%B0)

### 스트리밍 & 캐싱을 통한 대용량 JSON(350KB) 처리 최적화 ###
- **문제**: 1000명 동시 접속 환경에서 350KB 대용량 JSON 조회 시, 전체 데이터 힙(Heap) 메모리 적재로 인한 OOM(Out Of Memory) 및 잦은 I/O로 DB 커넥션 풀 고갈 발생
- **해결** : **스트리밍 직렬화(StreamingResponseBody)**를 도입하여 데이터를 메모리에 쌓지 않고 파이프라인으로 전송하고, 로컬 캐싱을 적용하여 DB 부하를 원천 차단
- **결과** : 저사양 서버(t3.small)에서 RPS 11배 향상(573.2 RPS 달성) 및 1000명 동접 상황에서도 안정적인 응답 속도(Median 0.5s) 확보
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/Spring-Boot-GZIP-%EC%8A%A4%ED%8A%B8%EB%A6%AC%EB%B0%8D-%EC%BA%90%EC%8B%B1%EC%9C%BC%EB%A1%9C-350KB-JSON%ED%99%98%EA%B2%BD-1000%EB%AA%85-%EB%8F%99%EC%8B%9C-%EC%A0%91%EC%86%8D%EC%97%90%EB%8F%84-%EA%B1%B0%EB%9C%AC%ED%95%98%EA%B2%8C)

### 대용량 JSON(350KB) 전송 성능 최적화 (GZIP Compression) ###
- **문제**: 단건 조회 시 350KB에 달하는 대용량 응답으로 인해 t3.small 환경에서 네트워크 병목(Network I/O Bound) 발생, I/O 대기 및 메모리 할당 비용으로 인해 오히려 비압축 상태에서 CPU가 과부하(189%)되는 현상 확인
- **해결** : GZIP 압축을 적용하여 응답 데이터 크기를 **약 95% 절감**하고, 남는 CPU 자원을 활용해 네트워크 병목을 해소하는 전략 채택
- **결과**: RPS 3.8배 폭등(48.5 → 184.2) 및 응답 속도 6.8배 단축(5.5s → 0.8s)으로 하드웨어 스펙의 한계를 소프트웨어 튜닝으로 극복
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/Spring-Boot-350KB-JSON-%EC%A0%84%EC%86%A1-%EC%B5%9C%EC%A0%81%ED%99%94-GZIP%EC%9C%BC%EB%A1%9C-%EB%84%A4%ED%8A%B8%EC%9B%8C%ED%81%AC-%EB%B3%91%EB%AA%A9-%EB%9A%AB%EA%B3%A0-RPS-27-%EB%86%92%EC%9D%B4%EA%B8%B0-feat.-t3.small)
  
### I/O 병목 95% 해소 (GZIP 압축)
- **문제:** 유저 1명의 장비 데이터가 약 400KB에 달해 DB 용량 및 네트워크 대역폭 낭비
- **해결:** JSON 데이터를 통째로 GZIP 압축하여 `LONGBLOB`으로 저장
- **결과:** 데이터 크기 **95% 감소 (350KB → 17KB)** 및 I/O 성능 향상
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/Performance-350KB-JSON%EC%9D%84-DB%EC%97%90-%EA%B7%B8%EB%83%A5-%EB%84%A3%EC%9D%84%EA%B1%B4%EA%B0%80-GZIP-%EC%95%95%EC%B6%95%EC%9C%BC%EB%A1%9C-%EC%9A%A9%EB%9F%89-95-%EC%A4%84%EC%9D%B4%EA%B8%B0)

### API 호출 비용 '0원' 전략 (15분 캐싱)
- **문제:** 외부 API 호출 제한(Rate Limit) 및 응답 지연
- **해결:** 넥슨 데이터 갱신 주기(15분)에 맞춘 Cache-Aside 전략 구현
- **결과:** 반복 조회 시 응답 속도 **1,712ms → 381ms**로 단축
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/Spring-%EB%84%A5%EC%8A%A8-API-%ED%98%B8%EC%B6%9C-%EB%B9%84%EC%9A%A9%EC%9D%84-0%EC%9B%90%EC%9C%BC%EB%A1%9C-%EB%A7%8C%EB%93%9C%EB%8A%94-15%EB%B6%84-%EC%BA%90%EC%8B%B1-%EC%A0%84%EB%9E%B5-feat.-JPA-Dirty-Checking)

### 데이터 적재 속도 33배 개선 (JPA vs JDBC)
- **문제:** JPA `saveAll` 사용 시 1만 건 적재에 15초 소요 (Identity 전략 한계)
- **해결:** `JdbcTemplate.batchUpdate` 도입
- **결과:** **15초 → 0.4초**로 성능 개선
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/%EC%84%B1%EB%8A%A5-%ED%8A%9C%EB%8B%9D-1%EB%A7%8C-%EA%B1%B4-%EB%8D%B0%EC%9D%B4%ED%84%B0-%EC%82%BD%EC%9E%85-JPA-vs-JDBC-%EC%84%B1%EB%8A%A5-33%EB%B0%B0-%EC%B0%A8%EC%9D%B4%EC%9D%98-%EB%B9%84%EB%B0%80-15.2s-0.4s)

### 조회 성능 50배 개선 (DB Index Tuning)
- **문제:** 100만 건 데이터 적재 후 특정 조건 조회 시 **Full Table Scan** 발생으로 0.98초 소요
- **해결:** 실행 계획(EXPLAIN) 분석 후, 조회 패턴(Cardinality)에 맞춰 **복합 인덱스(B-Tree)** 설계 및 적용
- **결과:** 조회 속도 **0.98s → 0.02s (약 50배 향상)** 및 스캔 행 수 최소화 (100만 → 429행)
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/성능-튜닝-100만-건-데이터-조회-인덱스Index-하나로-0.98s-0.02s-50배-개선)

### 동시성 이슈 해결 및 대기 시간 단축 (Lock Optimization)
- **문제:** '좋아요' 기능에 비관적 락(Pessimistic Lock) 적용 시, 트래픽 증가 상황에서 락 대기 시간(Lock Wait)이 **최대 30초**까지 치솟는 병목 현상 발생
- **해결:** 락의 범위를 트랜잭션 전체가 아닌 최소 구간으로 축소하고, 인덱스를 활용해 **Lock Escalation(테이블 락)** 방지
- **결과:** 데이터 정합성을 유지하면서 대기 시간을 **30s → 0.7s (97% 단축)**하여 처리량 개선
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/성능-튜닝-락Lock의-역설-비관적-락의-30초-지옥을-0.7초로-줄인-과정)

### 테스트 자동화 및 DX 개선 (AOP & JUnit 5 Extension)
- **문제:** 성능 측정을 위한 보일러플레이트 코드가 비즈니스 로직을 오염시키고, 멀티스레드 테스트 시 로그 폭발(Log Spamming)로 인한 성능 왜곡 발생
- **해결:** 측정 로직을 **AOP**로 분리하고, **JUnit 5 Extension**을 개발하여 테스트 종료 시 통계(평균/최대 시간)가 자동 출력되도록 환경 구축
- **결과:** 비즈니스 로직의 순수성 확보 및 테스트 실행 시 **가독성 높은 성능 리포트 자동 생성**
- [👉 기술 블로그 포스팅 보기](https://velog.io/@zbnerd/Refactoring-성능-측정-노가다에서-자동화로-AOP와-JUnit-5-Extension으로-테스트-환경-대격변)

## 4. 협업 기반 개발 프로세스 (Git Flow)
<img width="1000" height="420" alt="image" src="https://github.com/user-attachments/assets/21ff1b7f-e0e6-4656-b9dd-5e292891a22f" />
> 실제로도 해당 Git Flow를 기반으로 개발을 진행했습니다.  
> 기능 단위 Branch → Pull Request → Code Review → master Merge 프로세스 적용

### 📈 대표 Pull Request (성능 개선 사례)

> **[PR #3] 좋아요 기능 성능 86배 향상**  
> Caffeine Cache + Write-Behind 패턴 적용  
> 🔗 https://github.com/zbnerd/MapleExpectation/pull/3

- 동시 요청 처리량 **100 → 1000명 (10배 증가)**
- 평균 응답 속도 **3.2s → 0.037s (약 86배 개선)**
- DB 락 대기 제거(AtomicLong 기반)
- Eventual Consistency 기반 Trade-off 명확

> 병목 원인 분석 → 개선 전략 설계 → 실측 검증 → 문서화  
> End-to-End 성능 최적화 수행


## 5. CI/CD 자동 배포 파이프라인

> GitHub Actions → Build → Test → AWS EC2 자동 배포
> master 브랜치에 push 또는 merge되면 Build & Deploy가 자동 실행됩니다.
<img width="1023" height="443" alt="image" src="https://github.com/user-attachments/assets/071e1bc0-1133-4f9e-afdc-b255853e3318" />


🔗 Workflow:  
https://github.com/zbnerd/MapleExpectation/blob/master/.github/workflows/gradle.yml

### 배포 단계
1) master 브랜치에 push or merge 발생
2) GitHub Actions에서 Gradle Build & Test
3) SSH를 통한 EC2 서버 자동 배포 스크립트 실행
4) 서비스 재기동 및 상태 확인(Log 기반)


## 6. 기술 스택
- Java 17, Spring Boot 3.x
- JPA, JDBC Template, MySQL
- JUnit 5
- AWS EC2

## 7. 🌍 환경별 실행 가이드 (Environment Setup)

이 프로젝트는 `Spring Profiles`를 사용하여 **Local(개발)**과 **Prod(운영)** 환경을 철저히 분리하고 있습니다.

### 1. 로컬 개발 (Local) - Default
별도의 설정 없이 실행하면 자동으로 `local` 프로필이 적용됩니다.
- **DB**: 로컬 H2 (`localhost:3306`)
- **DDL Mode**: `update` (테이블 자동 생성/변경)
- **실행 방법**:
  - IntelliJ: 그냥 실행 (Run)
  - CLI: `./gradlew bootRun`

### 2. 운영 배포 (Prod) - EC2
운영 환경에서는 환경변수를 통해 민감 정보를 주입받습니다.
- **DB**: EC2 내부 MySQL (`localhost:3306`)
- **DDL Mode**: `validate` (스키마 변경 불가, 검증만 수행)
- **필수 환경변수**:
  - `SPRING_PROFILES_ACTIVE=prod`
  - `DB_USERNAME`: DB 계정명
  - `DB_PASSWORD`: DB 비밀번호
  - `NEXON_API_KEY`: 넥슨 API 키
- **실행 예시**:
  ```bash
  export SPRING_PROFILES_ACTIVE=prod
  export DB_USERNAME=root
  export DB_PASSWORD=your_password
  java -jar build/libs/donation-service.jar

## 📈 모니터링 및 운영 가이드 (Operational Checklist)

본 프로젝트는 **Spring Boot Actuator**와 **Micrometer**를 통해 시스템 내부 지표를 노출하며, 이를 바탕으로 데이터 기반의 장애 대응 및 성능 튜닝을 수행합니다.

### 1. 핵심 모니터링 지표 (Core Metrics)

| 분류 | 지표 명칭 (Metric Name) | 위험 임계치 (Threshold) | 비즈니스 의미 및 대응 로직 |
| :--- | :--- | :--- | :--- |
| **App** | `like.buffer.total_pending` | **> 1,000** | **Saturation:** 좋아요 버퍼 유입 속도가 DB 반영보다 빠름. 스케줄러 주기 단축 검토. |
| **App** | `scheduler.like.sync_max` | **> 1.0s** | **Latency:** 3초 주기 동기화 작업의 병목 발생. 배치 사이즈 최적화 필요. |
| **Ext** | `resilience4j.circuitbreaker.state` | **"open"** | **Error:** 넥슨 API 장애로 인한 서킷 개방. 외부 의존성 차단 및 사용자 공지. |
| **DB** | `hikaricp.connections.pending` | **> 0** | **Saturation:** DB 커넥션 풀 고갈. 쿼리 성능 점검 또는 풀 사이즈 증설. |
| **Infra** | `jvm.memory.used_bytes` | **> 85%** | **Saturation:** 메모리 압박 심화. GC 로그 분석 및 Heap 메모리 증설 검토. |

### 2. 장애 감지 및 알림 Flow (Alerting)

- **임계치 초과 시:** `DiscordAlertService`를 통해 개발팀 채널로 즉시 Critical Alert 전송.
- **추적 ID 활용:** 모든 에러 로그 및 메트릭은 `MDCFilter`에서 생성된 8자리 `requestId`와 연결되어 빠른 MTTR(장애 복구 시간) 확보.
