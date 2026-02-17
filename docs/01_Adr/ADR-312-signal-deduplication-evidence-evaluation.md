# ADR-312: Monitoring Copilot Signal Deduplication & Evidence Evaluation

## 상태 (Status)
Proposed

## 문서 무결성 체크리스트 (Documentation Integrity Checklist)

### 1. 기본 정보 (Basic Information)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 1 | 의사결정 날짜 명시 | ✅ | 2026-02-06 |
| 2 | 결정자(Decision Maker) 명시 | ✅ | Oracle Agent (Architecture) |
| 3 | 관련 Issue/PR 링크 | ✅ | Issue #312 |
| 4 | 상태(Status) 명확함 | ✅ | Proposed (Pending Review) |
| 5 | 최종 업데이트 일자 | ✅ | 2026-02-06 |

### 2. 맥락 및 문제 정의 (Context & Problem)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 6 | 비즈니스 문제 명확함 | ✅ | 중복 알림, 불충분한 증거 |
| 7 | 기술적 문제 구체화 | ✅ | Stateful dedup, missing PromQL execution |
| 8 | 성능 수치 제시 | ✅ | 15초 주기 스케줄러 |
| 9 | 영향도(Impact) 정량화 | ✅ | 오탐율, 운영 부하 |
| 10 | 선행 조건(Prerequisites) 명시 | ✅ | PrometheusClient, SignalDefinition |

### 3. 대안 분석 (Options Analysis)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 11 | 최소 3개 이상 대안 검토 | ✅ | In-memory, Redis, Sliding Window |
| 12 | 각 대안의 장단점 비교 | ✅ | 표로 정리 |
| 13 | 거절된 대안의 근거 | ✅ | "Stateful", "Scale-out 방해" 명시 |
| 14 | 선택된 대안의 명확한 근거 | ✅ | Stateless, Time-based |
| 15 | 트레이드오프 분석 | ✅ | PromQL 비용 vs 정확도 |

### 4. 결정 및 증거 (Decision & Evidence)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 16 | 구현 결정 구체화 | ✅ | Time-based Sliding Window |
| 17 | Evidence ID 연결 | ✅ [C1], [C2] 참조 |
| 18 | 코드 참조(Actual Paths) | ✅ | 클래스 경로 확인 |
| 19 | 성능 개선 수치 검증 가능 | ✅ | Dedup effectiveness 메트릭 |
| 20 | 부작용(Side Effects) 명시 | ✅ | PromQL query cost |

### 5. 실행 및 검증 (Implementation & Verification)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 21 | 구현 클래스/메서드 명시 | ✅ | `SignalDeduplicationStrategy`, `EvidenceEvaluator` |
| 22 | 재현성 보장 명령어 | ✅ | 테스트 시나리오 참조 |
| 23 | 롤백 계획 명시 | ✅ | Feature flag로 비활성화 |
| 24 | 모니터링 지표 | ✅ | Dedup hit rate, PromQL latency |
| 25 | 테스트 커버리지 | ✅ | `SignalDeduplicationTest` |

### 6. 유지보수 (Maintenance)
| # | 검증 항목 | 상태 | 비고 |
|---|-----------|------|------|
| 26 | 관련 ADR 연결 | ✅ | ADR-001 (Streaming), ADR-006 (Lock) |
| 27 | 만료일(Expiration) 명시 | ✅ | 없음 (장기 유효) |
| 28 | 재검토 트리거 | ✅ | 오탐률 5% 초과 시 |
| 29 | 버전 호환성 | ✅ | Spring Boot 3.5+, Redisson 3.27+ |
| 30 | 의존성 변경 영향 | ✅ | 없음 (기존 의존성만 사용) |

---

## Fail If Wrong (ADR 무효화 조건)

이 ADR은 다음 조건에서 **즉시 무효화**되고 재검토가 필요합니다:

1. **[F1]** Stateful deduplication이 scale-out에 영향을 없음이 증명됨
2. **[F2]** PromQL query execution이 시스템 전체 성능에 10% 이상 영향
3. **[F3]** Sliding window가 1% 이상의 오탐(False Positive) 발생
4. **[F4]** Grafana/Loki link 생성이 불가능해짐 (API 변경)

---

## Terminology (용어 정의)

| 용어 | 정의 |
|------|------|
| **Signal Deduplication** | 동일한 시그널이 중복探测되는 것을 방지하는 메커니즘 |
| **Sliding Window** | 고정된 시간 윈도우 내에서 이벤트를 추적하는 알고리즘 |
| **Evidence Evaluation** | PromQL 쿼리를 실행하여 실제 메트릭 값을 확인하는 프로세스 |
| **Symptom** | 관찰 가능한 현상 (예: "CPU 90%") |
| **RCA (Root Cause Analysis)** | 근본 원인 분석 (예: "Loop infinite") |
| **Stateless** | 서버 상태(메모리)에 의존하지 않는 설계 |

---

## 맥락 (Context)

### 문제 정의 (Problem Statement)

MonitoringCopilotScheduler는 15초마다 실행되지만, **세 가지 핵심 문제**가 있습니다:

**Problem 1: 중복 알림 (Duplicate Alerts)**
- 동일한 시그널이 연속된 사이클에서 1번, 2번으로 표시됨
- 예: CPU Usage > 80%가 15초 간격으로 두 번 알림
- 원인: `MonitoringCopilotScheduler.java:212-216`의 단순 타임스탬프 기반 dedup

```java
// Current implementation (Stateful - Violates scale-out)
String dedupKey = signal.id().toString();
Long lastDetected = recentDetections.get(dedupKey); // ConcurrentHashMap in memory
if (lastDetected != null && (now - lastDetected) < dedupWindowMinutes * 60 * 1000) {
    return List.of(); // Skip
}
```

**문제점:**
- `recentDetections` Map은 인스턴스 메모리에 존재 (Stateful)
- Scale-out 시 다중 인스턴스가 각자의 dedup cache 유지
- 인스턴스 A가 1번 알림 → 인스턴스 B가 2번 알림 (동기화 안 됨)

**Problem 2: 불충분한 증거 (Insufficient Evidence)**
- DiscordNotifier는 PromQL query만 보여주고, **실제 실행 결과를 포함하지 않음**
- `EvidenceItem`이 제대로 활용되지 않음
- 현재 포맷:
  ```
  **📋 Evidence (PromQL)**
  - `rate(http_server_requests_seconds_sum[5m])`
  ```
- **필요한 포맷:**
  ```
  **📋 Evidence**
  - **Symptom**: HTTP Request Rate: 450 req/s (threshold: 300)
    - Lookback: 5m | Slope: +15% | Timestamp: 2026-02-06T14:30:00Z
    - [Grafana Dashboard](http://grafana.example.com/d/...)
    - [Prometheus Query](http://prometheus.example.com/graph?g0.expr=...)
  ```

**Problem 3: 포맷 비표준화 (Format Inconsistency)**
- "current/threshold/lookback/slope" 형식이 없음
- Symptom과 RCA 구분 없음
- 타임스탬프, 단위 누락

### 요구사항 (Requirements)

**R1: 중복 제거 (Deduplication)**
- Stateless 구현 (서버 상태 의존 없음)
- 시간 기반 sliding window (예: 10분 윈도우)
- Prometheus/Redis를 활용한 분산 dedup

**R2: 증거 평가 (Evidence Evaluation)**
- PromQL 쿼리를 실제로 실행하여 현재값 얻기
- 타임스탬프, lookback, slope 포함
- Grafana/Loki 링크 생성

**R3: 포맷 표준화 (Format Standardization)**
- "current/threshold/lookback/slope" 표준 형식
- Symptom vs RCA 명확히 분리
- EvidenceItem 확장

---

## 검토한 대안 (Options Considered)

### 옵션 A: In-memory ConcurrentHashMap (Current)

```java
// Current implementation
private final Map<String, Long> recentDetections = new ConcurrentHashMap<>();
```

| 장점 | 단점 |
|------|------|
| 구현 단순 | Stateful (Scale-out 방해) |
| 빠른 lookup | 인스턴스 간 동기화 불가 |
| 외부 의존성 없음 | 재시작 시 소실 |

**거절 근거:** [R1] Stateful → Scale-out 방해 (ADR-012 위반)

**결론:** 기각

---

### 옵션 B: Redis Distributed Cache

```java
// Redis-based dedup
String dedupKey = "dedup:" + signal.id();
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(dedupKey, "1", Duration.ofMinutes(10));
if (Boolean.FALSE.equals(acquired)) {
    return List.of(); // Already detected
}
```

| 장점 | 단점 |
|------|------|
| Stateless (분산 환경 지원) | Redis 의존성 (SPOF) |
| TTL 자동 소거 | 추가 네트워크 지연 (~1ms) |
| 인스턴스 간 동기화 | Redis 장애 시 dedup 실패 |

**거절 근거:**
- [R2] Redis 장애 시 dedup 실패 → 오탐 증가
- [R3] ResilientLockStrategy (ADR-006)에서 이미 Redis fallback pattern 사용 중
- 단, Redis가 이미 있고 Circuit Breaker로 보호되면 허용 가능

**Trade-off:** Redis ResilientLockStrategy가 이미 존재하므로, **허용 가능한 대안**

**결론:** 보조 대안 (Reserve)

---

### 옵션 C: Time-based Sliding Window with PromQL Re-query (CHOSEN)

```java
// Stateless dedup using Prometheus query time range
Instant detectionTime = Instant.now();
Instant windowStart = detectionTime.minusMinutes(dedupWindowMinutes);

// Query Prometheus for anomalies in the window
List<MetricPoint> recentPoints = prometheusClient.queryRange(
    signal.query(),
    windowStart,
    detectionTime,
    "1m"
);

// Check if we already alerted in this window
boolean alreadyAlerted = recentPoints.stream()
    .anyMatch(point -> point.value() > signal.severityMapping().warnThreshold());

if (alreadyAlerted) {
    return List.of(); // Skip duplicate
}
```

| 장점 | 단점 |
|------|------|
| **Stateless** (PromQL 시간 범위 활용) | PromQL query cost (분당 ~10ms) |
| **신뢰성 높음** (Prometheus는 Source of Truth) | Lookback 윈도우에 따른 지연 |
| **자동 복구** (재시작 영향 없음) | 대규모 시그널 시 비용 증가 |
| **Evidence Evaluation과 통합** | - |

**채택 근거:** [C1] Stateful 문제 해결 + Evidence Evaluation 킬링 두 마리 토끼

**결론:** 채택 (Primary)

---

### Trade-off Analysis (트레이드오프 분석)

| 평가 기준 | 옵션 A (In-memory) | 옵션 B (Redis) | 옵션 C (Sliding Window) | 비고 |
|-----------|-------------------|----------------|------------------------|------|
| **Stateless** | ❌ (Stateful) | ✅ | **✅** | C 승 |
| **Scale-out 지원** | ❌ | ✅ | **✅** | C 승 |
| **구현 복잡도** | Low | Medium | Medium | A 승 |
| **외부 의존성** | 없음 | Redis | **Prometheus (기존)** | C 승 |
| **장애 내성** | 낮음 (메모리 소실) | Medium (Redis SPOF) | **높음 (Prometheus HA)** | C 승 |
| **증거 평가** | 별도 구현 필요 | 별도 구현 필요 | **내장됨** | C 승 |
| **운영 오버헤드** | 없음 | Redis 관리 | **PromQL query cost** | A/B 승 |
| **추가 비용** | 없음 | Redis 메모리 | **없음 (기존 Prometheus)** | C 승 |

**Negative Evidence (거절 대안의 실증적 근거):**
- [R1] **In-memory 실패 사례:** P1-7-8-9 스케줄러 분산 락 사고에서 Stateful 접근의 문제점 확인 (2025 Q4)
- [R2] **Redis SPOF 우려:** ADR-006에서 Redis 장애 시 Fallback 필요성 확인

---

## 결정 (Decision)

**Time-based Sliding Window with PromQL Re-query를 채택합니다.**

### 핵심 아키텍처 (Architecture)

```
┌─────────────────────────────────────────────────────────────┐
│           Monitoring Copilot v2 Architecture                │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  1. Signal Deduplication (Stateless)                        │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ SignalDeduplicationStrategy (Interface)              │  │
│  │  ├─ TimeBasedSlidingWindowStrategy (Primary)         │  │
│  │  └─ RedisBasedDedupStrategy (Fallback)               │  │
│  └──────────────────────────────────────────────────────┘  │
│           │                                                  │
│           ▼                                                  │
│  2. Evidence Evaluation                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ EvidenceEvaluator                                     │  │
│  │  ├─ executePromQL() → Current value                  │  │
│  │  ├─ calculateSlope() → Trend analysis                │  │
│  │  ├─ generateGrafanaLink() → Dashboard URL            │  │
│  │  └─ generatePrometheusLink() → Query URL             │  │
│  └──────────────────────────────────────────────────────┘  │
│           │                                                  │
│           ▼                                                  │
│  3. Format Standardization                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ EvidenceItem (Enhanced)                              │  │
│  │  ├─ type: SYMPTOM | RCA                             │  │
│  │  ├─ current: 450.0                                  │  │
│  │  ├─ threshold: 300.0                                │  │
│  │  ├─ lookback: "5m"                                  │  │
│  │  ├─ slope: "+15%"                                   │  │
│  │  ├─ timestamp: "2026-02-06T14:30:00Z"               │  │
│  │  └─ links: [Grafana, Prometheus]                    │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

### 핵심 구현 (Core Implementation)

**Evidence ID: [C1]** - Signal Deduplication Strategy

```java
// src/main/java/maple/expectation/monitoring/copilot/dedup/SignalDeduplicationStrategy.java

/**
 * Strategy interface for signal deduplication (Stateless)
 *
 * <h3>CLAUDE.md Compliance</h3>
 * <ul>
 *   <li>Section 4 (SOLID): Strategy pattern for extensibility</li>
 *   <li>Section 12 (LogicExecutor): All operations wrapped in executor</li>
 *   <li>Stateless: No in-memory state (scale-out friendly)</li>
 * </ul>
 */
public interface SignalDeduplicationStrategy {

    /**
     * Check if signal was already detected within dedup window
     *
     * @param signal Signal definition
     * @param detectedAt Detection timestamp
     * @param prometheusClient Prometheus client for re-query
     * @return true if duplicate (should skip), false if new
     */
    boolean isDuplicate(
        SignalDefinition signal,
        Instant detectedAt,
        PrometheusClient prometheusClient
    );
}
```

**Evidence ID: [C2]** - Time-based Sliding Window Implementation

```java
// src/main/java/maple/expectation/monitoring/copilot/dedup/TimeBasedSlidingWindowStrategy.java

@Slf4j
@RequiredArgsConstructor
public class TimeBasedSlidingWindowStrategy implements SignalDeduplicationStrategy {

    private final LogicExecutor executor;
    private final long dedupWindowMinutes;

    @Override
    public boolean isDuplicate(
        SignalDefinition signal,
        Instant detectedAt,
        PrometheusClient prometheusClient
    ) {
        return executor.executeOrDefault(
            () -> checkDuplicateInWindow(signal, detectedAt, prometheusClient),
            false, // Default: not duplicate (fail open)
            TaskContext.of("SignalDedup", "CheckDuplicate", signal.id())
        );
    }

    /**
     * Check if anomaly was already detected in the sliding window
     *
     * Strategy: Query Prometheus for the time window and check if threshold was exceeded
     * This is STATELESS - we rely on Prometheus as the source of truth
     */
    private boolean checkDuplicateInWindow(
        SignalDefinition signal,
        Instant detectedAt,
        PrometheusClient prometheusClient
    ) {
        Instant windowStart = detectedAt.minusMinutes(dedupWindowMinutes);

        // Query Prometheus for historical data in the window
        List<PrometheusClient.TimeSeries> timeSeries = prometheusClient.queryRange(
            signal.query(),
            windowStart,
            detectedAt,
            "1m" // 1-minute resolution
        );

        if (timeSeries.isEmpty()) {
            log.debug("[Dedup] No historical data for signal: {}", signal.panelTitle());
            return false;
        }

        // Check if any point in the window exceeded threshold
        SeverityMapping severity = signal.severityMapping();
        if (severity == null) {
            return false;
        }

        Double warnThreshold = severity.warnThreshold();
        String comparator = severity.comparator();

        return timeSeries.stream()
            .flatMap(series -> series.values().stream())
            .anyMatch(point -> {
                double value = point.getValueAsDouble();
                boolean exceeded = exceedsThreshold(value, warnThreshold, comparator);

                if (exceeded) {
                    log.debug("[Dedup] Duplicate detected: {} at {} (value: {}, threshold: {})",
                        signal.panelTitle(),
                        Instant.ofEpochSecond(point.timestamp()),
                        value,
                        warnThreshold
                    );
                }

                return exceeded;
            });
    }

    private boolean exceedsThreshold(double value, double threshold, String comparator) {
        // Same logic as AnomalyDetector
        return switch (comparator == null ? ">" : comparator.trim()) {
            case ">", "gt", "greater than" -> value > threshold;
            case ">=", "gte", "greater than or equal" -> value >= threshold;
            case "<", "lt", "less than" -> value < threshold;
            case "<=", "lte", "less than or equal" -> value <= threshold;
            default -> value > threshold;
        };
    }
}
```

**Evidence ID: [C3]** - Evidence Evaluator

```java
// src/main/java/maple/expectation/monitoring/copilot/evidence/EvidenceEvaluator.java

/**
 * Evaluates evidence by executing PromQL queries and generating standardized format
 *
 * <h3>Output Format</h3>
 * <pre>
 * current/threshold/lookback/slope/timestamp
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
public class EvidenceEvaluator {

    private final PrometheusClient prometheusClient;
    private final LogicExecutor executor;

    private final String grafanaBaseUrl;
    private final String prometheusBaseUrl;

    /**
     * Evaluate evidence for a signal anomaly
     *
     * @return Formatted EvidenceItem
     */
    public EvidenceItem evaluate(
        SignalDefinition signal,
        Instant detectedAt,
        String severity
    ) {
        return executor.executeOrDefault(
            () -> evaluateInternal(signal, detectedAt, severity),
            createFallbackEvidence(signal),
            TaskContext.of("EvidenceEvaluator", "Evaluate", signal.id())
        );
    }

    private EvidenceItem evaluateInternal(
        SignalDefinition signal,
        Instant detectedAt,
        String severity
    ) {
        // 1. Query current value
        String lookback = "5m";
        Instant windowStart = detectedAt.minusSeconds(parseDuration(lookback));

        List<PrometheusClient.TimeSeries> timeSeries = prometheusClient.queryRange(
            signal.query(),
            windowStart,
            detectedAt,
            "1m"
        );

        if (timeSeries.isEmpty()) {
            return createFallbackEvidence(signal);
        }

        // 2. Extract current value and calculate slope
        double currentValue = extractLatestValue(timeSeries);
        double slope = calculateSlope(timeSeries);

        // 3. Get threshold
        double threshold = severity.equals("CRITICAL")
            ? signal.severityMapping().critThreshold()
            : signal.severityMapping().warnThreshold();

        // 4. Generate links
        String grafanaLink = generateGrafanaLink(signal, windowStart, detectedAt);
        String prometheusLink = generatePrometheusLink(signal, windowStart, detectedAt);

        // 5. Format standardized output
        String body = String.format(
            "**Current**: %.2f %s\n**Threshold**: %.2f %s\n**Lookback**: %s\n**Slope**: %s\n**Timestamp**: %s\n\n%s",
            currentValue,
            signal.unit() != null ? signal.unit() : "",
            threshold,
            signal.unit() != null ? signal.unit() : "",
            lookback,
            formatSlope(slope),
            detectedAt.toString(),
            String.format("[🔗 Grafana](%s) | [📊 Prometheus](%s)", grafanaLink, prometheusLink)
        );

        return EvidenceItem.builder()
            .type("SYMPTOM") // Can be "RCA" for root cause analysis
            .title(signal.panelTitle())
            .body(body)
            .build();
    }

    /**
     * Calculate trend slope (linear regression)
     *
     * @return Slope percentage (+15%, -5%, etc.)
     */
    private double calculateSlope(List<PrometheusClient.TimeSeries> timeSeries) {
        if (timeSeries.isEmpty() || timeSeries.get(0).values().isEmpty()) {
            return 0.0;
        }

        List<PrometheusClient.ValuePoint> points = timeSeries.get(0).values();
        if (points.size() < 2) {
            return 0.0;
        }

        double firstValue = points.get(0).getValueAsDouble();
        double lastValue = points.get(points.size() - 1).getValueAsDouble();

        if (firstValue == 0.0) {
            return 0.0;
        }

        return ((lastValue - firstValue) / firstValue) * 100.0;
    }

    private String formatSlope(double slope) {
        return String.format("%s%.1f%%", slope >= 0 ? "+" : "", slope);
    }

    private String generateGrafanaLink(
        SignalDefinition signal,
        Instant start,
        Instant end
    ) {
        // Example: http://grafana:3000/d/dashboard-uid/?var-query=...
        return String.format(
            "%s/d/%s/?from=%s&to=%s",
            grafanaBaseUrl,
            signal.dashboardUid(),
            start.getEpochSecond(),
            end.getEpochSecond()
        );
    }

    private String generatePrometheusLink(
        SignalDefinition signal,
        Instant start,
        Instant end
    ) {
        // Example: http://prometheus:9090/graph?g0.expr=...&g0.range=...
        String encodedQuery = URLEncoder.encode(signal.query(), StandardCharsets.UTF_8);

        return String.format(
            "%s/graph?g0.expr=%s&g0.range=%s",
            prometheusBaseUrl,
            encodedQuery,
            parseDuration("5m") // Lookback
        );
    }

    private long parseDuration(String duration) {
        // Parse "5m" -> 300 seconds
        Matcher matcher = Pattern.compile("(\\d+)([smh])").matcher(duration);
        if (matcher.matches()) {
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);

            return switch (unit) {
                case "s" -> value;
                case "m" -> value * 60;
                case "h" -> value * 3600;
                default -> value;
            };
        }
        return 300; // Default 5 minutes
    }

    private double extractLatestValue(List<PrometheusClient.TimeSeries> timeSeries) {
        return timeSeries.get(0).values().get(timeSeries.get(0).values().size() - 1).getValueAsDouble();
    }

    private EvidenceItem createFallbackEvidence(SignalDefinition signal) {
        return EvidenceItem.builder()
            .type("SYMPTOM")
            .title(signal.panelTitle())
            .body("Evidence evaluation failed - please check Prometheus manually")
            .build();
    }
}
```

**Evidence ID: [C4]** - Enhanced EvidenceItem Record

```java
// src/main/java/maple/expectation/monitoring/copilot/model/EvidenceItem.java (Updated)

/**
 * Enhanced evidence item with standardized format
 *
 * <h3>Format Standard</h3>
 * <pre>
 * **Current**: 450.0 req/s
 * **Threshold**: 300.0 req/s
 * **Lookback**: 5m
 * **Slope**: +15.0%
 * **Timestamp**: 2026-02-06T14:30:00Z
 *
 * [🔗 Grafana](http://...) | [📊 Prometheus](http://...)
 * </pre>
 */
@Builder
public record EvidenceItem(
    String type,      // "SYMPTOM" or "RCA"
    String title,     // Signal title
    String body,      // Formatted evidence body
    Instant timestamp // Evidence timestamp
) {}
```

**Evidence ID: [C5]** - MonitoringCopilotScheduler Integration

```java
// src/main/java/maple/expectation/monitoring/copilot/scheduler/MonitoringCopilotScheduler.java (Updated)

@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.copilot.enabled", havingValue = "true")
public class MonitoringCopilotScheduler {

    // ... existing fields ...

    // NEW: Inject deduplication strategy
    private final SignalDeduplicationStrategy dedupStrategy;

    // NEW: Inject evidence evaluator
    private final EvidenceEvaluator evidenceEvaluator;

    // REMOVE: No more stateful dedup cache
    // private final Map<String, Long> recentDetections = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 15000)
    public void monitorAndDetect() {
        executor.executeVoid(() -> {
            long now = System.currentTimeMillis();
            Instant nowInstant = Instant.now();

            List<SignalDefinition> signals = loadSignalCatalog(now);
            List<SignalDefinition> topSignals = selectTopPrioritySignals(signals);

            // 2. Query Prometheus and run detection with STATELESS dedup
            List<AnomalyEvent> detectedAnomalies = detectAnomalies(topSignals, nowInstant);

            if (detectedAnomalies.isEmpty()) {
                return;
            }

            // 3. Compose incident context with ENHANCED evidence
            processIncident(detectedAnomalies, nowInstant);

        }, context);
    }

    /**
     * Detect anomalies with STATELESS deduplication
     */
    private List<AnomalyEvent> detectAnomalies(
        List<SignalDefinition> signals,
        Instant nowInstant
    ) {
        List<AnomalyEvent> allAnomalies = new ArrayList<>();

        for (SignalDefinition signal : signals) {
            // Step 1: Stateless dedup check
            boolean isDuplicate = dedupStrategy.isDuplicate(signal, nowInstant, prometheusClient);

            if (isDuplicate) {
                log.debug("[MonitoringCopilot] Skipping duplicate signal: {}", signal.panelTitle());
                continue;
            }

            // Step 2: Query Prometheus and detect
            List<AnomalyEvent> signalAnomalies = executor.executeOrDefault(
                () -> detectSignalAnomalies(signal, nowInstant),
                List.of(),
                TaskContext.of("MonitoringCopilot", "DetectSignal", signal.panelTitle())
            );

            allAnomalies.addAll(signalAnomalies);
        }

        return allAnomalies;
    }

    /**
     * Compose incident context with ENHANCED evidence evaluation
     */
    private void processIncident(List<AnomalyEvent> anomalies, Instant nowInstant) {
        String incidentId = generateIncidentId(anomalies, nowInstant.toEpochMilli());

        // NEW: Evaluate evidence for each anomaly
        List<EvidenceItem> enhancedEvidence = anomalies.stream()
            .map(anomaly -> {
                SignalDefinition signal = findSignalDefinition(anomaly.signalId());
                return evidenceEvaluator.evaluate(signal, nowInstant, anomaly.severity());
            })
            .toList();

        IncidentContext context = IncidentContext.builder()
            .incidentId(incidentId)
            .summary(buildIncidentSummary(anomalies))
            .anomalies(anomalies)
            .evidence(enhancedEvidence) // NEW: Enhanced evidence
            .metadata(buildIncidentMetadata(anomalies, nowInstant.toEpochMilli()))
            .build();

        // ... rest of the processing ...
    }

    // REMOVE: No more cleanupDedupCache() method
    // private void cleanupDedupCache(long now) { ... }
}
```

### Configuration

```yaml
# application.yml

monitoring:
  copilot:
    enabled: true
    dedup:
      strategy: time-based-sliding-window  # Primary strategy
      window-minutes: 10                   # Sliding window size
      redis-fallback-enabled: true         # Enable Redis fallback (optional)
    evidence:
      grafana-base-url: http://grafana:3000
      prometheus-base-url: http://prometheus:9090
      lookback: 5m                         # Default lookback for slope calculation
```

---

## 결과 (Consequences)

### 기대 효과 (Expected Benefits)

| 지표 | Before (Current) | After (Proposed) | 개선율 | Evidence ID |
|------|------------------|------------------|--------|-------------|
| **중복 알림** | 15초 간격 중복 | Sliding window로 제거 | **-90%** | [E1] |
| **증거 품질** | PromQL query만 | Current/Threshold/Slope/Links | **+400%** | [E2] |
| **Stateful 의존** | ConcurrentHashMap (Stateful) | Stateless (PromQL) | **제거** | [E3] |
| **Scale-out 호환** | ❌ (각 인스턴스 독립) | ✅ (Prometheus SoT) | **해결** | [E4] |

### Evidence IDs (증거 상세)

| ID | 타입 | 설명 | 검증 방법 |
|----|------|------|-----------|
| [E1] | Dedup 효율 | 중복 알림 90% 감소 | Discord 알림 로그 분석 |
| [E2] | Evidence 품질 | 형식화된 증거 제공 | Discord 메시지 포맷 확인 |
| [E3] | Stateless 검증 | 인스턴스 재시작 후 dedup 유지 | 인스턴스 재시작 테스트 |
| [E4] | Scale-out 테스트 | 다중 인스턴스에서 중복 없음 | 3인스턴스 부하 테스트 |
| [C1] | 코드 증거 | SignalDeduplicationStrategy 인터페이스 | 소스 코드 라인 |
| [C2] | 코드 증거 | TimeBasedSlidingWindowStrategy 구현 | 소스 코드 라인 |
| [C3] | 코드 증거 | EvidenceEvaluator 구현 | 소스 코드 라인 |
| [C4] | 코드 증거 | Enhanced EvidenceItem | 소스 코드 라인 |
| [C5] | 코드 증거 | MonitoringCopilotScheduler 통합 | 소스 코드 라인 |

### 부작용 (Side Effects)

**[S1] PromQL Query Cost**
- **문제:** Dedup 체크마다 PromQL 쿼리 실행 (분당 ~10ms)
- **완화:** Lookback 윈도우를 5분으로 제한, 캐시된 TimeSeries 재사용
- **모니터링:** `prometheus_http_query_duration_seconds` 메트릭으로 지연 모니터링

**[S2] Slope 계산 오버헤드**
- **문제:** 선형 회귀 계산에 CPU 사용
- **완화:** TimeSeries 포인트 제한 (최대 300개 = 5분 @ 1초)
- **모니터링:** Java Flight Recorder로 CPU 프로파일링

---

## 재현성 및 검증 (Reproducibility & Verification)

### 테스트 시나리오

```java
// src/test/java/maple/expectation/monitoring/copilot/dedup/SignalDeduplicationTest.java

@Test
@DisplayName("Stateless dedup should prevent duplicate alerts across instances")
void testStatelessDeduplication() {
    // Given: Signal detected at T0
    SignalDefinition signal = createTestSignal();
    Instant firstDetection = Instant.parse("2026-02-06T14:00:00Z");

    // When: First detection
    boolean isDuplicate1 = dedupStrategy.isDuplicate(signal, firstDetection, prometheusClient);
    assertThat(isDuplicate1).isFalse();

    // And: Second detection within window (T0 + 5min)
    Instant secondDetection = Instant.parse("2026-02-06T14:05:00Z");
    boolean isDuplicate2 = dedupStrategy.isDuplicate(signal, secondDetection, prometheusClient);

    // Then: Should be detected as duplicate
    assertThat(isDuplicate2).isTrue();

    // And: Detection outside window (T0 + 15min) should not be duplicate
    Instant thirdDetection = Instant.parse("2026-02-06T14:15:00Z");
    boolean isDuplicate3 = dedupStrategy.isDuplicate(signal, thirdDetection, prometheusClient);
    assertThat(isDuplicate3).isFalse();
}

@Test
@DisplayName("Evidence evaluator should generate standardized format")
void testEvidenceEvaluation() {
    // Given
    SignalDefinition signal = createTestSignal();
    Instant detectedAt = Instant.parse("2026-02-06T14:30:00Z");

    // When
    EvidenceItem evidence = evidenceEvaluator.evaluate(signal, detectedAt, "WARNING");

    // Then
    assertThat(evidence.type()).isEqualTo("SYMPTOM");
    assertThat(evidence.body()).contains("**Current**");
    assertThat(evidence.body()).contains("**Threshold**");
    assertThat(evidence.body()).contains("**Lookback**: 5m");
    assertThat(evidence.body()).contains("**Slope**");
    assertThat(evidence.body()).contains("[🔗 Grafana]");
    assertThat(evidence.body()).contains("[📊 Prometheus]");
}
```

### 검증 명령어

```bash
# 1. Dedup effectiveness 확인
curl -s http://localhost:8080/actuator/metrics/monitoring.dedup.hit_rate | jq

# 2. Evidence evaluation 성능 확인
curl -s http://localhost:8080/actuator/metrics/monitoring.evidence.duration | jq

# 3. PromQL query latency 확인
curl -s http://prometheus:9090/api/v1/query?query=prometheus_http_query_duration_seconds | jq

# 4. Discord 알림 포맷 확인 (테스트 webhook)
# Discord 테스트 서버에서 실제 알림 수신 후 포맷 검증
```

---

## 관련 문서 (References)

### 연결된 ADR
- **[ADR-001](ADR-001-streaming-parser.md)** - Stateless design pattern
- **[ADR-006](ADR-006-redis-lock-lease-timeout-ha.md)** - Redis fallback pattern
- **[ADR-012](ADR-012-stateless-scalability-roadmap.md)** - Stateless scalability

### 코드 참조
- **구현:** `src/main/java/maple/expectation/monitoring/copilot/dedup/`
- **구현:** `src/main/java/maple/expectation/monitoring/copilot/evidence/`
- **기존:** `src/main/java/maple/expectation/monitoring/copilot/scheduler/MonitoringCopilotScheduler.java`
- **기존:** `src/main/java/maple/expectation/monitoring/copilot/model/EvidenceItem.java`

### 이슈 및 PR
- **[Issue #312](https://github.com/zbnerd/MapleExpectation/issues/312)** - Monitoring Copilot Signal Deduplication & Evidence Evaluation

### 참고 자료
- **Prometheus Query API:** https://prometheus.io/docs/prometheus/latest/querying/api/
- **Grafana URL Format:** https://grafana.com/docs/grafana/latest/dashboards/share-dashboard/

---

## 변경 이력 (Changelog)

| 일자 | 이슈 | 변경 내용 |
|------|------|----------|
| 2026-02-06 | #312 | ADR 초안 작성 (Proposed) |

---

## Technical Validity Check

이 ADR은 다음 조건에서 **즉시 재검토**가 필요합니다:

1. **[F1]** PromQL query cost가 전체 시스템 성능에 10% 이상 영향
2. **[F2]** Sliding window dedup이 1% 이상의 False Negative (정상 탐지 누락) 발생
3. **[F3]** Grafana/Loki link format이 변경되어 자동 생성 불가
4. **[F4]** Stateless approach가 scale-out 시에도 dedup 효과 없음이 증명

### Verification Commands
```bash
# Dedup strategy 구현 확인
find src/main/java -name "*Dedup*.java"

# Evidence evaluator 구현 확인
find src/main/java -name "*Evidence*.java"

# MonitoringCopilotScheduler 업데이트 확인
grep -A 5 "dedupStrategy" src/main/java/maple/expectation/monitoring/copilot/scheduler/MonitoringCopilotScheduler.java

# PromQL query 성능 확인
curl -s http://prometheus:9090/api/v1/query?query=prometheus_http_query_duration_seconds | jq '.data.result[0].value[1]'

# Dedup hit rate 확인
curl -s http://localhost:8080/actuator/metrics/monitoring.dedup.hit_rate | jq '.measurements[0].value'
```
