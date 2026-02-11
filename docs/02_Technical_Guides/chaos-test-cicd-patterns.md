# CI/CD Integration Patterns for Chaos Tests

> **Reference Guide**: Chaos test execution patterns across different pipeline stages
> **Created**: 2026-02-11

---

## Overview

This document defines the CI/CD integration patterns for chaos and nightmare tests after migration to `module-chaos-test`.

---

## 1. Pipeline Architecture

### 1.1 Three-Tier Testing Strategy

```
┌─────────────────────────────────────────────────────────────┐
│                   PR Validation (Fast)                       │
│  Trigger: Pull Request to master/develop                     │
│  Tests: Unit + Integration (-PfastTest)                      │
│  Duration: 3-5 minutes                                        │
│  Gate: BLOCK on failure                                      │
│  Module: All modules EXCEPT module-chaos-test                │
├─────────────────────────────────────────────────────────────┤
│                   Merge to develop                           │
│  Trigger: Push to develop                                    │
│  Tests: Unit + Integration (-PfastTest)                      │
│  Duration: 3-5 minutes                                        │
│  Gate: BLOCK on failure                                      │
├─────────────────────────────────────────────────────────────┤
│                   Nightly Full Build                         │
│  Trigger: Schedule (KST 00:00) OR Manual                     │
│  Tests: Unit → Integration → Chaos → Nightmare               │
│  Duration: 30-45 minutes                                      │
│  Gate: Report failure, don't block deployment                │
│  Module: All modules INCLUDING module-chaos-test             │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Workflow Files

### 2.1 PR Validation (CI Pipeline)

**File**: `.github/workflows/ci.yml`
**Status**: NO CHANGES REQUIRED (already excludes chaos/nightmare via `-PfastTest`)

```yaml
- name: Run Tests
  run: |
    echo "🚀 Running fast tests (CI Gate)"
    ./gradlew clean test -PfastTest --no-daemon --stacktrace
```

**Behavior**:
- Excludes tags: `chaos`, `nightmare`, `sentinel`, `slow`, `quarantine`, `flaky`
- Executes only from `module-*/src/test` (NOT `module-chaos-test`)
- Runtime: 3-5 minutes
- Gate: BLOCKS PR merge on failure

---

### 2.2 Nightly Chaos Pipeline (NEW)

**File**: `.github/workflows/nightly-chaos.yml` (TO BE CREATED)

#### Workflow Triggers

```yaml
on:
  schedule:
    - cron: '0 15 * * *'  # Daily at KST 00:00 (UTC 15:00)
  workflow_dispatch:  # Manual trigger with options
    inputs:
      chaos_category:
        description: 'Chaos Category'
        required: false
        default: 'all'
        type: choice
        options:
          - all
          - network
          - resource
          - core
      skip_nightmare:
        description: 'Skip Nightmare Tests'
        type: boolean
        default: false
      timeout_minutes:
        description: 'Test Timeout (minutes)'
        required: false
        default: '45'
```

#### Job: Chaos Tests

```yaml
chaos-tests:
  name: 'Chaos Engineering Tests'
  runs-on: ubuntu-latest
  timeout-minutes: ${{ github.event.inputs.timeout_minutes || 30 }}

  steps:
    - name: Checkout Repository
      uses: actions/checkout@v4

    - name: Setup JDK 21
      uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: 'gradle'

    - name: Grant Execute Permission
      run: chmod +x gradlew

    - name: Determine Test Category
      id: category
      run: |
        if [ "${{ github.event.inputs.chaos_category }}" = "network" ]; then
          echo "task=chaosTestNetwork" >> $GITHUB_OUTPUT
        elif [ "${{ github.event.inputs.chaos_category }}" = "resource" ]; then
          echo "task=chaosTestResource" >> $GITHUB_OUTPUT
        elif [ "${{ github.event.inputs.chaos_category }}" = "core" ]; then
          echo "task=chaosTestCore" >> $GITHUB_OUTPUT
        else
          echo "task=chaosTest" >> $GITHUB_OUTPUT
        fi

    - name: Run Chaos Tests
      run: |
        echo "💥 Running Chaos Tests (category: ${{ github.event.inputs.chaos_category }})"
        ./gradlew :module-chaos-test:${{ steps.category.outputs.task }} \
          --no-daemon --stacktrace
      env:
        SPRING_PROFILES_ACTIVE: chaos
        DOCKER_HOST: unix:///var/run/docker.sock
        TESTCONTAINERS_RYUK_DISABLED: false
        CI: true

    - name: Upload Chaos Test Reports
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: chaos-test-reports-${{ github.run_id }}
        path: |
          module-chaos-test/build/reports/tests/
          module-chaos-test/build/test-results/
          module-chaos-test/build/flaky-chaos/
        retention-days: 30

    - name: Publish Chaos Test Results
      if: always()
      uses: dorny/test-reporter@v1
      with:
        name: Chaos Test Results
        path: 'module-chaos-test/build/test-results/chaosTest/*.xml'
        reporter: java-junit
        fail-on-error: false  # Don't block workflow on failure
```

#### Job: Nightmare Tests

```yaml
nightmare-tests:
  name: 'Nightmare Scenario Tests'
  runs-on: ubuntu-latest
  timeout-minutes: 45
  needs: chaos-tests
  if: ${{ github.event.inputs.skip_nightmare != 'true' }}

  steps:
    - name: Checkout Repository
      uses: actions/checkout@v4

    - name: Setup JDK 21
      uses: actions/setup-java@v4
      with:
        java-version: '21'
        cache: 'gradle'

    - name: Run Nightmare Tests
      run: |
        echo "😱 Running Nightmare Tests (15 extreme scenarios)"
        ./gradlew :module-chaos-test:nightmareTest --no-daemon --stacktrace
      env:
        SPRING_PROFILES_ACTIVE: nightmare
        DOCKER_HOST: unix:///var/run/docker.sock
        CI: true

    - name: Upload Nightmare Test Reports
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: nightmare-test-reports-${{ github.run_id }}
        path: |
          module-chaos-test/build/reports/tests/
          module-chaos-test/build/test-results/
        retention-days: 30

    - name: Publish Nightmare Test Results
      if: always()
      uses: dorny/test-reporter@v1
      with:
        name: Nightmare Test Results
        path: 'module-chaos-test/build/test-results/nightmareTest/*.xml'
        reporter: java-junit
        fail-on-error: false
```

#### Job: Summary

```yaml
summary:
  name: 'Chaos Test Summary'
  runs-on: ubuntu-latest
  needs: [chaos-tests, nightmare-tests]
  if: always()

  steps:
    - name: Generate Summary
      run: |
        echo "## 🧪 Chaos Test Summary" >> $GITHUB_STEP_SUMMARY
        echo "" >> $GITHUB_STEP_SUMMARY
        echo "| Test Suite | Status |" >> $GITHUB_STEP_SUMMARY
        echo "|------------|--------|" >> $GITHUB_STEP_SUMMARY
        echo "| Chaos Tests | ${{ needs.chaos-tests.result }} |" >> $GITHUB_STEP_SUMMARY
        echo "| Nightmare Tests | ${{ needs.nightmare-tests.result }} |" >> $GITHUB_STEP_SUMMARY

    - name: Download All Reports
      uses: actions/download-artifact@v4
      with:
        path: all-chaos-reports
      continue-on-error: true

    - name: Upload Combined Reports
      uses: actions/upload-artifact@v4
      with:
        name: chaos-combined-reports-${{ github.run_id }}
        path: all-chaos-reports/
        retention-days: 30
      continue-on-error: true
```

---

### 2.3 Legacy Nightly Pipeline (UPDATE REQUIRED)

**File**: `.github/workflows/nightly.yml`
**Action**: REMOVE chaos and nightmare test steps (already covered by new workflow)

#### Before Migration (Current State)

```yaml
jobs:
  chaos-tests:
    name: 'Step 3: Chaos Tests'
    steps:
      - name: Run Chaos Tests
        run: ./gradlew test -PchaosTest --no-daemon --stacktrace
        # Uses module-app/src/test-legacy (TO BE DEPRECATED)

  nightmare-tests:
    name: 'Step 4: Nightmare Tests'
    steps:
      - name: Run Nightmare Tests
        run: ./gradlew test -PnightmareTest --no-daemon --stacktrace
        # Uses module-app/src/test-legacy (TO BE DEPRECATED)
```

#### After Migration (Target State)

```yaml
jobs:
  # Step 1: Unit Tests (unchanged)
  unit-tests:
    name: 'Step 1: Unit Tests'
    steps:
      - name: Run Unit Tests
        run: ./gradlew clean test -PfastTest --no-daemon --stacktrace

  # Step 2: Integration Tests (unchanged)
  integration-tests:
    name: 'Step 2: Integration Tests'
    needs: unit-tests
    steps:
      - name: Run Integration Tests
        run: ./gradlew test -PintegrationTest --no-daemon --stacktrace

  # Step 3 & 4: REMOVED (moved to nightly-chaos.yml)

  # Step 3: Summary
  summary:
    name: 'Summary'
    needs: [unit-tests, integration-tests]
    # References new chaos workflow
    steps:
      - name: Test Summary
        run: |
          echo "📊 Nightly Test Summary"
          echo "Unit Tests: ${{ needs.unit-tests.result }}"
          echo "Integration Tests: ${{ needs.integration-tests.result }}"
          echo "Chaos/Nightmare Tests: See nightly-chaos workflow"
```

---

## 3. Execution Patterns

### 3.1 Pattern: PR Validation (Fast Feedback)

**Trigger**: Pull Request to `master` or `develop`
**Execution**: Immediate (on push)
**Scope**: Unit + Integration tests only
**Duration**: 3-5 minutes
**Gate**: Block merge on failure

```yaml
# .github/workflows/ci.yml
./gradlew clean test -PfastTest --no-daemon --stacktrace
```

**Tests Excluded**:
- `@Tag("chaos")` → All chaos tests
- `@Tag("nightmare")` → All nightmare tests
- `@Tag("slow")` → Slow integration tests
- `@Tag("sentinel")` → Canary tests
- `@Tag("quarantine")` → Flaky tests under investigation

---

### 3.2 Pattern: Nightly Full Build (Comprehensive)

**Trigger**: Schedule (KST 00:00) OR Manual `workflow_dispatch`
**Execution**: Sequential (Unit → Integration → Chaos → Nightmare)
**Scope**: All tests including chaos/nightmare
**Duration**: 30-45 minutes total
**Gate**: Report failure, don't block deployment

```yaml
# .github/workflows/nightly-chaos.yml
./gradlew :module-chaos-test:chaosTest --no-daemon --stacktrace
./gradlew :module-chaos-test:nightmareTest --no-daemon --stacktrace
```

**Test Categories**:
1. **Unit Tests** (`-PfastTest`): 3-5 min
2. **Integration Tests** (`-PintegrationTest`): 10-15 min
3. **Chaos Tests** (`chaosTest`): 10-15 min
4. **Nightmare Tests** (`nightmareTest`): 15-20 min

---

### 3.3 Pattern: Manual Chaos Validation

**Trigger**: Manual `workflow_dispatch` with category selection
**Execution**: On-demand
**Scope**: Selectable category
**Duration**: 5-30 min (depends on category)
**Gate**: Report failure only

**Input Options**:
```yaml
inputs:
  chaos_category:
    options:
      - all          # 22 tests (20-30 min)
      - network      # 2 tests (5-10 min)
      - resource     # 3 tests (10-15 min)
      - core         # 1 test (5 min)
  skip_nightmare:
    type: boolean
    default: false
```

**Execution Examples**:
```bash
# Network chaos only
./gradlew :module-chaos-test:chaosTestNetwork

# Resource chaos only
./gradlew :module-chaos-test:chaosTestResource

# All chaos, skip nightmare
./gradlew :module-chaos-test:chaosTest
```

---

## 4. Artifact Management

### 4.1 Report Artifacts

| Workflow | Artifact Name | Retention | Contents |
|----------|---------------|-----------|----------|
| **PR (CI)** | `test-reports` | 7 days | Unit + integration reports |
| **Nightly Chaos** | `chaos-test-reports-{run_id}` | 30 days | Chaos test HTML/XML + flaky logs |
| **Nightly Chaos** | `nightmare-test-reports-{run_id}` | 30 days | Nightmare test HTML/XML |
| **Nightly Chaos** | `chaos-combined-reports-{run_id}` | 30 days | All chaos reports merged |

### 4.2 Report Contents

```
chaos-test-reports-{run_id}/
├── reports/
│   └── tests/
│       └── chaosTest/
│           └── index.html          # Main HTML report
├── test-results/
│   └── chaosTest/
│       ├── TEST-*.xml              # JUnit XML results
│       └── test-results-*.xml      # Detailed results
└── flaky-chaos/
    └── flaky-test-results.json     # Flaky test analysis
```

---

## 5. Failure Handling

### 5.1 PR Validation

**Policy**: Fail-fast, block merge

```yaml
- name: Run Tests
  run: ./gradlew test -PfastTest --no-daemon --stacktrace
  continue-on-error: false  # CRITICAL: Block merge on failure
```

**Actions on Failure**:
- Workflow exits with non-zero status
- PR cannot be merged
- Developer must fix and re-push

---

### 5.2 Nightly Chaos Tests

**Policy**: Record failure, don't block deployment

```yaml
- name: Run Chaos Tests
  run: ./gradlew :module-chaos-test:chaosTest --no-daemon --stacktrace
  continue-on-error: true  # Continue to report generation
```

**Actions on Failure**:
- Upload test reports (including failure logs)
- Publish test results as GitHub check
- Create issue if failure is unexpected
- Don't block deployment (chaos tests are non-blocking validation)

---

## 6. Notifications

### 6.1 Discord Webhook Integration

**Chaos Test Failure Notification**:

```yaml
- name: Notify Discord on Failure
  if: failure()
  run: |
    curl -X POST ${{ secrets.CHAOS_DISCORD_WEBHOOK }} \
      -H "Content-Type: application/json" \
      -d '{
        "content": "💥 Chaos Test Failure",
        "embeds": [{
          "title": "Nightly Chaos Tests Failed",
          "url": "${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}",
          "color": 16711680,
          "fields": [
            {"name": "Workflow", "value": "nightly-chaos.yml"},
            {"name": "Run ID", "value": "${{ github.run_id }}"},
            {"name": "Actor", "value": "${{ github.actor }}"}
          ]
        }]
      }'
```

---

## 7. Optimization Patterns

### 7.1 Gradle Build Cache

```yaml
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v4
  with:
    cache-read-only: ${{ github.ref != 'refs/heads/develop' }}
    cache-write-only: false
    cache-encryption-key: ${{ secrets.GRADLE_CACHE_ENCRYPTION_KEY }}
```

### 7.2 Docker Layer Caching

```yaml
- name: Run Chaos Tests
  uses: docker://ghcr.io/gradle/gradle:8.10.0-jdk21
  with:
    args: chaosTest
  env:
    DOCKER_HOST: unix:///var/run/docker.sock
```

### 7.3 Parallel Job Execution (Future Enhancement)

```yaml
# Run chaos categories in parallel (requires isolation)
jobs:
  chaos-network:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew :module-chaos-test:chaosTestNetwork

  chaos-resource:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew :module-chaos-test:chaosTestResource

  chaos-core:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew :module-chaos-test:chaosTestCore

  # Aggregate results
  chaos-summary:
    needs: [chaos-network, chaos-resource, chaos-core]
    # Merge reports and publish
```

---

## 8. Monitoring & Observability

### 8.1 Test Metrics Collection

```yaml
- name: Publish Test Metrics
  run: |
    # Parse test results and send to Prometheus Pushgateway
    cat module-chaos-test/build/test-results/chaosTest/*.xml | \
      jq '.tests | map({name: .name, status: .status})' | \
      curl --data-binary @- http://pushgateway:9091/metrics/job/chaos-tests
```

### 8.2 Flaky Test Tracking

```yaml
- name: Upload Flaky Logs
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: flaky-logs-${{ github.run_id }}
    path: module-chaos-test/build/flaky-chaos/
    retention-days: 90  # Longer retention for analysis
```

---

**Document Status**: Ready for Implementation
**Next Step**: Create `.github/workflows/nightly-chaos.yml` and update `nightly.yml`
