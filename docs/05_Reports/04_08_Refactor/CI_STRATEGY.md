# CI/CD Pipeline Strategy

**Document Version:** 1.0
**Last Updated:** 2026-02-07
**Author:** Phase 1 Refactoring Team
**Related Issue:** #325 - CI/CD Pipeline Configuration

---

## 1. Overview

MapleExpectation employs a **two-lane CI/CD strategy** to balance fast feedback with comprehensive quality assurance:

- **Fast Lane (CI Pipeline):** PR gates with 3-5 minute feedback loop
- **Nightly Lane:** Full test suite including chaos and nightmare scenarios

### Design Principles

1. **Fail Fast:** Block merge on any quality gate failure
2. **Observability:** Upload reports even on failure
3. **Resource Efficiency:** Cancel in-progress runs for same PR
4. **Incremental Quality:** SpotBugs non-blocking initially, strict enforcement later

---

## 2. Fast Lane: CI Pipeline

### 2.1 Trigger Conditions

```yaml
on:
  push:
    branches: [ "develop" ]
  pull_request:
    branches: [ "master", "develop" ]
```

**When it runs:**
- Every push to `develop` branch
- Every PR to `master` or `develop`

### 2.2 Jobs

#### Job 1: Code Quality Gates (5 min timeout)

**Purpose:** Fast feedback on code quality before running tests

| Step | Command | Purpose | Blocking |
|------|---------|---------|----------|
| Spotless Check | `./gradlew spotlessCheck` | Enforce Google Java Format | **YES** |
| ArchUnit Tests | `./gradlew test --tests "*ArchitectureTest*"` | Enforce Clean Architecture | **YES** |
| SpotBugs Analysis | `./gradlew spotbugsMain` | Static analysis for bug patterns | NO (initially) |

**Spotless Fix:**
```bash
# Locally fix formatting issues
./gradlew spotlessApply
```

**ArchUnit Violations:**
- Issue: #325 - Architecture test suite
- Violations indicate technical debt
- Should be addressed incrementally

#### Job 2: Build & Test (10 min timeout)

**Purpose:** Execute unit tests with fast feedback

| Step | Command | Purpose | Blocking |
|------|---------|---------|----------|
| Run Tests | `./gradlew clean test -PfastTest` | Unit tests only (3-5 min) | **YES** |
| Upload Reports | Auto | Test results + flaky logs | N/A |
| Publish Results | Auto | PR annotations | N/A |
| Build JAR | `./gradlew build -x test` | Verify build succeeds | **YES** |

**Excluded Tests (fastTest profile):**
- Slow tests (> 2s)
- Sentinel tests
- Quarantined tests
- Nightmare scenarios
- Chaos engineering tests

#### Job 3: Security Scan (10 min timeout, non-blocking)

**Purpose:** Dependency vulnerability analysis

| Step | Purpose | Blocking |
|------|---------|----------|
| Dependency Export | Review runtime dependencies | NO |

### 2.3 Expected Build Times

| Job | Expected Time | Timeout |
|-----|---------------|----------|
| Code Quality | 2-3 min | 5 min |
| Build & Test | 5-7 min | 10 min |
| Security Scan | 1-2 min | 10 min |
| **Total** | **8-12 min** | **25 min** |

### 2.4 Concurrency Control

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```

**Behavior:**
- Same PR: Cancel previous run, start new one
- Different PRs: Run in parallel
- Branch protection: Requires all checks to pass

---

## 3. Nightly Lane: Full Test Suite

### 3.1 Trigger Conditions

```yaml
on:
  schedule:
    - cron: '0 15 * * *'  # Daily at KST 00:00 (UTC 15:00)
  workflow_dispatch:      # Manual trigger with options
```

**When it runs:**
- Daily at midnight KST
- Manual trigger from GitHub Actions UI
- Manual trigger supports skipping specific stages

### 3.2 Jobs (Sequential Execution)

#### Step 1: Unit Tests (10 min timeout)

```bash
./gradlew clean test -PfastTest
```

**Purpose:** Verify fast tests still pass
**Expected Time:** 3-5 min

#### Step 2: Integration Tests (15 min timeout)

```bash
./gradlew test -PintegrationTest
```

**Purpose:** Test DB/Redis integration with Testcontainers
**Expected Time:** 10-12 min

#### Step 3: Chaos Tests (20 min timeout)

```bash
./gradlew test -PchaosTest
```

**Purpose:** Failure injection testing (resilience validation)
**Expected Time:** 15-18 min

**Coverage:**
- Service degradation
- Dependency failure
- Network timeout
- Cache miss spikes

#### Step 4: Nightmare Tests (30 min timeout)

```bash
./gradlew test -PnightmareTest
```

**Purpose:** Extreme scenario testing (N01-N18)
**Expected Time:** 20-25 min

**Scenarios:**
- N01-N06: Core failure modes
- N07-N12: Performance degradation
- N13-N18: Catastrophic failures

### 3.3 Manual Trigger Options

```yaml
inputs:
  skip_unit: boolean (default: false)
  skip_integration: boolean (default: false)
  skip_chaos: boolean (default: false)
  skip_nightmare: boolean (default: false)
```

**Usage:**
- GitHub Actions UI → Nightly Full Test → "Run workflow"
- Check boxes to skip specific stages
- Useful for targeted re-runs

### 3.4 Expected Build Times

| Step | Expected Time | Timeout |
|------|---------------|----------|
| Unit Tests | 3-5 min | 10 min |
| Integration Tests | 10-12 min | 15 min |
| Chaos Tests | 15-18 min | 20 min |
| Nightmare Tests | 20-25 min | 30 min |
| **Total** | **48-60 min** | **75 min** |

---

## 4. Quality Gates Summary

### 4.1 Required Checks (PR Merge Blocking)

| Check | Tool | Purpose | Fix Command |
|-------|------|---------|-------------|
| Code Formatting | Spotless | Google Java Format | `./gradlew spotlessApply` |
| Architecture Rules | ArchUnit | Clean Architecture | Refactor violating code |
| Unit Tests | JUnit | Fast feedback | Fix failing tests |
| Build Success | Gradle | Compilation | Fix compilation errors |

### 4.2 Non-Blocking Checks (Informational)

| Check | Tool | Purpose | Future Status |
|-------|------|---------|---------------|
| Static Analysis | SpotBugs | Bug patterns | Blocking after Issue #325 |
| Security Scan | Gradle | Dependency review | Blocking after CVE policy |

### 4.3 Quality Metrics

**Current Baseline (Phase 1):**
- Spotless: 100% compliance (blocking)
- ArchUnit: 15+ rules (violations tracked)
- SpotBugs: Non-blocking (report only)
- Test Coverage: Not enforced (future work)

**Target State (Phase 3):**
- SpotBugs: Zero high-priority bugs (blocking)
- Test Coverage: 60% for service layer (enforced)
- ArchUnit: Zero violations (clean architecture)

---

## 5. Failure Notification Strategy

### 5.1 CI Failures (Fast Lane)

**Immediate Actions:**
1. **PR Block:** Merge blocked automatically
2. **Annotations:** Test results published to PR
3. **Artifact Upload:** Reports stored for 7 days
4. **Flaky Logs:** Stored for 30 days (Issue #210)

**Notification Channels:**
- GitHub PR Comments (automatic)
- GitHub Actions UI (run logs)
- Discord Webhook (future - Issue #143)

### 5.2 Nightly Failures

**Immediate Actions:**
1. **Artifact Upload:** Reports stored for 14 days
2. **Test Results:** Published to GitHub Actions
3. **Summary Job:** Aggregates all step results

**Notification Channels:**
- GitHub Issues (auto-created on failure - future)
- Discord Webhook (daily summary - future)
- Email (owner notification - future)

### 5.3 Flaky Test Handling (Issue #210)

**Detection:**
```bash
./gradlew test -PfastTest
# Generates: build/flaky/flaky-test-report.json
```

**Upload:**
- Artifact: `flaky-logs-{run_id}`
- Retention: 30 days
- Format: JSON + HTML summary

**Actions:**
- Review flaky logs weekly
- Fix or quarantine flaky tests
- Update retry configuration in `build.gradle`

---

## 6. Local Development Workflow

### 6.1 Before Committing

```bash
# 1. Format code (Spotless)
./gradlew spotlessApply

# 2. Run architecture tests
./gradlew test --tests "*ArchitectureTest*"

# 3. Run fast tests
./gradlew test -PfastTest

# 4. Optional: Run static analysis
./gradlew spotbugsMain
```

### 6.2 Before Pushing to PR

```bash
# Full CI pipeline simulation
./gradlew clean build spotlessCheck test -PfastTest
```

### 6.3 Running Nightly Tests Locally

```bash
# Step 1: Unit tests
./gradlew test -PfastTest

# Step 2: Integration tests
./gradlew test -PintegrationTest

# Step 3: Chaos tests
./gradlew test -PchaosTest

# Step 4: Nightmare tests (caution: slow)
./gradlew test -PnightmareTest
```

**Prerequisites:**
- Docker running (Testcontainers)
- MySQL and Redis containers will be auto-spawned
- Minimum 4GB RAM available

---

## 7. Gradle Tasks Reference

### 7.1 Code Quality

| Task | Purpose | Duration |
|------|---------|----------|
| `spotlessCheck` | Verify code formatting | 10s |
| `spotlessApply` | Auto-fix formatting | 10s |
| `spotbugsMain` | Static analysis (main code) | 30s |
| `spotbugsTest` | Static analysis (test code) | 20s |
| `test --tests "*ArchitectureTest*"` | Architecture validation | 15s |

### 7.2 Testing

| Task | Purpose | Duration |
|------|---------|----------|
| `test -PfastTest` | Unit tests only | 3-5 min |
| `test -PintegrationTest` | Integration tests | 10-12 min |
| `test -PchaosTest` | Chaos engineering | 15-18 min |
| `test -PnightmareTest` | Nightmare scenarios | 20-25 min |
| `test` | All tests | 45-60 min |

### 7.3 Build

| Task | Purpose | Duration |
|------|---------|----------|
| `build` | Full build with tests | 50-65 min |
| `build -x test` | Build without tests | 1-2 min |
| `clean` | Clean build artifacts | 5s |

---

## 8. Monitoring & Metrics

### 8.1 Key Metrics

**CI Pipeline (Fast Lane):**
- Success rate: Target > 95%
- Average duration: 8-12 min
- Flaky test rate: < 2% (Issue #210)

**Nightly Pipeline:**
- Success rate: Target > 90%
- Average duration: 48-60 min
- Chaos test pass rate: > 85%

### 8.2 Monitoring Tools

**GitHub Actions:**
- Workflow runs history
- Job timing trends
- Artifact storage

**Future Enhancements:**
- Grafana dashboards (Issue #143)
- Prometheus metrics integration
- Discord alerts for failures

---

## 9. Troubleshooting

### 9.1 Spotless Failures

**Symptom:** `spotlessCheck` fails

**Solution:**
```bash
# Auto-fix formatting
./gradlew spotlessApply
git add .
git commit -m "fix: Apply Spotless formatting"
```

### 9.2 ArchUnit Violations

**Symptom:** Architecture tests fail

**Solution:**
1. Review violation details in test output
2. Identify violating classes
3. Refactor to comply with architecture rules
4. Document technical debt if postponing fix

**Common Violations:**
- Domain layer depending on infrastructure
- Controller calling repository directly
- Cyclic dependencies between packages

### 9.3 SpotBugs Warnings

**Symptom:** `spotbugsMain` reports bugs

**Solution:**
1. Download SpotBugs report from artifacts
2. Review severity (High/Medium/Low)
3. Fix high-priority bugs first
4. Annotate false positives with `@SuppressFBWarnings`

### 9.4 Flaky Tests (Issue #210)

**Symptom:** Tests pass locally, fail in CI

**Solution:**
1. Download `flaky-logs-{run_id}` artifact
2. Review `flaky-test-report.json`
3. Check for race conditions or timing issues
4. Increase retry count or fix test isolation

**Retry Configuration:**
```gradle
test {
    retry {
        maxRetries = 1
        maxFailures = 10
        failOnPassedAfterRetry = false
    }
}
```

### 9.5 Testcontainers Failures

**Symptom:** Tests fail with Docker connection errors

**Solution:**
1. Verify Docker socket: `unix:///var/run/docker.sock`
2. Check Ryuk disabled: `TESTCONTAINERS_RYUK_DISABLED=true`
3. Ensure sufficient resources (4GB RAM minimum)
4. Review container logs in test output

---

## 10. Future Enhancements

### 10.1 Phase 2: Coverage Enforcement

```gradle
jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = 0.45  // 45% coverage baseline
            }
        }
    }
}
```

### 10.2 Phase 3: Performance Regression

```yaml
- name: Performance Benchmark
  run: ./gradlew jmh
  # Compare with baseline, fail on regression > 10%
```

### 10.3 Phase 4: Security Scanning

```yaml
- name: OWASP Dependency Check
  run: ./gradlew dependencyCheckAnalyze
  # Block on high-severity CVEs
```

### 10.4 Phase 5: Deployment Automation

```yaml
- name: Deploy to Staging
  if: github.ref == 'refs/heads/develop'
  run: ./deploy.sh staging
```

---

## 11. References

- **Issue #325:** ArchUnit Architecture Test Suite Implementation
- **Issue #210:** Flaky Test Detection and Handling
- **Issue #143:** Observability & Loki Integration
- **CLAUDE.md:** Section 10 - Mandatory Testing & Zero-Failure Policy
- **Testing Guide:** `docs/03_Technical_Guides/testing-guide.md`
- **Chaos Engineering:** `docs/02_Chaos_Engineering/00_Overview/TEST_STRATEGY.md`

---

## Appendix A: Workflow File Locations

```
.github/
└── workflows/
    ├── ci.yml           # Fast lane (PR gates)
    ├── nightly.yml      # Nightly full tests
    └── gradle.yml       # Gradle wrapper validation (existing)
```

---

## Appendix B: Quick Reference

**Fast Lane Commands:**
```bash
# Full CI simulation
./gradlew clean spotlessCheck test --tests "*ArchitectureTest*" test -PfastTest spotbugsMain

# Format and test
./gradlew spotlessApply test -PfastTest

# Architecture check only
./gradlew test --tests "*ArchitectureTest*"
```

**Nightly Commands:**
```bash
# Full suite
./gradlew clean test

# Specific stage
./gradlew test -PchaosTest
./gradlew test -PnightmareTest
```

---

**End of Document**
