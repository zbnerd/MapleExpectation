# Phase 3 Quick Start Guide

> **For Developers**: How to use the Phase 3 baseline metrics system
> **Time to Read**: 5 minutes
> **Time to Execute**: 10 minutes

---

## What is Phase 3?

Phase 3 = **Domain Extraction** - Refactoring to extract domain logic from service layer into dedicated domain modules.

**Why do we need metrics?**
To prove the refactoring doesn't break performance or resilience. We need **quantitative evidence** (before vs after comparison).

---

## 3-Minute Summary

**Before you start refactoring:**
```bash
./scripts/capture-phase3-baseline.sh
```

**After you finish refactoring:**
```bash
./scripts/validate-phase3-no-regression.sh
```

**That's it!** The scripts handle everything else.

---

## Detailed Workflow

### Step 1: Pre-Phase 3 (Before Refactoring) ⏱️ 5 min

```bash
# 1.1 Capture baseline
./scripts/capture-phase3-baseline.sh

# 1.2 Review what was captured
cat do../05_Reports/04_08_Refactor/phase3-baseline/*/SUMMARY.txt

# 1.3 (Optional) Commit baseline
git add do../05_Reports/04_08_Refactor/phase3-baseline/
git commit -m "feat: capture Phase 3 baseline metrics"
```

**What gets captured?**
- Git state (commit, branch, files changed)
- Application health status
- Performance metrics (RPS, latency, cache hit rates)
- Resilience metrics (circuit breaker, thread pools, DB pools)
- Test execution time
- Build time
- Grafana dashboard definitions
- Load test results (if available)

### Step 2: Execute Phase 3 Refactoring 🏗️ Variable

**Follow the refactoring plan:**
- Read: `do../05_Reports/04_08_Refactor/REFACTOR_PLAN.md`
- Extract domain logic from service layer
- Create domain modules
- Update dependencies
- Run tests frequently: `./gradlew test -PfastTest`

### Step 3: Post-Phase 3 (After Refactoring) ⏱️ 5 min

```bash
# 3.1 Validate no regression
./scripts/validate-phase3-no-regression.sh

# 3.2 If PASS, create comparison report
# Edit: do../05_Reports/04_08_Refactor/PHASE3_BASELINE_METRICS.md (Section 7)
# Fill out the "Before/After Comparison Template"

# 3.3 Create PR with evidence
# Include:
# - Screenshot of validation script output
# - Link to baseline capture
# - Link to comparison report
```

---

## What Gets Validated?

| Metric | Before | After | Threshold |
|--------|--------|-------|-----------|
| **RPS** | 965 | Must be ≥917 | ±5% variance |
| **p99 Latency** | 214ms | Must be ≤235ms | ±10% variance |
| **L1 Hit Rate** | 85-90% | Must be ≥82% | -3% variance |
| **L2 Hit Rate** | 95-98% | Must be ≥93% | -2% variance |
| **Test Time** | 38s | Must be ≤45s | +20% variance |
| **Circuit Breaker** | CLOSED | Must stay CLOSED | No change |
| **Thread Rejections** | 0/s | Must stay 0/s | No change |
| **DB Pool Pending** | 0 | Must be <5 | No change |

**If any metric fails:**
- Script exits with error code 1
- Investigation required
- Fix regression OR justify with evidence
- Re-run validation

---

## Example Output

### ✅ Success Case

```bash
$ ./scripts/validate-phase3-no-regression.sh

=== Phase 3 Regression Validation ===
[1/7] Validating test execution...
✓ PASS: Test execution within threshold (39s)
[2/7] Running load test validation...
✓ PASS: RPS within acceptable range (958)
[3/7] Validating p99 latency...
✓ PASS: p99 latency within threshold (218ms)
[4/7] Validating cache hit rates...
✓ PASS: L1 hit rate within threshold (86.2%)
✓ PASS: L2 hit rate within threshold (96.8%)
[5/7] Validating database pool...
✓ PASS: DB pool pending within threshold (0)
[6/7] Validating circuit breaker state...
✓ PASS: All circuit breakers CLOSED
[7/7] Validating thread pool rejections...
✓ PASS: No thread pool rejections

✅ ALL CHECKS PASSED - NO REGRESSION DETECTED
```

### ❌ Failure Case

```bash
$ ./scripts/validate-phase3-no-regression.sh

=== Phase 3 Regression Validation ===
[1/7] Validating test execution...
✗ FAIL: Test execution exceeded 45s threshold (52s)

❌ REGRESSION DETECTED

Action Required:
1. Review failed metrics above
2. Investigate root cause
3. Fix regression OR justify variance with evidence
4. Re-run validation: ./scripts/validate-phase3-no-regression.sh
```

---

## Troubleshooting

### "Application not running"

**Problem**: Scripts skip health/metrics checks

**Solution**:
```bash
# Start application
./gradlew bootRun

# Wait 30 seconds for startup
sleep 30

# Re-run script
./scripts/validate-phase3-no-regression.sh
```

### "Prometheus not running"

**Problem**: Scripts skip Prometheus metrics

**Solution**:
```bash
# Start monitoring stack
docker-compose up -d prometheus grafana

# Wait 10 seconds
sleep 10

# Re-run script
./scripts/validate-phase3-no-regression.sh
```

### "wrk not installed"

**Problem**: Load test skipped

**Solution**:
```bash
# Install wrk
sudo apt-get install wrk  # Ubuntu/Debian
brew install wrk          # macOS

# Re-run script
./scripts/validate-phase3-no-regression.sh
```

### Regression detected - what now?

**Don't panic!** Options:

1. **Investigate**: Profile the code to find bottleneck
2. **Fix**: Optimize the problematic code
3. **Justify**: If variance is tiny (<1%), document as measurement noise
4. **Re-baseline**: If justified, update baseline with rationale

---

## Manual Validation (Alternative)

If scripts fail, validate manually:

```bash
# 1. Test execution
time ./gradlew clean test -PfastTest --rerun-tasks
# Should complete in <45s

# 2. Check metrics (if app running)
curl -s http://localhost:8080/actuator/metrics/http.server.requests | jq '.measurements'
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq '.measurements'

# 3. Check circuit breaker
curl -s http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

---

## Key Files Reference

| File | Purpose | When to Use |
|------|---------|-------------|
| `PHASE3_BASELINE_METRICS.md` | Complete baseline documentation | Deep dive into metrics |
| `PHASE3_PREPARATION_COMPLETE.md` | Executive summary | Quick status check |
| `scripts/capture-phase3-baseline.sh` | Baseline capture script | BEFORE refactoring |
| `scripts/validate-phase3-no-regression.sh` | Regression validation | AFTER refactoring |
| `scripts/README.md` | Scripts quick reference | Troubleshooting |

---

## CI/CD Integration (Optional)

Add to `.github/workflows/phase3-validation.yml`:

```yaml
name: Phase 3 Regression Check

on:
  pull_request:
    branches: [develop]

jobs:
  regression-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run validation
        run: ./scripts/validate-phase3-no-regression.sh
```

---

## FAQ

**Q: Do I need to capture baseline every time?**
A: No, only ONCE before starting Phase 3. Re-capture if major changes occur.

**Q: What if validation fails?**
A: Investigate, fix, or justify. Don't merge until resolved.

**Q: Can I adjust thresholds?**
A: Only with strong justification. Document variance in baseline report.

**Q: Do I need all services running?**
A: No, scripts skip missing services. But full validation requires app + Prometheus.

**Q: How long does validation take?**
A: ~2 minutes (mostly test execution).

---

## Support

**Questions?**
1. Check `scripts/README.md` for detailed troubleshooting
2. Review `PHASE3_BASELINE_METRICS.md` for metric definitions
3. Ask 5-Agent Council (Green/Blue/Red agents)

**Issue with scripts?**
- Check file permissions: `chmod +x scripts/*.sh`
- Check dependencies: `jq`, `python3`, `wrk`
- Check service status: `docker-compose ps`

---

## Summary Checklist

**Pre-Phase 3:**
- [ ] Run `./scripts/capture-phase3-baseline.sh`
- [ ] Review captured baseline
- [ ] Commit baseline (optional)

**During Phase 3:**
- [ ] Follow refactoring plan
- [ ] Run tests frequently
- [ ] Monitor metrics

**Post-Phase 3:**
- [ ] Run `./scripts/validate-phase3-no-regression.sh`
- [ ] If PASS, complete comparison report
- [ ] Obtain sign-off from 5-Agent Council
- [ ] Create PR with evidence

---

**That's it!** The system is designed to be simple and automated.

**Ready to start Phase 3?** Run `./scripts/capture-phase3-baseline.sh` and begin refactoring!

---

*Last Updated: 2026-02-07*
*Owner: 🟢 Green Performance Guru*
*Status: ✅ Ready for use*
