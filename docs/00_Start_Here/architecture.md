# MapleExpectation Backend Architecture

> **상위 문서:** [CLAUDE.md](../CLAUDE.md)
>
> **5-Agent Council 승인:** Blue, Green, Yellow, Purple, Red

---

## 1. System Architecture Overview

```mermaid
flowchart TB
    subgraph Client["Client Layer"]
        Browser["Browser/Mobile App"]
    end

    subgraph Edge["Edge Layer (Security)"]
        RL["Rate Limiter<br/>(Bucket4j)"]
        JWT["JWT Filter"]
        MDC["MDC Filter<br/>(TraceId)"]
    end

    subgraph App["Spring Boot Application"]
        subgraph Controllers["Controller Layer"]
            CV1["V1 Controller"]
            CV2["V2 Controller"]
            CV3["V3 Controller"]
        end

        subgraph Services["Service Layer"]
            Facade["GameCharacterFacade"]
            EqSvc["EquipmentService"]
            LikeSvc["CharacterLikeService"]
            CalcSvc["ExpectationCalculator"]
        end

        subgraph AOP["AOP Layer"]
            CacheAsp["NexonDataCacheAspect"]
            LockAsp["LockAspect"]
            TraceAsp["TraceAspect"]
        end

        subgraph Core["Core Components"]
            Executor["LogicExecutor"]
            SF["SingleFlightExecutor"]
        end
    end

    subgraph Cache["Cache Layer (2-Tier)"]
        L1["L1: Caffeine<br/>(Local, 5min TTL)"]
        L2["L2: Redis<br/>(Distributed, 10min TTL)"]
        TC["TieredCacheManager"]
    end

    subgraph DB["Database Layer"]
        MySQL["MySQL 8.0<br/>+ GZIP Compression"]
        SlowLog["Slow Query Log<br/>(1s threshold)"]
    end

    subgraph RedisHA["Redis HA Cluster"]
        Master["Redis Master<br/>(172.20.0.10)"]
        Slave["Redis Slave"]
        S1["Sentinel 1"]
        S2["Sentinel 2"]
        S3["Sentinel 3"]
    end

    subgraph External["External API"]
        Nexon["Nexon Open API"]
        CB["Circuit Breaker<br/>(Resilience4j)"]
        Retry["Retry + TimeLimiter"]
    end

    subgraph Observability["Observability Stack"]
        Prom["Prometheus<br/>(:9090)"]
        Loki["Loki<br/>(:3100)"]
        Promtail["Promtail"]
        Grafana["Grafana<br/>(:3000)"]
    end

    %% Client to Edge
    Browser --> RL
    RL --> JWT
    JWT --> MDC

    %% Edge to Controllers
    MDC --> Controllers

    %% Controllers to Services
    Controllers --> Services
    Services --> AOP
    AOP --> Core

    %% Core to Cache
    Core --> TC
    TC --> L1
    L1 -.->|MISS| L2
    L2 -.->|Backfill| L1

    %% Cache to Redis HA
    L2 --> Master
    Master --> Slave
    S1 --> Master
    S2 --> Master
    S3 --> Master

    %% Services to DB
    Services --> MySQL
    MySQL --> SlowLog

    %% External API
    Services --> CB
    CB --> Retry
    Retry --> Nexon

    %% Observability
    SlowLog --> Promtail
    Promtail --> Loki
    App -->|Metrics| Prom
    Prom --> Grafana
    Loki --> Grafana

    %% Styling
    classDef client fill:#e1f5fe
    classDef edge fill:#fff3e0
    classDef app fill:#e8f5e9
    classDef cache fill:#fce4ec
    classDef db fill:#f3e5f5
    classDef redis fill:#ffebee
    classDef external fill:#e0f2f1
    classDef obs fill:#fff8e1

    class Browser client
    class RL,JWT,MDC edge
    class CV1,CV2,CV3,Facade,EqSvc,LikeSvc,CalcSvc,CacheAsp,LockAsp,TraceAsp,Executor,SF app
    class L1,L2,TC cache
    class MySQL,SlowLog db
    class Master,Slave,S1,S2,S3 redis
    class Nexon,CB,Retry external
    class Prom,Loki,Promtail,Grafana obs
```

---

## 2. Data Flow Diagram

```mermaid
flowchart LR
    subgraph Request["Request Flow"]
        C["Client"]
        C -->|"GET /api/v2/characters/{ign}"| RL2["Rate Limiter"]
        RL2 -->|"Passed"| JWT2["JWT Auth"]
        JWT2 -->|"Valid"| Ctrl["Controller"]
    end

    subgraph CacheFlow["Cache Strategy"]
        Ctrl -->|"1. Check"| TC2["TieredCache"]
        TC2 -->|"L1 HIT"| Resp["Response<br/>(< 5ms)"]
        TC2 -->|"L1 MISS"| L2C["L2 Redis"]
        L2C -->|"L2 HIT + Backfill"| Resp
        L2C -->|"L2 MISS"| SF2["SingleFlight"]
    end

    subgraph Compute["Compute Path"]
        SF2 -->|"Leader"| Svc["EquipmentService"]
        SF2 -.->|"Follower Wait"| Resp
        Svc -->|"Fetch"| API["Nexon API"]
        API -->|"350KB JSON"| Parser["StreamingParser"]
        Parser -->|"Parse"| Calc["Calculator"]
        Calc -->|"Result"| Cache2["Cache Store"]
        Cache2 --> Resp
    end

    subgraph Fallback["Fallback Path"]
        API -.->|"Timeout/Error"| CB2["Circuit Breaker"]
        CB2 -.->|"OPEN"| DBFall["DB Fallback"]
        DBFall -.->|"GZIP Decompress"| Resp
    end
```

---

## 3. Cache Architecture (TieredCache)

```mermaid
flowchart TB
    subgraph Request["Cache Request"]
        Key["cache.get(key)"]
    end

    subgraph L1["L1: Caffeine (Local)"]
        L1Check{"L1 HIT?"}
        L1Get["Return Value"]
        L1Miss["L1 MISS"]
    end

    subgraph L2["L2: Redis (Distributed)"]
        L2Check{"L2 HIT?"}
        L2Get["Get + Backfill L1"]
        L2Miss["L2 MISS"]
    end

    subgraph Load["Data Loading"]
        Loader["Load from Source"]
        Store["Store L1 + L2"]
    end

    Key --> L1Check
    L1Check -->|"HIT"| L1Get
    L1Check -->|"MISS"| L2Check
    L2Check -->|"HIT"| L2Get
    L2Get --> L1Get
    L2Check -->|"MISS"| Loader
    Loader --> Store
    Store --> L1Get

    style L1 fill:#e3f2fd
    style L2 fill:#fce4ec
```

### Cache Configuration

| Cache Name | L1 TTL | L1 Max | L2 TTL | Purpose |
|------------|--------|--------|--------|---------|
| `equipment` | 5 min | 5,000 | 10 min | Nexon API 장비 데이터 |
| `cubeTrials` | 10 min | 5,000 | 20 min | Cube 확률 계산 |
| `ocidCache` | 30 min | 5,000 | 60 min | OCID 매핑 |
| `totalExpectation` | 5 min | 10,000 | 10 min | 기대값 계산 결과 |
| `negativeCache` | 5 min | 1,000 | - | 존재하지 않는 캐릭터 |

---

## 4. Redis HA Architecture

```mermaid
flowchart TB
    subgraph App["Spring Boot App"]
        Redisson["Redisson Client"]
    end

    subgraph Sentinel["Sentinel Cluster (Quorum 2/3)"]
        S1["Sentinel 1<br/>:26379"]
        S2["Sentinel 2<br/>:26380"]
        S3["Sentinel 3<br/>:26381"]
    end

    subgraph Redis["Redis Cluster"]
        Master["Master<br/>172.20.0.10:6379"]
        Slave["Slave<br/>:6380"]
    end

    Redisson -->|"Discover"| S1
    Redisson -->|"Discover"| S2
    Redisson -->|"Discover"| S3

    S1 -->|"Monitor"| Master
    S2 -->|"Monitor"| Master
    S3 -->|"Monitor"| Master

    Master -->|"Replicate"| Slave

    S1 -.->|"Failover"| Slave
    S2 -.->|"Failover"| Slave
    S3 -.->|"Failover"| Slave

    style Master fill:#4caf50,color:#fff
    style Slave fill:#ff9800,color:#fff
    style S1,S2,S3 fill:#2196f3,color:#fff
```

### Redis Usage

| Feature | Redis Structure | Purpose |
|---------|-----------------|---------|
| L2 Cache | `String` | Equipment, OCID, Expectation 캐싱 |
| Like Buffer | `Sorted Set` | 좋아요 버퍼 (timestamp 정렬) |
| Distributed Lock | `RLock` | 분산 락 |
| Leader Latch | `RCountDownLatch` | SingleFlight Leader/Follower |
| Rate Limit | `RBucket` | 사용자별 요청 제한 |

---

## 5. Resilience Architecture

```mermaid
flowchart LR
    subgraph Client["Service Call"]
        Svc["EquipmentService"]
    end

    subgraph Resilience["Resilience4j Stack"]
        TL["TimeLimiter<br/>(10s timeout)"]
        CB["CircuitBreaker<br/>(50% threshold)"]
        RT["Retry<br/>(3 attempts, exp backoff)"]
    end

    subgraph States["Circuit States"]
        Closed["CLOSED<br/>(Normal)"]
        Open["OPEN<br/>(5min cooldown)"]
        HalfOpen["HALF_OPEN<br/>(Probe)"]
    end

    subgraph Target["External API"]
        API["Nexon API"]
        FB["Fallback<br/>(DB + Discord)"]
    end

    Svc --> TL
    TL --> CB
    CB --> RT
    RT --> API

    CB -.->|"Error Rate > 50%"| Open
    Open -.->|"5min elapsed"| HalfOpen
    HalfOpen -.->|"Success"| Closed
    HalfOpen -.->|"Fail"| Open

    CB -.->|"OPEN"| FB

    style Closed fill:#4caf50,color:#fff
    style Open fill:#f44336,color:#fff
    style HalfOpen fill:#ff9800,color:#fff
```

### Exception Hierarchy

```mermaid
classDiagram
    class BaseException {
        <<abstract>>
        +ErrorCode errorCode
        +String dynamicMessage
    }

    class ClientBaseException {
        <<4xx>>
        +CircuitBreakerIgnoreMarker
    }

    class ServerBaseException {
        <<5xx>>
        +CircuitBreakerRecordMarker
    }

    BaseException <|-- ClientBaseException
    BaseException <|-- ServerBaseException

    ClientBaseException <|-- CharacterNotFoundException
    ClientBaseException <|-- SelfLikeNotAllowedException
    ClientBaseException <|-- DuplicateLikeException

    ServerBaseException <|-- ExternalServiceException
    ServerBaseException <|-- ApiTimeoutException
    ServerBaseException <|-- CompressionException
```

---

## 6. GZIP Compression Flow

```mermaid
flowchart LR
    subgraph Write["Write Path"]
        JSON1["JSON String<br/>(350KB)"]
        Compress["GzipUtils.compress()"]
        Blob["LONGBLOB<br/>(~35KB)"]
        DB1["MySQL"]
    end

    subgraph Read["Read Path"]
        DB2["MySQL"]
        Blob2["LONGBLOB"]
        Decompress["GzipUtils.decompress()"]
        JSON2["JSON String"]
    end

    JSON1 -->|"@Convert"| Compress
    Compress -->|"GZIP"| Blob
    Blob --> DB1

    DB2 --> Blob2
    Blob2 -->|"@Convert"| Decompress
    Decompress -->|"GUNZIP"| JSON2

    style Compress fill:#4caf50,color:#fff
    style Decompress fill:#2196f3,color:#fff
```

### Compression Stats

| Metric | Before | After | Reduction |
|--------|--------|-------|-----------|
| JSON Size | 350 KB | 35 KB | **90%** |
| DB Storage | 1 GB / 3000 chars | 100 MB / 3000 chars | **90%** |
| Network Transfer | 350 KB | 35 KB | **90%** |

---

## 7. Observability Stack

```mermaid
flowchart TB
    subgraph App["Spring Boot App"]
        Actuator["Actuator<br/>/actuator/prometheus"]
        Log["Logback<br/>(Loki4j Appender)"]
    end

    subgraph MySQL["MySQL"]
        SlowLog2["Slow Query Log<br/>/var/log/mysql/slow.log"]
    end

    subgraph Collectors["Collectors"]
        Promtail2["Promtail"]
    end

    subgraph Storage["Time Series Storage"]
        Prom2["Prometheus<br/>(:9090)"]
        Loki2["Loki<br/>(:3100)"]
    end

    subgraph Viz["Visualization"]
        Grafana2["Grafana<br/>(:3000)"]
    end

    subgraph Dashboards["Dashboards"]
        D1["Slow Query Dashboard"]
        D2["JVM Metrics Dashboard"]
        D3["Application Logs"]
    end

    Actuator -->|"Scrape 15s"| Prom2
    Log -->|"Push"| Loki2
    SlowLog2 -->|"Tail"| Promtail2
    Promtail2 -->|"Push"| Loki2

    Prom2 --> Grafana2
    Loki2 --> Grafana2

    Grafana2 --> D1
    Grafana2 --> D2
    Grafana2 --> D3

    style Prom2 fill:#e65100,color:#fff
    style Loki2 fill:#1565c0,color:#fff
    style Grafana2 fill:#f57c00,color:#fff
```

### Metrics Collected

| Category | Metrics | Source |
|----------|---------|--------|
| JVM | Memory, GC, Threads | Micrometer + Actuator |
| HTTP | Request Rate, Latency (p50/p95/p99) | Spring MVC |
| Cache | Hit Rate, Eviction | Caffeine + Redis |
| Circuit Breaker | State, Failure Rate | Resilience4j |
| DB | Connection Pool, Slow Query Count | HikariCP + MySQL |

---

## 8. Security Architecture

```mermaid
flowchart LR
    subgraph Client["Client"]
        Req["HTTP Request"]
    end

    subgraph Filters["Security Filter Chain"]
        RL3["RateLimitingFilter"]
        JWT3["JwtAuthenticationFilter"]
        MDC3["MDCFilter"]
    end

    subgraph Auth["Authentication"]
        Provider["JwtTokenProvider"]
        FP["FingerprintGenerator"]
    end

    subgraph Authz["Authorization"]
        SC["SecurityConfig"]
        Rules["Access Rules"]
    end

    subgraph Protected["Protected Resources"]
        Public["/api/public/**"]
        Like["/api/v2/*/like"]
        Admin["/api/admin/**"]
    end

    Req --> RL3
    RL3 -->|"Passed"| JWT3
    JWT3 --> Provider
    Provider --> FP
    JWT3 --> MDC3
    MDC3 --> SC
    SC --> Rules

    Rules -->|"permitAll"| Public
    Rules -->|"authenticated"| Like
    Rules -->|"hasRole ADMIN"| Admin

    style Public fill:#4caf50,color:#fff
    style Like fill:#ff9800,color:#fff
    style Admin fill:#f44336,color:#fff
```

### Access Control Rules

| Endpoint | Access | Rate Limit |
|----------|--------|------------|
| `/api/public/**` | permitAll | IP-based |
| `/api/v2/characters/{ign}` | permitAll | IP-based |
| `/api/v2/characters/*/like` | authenticated | User-based |
| `/api/admin/**` | hasRole(ADMIN) | User-based |
| `/actuator/prometheus` | Internal IP only | None |

---

## 9. Deployment Architecture

```mermaid
flowchart TB
    subgraph Internet["Internet"]
        User["Users"]
    end

    subgraph AWS["AWS t3.small"]
        subgraph Host["EC2 Host"]
            App2["Spring Boot<br/>:8080"]
        end

        subgraph Docker["Docker Containers"]
            subgraph Main["docker-compose.yml"]
                MySQL2["MySQL<br/>:3306"]
                RedisMaster2["Redis Master<br/>:6379"]
                RedisSlave2["Redis Slave<br/>:6380"]
                Sent1["Sentinel 1<br/>:26379"]
                Sent2["Sentinel 2<br/>:26380"]
                Sent3["Sentinel 3<br/>:26381"]
            end

            subgraph Obs["docker-compose.observability.yml"]
                Loki3["Loki<br/>:3100"]
                Prom3["Prometheus<br/>:9090"]
                Promtail3["Promtail"]
                Grafana3["Grafana<br/>:3000"]
            end
        end
    end

    subgraph External2["External"]
        NexonAPI["Nexon Open API"]
    end

    User -->|"HTTPS"| App2
    App2 --> MySQL2
    App2 --> RedisMaster2
    App2 -->|"Circuit Breaker"| NexonAPI

    MySQL2 -->|"Slow Log"| Promtail3
    App2 -->|"Metrics"| Prom3

    RedisMaster2 --> RedisSlave2
    Sent1 --> RedisMaster2
    Sent2 --> RedisMaster2
    Sent3 --> RedisMaster2

    style App2 fill:#4caf50,color:#fff
    style MySQL2 fill:#1976d2,color:#fff
    style RedisMaster2 fill:#d32f2f,color:#fff
```

---

## 10. Technology Stack Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                     MapleExpectation Stack                       │
├─────────────────────────────────────────────────────────────────┤
│  Frontend    │  React (별도 프로젝트)                             │
├─────────────────────────────────────────────────────────────────┤
│  Backend     │  Java 17, Spring Boot 3.5.4, Spring Data JPA     │
├─────────────────────────────────────────────────────────────────┤
│  Security    │  Spring Security 6.x, JWT, Bucket4j Rate Limit   │
├─────────────────────────────────────────────────────────────────┤
│  Cache       │  Caffeine (L1), Redis/Redisson 3.27.0 (L2)       │
├─────────────────────────────────────────────────────────────────┤
│  Database    │  MySQL 8.0 + GZIP Compression                     │
├─────────────────────────────────────────────────────────────────┤
│  Redis HA    │  Master-Slave + Sentinel x3 (Quorum 2/3)         │
├─────────────────────────────────────────────────────────────────┤
│  Resilience  │  Resilience4j 2.2.0 (CB, Retry, TimeLimiter)     │
├─────────────────────────────────────────────────────────────────┤
│  Observ.     │  Prometheus, Loki, Promtail, Grafana             │
├─────────────────────────────────────────────────────────────────┤
│  External    │  Nexon Open API (장비 데이터)                      │
├─────────────────────────────────────────────────────────────────┤
│  Infra       │  AWS t3.small, Docker Compose                     │
├─────────────────────────────────────────────────────────────────┤
│  Performance │  240 RPS, 1,000+ concurrent users                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 11. 5-Agent Council 검증 결과

| Agent | Role | Verification |
|-------|------|--------------|
| 🟦 Blue | Architect | SOLID 준수, 계층 분리, Design Pattern 적용 ✅ |
| 🟩 Green | Performance | 2-Tier Cache, GZIP 90% 절감, SingleFlight ✅ |
| 🟨 Yellow | QA | 다이어그램 정확성 검증 ✅ |
| 🟪 Purple | Auditor | 보안 계층, Circuit Breaker 분류 ✅ |
| 🟥 Red | SRE | Redis HA, Observability, Resilience ✅ |

---

*Last Updated: 2026-01-18*
*Generated by 5-Agent Council*
