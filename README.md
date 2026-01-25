# MapleExpectation

[![CI](https://github.com/zbnerd/MapleExpectation/actions/workflows/ci.yml/badge.svg)](https://github.com/zbnerd/MapleExpectation/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/zbnerd/MapleExpectation/branch/develop/graph/badge.svg)](https://codecov.io/gh/zbnerd/MapleExpectation)

## TL;DR

| Item | Description |
|------|-------------|
| **What** | MapleStory Equipment Upgrade Cost Calculator |
| **Stack** | Java 21, Spring Boot 3.5.4, Redis, MySQL, Resilience4j |
| **Highlight** | 7 Core Modules: LogicExecutor, TieredCache, Singleflight, Circuit Breaker |
| **Result** | **RPS 719**, p50 164ms, **0% Failure** (로컬 벤치마크 #266) |

---

## Overview

MapleExpectation calculates equipment upgrade costs for MapleStory using Nexon's Open API. The system processes complex calculations involving 200-350KB payloads per request, equivalent to handling **100,000+ standard API requests** in throughput.

### Value Proposition

> **"1 Request = 150 Standard Requests"** handled with enterprise-grade resilience

| Capability | Evidence |
|------------|----------|
| 1,000+ 동시 사용자 | [Load Test Report #266](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md) |
| Zero Failure Rate | 18개 Nightmare 카오스 테스트 검증 |
| Cost Efficiency | Single t3.small (~$15/month) |

### Quick Links

| Document | Description |
|----------|-------------|
| [Architecture](docs/00_Start_Here/architecture.md) | System architecture diagram |
| [Load Test #266](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md) | 최신 부하테스트 (RPS 719) |
| [Chaos Engineering](docs/01_Chaos_Engineering/06_Nightmare/) | Nightmare scenarios N01-N18 |
| [KPI Dashboard](docs/04_Reports/KPI_BSC_DASHBOARD.md) | BSC metrics and KPIs |

---

## Architecture Highlights

### 7 Core Modules

```
┌─────────────────────────────────────────────────────────────┐
│                    API Gateway Layer                        │
├─────────────────────────────────────────────────────────────┤
│  LogicExecutor  │  TieredCache  │  Resilience4j  │  AOP    │
├─────────────────────────────────────────────────────────────┤
│           Business Logic (DP Calculator)                    │
├─────────────────────────────────────────────────────────────┤
│     MySQL (GZIP)     │     Redis (L2)     │   Caffeine (L1) │
└─────────────────────────────────────────────────────────────┘
```

| Module | Purpose | Key Feature |
|--------|---------|-------------|
| **LogicExecutor** | Zero try-catch policy | 6 execution patterns |
| **TieredCache** | L1(Caffeine) + L2(Redis) | Singleflight dedup |
| **Resilience4j** | Circuit Breaker | Marker-based exception handling |
| **AOP Pipeline** | Cross-cutting concerns | @Async, @Transactional |
| **DP Calculator** | Cost calculation | Kahan Summation for precision |
| **Graceful Shutdown** | Zero data loss | SmartLifecycle ordering |
| **Write-Behind Buffer** | Async DB writes | 1,500x latency reduction |

---

## Performance

> **RPS 719 | p50 164ms | Error 0%** - [wrk Load Test Report #266](docs/04_Reports/Load_Tests/LOAD_TEST_REPORT_20260125_V4_PARALLEL_WRITEBEHIND.md)

### Benchmark Results (wrk, 로컬 환경)

| Metric | Value | Notes |
|--------|-------|-------|
| **RPS** | 719 | 200 connections, 10s |
| **p50 Latency** | 164ms | Average response time |
| **Throughput** | 4.56 MB/s | ~215 MB/s equivalent |
| **Error Rate** | 0% | Zero failures |

### Evolution

```
#262 Singleflight  : 120 RPS (baseline)
#264 L1 Fast Path  : 555 RPS (+362%)
#266 Parallel+Buffer: 719 RPS (+500% from baseline)
```

### Equivalent Throughput

```
719 RPS × 300KB payload = 215.7 MB/s
215.7 MB/s ÷ 2KB standard = 107,850 equivalent RPS
```

---

## Chaos Engineering

18 Nightmare scenarios tested and passed:

| Category | Scenarios | Status |
|----------|-----------|--------|
| Concurrency | N01 Thundering Herd, N02 Deadlock | ✅ PASS |
| Resource | N03 Thread Pool, N04 Connection | ✅ PASS |
| Timeout | N05 Celebrity, N06 Cascade | ✅ PASS |
| Data | N07-N18 Various edge cases | ✅ PASS |

→ [Full Chaos Report](docs/01_Chaos_Engineering/06_Nightmare/)

---

## Getting Started

```bash
# Prerequisites: Docker, Java 21

# 1. Start infrastructure
docker-compose up -d

# 2. Run application
./gradlew bootRun

# 3. Test API
curl http://localhost:8080/api/v4/characters/{characterName}/expectation
```

---

## Documentation

| Category | Documents |
|----------|-----------|
| **Start Here** | [Architecture](docs/00_Start_Here/architecture.md), [Roadmap](docs/00_Start_Here/ROADMAP.md) |
| **Technical** | [Infrastructure](docs/02_Technical_Guides/infrastructure.md), [Async](docs/02_Technical_Guides/async-concurrency.md) |
| **Testing** | [Test Guide](docs/02_Technical_Guides/testing-guide.md), [Chaos](docs/01_Chaos_Engineering/) |
| **Reports** | [KPI Dashboard](docs/04_Reports/KPI_BSC_DASHBOARD.md), [Load Tests](docs/04_Reports/Load_Tests/) |

---

## Contributing

See [CLAUDE.md](CLAUDE.md) for coding standards and the 5-Agent Council protocol.

---

## License

MIT License - See [LICENSE](LICENSE) for details.
