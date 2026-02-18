# Expectation API Flow Sequence Diagram

> **Last Updated:** 2026-02-13
> **Code Version:** MapleExpectation v1.x
> **Diagram Version:** 1.0

## 개요

`GET /api/v3/characters/{userIgn}/expectation` API의 **외부 API 호출 및 데이터 처리** 흐름을 분석한 문서입니다. Nexon API 호출, EquipmentDataResolver, Single-Flight 패턴에 집중합니다.

## 핵심 API 아키텍처

| 패턴 | 설명 | 효과 |
|------|------|------|
| **Resilient API Client** | Circuit Breaker + Retry | 외부 API 장애 격리 |
| **Data Resolver** | DB → API Fallback | 신뢰성 있는 데이터 확보 |
| **Two-Phase Loading** | Light → FullSnapshot | 불필요한 API 호출 최소화 |
| **Write-Behind** | 응답 후 비동기 저장 | 응답 속도 최적화 |

---

## Nexon API 호출 다이어그램

### API 신규 조회 시나리오 (Cache MISS)

```mermaid
sequenceDiagram
    autonumber

    participant Service as EquipmentService<br/>(expectation-*)
    participant Resolver as EquipmentDataResolver
    participant DbWorker as EquipmentDbWorker
    participant Fetcher as EquipmentFetchProvider
    participant NexonAPI as NexonApiClient
    participant CircuitBreaker as Resilience4j<br/>(CircuitBreaker)
    participant DB as MySQL
    participant Parser as StreamingParser

    rect rgb(255, 240, 240)
        Note over Service: Phase 1: 캐시 MISS + Single-Flight 시작
        activate Service
        Service->>+Resolver: resolveAsync(ocid, userIgn)
        activate Resolver
    end

    %% Phase 2: DB 우선 조회
    rect rgb(240, 248, 255)
        Note over Resolver: Phase 2: DB 조회 (우선순위)
        activate DbWorker
        DbWorker->>+DB: findValidJson(ocid)
        DB-->>-DbWorker: Optional<json>

        alt DB HIT
            DbWorker->>DbWorker: parseRawJson()
            DbWorker-->>-Resolver: byte[] (성공)
            Note right of DbWorker: DB HIT!<br/>API 호출 생략
        else DB MISS
            DbWorker-->>-Resolver: null
            Note right of Resolver: DB MISS<br/>API 호출 필요
        end
        deactivate DbWorker
    end

    %% Phase 3: Nexon API 호출 (Fallback)
    rect rgb(255, 255, 240)
        Note over Resolver: Phase 3: Nexon API 호출
        activate Fetcher
        Fetcher->>+CircuitBreaker: executeSupplier()
        activate CircuitBreaker

        alt Circuit Breaker CLOSED
            CircuitBreaker->>+NexonAPI: getItemDataByOcid(ocid)
            Note right of NexonAPI: Nexon API 호출<br/>~440ms
            NexonAPI-->>-CircuitBreaker: EquipmentResponse
            CircuitBreaker-->>-Fetcher: EquipmentResponse
            Note right of CircuitBreaker: API 호출 성공
        else Circuit Breaker OPEN
            CircuitBreaker-->>-Fetcher: throw CircuitBreakerOpenException
            Note right of CircuitBreaker: Circuit Breaker<br/>장애 상태
        end

        deactivate CircuitBreaker
        deactivate Fetcher

        alt API 성공
            Fetcher->>Fetcher: toByteArray()
            Fetcher-->>-Resolver: byte[] (장비 데이터)
        else API 실패
            Resolver-->>-Service: throw NexonAPIException
            deactivate Resolver
            deactivate Service
            Note right of Service: API 장애 처리
        end
    end

    %% Phase 4: 데이터 파싱
    rect rgb(240, 240, 255)
        Note over Resolver: Phase 4: 파싱 준비
        Resolver->>+Parser: parseCubeInputs(byte[])
        activate Parser

        loop 각 장비 (5개 기준)
            Parser->>Parser: parseEquipment()
            Parser->>Parser: extractCubeData()
            Parser->>Parser: buildCalculationInput()
        end

        Parser-->>-Resolver: List<CubeCalculationInput>
        deactivate Parser
    end

    deactivate Resolver
    deactivate Service
```

### 리트라이 정책

```mermaid
sequenceDiagram
    participant Service as EquipmentService
    participant Retry as RetryTemplate
    participant NexonAPI as NexonApiClient
    participant CircuitBreaker as CircuitBreaker

    Service->>+Retry: execute(() -> apiCall())
    activate Retry

    alt 1회 시도 성공
        Retry-->>-Service: Result.success
    else 1회 시도 실패
        Retry->>Retry: retry(2번 더 시도)

        par Retry 2
            NexonAPI-->>-Retry: timeout
        and Retry 3
            NexonAPI-->>-Retry: 503 Service Unavailable
        end

        alt 모든 리트라이 실패
            Retry->>CircuitBreaker: recordFailure()
            Retry-->>-Service: throw ApiRetryException
            Note right of Service: 최종 실패<br/>Fallback 또는 에러 반환
        end
    end

    deactivate Retry
```

---

## 데이터 흐름 상세

### 1. DB → API Fallback 전략

```mermaid
graph TD
    A[resolveAsync 호출] --> B{DB 조회}
    B -->|SUCCESS| C[Parse Raw JSON]
    B -->|FAIL| D[Nexon API 호출]
    C --> E[byte[] 반환]
    D --> F{Circuit Breaker}
    F -->|CLOSED| G[API 호출 성공]
    F -->|OPEN| H[장애 처리]
    G --> I[byte[] 반환]
    H --> J[예외 발생]
    E --> K[파싱 시작]
    I --> K
    J --> L[에러 전파]
    K --> M[parseCubeInputs]
```

### 2. Circuit Breaker 상태

| 상태 | 설명 | 행동 |
|------|------|------|
| **CLOSED** | 정상 작동 | 모든 요청 허용 |
| **OPEN** | 장애 발생 | 모든 요청 거부 |
| **HALF_OPEN** | 테스트 모드 | 일부 요청 허용 |

### 3. API 응답 분석

| API 엔드포인트 | 평균 응답시간 | 실패율 | Circuit Breaker Threshold |
|---------------|--------------|--------|----------------------------|
| `/character/item-equipment` | ~440ms | <5% | 50% / 10회 |
| `/character/id` | ~89ms | <2% | 50% / 5회 |

---

## 성능 특성

| 구간 | 평균 응답시간 | 설명 |
|------|-------------|------|
| **DB 조회 (HIT)** | ~8ms | Memory DB 접근 |
| **Nexon API** | ~440ms | 외부 네트워크 지연 |
| **Circuit Breaker 체크** | ~1ms | 메모리 내 체크 |
| **데이터 파싱** | ~5ms | CPU Bound (5장비 기준) |
| **리트라이 오버헤드** | +최대 2x | 3회 시도 시 |

### 장애 처리 시나리오

- **Circuit Breaker OPEN**: 30초 동안 API 호출 금지 → DB에서만 데이터 조회
- **리트라이 실패**: 3회 시도 후 실패 시 → `NexonAPIException` 발생
- **Partial Failure**: 일부 장비 데이터만 있는 경우 → 계산 가능한 장비만 처리

---

## 모니터링 지표

```yaml
# API 관련 메트릭
nexon.api.latency{endpoint=item-equipment}
nexon.api.latency{endpoint=character-id}
nexon.api.success.rate
nexon.api.failure.rate

# Circuit Breaker 메트릭
circuit.state{state=CLOSED,HALF_OPEN,OPEN}
circuit.permitted.count
circuit.denied.count
circuit.failure.rate

# Resolver 메트릭
resolver.db.hit.count
resolver.db.miss.count
resolver.api.call.count
resolver.fallback.rate
```

---

## 참고

- **CLAUDE.md**: [Resilient API Client 가이드](../CLAUDE.md#12-1-circuit-breaker--resilience-rules)
- **docs/03_Technical_Guides/infrastructure.md**: 인프라 상세 가이드
- **Issue #205**: Nexon API 장애 처리 강화

## Evidence Links

- **EquipmentDataResolver**: `src/main/java/maple/expectation/service/v2/resolver/EquipmentDataResolver.java`
- **NexonApiClient**: `src/main/java/maple/expectation/global/client/NexonApiClient.java`
- **Resilience4jConfig**: `src/main/java/maple/expectation/config/Resilience4jConfig.java`

## Fail If Wrong

이 다이어그램이 부정확한 경우:
- **API 호출 순서가 다름**: EquipmentDataResolver 실제 흐름 확인
- **Circuit Breaker 동작 오류**: Resilience4j 설정 확인
- **DB → API Fallback 미작동**: Resolver 구현체 확인

### Verification Commands
```bash
# Nexon API 호출 확인
grep -A 10 "getItemDataByOcid" src/main/java/maple/expectation/

# Circuit Breaker 설정 확인
grep -A 15 "CircuitBreaker" src/main/java/maple/expectation/config/

# Resolver 구현 확인
grep -A 20 "resolveAsync" src/main/java/maple/expectation/service/v2/
```