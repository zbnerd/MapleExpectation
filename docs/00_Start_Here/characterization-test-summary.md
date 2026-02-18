# Characterization Test Summary - CharacterEquipment Entity

**Date:** 2026-02-10
**Test Suite:** `CharacterEquipmentCharacterizationTest`
**Status:** ✅ All Tests Passing (20/20)
**File:** `/home/maple/MapleExpectation/src/test/java/maple/expectation/characterization/CharacterEquipmentCharacterizationTest.java`

---

## Purpose

Characterization tests document and lock the **EXISTING BEHAVIOR** of the `CharacterEquipment` entity before any refactoring. These tests serve as a regression guard to ensure that behavior changes are intentional and documented.

---

## Test Coverage

### Test Suite 1: Expiration Logic (4 tests)
- ✅ Data older than TTL returns expired (true)
- ✅ **BUG DISCOVERED:** Data at exactly threshold returns expired (true) - boundary is inclusive
- ✅ Recent data returns fresh (false)
- ✅ Null updatedAt returns expired (true)

### Test Suite 2: Freshness Logic (2 tests)
- ✅ `isFresh()` is always opposite of `isExpired()`
- ✅ Zero TTL marks any past data as stale

### Test Suite 3: JSON Content Round-trip (3 tests)
- ✅ JSON survives GZIP compression round-trip
- ✅ Large JSON (10KB) compresses without data loss
- ✅ Unicode and special characters preserve encoding

### Test Suite 4: UpdatedAt Auto-Update (3 tests)
- ✅ Builder sets updatedAt to current time
- ✅ JPA save without field modification doesn't change timestamp
- ✅ `updateData()` advances timestamp

### Test Suite 5: Null/Empty Edge Cases (5 tests)
- ✅ Null jsonContent returns false from `hasData()`
- ✅ Empty string returns false from `hasData()`
- ✅ Whitespace-only string returns false from `hasData()`)
- ✅ Valid content returns true from `hasData()`
- ✅ Database handles null content according to JPA validation

### Test Suite 6: Repository Query Behavior (3 tests)
- ✅ Non-existent OCID returns `Optional.empty()`
- ✅ Time-based filtering via `findAll()` + `isExpired()`
- ✅ Existence check via `findById()` + `isExpired()`

---

## Critical Discovery: Boundary Condition Bug

### Test Case: `given_data_exactly_at_threshold_when_checkExpired_shouldReturnTrue()`

**Current Behavior (BUGGY):**
```java
// Equipment updated exactly 24 hours ago
// TTL = 24 hours
isExpired(Duration.ofHours(24)) → true  // BUG: Should be false!
```

**Root Cause:**
```java
// Current implementation (CharacterEquipment.java:57)
public boolean isExpired(Duration ttl) {
    return this.updatedAt == null
        || this.updatedAt.isBefore(LocalDateTime.now().minus(ttl));
}

// When updatedAt = now - 24h and ttl = 24h:
// now().minus(24h) = updatedAt
// updatedAt.isBefore(updatedAt) = false
// BUT: Due to millisecond precision in practice, this evaluates to true
```

**Expected Behavior:**
- Data at exactly `age == TTL` should be FRESH (exclusive boundary)
- Only `age > TTL` should be EXPIRED

**Impact:**
- Cached data expires 1 millisecond earlier than intended
- Affects 15-minute TTL equipment cache
- Low priority (timing window is tiny)

**Refactoring Decision Required:**
- [ ] Fix boundary to exclusive (use `isBefore()` + tolerance)
- [ ] Document current behavior as intentional
- [ ] Leave as-is (deemed acceptable)

---

## Flaky Test Prevention

All tests follow the guidelines from `docs/02_Technical_Guides/testing-guide.md`:

### ✅ No Thread.sleep()
- Uses `Duration` calculations for time-based tests
- Uses reflection to set timestamps precisely

### ✅ No Hardcoded Timestamps
- All timestamps use `LocalDateTime.now()`
- Time deltas calculated with `Duration` methods

### ✅ Test Isolation
- `@Transactional` ensures database rollback
- `@BeforeEach` cleans test data
- Each test is independent

### ✅ Deterministic Results
- No random values or UUIDs
- Fixed test constants (`TEST_OCID`, `TEST_JSON_CONTENT`)
- Reproducible on any environment

---

## Test Quality Metrics

| Metric | Value | Status |
|--------|-------|--------|
| Total Tests | 20 | ✅ |
| Passed | 20 | ✅ |
| Failed | 0 | ✅ |
| Coverage Areas | 6 suites | ✅ |
| Flaky Risk | None | ✅ |
| Documentation | Comprehensive JavaDoc | ✅ |

---

## Usage During Refactoring

### Before Making Changes
```bash
# Run characterization tests to establish baseline
./gradlew test --tests "maple.expectation.characterization.CharacterEquipmentCharacterizationTest"
# Expected: 20 tests completed, 0 failed
```

### After Making Changes
```bash
# Re-run to detect unintended behavior changes
./gradlew test --tests "maple.expectation.characterization.CharacterEquipmentCharacterizationTest"
# Any failure indicates UNINTENDED behavior change
```

### If Test Fails After Refactoring
1. **Review the failure** - Is this intentional behavior change?
2. **If intentional:**
   - Update test to document new behavior
   - Update JavaDoc to explain rationale
   - Commit with message: "docs: Update characterization test for [reason]"
3. **If unintentional:**
   - Fix the refactoring bug
   - Re-run tests to verify

---

## Documentation Standards

Each test follows this structure:

```java
/**
 * JavaDoc explaining WHAT behavior is being locked
 *
 * <p><b>Current Behavior:</b> Exact description of current behavior
 *
 * <p><b>Why This Matters:</b> Impact on refactoring decisions
 *
 * <p><b>Refactoring Note:</b> Guidance for future changes
 */
@Test
@DisplayName(
    "GIVEN: [precondition] "
    + "WHEN: [action] "
    + "THEN: [expected result]")
void test_name() {
    // Test implementation
}
```

---

## Related Documentation

- [Testing Guide Section 23-25](../03_Technical_Guides/testing-guide.md) - ExecutorService, Flaky Test Prevention
- [Flaky Test Management](../03_Technical_Guides/flaky-test-management.md) - Identification and Resolution
- [Issue #120](https://github.com/zbnerd/probabilistic-valuation-engine/issues/120) - Original CharacterEquipment Logic
- [Character Equipment Entity](../../src/main/java/maple/expectation/domain/v2/CharacterEquipment.java) - Source Code

---

## Next Steps

### Immediate
- [x] Create comprehensive characterization tests
- [x] Verify all tests pass against current implementation
- [x] Document discovered bugs

### Before Refactoring
- [ ] Decide on boundary condition bug fix
- [ ] Update test expectations if behavior changes
- [ ] Run baseline performance tests

### During Refactoring
- [ ] Run characterization tests after each change
- [ ] Update test documentation for intentional changes
- [ ] Maintain 100% test pass rate

### After Refactoring
- [ ] Verify all characterization tests still pass
- [ ] Remove or update bug documentation if fixed
- [ ] Update this summary with final results

---

## Test Execution Command

```bash
# Run all characterization tests
./gradlew test --tests "maple.expectation.characterization.CharacterEquipmentCharacterizationTest"

# Run specific test suite
./gradlew test --tests "*ExpirationLogicCharacterization"

# Run with HTML report
./gradlew test --tests "maple.expectation.characterization.*"
open build/reports/tests/test/index.html
```

---

*Last Updated: 2026-02-10*
*Test Suite Version: 1.0.0*
*Status: ✅ Ready for Refactoring*
