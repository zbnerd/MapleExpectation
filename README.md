# 🍁 MapleExpectation

> **"400KB 대용량 데이터를 17KB로 압축하여 I/O 병목을 95% 해결한 백엔드 프로젝트"**

## 1. 프로젝트 소개
넥슨 Open API를 활용하여 유저 장비 데이터를 수집하고, 확률형 아이템(큐브)의 기댓값을 계산하여 "스펙 완성 비용"을 시뮬레이션해주는 서비스입니다.

## 2. 프로젝트 아키텍처
<img width="714" height="432" alt="image" src="https://github.com/user-attachments/assets/96083003-42ef-494d-93a6-d5525b2b611d" />


> **[System Architecture]**
> 15분 만료 정책을 가진 **Cache-Aside 전략**을 도식화한 구조입니다. GZIP 압축된 데이터(BLOB)를 MySQL에 저장하여, Cache Hit 시 **0.38초** 만에 응답하도록 설계했습니다.

## 3. 핵심 기술적 성과 (Key Engineering)
이 프로젝트는 단순 기능 구현보다 **"대용량 데이터 처리와 성능 최적화"**에 집중했습니다.

### 동시성 이슈 해결: DB 락의 한계를 넘는 In-Memory 버퍼링 ###
- **문제**: 데이터 정합성을 위해 비관적 락(Pessimistic Lock) 적용 시, DB Row Lock으로 인한 직렬화 병목 발생 (100명 동시 요청 처리에 3.2초 소요, 확장성 한계)
- **해결**: Write-Behind(지연 쓰기) 패턴과 **Caffeine Cache(AtomicLong)**를 도입하여 요청을 메모리에서 즉시 처리하고, 스케줄러를 통해 DB에 비동기 배치 업데이트(Bulk Update) 수행
- **결과**: 트래픽이 10배(1000명) 폭증했음에도 평균 응답 속도 0.05ms 달성(약 7만 배 단축) 및 시스템 처리 효율 약 70만 배 향상
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

## 4. 기술 스택
- Java 17, Spring Boot 3.x
- JPA, JDBC Template, MySQL
- JUnit 5
- AWS EC2
