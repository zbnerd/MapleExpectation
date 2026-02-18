# Rollback Strategy for Multi-Module Refactoring

**Date:** 2026-02-16
**Status:** Active (Pre-Refactoring)
**Related Issues:** #282 (Multi-Module Refactoring), #283 (Scale-out Roadmap)
**Related ADRs:** ADR-039 (Current Architecture Assessment), ADR-014 (Multi-Module Cross-Cutting Concerns)

---

## Executive Summary

This document provides a comprehensive rollback strategy for the multi-module refactoring project. The strategy covers four rollback scenarios: mid-phase rollback, post-merge rollback, partial rollback, and emergency rollback. Each scenario includes specific procedures, verification steps, and communication plans.

**Key Principle:** Rollback is a safety net, not a failure. Quick rollback with analysis is better than prolonged debugging.

---

## Pre-Refactoring Checklist

### Baseline Establishment (MANDATORY Before Starting)

**Execute this checklist before beginning Phase 1:**

- [ ] **All tests passing** - Run full test suite and document baseline
  ```bash
  ./gradlew clean test > test-baseline.log
  ./gradlew chaos-test > chaos-baseline.log
  ```

- [ ] **CI/CD green** - Verify pipeline is healthy
  ```bash
  # Check last 5 builds all passed
  ```

- [ ] **Database backup** (if schema changes planned)
  ```bash
  # Document current schema version
  mysqldump -u root -p maple_expectation > backup-pre-refactor-$(date +%Y%m%d).sql
  ```

- [ ] **Current architecture documented** - ADR-039 exists and is accurate
  ```bash
  # Verify ADR-039 reflects current state
  ```

- [ ] **Baseline metrics recorded**
  ```bash
  # Build time
  time ./gradlew clean build > build-time-baseline.log

  # Module file counts
  find module-app/src/main/java -type f -name "*.java" | wc -l
  find module-infra/src/main/java -type f -name "*.java" | wc -l
  find module-core/src/main/java -type f -name "*.java" | wc -l
  find module-common/src/main/java -type f -name "*.java" | wc -l

  # Dependency graph
  ./gradlew dependencies > dependency-graph-baseline.txt
  ```

- [ ] **Git baseline tag created** (see Section 2.1)
- [ ] **Feature freeze on V5** - No new V5 features during refactoring
- [ ] **Rollback strategy reviewed** - Team acknowledges this document

---

## Git Strategy

### 2.1 Tagging Strategy

**Create tags before each phase begins:**

```bash
# Baseline tag (before any refactoring)
git tag -a pre-phase-0 -m "Baseline before multi-module refactoring
Module file counts:
- module-app: 342 files
- module-infra: 177 files
- module-core: 59 files
- module-common: 35 files
Related: #282, ADR-039"

# Before Phase 1: Low-risk cleanups
git tag -a pre-phase-1 -m "Before Phase 1: Delete empty packages, consolidate utilities"

# Before Phase 2: Infrastructure migration
git tag -a pre-phase-2a -m "Before Phase 2a: Move config/ package to module-infra"
git tag -a pre-phase-2b -m "Before Phase 2b: Move monitoring/ to module-infra"
git tag -a pre-phase-2c -m "Before Phase 2c: Move aop/scheduler/batch/alert to module-infra"

# Before Phase 3: Service layer restructuring
git tag -a pre-phase-3 -m "Before Phase 3: Service layer v2/v4/v5 analysis"

# Before Phase 4: Test validation
git tag -a pre-phase-4 -m "Before Phase 4: ArchUnit tests, performance validation"

# Push all tags to remote
git push origin --tags
```

### 2.2 Branch Protection Rules

**Repository Settings:**

```yaml
branch_protection:
  develop:
    required_status_checks:
      - "CI/CD Pipeline"
      - "ArchUnit Validation"
      - "Test Coverage"
    require_pull_request_reviews: true
    required_approving_review_count: 1
    enforce_admins: true

  master:
    required_status_checks:
      - "CI/CD Pipeline"
      - "Integration Tests"
      - "Chaos Tests"
    require_pull_request_reviews: true
    required_approving_review_count: 2
    enforce_admins: true
```

### 2.3 Branch Naming Convention

```bash
# Feature branches for refactoring phases
featu../05_Reports/04_08_Refactor/phase-1-cleanup
featu../05_Reports/04_08_Refactor/phase-2-infra-migration
featu../05_Reports/04_08_Refactor/phase-3-service-split

# Rollback branches (if needed)
hotfix/rollback-phase-2-monitoring
hotfix/rollback-phase-3-service-split
```

---

## Rollback Procedures

### 3.1 Scenario 1: Mid-Phase Rollback

**When to Use:**
- Compilation fails during package move
- Test failures after individual package move
- Spring Bean initialization errors
- Circular dependency detected

**Trigger Examples:**
```bash
# Compilation failure
./gradlew build
# BUILD FAILED: 157 compilation errors

# Test failure
./gradlew test
# 23 tests failed in GameCharacterServiceTest

# Bean resolution error
./gradlew bootRun
# Error: Field cacheStrategy in SomeService required a bean of type 'CacheStrategy'
```

**Rollback Steps:**

1. **Stop work immediately**
   ```bash
   # Commit current state (even if broken) for analysis
   git add -A
   git commit -m "WIP: Failed mid-phase - needs rollback"
   ```

2. **Identify what broke**
   ```bash
   # Check recent moves
   git diff --name-status HEAD~5 HEAD

   # Check test failures
   ./gradlew test --rerun-tasks | grep -A 5 "FAILED"

   # Check Spring context
   ./gradlew bootRun --debug 2>&1 | grep -i "error"
   ```

3. **Decision: Fix or Rollback?**

   **Fix forward if:**
   - Only 1-2 tests failing
   - Simple import path issue
   - Can be resolved in < 30 minutes

   **Rollback if:**
   - Multiple package moves broken
   - Circular dependency detected
   - Spring context fails to load
   - Cannot identify root cause

4. **Execute rollback**
   ```bash
   # Reset to last good tag
   git reset --hard pre-phase-N
   git push origin featu../05_Reports/04_08_Refactor/phase-N --force

   # Verify clean state
   ./gradlew clean build
   ./gradlew test
   ```

5. **Document what went wrong**
   ```markdown
   ## Rollback Report: Phase N - Package Move

   **Date:** 2026-02-XX
   **Phase:** Phase 2a (config/ move)
   **Trigger:** 157 compilation errors after move

   **Root Cause:**
   - @Configuration classes in config/ had implicit dependencies on module-app services
   - Moving to module-infra broke Spring Bean resolution order

   **Lessons Learned:**
   - Need to analyze @Configuration dependencies before move
   - Should move config classes in smaller batches
   - Spring Boot AutoConfiguration order matters

   **Next Steps:**
   - Create dependency graph of @Configuration classes
   - Retry with smaller batch (5 classes at a time)
   ```

6. **Communicate with team**
   - Post rollback summary in #development Slack
   - Update project board with rollback note
   - Schedule retrospective if rollback > 2 times

**Verification After Rollback:**
```bash
# Ensure system is back to baseline
./gradlew clean build
./gradlew test
git diff HEAD pre-phase-N
# Expected: No differences

# Run chaos tests to ensure stability
./gradlew chaos-test
```

---

### 3.2 Scenario 2: Post-Merge Rollback

**When to Use:**
- Issue found after PR merged to develop
- Production-like environment tests fail
- Performance regression detected
- Integration test failures in staging

**Trigger Examples:**
```bash
# Staging environment test failed
./gradlew integrationTest -Penv=staging
# Database connection pool exhaustion

# Performance regression
wrk -t12 -c400 -d30s http://staging.api.example.com/api/characters/123
# Latency increased from 50ms to 350ms (P99)
```

**Rollback Steps:**

1. **Create revert PR immediately**
   ```bash
   # Create revert branch
   git checkout -b revert/phase-N-develop

   # Revert the merge commit
   git revert -m 1 <merge-commit-hash>

   # Push revert PR
   git push origin revert/phase-N-develop
   ```

2. **Hotfix if needed**
   ```bash
   # If revert causes conflicts, create hotfix branch
   git checkout -b hotfix/rollback-phase-N develop

   # Manually revert changes
   git checkout pre-phase-N -- module-app/src/main/java/config/
   git checkout pre-phase-N -- module-infra/src/main/java/

   # Commit and PR
   git commit -m "Hotfix: Manual rollback of Phase N due to staging failures"
   git push origin hotfix/rollback-phase-N
   ```

3. **Re-run full test suite**
   ```bash
   ./gradlew clean test
   ./gradlew chaos-test
   ./gradlew integrationTest -Penv=staging
   ```

4. **Deploy rollback to staging**
   ```bash
   # Deploy via CI/CD pipeline
   # Verify staging is healthy
   curl https://staging.api.example.com/actuator/health
   ```

5. **Post-mortem analysis**
   - Why didn't tests catch this?
   - What coverage gaps exist?
   - Update test strategy (Section 6.2 of ADR-039)

6. **Update rollback lessons learned**
   ```markdown
   ## Post-Merge Rollback: Phase 2 - Monitoring Move

   **Date:** 2026-02-XX
   **Phase:** Phase 2b (monitor/ move to module-infra)
   **Trigger:** Staging environment - P99 latency increased 7x

   **Root Cause:**
   - Monitoring package had hidden dependency on module-app controllers
   - AOP aspects in monitoring/ broke without controller context
   - Integration tests didn't cover full monitoring path

   **Impact:**
   - Rollback required 4 hours
   - Staging unavailable for 2 hours
   - Team context switch cost

   **Prevention:**
   - Add integration test for monitoring before move
   - Test in staging before merging to develop
   - Create dependency graph of AOP aspects

   **Next Steps:**
   - Fix monitoring dependencies
   - Retry Phase 2b with additional tests
   ```

**Verification After Rollback:**
```bash
# Staging health check
curl https://staging.api.example.com/actuator/health
# Expected: {"status":"UP"}

# Performance baseline
wrk -t12 -c400 -d30s http://staging.api.example.com/api/characters/123
# Expected: P99 < 100ms

# Integration tests
./gradlew integrationTest -Penv=staging
# Expected: All tests pass
```

---

### 3.3 Scenario 3: Partial Rollback

**When to Use:**
- Phase 1 & 2 complete and working
- Phase 3 fails (service layer split)
- Need to keep working phases, rollback only failed phase

**Trigger Examples:**
```bash
# Phase 3 (service split) failed
./gradlew test --tests "*ServiceTest"
# 45 tests failed after service module split

# But Phase 1 & 2 are working
./gradlew test --tests "*RepositoryTest"
# All tests pass
```

**Rollback Steps:**

1. **Assess what's working**
   ```bash
   # Verify Phase 1 & 2 are stable
   git checkout pre-phase-3
   ./gradlew test
   # Result: All tests pass

   # Verify Phase 3 is broken
   git checkout featu../05_Reports/04_08_Refactor/phase-3-service-split
   ./gradlew test
   # Result: 45 tests fail
   ```

2. **Decision: Keep Phase 1 & 2, Rollback Phase 3 only**
   ```bash
   # Reset to pre-phase-3
   git checkout develop
   git merge featu../05_Reports/04_08_Refactor/phase-2-infra-migration  # Keep Phase 2
   git push origin develop
   ```

3. **Re-strategize Phase 3 approach**
   ```markdown
   ## Phase 3 Restructure Plan

   **Previous Approach (Failed):**
   - Split all 146 service files at once
   - Create module-app-service for all services

   **New Approach (Incremental):**
   - Week 1: Analyze v2 services only (97 files)
   - Week 2: Split v2 into calculator/cube/starforce submodules
   - Week 3: Test v2 split thoroughly
   - Week 4: Repeat for v4 (10 files)
   - Week 5: Analyze v5 (8 files) - defer if unstable

   **Success Criteria:**
   - Zero test failures
   - No circular dependencies
   - Performance baseline maintained
   ```

4. **Update project board**
   - Close Phase 3 (failed attempt)
   - Create Phase 3.1 (v2 only)
   - Link lessons learned from failure

5. **Communicate with team**
   - Email: "Phase 3 rollback completed, Phase 1 & 2 stable"
   - Slack: "Partial rollback successful, re-strategizing Phase 3"
   - Update ADR-039 with new Phase 3 plan

**Verification After Partial Rollback:**
```bash
# Verify Phase 1 & 2 still work
./gradlew clean build
./gradlew test

# Verify Phase 3 is reverted
git diff develop pre-phase-3
# Expected: No differences in service/ package

# Verify module boundaries
./gradlew test --tests "*ArchUnit*"
# Expected: All ArchUnit tests pass
```

---

### 3.4 Scenario 4: Emergency Rollback

**When to Use:**
- Production issue detected
- Critical bug affecting users
- Data corruption risk
- Security vulnerability

**Trigger Examples:**
```bash
# Production health check failing
curl https://api.example.com/actuator/health
# {"status":"DOWN"}

# Error rate spike
# Datadog/Grafana: Error rate 15% (baseline < 0.1%)

# Database connection exhaustion
# MySQL: Too many connections error
```

**Rollback Steps:**

1. **IMMEDIATE rollback to last stable tag**
   ```bash
   # This is NOT a git operation - use deployment rollback
   # Kubernetes example:
   kubectl rollout undo deployment/maple-expectation

   # Docker Compose example:
   docker-compose down
   git checkout pre-phase-0  # Last known good
   docker-compose up -d

   # Traditional deployment example:
   # Deploy previous artifact from artifact repository
   ./deploy.sh --artifact mapl-expectation-1.2.3.jar
   ```

2. **Verify production health**
   ```bash
   # Health check
   curl https://api.example.com/actuator/health
   # Expected: {"status":"UP"}

   # Smoke tests
   curl https://api.example.com/api/characters/123
   # Expected: 200 OK

   # Monitor error rate
   # Wait 5 minutes, check error rate is back to baseline
   ```

3. **Post-mortem analysis** (AFTER production is stable)
   ```markdown
   ## Emergency Rollback Post-Mortem

   **Date:** 2026-02-XX
   **Severity:** P0 - Production Outage
   **Downtime:** 23 minutes
   **Users Affected:** ~1,500

   **Timeline:**
   - 14:32 - Deployment complete
   - 14:35 - Error rate spiked to 15%
   - 14:38 - On-call engineer paged
   - 14:40 - Emergency rollback initiated
   - 14:55 - Production back to normal

   **Root Cause:**
   - Phase 2 (config/ move) broke @Configuration order
   - Redisson Redis bean failed to initialize
   - Fallback logic didn't trigger (bug in CircuitBreaker)

   **Immediate Fixes:**
   - Rollback to pre-phase-0
   - Hotfix: Redisson config order in module-app
   - Add health check for Redisson before exposing traffic

   **Long-term Prevention:**
   - Add smoke tests for all infrastructure beans
   - Test in staging for 24 hours before production deploy
   - Gradual rollout (canary deployment) for refactoring changes
   ```

4. **Fix forward vs rollback decision**

   **Fix forward if:**
   - Issue is well-understood
   - Fix can be implemented in < 1 hour
   - Rollback risk is similar to fix risk

   **Rollback if:**
   - Root cause unclear
   - Fix estimate > 2 hours
   - User impact is severe

5. **Communication plan**
   ```markdown
   ## User Communication

   **Status Page:** "Investigating connectivity issues"
   **Twitter:** "We're experiencing issues with character lookups. Team is investigating."

   ## Internal Communication

   **Slack #incidents:**
   - "@oncall Production incident declared"
   - "Rolling back deployment"
   - "Production restored"

   **Email to stakeholders:**
   - "Production incident summary - 23 minutes downtime"
   - "Post-mortem will be shared in 24 hours"
   ```

**Verification After Emergency Rollback:**
```bash
# Production health
curl https://api.example.com/actuator/health
# Expected: {"status":"UP"}

# Error rate (Grafana/Datadog)
# Expected: Back to baseline < 0.1%

# Smoke tests
./gradlew test --tests "*SmokeTest*"
# Expected: All tests pass

# Chaos tests (run in staging)
./gradlew chaos-test -Penv=staging
# Expected: All nightmare scenarios pass
```

---

## Rollback Verification

### 4.1 General Verification Steps

**After ANY rollback, execute:**

```bash
# 1. Clean build
./gradlew clean build

# 2. Full test suite
./gradlew test

# 3. Chaos tests (critical for refactoring)
./gradlew chaos-test

# 4. ArchUnit validation
./gradlew test --tests "*ArchUnit*"

# 5. Verify no unexpected changes
git diff HEAD pre-phase-N
# Expected: No differences (or only intended changes)

# 6. Check module dependencies
./gradlew dependencies
# Expected: No circular dependencies

# 7. Spring context load test
./gradlew bootRun --args='--spring.profiles.active=test'
# Expected: Application starts successfully
```

### 4.2 Phase-Specific Verification

| Phase | Additional Verification |
|-------|------------------------|
| **Phase 1** | Verify empty packages deleted, util/ consolidated |
| **Phase 2a** | Verify all @Configuration beans load in module-infra |
| **Phase 2b** | Verify monitoring AOP aspects work from module-infra |
| **Phase 2c** | Verify schedulers/batches run correctly |
| **Phase 3** | Verify service imports resolve correctly |
| **Phase 4** | Verify ArchUnit tests pass, performance baseline |

---

## Communication Plan

### 5.1 Scenario 1: Mid-Phase Rollback

**Who to Notify:**
- Team lead immediately
- Development team (Slack #development)
- (Optional) Architecture team if complex issue

**Message Template:**
```
🔄 Rollback: Phase N - [Brief Reason]

Status: Rolling back to pre-phase-N
Impact: Development blocked, no production impact
ETA: 30 minutes to restore baseline
Root Cause: [1-sentence summary]
Next Steps: [Plan to fix and retry]
```

### 5.2 Scenario 2: Post-Merge Rollback

**Who to Notify:**
- Team lead + Engineering manager
- Development team (Slack #development)
- QA team (Slack #qa)
- (Optional) Stakeholders if staging is customer-facing

**Message Template:**
```
🚨 Rollback: Phase N - Staging Failure Detected

Status: Reverting PR #123 from develop
Impact: Staging environment unavailable, production safe
ETA: 2 hours to restore staging
Root Cause: [Detailed technical summary]
Post-Mortem: Scheduled for tomorrow 10 AM
```

### 5.3 Scenario 3: Partial Rollback

**Who to Notify:**
- Team lead
- Development team (Slack #development)
- Architecture team (for re-strategy discussion)

**Message Template:**
```
📋 Partial Rollback: Phase 3 Failed, Phase 1 & 2 Stable

Status: Keeping Phase 1 & 2, rolling back Phase 3 only
Impact: Service split delayed, infrastructure migration intact
Next Steps: Re-strategizing Phase 3 with incremental approach
Retrospective: Scheduled for Friday
```

### 5.4 Scenario 4: Emergency Rollback

**Who to Notify:**
- **ALL:** On-call engineer → Team lead → Engineering manager → CTO
- Development team (Slack #incidents)
- Customer support (for user inquiries)
- (If severe) Company-wide announcement

**Message Template:**
```
🚨 EMERGENCY ROLLBACK - Production Outage

Status: Rolling back to pre-phase-0
Impact: Production down, users affected
ETA: 30 minutes to restore service
Incident Command: [On-call engineer name]
Communication Channel: Slack #incidents
Status Page: [Update live status page]
```

---

## Lessons Learned Template

**Document each rollback for continuous improvement:**

```markdown
## Rollback Post-Mortem: Phase N - [Name]

**Metadata:**
- Date: 2026-02-XX
- Phase: Phase N - [Description]
- Scenario: [Mid-phase / Post-merge / Partial / Emergency]
- Duration: [Minutes/Hours]
- People Involved: [Names]

**Trigger:**
- What failed?
- How was it detected?
- Who reported it?

**Root Cause:**
- [5 Whys analysis]
- What assumptions were wrong?
- What tests missed this?

**Impact:**
- Development blocked? [Yes/No, duration]
- Production affected? [Yes/No, user count]
- Team morale impact? [Low/Medium/High]
- Technical debt incurred? [Description]

**Timeline:**
| Time | Event | Owner |
|------|-------|-------|
| 14:32 | Deployment complete | DevOps |
| 14:35 | Error rate spiked | Monitoring |
| 14:38 | On-call paged | On-call |
| 14:40 | Rollback initiated | On-call |
| 14:55 | Production restored | On-call |

**What Went Well:**
- Quick detection
- Smooth rollback process
- Good communication

**What Could Be Improved:**
- Test coverage gaps
- Monitoring blind spots
- Rollback process inefficiencies

**Action Items:**
- [ ] Add integration test for [specific scenario]
- [ ] Update monitoring to alert on [metric]
- [ ] Refactor Phase N approach to [new strategy]
- [ ] Update this rollback strategy document

**Prevention:**
- What will we do differently next time?
- How do we prevent this specific failure?

**Next Steps:**
- Retry Phase N with new approach on [date]
- Schedule retrospective for [date]
- Update ADR-039 with lessons learned

---
**Document Owner:** [Name]
**Review Date:** [1 week after rollback]
```

---

## Rollback Decision Tree

```
                    Issue Detected
                          |
          +---------------+---------------+
          |                               |
    Can you fix it?              Don't understand root cause?
          |                               |
    Fix forward < 30min?            Yes → EMERGENCY ROLLBACK
          |                           (Scenario 4)
    Yes → Fix and continue
          |
    No → Rollback to last good state
          |
          +-------+-------+-------+
          |       |       |       |
    Mid-   Post-   Partial  Emergency
    Phase  Merge   (Phases  Production
          |         1&2 OK,  Down
          |         Ph3
          |         Fail)
          |         |
    Scenario 1  Scenario 3
```

**Key Decision Points:**

1. **Fix forward vs Rollback?**
   - Fix < 30min → Fix forward
   - Fix > 30min → Rollback

2. **Full vs Partial Rollback?**
   - All phases broken → Full rollback
   - Some phases working → Partial rollback

3. **Git Revert vs Hard Reset?**
   - Post-merge → Git revert (create revert PR)
   - Mid-phase → Hard reset to tag
   - Emergency → Deployment rollback (not git)

---

## Prevention Strategies

### 6.1 Pre-Refactoring Prevention

**Do this BEFORE starting refactoring:**

```bash
# 1. Enable all ArchUnit tests
./gradlew test --tests "*ArchUnit*"

# 2. Run full chaos test suite
./gradlew chaos-test

# 3. Record performance baseline
wrk -t12 -c400 -d30s http://localhost:8080/api/characters/123

# 4. Create dependency graph
./gradlew dependencies > pre-refactor-deps.txt

# 5. Freeze V5 development
# Label all V5 PRs with "on-hold-until-refactor-complete"
```

### 6.2 During Refactoring Prevention

**Follow these rules DURING refactoring:**

| Rule | Rationale |
|------|-----------|
| **One package at a time** | Minimize blast radius |
| **Run full test suite after each move** | Catch issues early |
| **Test in staging before develop** | Detect environment-specific issues |
| **Create rollback tag before each phase** | Enable quick rollback |
| **Document dependencies before move** | Avoid breaking implicit dependencies |

### 6.3 Post-Merge Prevention

**Do this AFTER merging to develop:**

```bash
# 1. Monitor staging for 24 hours
# Watch error rate, latency, database connections

# 2. Run chaos tests in staging
./gradlew chaos-test -Penv=staging

# 3. Performance regression test
wrk -t12 -c400 -d30s http://staging.api.example.com/api/characters/123
# Compare with baseline from Section 6.1

# 4. Smoke test all critical paths
./gradlew integrationTest -Penv=staging

# 5. Gradual rollout to production (canary deployment)
# Deploy to 10% of traffic → Monitor → 50% → Monitor → 100%
```

---

## Rollback Automation Scripts

### 7.1 Automated Rollback Verification

**Save as `scripts/verify-rollback.sh`:**

```bash
#!/bin/bash
# Verify system is healthy after rollback

set -e

echo "🔍 Verifying rollback to $1..."

# 1. Check tag exists
if ! git rev-parse "$1" >/dev/null 2>&1; then
    echo "❌ Tag $1 does not exist"
    exit 1
fi

# 2. Reset to tag
git reset --hard "$1"

# 3. Clean build
echo "📦 Running clean build..."
./gradlew clean build

# 4. Run tests
echo "🧪 Running test suite..."
./gradlew test

# 5. Check for unexpected changes
echo "🔎 Checking for unexpected changes..."
if [ -n "$(git diff HEAD $1)" ]; then
    echo "⚠️  Warning: Uncommitted changes detected"
else
    echo "✅ No unexpected changes"
fi

# 6. Verify Spring context
echo "🟢 Verifying Spring context..."
timeout 30s ./gradlew bootRun --args='--spring.profiles.active=test' || {
    echo "❌ Spring context failed to load"
    exit 1
}

echo "✅ Rollback verification complete"
```

**Usage:**
```bash
chmod +x scripts/verify-rollback.sh
./scripts/verify-rollback.sh pre-phase-2
```

### 7.2 Automated Health Check

**Save as `scripts/health-check.sh`:**

```bash
#!/bin/bash
# Check application health after rollback

set -e

BASE_URL="${1:-http://localhost:8080}"

echo "🏥 Health check for $BASE_URL..."

# 1. Actuator health
echo "1️⃣ Checking actuator health..."
HEALTH=$(curl -s "$BASE_URL/actuator/health")
echo "$HEALTH" | grep -q '"status":"UP"' || {
    echo "❌ Health check failed: $HEALTH"
    exit 1
}

# 2. Smoke test API endpoint
echo "2️⃣ Testing character API..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/characters/123")
if [ "$HTTP_CODE" -ne 200 ]; then
    echo "❌ API test failed: HTTP $HTTP_CODE"
    exit 1
fi

# 3. Check error rate (requires Prometheus/Grafana)
echo "3️⃣ Checking error rate..."
# Placeholder: Add Prometheus query here
# curl -s 'http://prometheus:9090/api/v1/query?query=rate(errors[5m])'

echo "✅ Health check passed"
```

**Usage:**
```bash
chmod +x scripts/health-check.sh
./scripts/health-check.sh http://staging.api.example.com
```

---

## Related Documents

### ADRs
- **ADR-039:** Current Architecture Assessment (baseline for rollback comparison)
- **ADR-014:** Multi-Module Cross-Cutting Concerns (original design)
- **ADR-035:** Multi-Module Migration Completion (historical context)

### Analysis Reports
- **Multi-Module-Refactoring-Analysis.md:** Detailed refactoring plan (this document supports)
- **scale-out-blockers-analysis.md:** Stateful component risks
- **high-traffic-performance-analysis.md:** Performance baseline

### Technical Guides
- **infrastructure.md:** Module structure and dependencies
- **testing-guide.md:** Test strategy for refactoring validation
- **flaky-test-management.md:** Avoiding false positives during rollback

---

## Appendices

### Appendix A: Rollback Checklist Summary

**Quick reference for each scenario:**

| Scenario | Trigger | Action | Verification |
|----------|---------|--------|--------------|
| **Mid-Phase** | Compilation/test failure | `git reset --hard pre-phase-N` | `./gradlew clean build && ./gradlew test` |
| **Post-Merge** | Staging failure | Create revert PR | Deploy to staging, smoke tests |
| **Partial** | Phase 3 fails, 1&2 OK | Revert Phase 3 only | Verify Phase 1&2 still work |
| **Emergency** | Production down | Deployment rollback | Health check, error rate monitoring |

### Appendix B: Contact Information

| Role | Name | Slack | Email |
|------|------|-------|-------|
| On-Call Engineer | [Name] | @oncall | oncall@example.com |
| Team Lead | [Name] | @team-lead | team-lead@example.com |
| Engineering Manager | [Name] | @eng-manager | eng-manager@example.com |
| Architect | [Name] | @architect | architect@example.com |

### Appendix C: Useful Commands

```bash
# List all rollback tags
git tag | grep pre-phase

# Show diff between current state and tag
git diff HEAD pre-phase-N

# Revert a specific commit
git revert <commit-hash>

# Revert a merge commit
git revert -m 1 <merge-commit-hash>

# Hard reset to tag (destructive)
git reset --hard pre-phase-N

# Soft reset to tag (keep changes)
git reset --soft pre-phase-N

# Check branch protection rules
gh api repos/zbnerd/MapleExpectation/branches/develop/protection

# Create rollback branch
git checkout -b rollback/phase-N pre-phase-N
```

---

**Document Status:** Active (Pre-Refactoring)
**Next Review:** After first rollback incident
**Maintainer:** Prometheus (Strategic Planning Consultant)
**Approvers:** Oracle (Architect), Metis (Analyst)

---

*This rollback strategy is a living document. Update it after each rollback incident to capture lessons learned and improve the process.*
*When in doubt, rollback first and analyze later. A quick rollback with thorough analysis is better than a prolonged debugging session that blocks the team.*
