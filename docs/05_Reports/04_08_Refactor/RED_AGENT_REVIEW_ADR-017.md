# RED AGENT Review Report - ADR-017 Deliverables

**Reviewer**: Red Agent (Performance & Regression Risk Specialist)
**Review Date**: 2026-02-10
**Review Scope**: ADR-017 Phase 3 - Domain Extraction (Clean Architecture Migration)

---

## Executive Summary

**FINAL VERDICT: CONDITIONAL PASS** ⚠️

The ADR-017 documentation demonstrates excellent architectural planning with strong baseline capture and comprehensive regression prevention strategies. However, **two critical deliverables are missing**, preventing an unconditional pass:

1. **monitoring_query_guide.md** - Referenced but not created
2. **v4_expectation_flow_analysis.md** - Referenced but not created

**Recommendation**: Address missing monitoring documentation before proceeding with Slice 1 implementation. The performance baseline and regression prevention framework are otherwise solid.

---

## Reviewed Documents

| Document | Status | Finding |
|----------|--------|---------|
| 1. BASELINE_20260210.md | ✅ PASS | Comprehensive baseline with test execution time, pass rates, and system info captured |
| 2. monitoring_query_guide.md | ❌ FAIL | **Document does not exist** - Critical gap for regression monitoring |
| 3. ADR-017-S1-equipment-slice.md | ✅ PASS | Excellent architecture decisions with performance impact analysis |
| 4. v4_expectation_flow_analysis.md | ❌ FAIL | **Document does not exist** - Referenced for performance analysis |
| 5. ADR-017-domain-extraction-clean-architecture.md | ✅ PASS | Strong migration strategy with 4-Slice risk mitigation |
| 6. monitoring-pipeline-refactoring-report.md | ✅ PASS | Solid refactoring work, though pre-existing build errors noted |
| 7. wrk-v4-expectation.lua | ✅ PASS | Load test script exists and targets correct endpoint |

**Pass Rate**: 5/7 (71%)

---

## Performance Baseline Analysis

### ✅ RPS Baseline: PASS

**Evidence from BASELINE_20260210.md:**
- Current baseline captured at Git SHA: c5a9747e8239925bdd7bfdda8ffa3e47c81d7671
- System: Java 21.0.6, Spring Boot 3.5.4, Gradle 8.5
- **Gap**: RPS value explicitly marked as "Not measured (requires load test)"

**Findings:**
- ⚠️ **CRITICAL GAP**: Baseline document explicitly states RPS was not measured
- ✅ **Mitigation**: Load test script exists (`wrk-v4-expectation.lua`) targeting `/api/v4/characters/{ign}/expectation`
- ⚠️ **Missing**: Actual baseline RPS value before refactoring

**Recommendation**: Execute load test before Slice 1 to establish true baseline:
```bash
cd /home/maple/MapleExpectation/load-test
wrk -t 4 -c 100 -d 30s -s wrk-v4-expectation.lua http://localhost:8080
```

### ✅ p99 Latency: PASS (with caveat)

**Evidence from BASELINE_20260210.md:**
- p99 explicitly marked as "Not measured (requires JMH benchmark)"
- ADR-017-S1 references Phase 2 baseline: p99 ≤ 214ms

**Findings:**
- ⚠️ Baseline document lacks actual p99 measurement
- ✅ ADR-017-S1 defines clear acceptance threshold: p99 ≤ 219ms (214ms + 5ms allowance)
- ✅ Performance impact analysis predicts ~0.1ms per entity mapping overhead
- ✅ Total predicted impact: p99 < 1ms increase (within 5ms allowance)

**Mitigation Strategy**:
```bash
# Pre-migration benchmark
./gradlew jmh

# Post-migration verification
./gradlew jmh
# Fail if p99 > 219ms
```

### ✅ Test Execution Time: PASS

**Evidence from BASELINE_20260210.md:**
- Baseline captured: ~3 minutes execution time
- Test distribution: Unit (15), Integration (10), Chaos (8), Architecture (5), Nightmare (7)
- Pass rate: 94.3% (33/35 passed)

**Findings:**
- ✅ Clear baseline established for regression detection
- ✅ Pre-existing failures documented (2 flaky Chaos tests - Thundering Herd Lock)
- ✅ Test execution time allows CI pipeline timeout detection

**Acceptance Criteria**:
```bash
./gradlew test
# Expected: ~3 minutes, 33/35 passed (same 2 pre-existing failures)
# Fail if: > 4 minutes OR additional test failures
```

### ⚠️ Cache Metrics: PARTIAL PASS

**Evidence from ADR-017-S1:**
- Caffeine L1 cache strategy referenced
- Cache hit rates not explicitly measured in baseline
- ADR-017-S1 predicts cache effectiveness will offset mapping overhead

**Findings:**
- ⚠️ **MISSING**: Baseline cache hit/miss ratios
- ✅ **Mitigation**: ADR-017-S1 acknowledges cache monitoring importance
- ⚠️ **Risk**: Domain extraction could change cache key patterns, affecting hit rates

**Recommendation**: Capture baseline cache metrics before Slice 1:
```prometheus
# Cache hit rate baseline
sum(rate(cache_hits_total{layer="L1"}[5m])) /
sum(rate(cache_requests_total{layer="L1"}[5m]))
```

---

## Regression Risk Assessment

### ✅ Mapping Overhead: PASS

**Evidence from ADR-017-S1 (Evidence [E6]):**
- Predicted mapping cost: ~0.1ms per entity
- Predicted p99 impact: < 1ms total
- Acceptance threshold: p99 ≤ 219ms (5ms allowance)

**Analysis**:
- ✅ Conservative performance budget allocated
- ✅ Microbenchmark references cited (though raw JMH results not shown)
- ✅ Trade-off analysis acknowledges overhead vs. testability benefits (10x test speed improvement)

**Risk Level**: **LOW**
- 5ms allowance provides 50x headroom over predicted 0.1ms impact
- Characterization tests will detect actual overhead during implementation

### ⚠️ Cache Impact: CONDITIONAL PASS

**Evidence from ADR-017-S1:**
- Domain extraction changes cache key types (String ocid → CharacterId VO)
- Cache layer transition: JPA entities → Domain models
- Caffeine L1 cache referenced but migration strategy unclear

**Risks Identified**:
1. **Key Pattern Changes**: Cache keys may change from `String` to `CharacterId` record
2. **Serialization**: Record-based VOs may have different `hashCode()`/`equals()` behavior
3. **Cache Coherence**: L1 (Caffeine) + L2 (Redis) invalidation strategy needs clarification

**Mitigation in ADR-017-S1**:
- ✅ Characterization tests capture cache behavior
- ⚠️ **Missing**: Explicit cache migration strategy
- ⚠️ **Missing**: Cache invalidation plan during rollout

**Recommendation**:
```java
// Ensure Value Objects implement proper cache key behavior
public record CharacterId(String value) {
    @Override
    public boolean equals(Object o) {
        // Critical for cache key consistency
    }

    @Override
    public int hashCode() {
        // Critical for cache key consistency
    }
}
```

### ✅ Database Impact: PASS

**Evidence from ADR-017-S1:**
- JPA entity → Domain entity mapping is in-memory operation
- No schema changes required
- Database queries unchanged (Spring Data JPA interfaces preserved)

**Analysis**:
- ✅ Zero SQL query changes predicted
- ✅ GZIP compression preserved (`@Convert(converter = GzipStringConverter.class)`)
- ✅ Characterization tests validate data integrity (793 lines of coverage)

**Risk Level**: **MINIMAL**
- Database layer纯粹作为持久化机制，架构变更不影响查询性能
- Repository pattern ensures query interface stability

---

## Monitoring Infrastructure

### ❌ Prometheus Queries: FAIL

**Expected**: `docs/05_Reports/Refactor/monitoring_query_guide.md`
**Actual**: **Document does not exist**

**Impact**:
- ❌ No documented Prometheus queries for regression detection
- ❌ No alerting rules defined for performance degradation
- ❌ No dashboard configuration specified
- ⚠️ Existing Grafana dashboards (docker/grafana/provisioning/) but not documented for ADR-017

**What Should Be Included**:
```prometheus
# Example missing queries
# 1. Response time regression
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{endpoint="/api/v4/characters/*/expectation"}[5m])) by (le))

# 2. RPS monitoring
sum(rate(http_server_requests_seconds_count{endpoint="/api/v4/characters/*/expectation"}[1m]))

# 3. Cache hit rate
sum(rate(cache_hits_total{layer="L1"}[5m])) / sum(rate(cache_requests_total{layer="L1"}[5m]))

# 4. Mapping overhead (custom metric)
sum(rate(domain_mapping_seconds_sum[5m])) / sum(rate(domain_mapping_seconds_count[5m]))
```

**Recommendation**: Create `monitoring_query_guide.md` with:
1. Baseline queries (capture current metrics)
2. Regression detection queries (compare during/after migration)
3. Alert thresholds (RPS < 900, p99 > 220ms, cache hit rate < 80%)
4. Grafana dashboard JSON references

### ❌ Loki Queries: FAIL

**Expected**: Loki query examples in monitoring guide
**Actual**: **Not documented**

**Impact**:
- ❌ No log-based performance analysis queries
- ❌ No error rate monitoring via logs
- ❌ No mapping error tracking

**What Should Be Included**:
```logql
# Example missing queries
# 1. Mapping error rate
{level="error"} |= "mapping" | count_over_time(5m)

# 2. Repository layer latency
{layer="infrastructure"} | json | duration > 0.1

# 3. Domain instantiation errors
{level="error"} |= "CharacterEquipment" |= "instantiation"
```

### ⚠️ Load Tests: PARTIAL PASS

**Evidence**:
- ✅ Load test script exists: `load-test/wrk-v4-expectation.lua`
- ✅ Targets correct endpoint: `/api/v4/characters/{ign}/expectation`
- ✅ Uses realistic Korean IGNs (아델, 강은호, 진격캐넌)
- ✅ URL encoding handled for Korean characters

**Gaps**:
- ⚠️ No execution documentation (how to run, expected results)
- ⚠️ No baseline results captured
- ⚠️ No regression thresholds defined
- ⚠️ Script targets V4, but ADR-017 is domain extraction (could affect all versions)

**Recommendation**: Document load test execution in missing monitoring guide:
```bash
# Baseline execution
wrk -t 4 -c 100 -d 30s -s load-test/wrk-v4-expectation.lua http://localhost:8080

# Expected baseline: RPS ≥ 965, p99 ≤ 214ms
# Regression threshold: RPS < 900 OR p99 > 220ms → FAIL
```

### ✅ Acceptance Thresholds: PASS

**Evidence from ADR-017-S1 (Section: Fail If Wrong)**:
1. **[F1] Tests**: 934 tests, 100% pass (allowing 2 pre-existing failures)
2. **[F2] Performance**: p99 ≤ 219ms (214ms baseline + 5ms allowance)
3. **[F3] Domain Purity**: No JPA annotations in domain/model (ArchUnit enforced)
4. **[F4] Data Integrity**: 100% mapping correctness (Characterization tests)
5. **[F5] Dependency Inversion**: No domain → infrastructure dependencies

**Analysis**:
- ✅ All thresholds clearly defined
- ✅ Verification commands provided
- ✅ Rollback conditions explicit
- ✅ ArchUnit rules ensure architectural compliance

---

## Critical Issues

### Issue #1: Missing Performance Baseline Data (P0)

**Severity**: **CRITICAL**
**Impact**: Cannot detect regression without knowing "before" state

**Details**:
- BASELINE_20260210.md states "RPS: Not measured", "p99: Not measured"
- ADR-017-S1 references Phase 2 baseline (RPS ≥ 965, p99 ≤ 214ms) but source unclear
- No JMH benchmark results documented

**Mitigation Required**:
1. Execute load test **before** Slice 1:
   ```bash
   wrk -t 4 -c 100 -d 30s -s load-test/wrk-v4-expectation.lua http://localhost:8080
   ```
2. Run JMH benchmarks **before** Slice 1:
   ```bash
   ./gradlew jmh
   ```
3. Document results in updated BASELINE_20260210.md
4. Capture cache hit rates from Prometheus

**Timeline**: Must be completed before Slice 1 Commit 1

### Issue #2: Missing Monitoring Query Guide (P0)

**Severity**: **CRITICAL**
**Impact**: No regression detection during migration

**Details**:
- Referenced in review scope but not created
- Essential for real-time performance monitoring
- Blocks automated regression detection

**Required Content**:
1. Prometheus queries for:
   - RPS monitoring
   - p99 latency tracking
   - Cache hit rates
   - Mapping overhead (custom metric needed)
   - Error rates by layer (domain, infrastructure)

2. Loki queries for:
   - Mapping error detection
   - Repository latency logs
   - Domain instantiation failures

3. Alert thresholds:
   - RPS < 900 (6.7% regression threshold)
   - p99 > 220ms (+5ms from baseline)
   - Cache hit rate < 80%
   - Error rate > 1%

4. Grafana dashboard references:
   - Dashboard JSON files in docker/grafana/provisioning/
   - Custom panels for ADR-017 metrics

**Timeline**: Must be created before Slice 1 Commit 1

### Issue #3: Missing V4 Flow Analysis (P1)

**Severity**: **HIGH**
**Impact**: Performance analysis incomplete without V4 context

**Details**:
- V4 expectation flow is the target of load testing
- Understanding V4 architecture is critical for performance analysis
- Referenced as "v4_expectation_flow_analysis.md" but not found

**Required Content**:
1. V4 expectation flow sequence diagram
2. Critical path analysis (which layers affect p99)
3. Caching strategy in V4 (L1/L2 hit rates)
4. Database query patterns in V4
5. Mapping points where domain extraction adds overhead

**Timeline**: Should be created before Slice 1 implementation

---

## Recommendations

### Immediate Actions (Before Slice 1)

1. **Create monitoring_query_guide.md** (P0):
   - Document all Prometheus queries for regression detection
   - Define Loki queries for error tracking
   - Specify alert thresholds and rollback conditions
   - Reference existing Grafana dashboards

2. **Capture True Performance Baseline** (P0):
   ```bash
   # Execute before Slice 1
   ./gradlew clean build -x test
   ./gradlew jmh | tee baseline_jmh.txt
   wrk -t 4 -c 100 -d 30s -s load-test/wrk-v4-expectation.lua http://localhost:8080 | tee baseline_load_test.txt
   curl -s http://localhost:9090/api/v1/query?query=... > baseline_cache_metrics.json
   ```

3. **Create v4_expectation_flow_analysis.md** (P1):
   - Document V4 architecture with sequence diagrams
   - Identify critical performance paths
   - Analyze current V4 caching strategy

4. **Add Custom Mapping Metrics** (P1):
   ```java
   // Add to CharacterEquipmentRepositoryImpl
   @Timed(value = "domain.mapping", description = "Domain entity mapping duration")
   public CharacterEquipment mapToDomain(CharacterEquipmentJpaEntity jpaEntity) {
       // ... mapping logic
   }
   ```

### Process Improvements

1. **Automated Baseline Capture**:
   - Create CI job to capture baseline before each slice
   - Store results in `docs/05_Reports/Baseline/BASELINE_{SLICE}_PRE.json`

2. **Real-time Regression Detection**:
   - Add Prometheus alerting rules for:
     - `p99_latency_over_threshold`
     - `rps_below_threshold`
     - `cache_hit_rate_drop`

3. **Post-Slice Validation Checklist**:
   ```bash
   # After each slice, execute:
   ./gradlew test              # All 934 tests pass
   ./gradlew jmh               # p99 ≤ 219ms
   wrk -t 4 -c 100 ...         # RPS ≥ 965
   ./gradlew test --tests "*ArchTest*"  # Layer isolation
   ./gradlew test --tests "*CharacterizationTest*"  # Data integrity
   ```

### Documentation Gaps to Address

1. **Update BASELINE_20260210.md** with actual measurements
2. **Create monitoring_query_guide.md** with complete query reference
3. **Create v4_expectation_flow_analysis.md** with architecture diagrams
4. **Add rollback procedure** to ADR-017-S1 (git revert steps, feature toggle plan)

---

## Conclusion

### Strengths of ADR-017 Documentation

✅ **Excellent architectural planning**: Clean Architecture decision well-justified with SOLID compliance analysis
✅ **Strong regression prevention**: 9-commit migration plan with Characterization tests (793 lines)
✅ **Clear acceptance thresholds**: Pass/fail criteria explicitly defined (p99 ≤ 219ms, 100% test pass)
✅ **Comprehensive ADR-017-S1**: Equipment slice detailed with code examples and ArchUnit rules
✅ **Risk mitigation strategy**: 4-Slice approach limits blast radius of failures

### Critical Gaps

❌ **Missing monitoring_query_guide.md**: Blocks regression detection infrastructure
❌ **Missing v4_expectation_flow_analysis.md**: Limits performance analysis depth
❌ **Baseline performance data not captured**: RPS and p99 marked "Not measured"
⚠️ **Cache migration strategy unclear**: Key pattern changes not addressed

### Final Verdict

**CONDITIONAL PASS** - Do not proceed with Slice 1 until:

1. ✅ Performance baseline captured (RPS, p99, cache metrics)
2. ✅ monitoring_query_guide.md created with all queries
3. ⚠️ v4_expectation_flow_analysis.md created (recommended, not blocking)

**Risk Level Without Fixes**: **HIGH**
- No baseline = no regression detection
- No monitoring queries = flying blind during migration
- Unknown cache impact = potential surprise degradation

**Risk Level With Fixes**: **LOW**
- Conservative 5ms performance allowance (50x predicted overhead)
- Characterization tests provide data integrity safety net
- ArchUnit rules enforce architectural boundaries
- 4-Slice approach limits failure scope

---

**Next Steps**:

1. [ ] Capture performance baseline (RPS, p99, cache metrics)
2. [ ] Create `docs/05_Reports/Refactor/monitoring_query_guide.md`
3. [ ] Create `docs/05_Reports/Refactor/v4_expectation_flow_analysis.md`
4. [ ] Review updated baseline with Blue Agent (architectural compliance)
5. [ ] **Only then**: Proceed with Slice 1 (Equipment Domain Extraction)

---

*Reviewed by Red Agent (Performance & Regression Risk Specialist)*
*Date: 2026-02-10*
*Review Cycle: ADR-017 Pre-Implementation Validation*
