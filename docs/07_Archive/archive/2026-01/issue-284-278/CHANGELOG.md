# MapleExpectation Changelog

All notable changes to this project will be documented in this file.

---

## [2026-01-31] - Issue #284 + #278 완료

### Added
- **Thread Pool 외부화** (Issue #284 Phase A)
  - `ExecutorProperties` Record 기반 YAML 설정 바인딩
  - EquipmentProcessingExecutor: Core 8~16, Max 16~32, Queue 200~500
  - PresetCalculationExecutor: Core 3~6, Max 6~12, Queue 100~200

- **Connection Pool 외부화** (Issue #284 Phase A)
  - `LockHikariConfig`: @Value("${lock.datasource.pool-size:30}")
  - 프로덕션 설정: 150 connections (Little's Law 기반)
  - Fixed Pool: Min = Max (연결 비용 제거)

- **orTimeout 타임아웃** (Issue #284 Phase B)
  - `EquipmentFetchProvider.fetchFromApi()`: .orTimeout(10, SECONDS)
  - `CharacterCreationService.createCharacter()`: .orTimeout(10, SECONDS)
  - `GameCharacterService.fetchGameCharacter()`: .orTimeout(10, SECONDS)

- **Micrometer 메트릭 통합** (Issue #284)
  - ExecutorServiceMetrics: executor.completed, executor.active, executor.queued
  - Custom Counter: executor.rejected{name=equipment.processing}
  - Hikari Metrics: hikaricp.connections.active{pool=MySQLLockPool}
  - Legacy Gauge 호환: equipment.executor.queue.size, preset.calculation.active.count

- **RReliableTopic at-least-once** (Issue #278 Phase A-D)
  - `ReliableRedisLikeEventPublisher`: Redis Streams 기반 발행자
  - `ReliableRedisLikeEventSubscriber`: Consumer Group 기반 구독자
  - `LikeRealtimeSyncConfig`: Strategy 패턴으로 RTopic/RReliableTopic 전환
  - `RedisKey.LIKE_EVENTS_RELIABLE_TOPIC`: Hash Tag {likes} 슬롯 최적화

- **Transport 프로퍼티화** (Issue #278)
  - `like.realtime.transport`: rtopic (기본) / reliable-topic (at-least-once)
  - Blue-Green 배포 전환 지원

### Changed
- **EquipmentProcessingExecutorConfig**
  - Reject Handler: AbortPolicy + rejected Counter 기록
  - TaskDecorator: contextPropagatingDecorator 추가 (MDC 전파)
  - Graceful Shutdown: waitForTasksToCompleteOnShutdown = true, awaitTerminationSeconds = 30

- **PresetCalculationExecutorConfig**
  - Reject Handler: CallerRunsPolicy (Deadlock 방지)
  - TaskDecorator: contextPropagatingDecorator 추가
  - 메트릭: Micrometer ExecutorServiceMetrics 등록

- **LockHikariConfig**
  - Pool Size: 하드코딩 10/50 → 프로퍼티 기반 (기본 30, prod 150)
  - 메트릭: Micrometer 자동 등록

- **application.yml**
  - executor 프로퍼티 추가 (equipment, preset)
  - lock.datasource.pool-size: 30 (기본값)
  - like.realtime.transport: rtopic (기본값)

- **application-prod.yml**
  - executor 프로덕션 최적화
    - equipment: core-pool-size=16, max-pool-size=32, queue-capacity=500
    - preset: core-pool-size=6, max-pool-size=12, queue-capacity=200
  - lock.datasource.pool-size: 150 (Redis 장애 대비)
  - like.realtime.transport: rtopic (Blue-Green 후 reliable-topic으로 전환)

- **ReliableRedisLikeEventPublisher**
  - LogicExecutor.executeOrDefault로 Graceful Degradation
  - 메트릭: countSubscribers() Gauge + publish success/failure Counter
  - 발행 실패 시에도 좋아요 기능 정상 동작

- **RedisKey.java**
  - LIKE_EVENTS_RELIABLE_TOPIC 추가 (Hash Tag {likes})

### Fixed
- **Thread Pool 병목** (Issue #284)
  - L1 캐시 미스 시 235 RPS 달성 (기존 120 RPS 대비 2배 개선)
  - 0% 에러율 (Connection Pool 고갈 해결)

- **무기한 대기** (Issue #284)
  - .join() 타임아웃: orTimeout(10, SECONDS) 추가
  - TimeoutException → ApiTimeoutException 변환

- **Scale-out 좋아요 동기화** (Issue #278)
  - RTopic at-most-once → RReliableTopic at-least-once 지원
  - 인스턴스 재시작 시 메시지 유실 방지

### Performance
- **부하 테스트 결과** (로컬 환경, 단일 인스턴스)
  - Total Requests: 48,183 (누락 없음)
  - Failures: 0% (완전 무결성)
  - RPS (Mean): 235.7
  - Median Latency: 160 ms
  - Throughput: 82.5 MB/s (350 KB × 235 RPS)
  - Concurrent Users: 500

### Documentation
- **Issue #284 + #278 완료 보고서**
  - 경로: `docs/04-report/features/issue-284-278.report.md`
  - PDCA Cycle 분석: Plan → Design → Do → Check → Act
  - 설계 결정 및 근거 문서화
  - 프로덕션 배포 체크리스트 제시

- **메트릭 임계값** (Prometheus Alert)
  - Equipment Executor:
    - queue.size > 160: WARNING
    - queue.size == 200: CRITICAL
    - active.count >= 14: WARNING
  - Preset Calculator:
    - queue.size > 80: WARNING
    - active.count >= 22: WARNING

---

## 주요 마일스톤

### Phase A: 외부화 (완료)
- Thread Pool YAML 바인딩
- Connection Pool 프로퍼티화
- Micrometer 메트릭 등록

### Phase B: 타임아웃 (완료)
- orTimeout(10, SECONDS) 추가
- ADR #118 준수 유지

### Phase C: Single-Flight (완료)
- TotalExpectationResponse @Cacheable 기존 구현

### Phase D: RReliableTopic (완료)
- at-least-once 메시징
- Transport 프로퍼티화
- Blue-Green 배포 가이드

---

## 관련 PR & Issues

- **PR #297**: feat: Issue #284 DoD 미충족 2건 보완
  - LockHikari Pool Size 외부화
  - orTimeout 추가

- **Issue #284**: [Performance] 대규모 트래픽(1000+ RPS) P0/P1 병목 해결
  - DoD 7항 중 7항 충족

- **Issue #278**: Scale-out 환경 실시간 좋아요 동기화
  - RReliableTopic at-least-once 구현

---

## 다음 단계 (Next Steps)

| 이슈 | 예상 난이도 | 우선순위 | 담당자 |
|------|----------|---------|--------|
| 실제 부하 테스트 (1000 RPS) | 높음 | P0 | Performance |
| RReliableTopic 마이그레이션 | 중간 | P0 | Data |
| 메트릭 대시보드 구성 | 낮음 | P1 | SRE |
| Runbook 작성 | 낮음 | P2 | Docs |
| CDC/샤딩 검토 | 높음 | P2 | Architecture |

---

## 기술 스택 업데이트

- **Java 21** ✅ Virtual Threads 지속 활용
- **Spring Boot 3.5.4** ✅ Latest stable
- **Redisson 3.27.0** ✅ RReliableTopic 지원
- **Micrometer** ✅ ExecutorServiceMetrics 통합
- **Resilience4j 2.2.0** ✅ Circuit Breaker 유지

---

Generated: 2026-01-31
Status: APPROVED
Review: Architecture Council (Blue, Green, Yellow, Purple, Red)
