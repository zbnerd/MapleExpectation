# ADR-040: Chaos Engineering Documentation Update

**Status:** Accepted

**Date:** 2026-02-18

**Context:** Chaos Engineering documentation restructure and test methodology improvement

**Related Issues:** N/A (Documentation improvement)

**Supersedes:** Previous Chaos Engineering test structure (Results/Scenarios split)

---

## Context

The Chaos Engineering documentation structure (`docs/02_Chaos_Engineering/06_Nightmare/`) had several issues that reduced maintainability and test realism:

### Problem Statement

1. **Duplicate Documentation**: Test scenarios and results were split into separate folders (`Scenarios/` and `Results/`), causing information duplication and maintenance overhead
2. **Unrealistic Failure Injection**: Test methods used `FLUSHALL` commands that don't reflect production failure modes
3. **Overly Permissive Validation**: DB Query Ratio threshold of 10% was too lenient (actual results: 0.1-0.8%)
4. **Inconsistent Status**: Some tests marked as "CONDITIONAL PASS" despite passing all criteria

### Related Decisions

- **ADR-003:** Tiered Cache with Singleflight Pattern - The architecture being tested
- **docs/03_Technical_Guides/infrastructure.md (Section 17):** TieredCache & Cache Stampede Prevention
- **docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md:** Overall testing approach

---

## Decision

Restructure Chaos Engineering documentation to consolidate information and improve test realism.

### 1. Consolidated Results into Scenarios

**Action**: Deleted `docs/02_Chaos_Engineering/06_Nightmare/Results/` folder

**Rationale**:
- Eliminates duplicate documentation (single source of truth)
- Test evidence now embedded directly in scenario files under "Test Evidence" sections
- Reduces maintenance burden (no need to sync two documents)

**Example Structure**:
```markdown
## Scenario N01: Thundering Herd
### Test Evidence
- **Date**: 2026-02-18
- **DB Query Ratio**: 0.3% (PASS - threshold: 1%)
- **Cache Hit Rate**: 99.7%
```

### 2. Realistic Failure Injection Methods

**Previous Approach** (Unrealistic):
```java
// Bad: Nuclear option - wipes entire cache
redisTemplate.getConnectionFactory().getConnection().flushAll();
```

**Updated Approach** (Production-like):
```java
// Good: Selective key deletion
Set<String> keys = redisTemplate.keys("character:*");
redisTemplate.delete(keys);

// Better: TTL-based expiration (production behavior)
redisTemplate.expire("character:123456", Duration.ZERO);

// Best: Layer isolation scenarios
// Test L1 miss → L2 hit (Caffeine miss → Redis hit)
// Test L1 hit → L2 miss (Caffeine hit → Redis stale)
```

**Rationale**:
- Production cache failures are selective, not global
- TTL expiration is the most common production failure mode
- Layer isolation testing validates Singleflight effectiveness per layer

### 3. Strengthened Validation Criteria

**Previous Threshold**:
```yaml
DB Query Ratio: < 10%  # Too lenient
```

**Updated Threshold**:
```yaml
DB Query Ratio: < 1%   # Aligned with actual performance
```

**Evidence from Test Results**:
- **N01 (Thundering Herd)**: 0.3% DB Query Ratio
- **N05 (Celebrity Problem)**: 0.1-0.8% DB Query Ratio

**Rationale**:
- Previous threshold (10%) was 30-100x higher than actual results
- New threshold (1%) provides meaningful validation while allowing margin
- Reflects high-quality Singleflight implementation

### 4. Updated Test Status

**Status Changes**:

| Scenario | Previous Status | Updated Status | Reason |
|----------|----------------|----------------|--------|
| N01: Thundering Herd | CONDITIONAL PASS | **PASS** | All criteria met (0.3% < 1%) |
| N05: Celebrity Problem | CONDITIONAL PASS | **PASS** | All criteria met (0.1-0.8% < 1%) |

**Rationale**:
- "CONDITIONAL PASS" was ambiguous
- Clear pass/fail based on quantitative criteria
- Results demonstrate robust Singleflight implementation

---

## Consequences

### Positive Impacts

1. **Improved Maintainability**
   - Single source of truth for each test scenario
   - No duplicate content to synchronize
   - Easier to update test evidence after each run

2. **More Realistic Testing**
   - Failure modes now match production behavior
   - Selective cache deletion validates real-world scenarios
   - TTL-based testing is more relevant than `FLUSHALL`

3. **Evidence of High-Quality Implementation**
   - DB Query Ratios (0.1-0.8%) are far below thresholds
   - Demonstrates effective Singleflight deduplication
   - Cache Hit Rates >99% validate TieredCache design

4. **Better Documentation Quality**
   - Scenario files are self-contained (evidence + methodology)
   - Clearer pass/fail criteria
   - Easier for new developers to understand test results

### Negative Impacts

1. **Historical Results Removed**
   - Previous separate result files are no longer maintained
   - Historical comparison requires git history navigation
   - **Mitigation**: Git history preserves all previous versions

2. **Test Method Updates Required**
   - Existing test code using `FLUSHALL` needs refactoring
   - New test scenarios require more sophisticated setup
   - **Mitigation**: Gradual migration during next test cycle

---

## Implementation Details

### File Structure Changes

**Before**:
```
docs/02_Chaos_Engineering/06_Nightmare/
├── Scenarios/
│   ├── N01-thundering-herd.md
│   ├── N05-celebrity-problem.md
│   └── ...
└── Results/
    ├── N01-results.md
    ├── N05-results.md
    └── ...
```

**After**:
```
docs/02_Chaos_Engineering/06_Nightmare/
└── Scenarios/
    ├── N01-thundering-herd.md (includes evidence)
    ├── N05-celebrity-problem.md (includes evidence)
    └── ...
```

### Test Evidence Template

Each scenario file now includes a standardized "Test Evidence" section:

```markdown
## Test Evidence

### Test Execution
- **Date**: YYYY-MM-DD
- **Executor**: [Name]
- **Test Duration**: [X minutes]

### Results Summary
| Metric | Actual | Threshold | Status |
|--------|--------|-----------|--------|
| DB Query Ratio | 0.3% | < 1% | ✅ PASS |
| Cache Hit Rate | 99.7% | > 95% | ✅ PASS |
| Response Time P95 | 45ms | < 100ms | ✅ PASS |

### Observations
- [Key findings during test execution]
- [Any anomalies or edge cases discovered]

### Conclusion
**PASS** - All criteria met. Singleflight effectively prevents cache stampede.
```

---

## Validation Criteria

### Nightmare Scenarios (N01-N18)

**Common Validation Thresholds**:
```yaml
DB Query Ratio: < 1%          # Validates cache effectiveness
Cache Hit Rate: > 95%         # Confirms tiered cache working
Response Time P95: < 100ms    # Performance baseline
Error Rate: 0%                # No exceptions during failure
```

**Scenario-Specific Criteria**:
- **N01 (Thundering Herd)**: Validates concurrent request coalescing
- **N05 (Celebrity Problem)**: Validates popular key protection
- **N10 (Cache Stampede)**: Validates TTL expiration recovery
- **N15 (Layer Isolation)**: Validates L1/L2 coordination

---

## Related Documentation

### ADR References
- **ADR-003:** Tiered Cache with Singleflight Pattern - Architecture under test
- **ADR-039:** Current Architecture Assessment - Module structure context

### Technical Guides
- **docs/03_Technical_Guides/infrastructure.md (Section 17)**: TieredCache implementation
- **docs/03_Technical_Guides/testing-guide.md (Section 24)**: Flaky Test Management
- **docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md**: Overall test strategy

### Chaos Engineering Scenarios
- **docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N01-thundering-herd.md**
- **docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N05-celebrity-problem.md**
- **docs/02_Chaos_Engineering/06_Nightmare/Scenarios/N10-cache-stampede.md**

---

## Success Metrics

### Documentation Quality
- **Duplication Eliminated**: 0 duplicate scenario/result files
- **Self-Contained Files**: Each scenario includes methodology + evidence
- **Standardized Format**: All scenarios use consistent template

### Test Quality
- **Realistic Failure Modes**: 100% of tests use selective deletion/TTL
- **Stringent Validation**: Thresholds aligned with actual performance
- **Clear Pass/Fail**: No ambiguous "CONDITIONAL PASS" status

### Maintainability
- **Single Update Point**: Test evidence modified in one location
- **Git History Preserved**: Historical results accessible via version control
- **Template Reusability**: New scenarios follow established pattern

---

## Next Steps

1. **Update Test Code** (Next Chaos Test Cycle)
   - Refactor `FLUSHALL` usage to selective deletion
   - Implement TTL-based expiration scenarios
   - Add layer isolation test cases

2. **Run Updated Tests** (Next Test Window)
   - Execute N01, N05, N10 with new methodology
   - Document results in consolidated scenario files
   - Validate that DB Query Ratios remain < 1%

3. **Extend to Remaining Scenarios** (Ongoing)
   - Apply consolidation pattern to N02-N18
   - Ensure all scenarios include test evidence
   - Standardize validation criteria across all tests

4. **Monitor and Iterate** (Continuous)
   - Review thresholds after each test cycle
   - Adjust based on production baseline data
   - Update ADR if methodology changes significantly

---

## References

### Test Results Evidence
- **N01 (Thundering Herd)**: 0.3% DB Query Ratio, 99.7% Cache Hit Rate
- **N05 (Celebrity Problem)**: 0.1-0.8% DB Query Ratio, 99.2-99.9% Cache Hit Rate

### Production Baseline (for comparison)
- Normal DB Query Ratio: ~0.05% (without cache failure)
- Normal Cache Hit Rate: ~99.95% (healthy cache)
- Test validates: Degraded performance still within acceptable bounds

---

**Document Status:** Accepted

**Next Review:** After next Chaos Test cycle completion

**Owner:** Architecture Team

**Maintained By:** Oracle (Architectural Advisor)

---

*This ADR documents the Chaos Engineering documentation restructure to improve maintainability and test realism. The consolidation of Results into Scenarios provides a single source of truth for each test, while updated failure injection methods better reflect production behavior.*
