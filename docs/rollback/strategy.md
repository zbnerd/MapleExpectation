# Rollback Strategy

**Status:** Active
**Version:** 1.0
**Created:** 2026-02-16
**Applicable to:** Multi-Module Refactoring Phases 1-4

---

## Overview

This document outlines comprehensive rollback procedures for the multi-module refactoring effort (Issue #282). The rollback strategy is designed to ensure minimal service disruption during refactoring and provide clear procedures for recovering from failed migrations.

### Refactoring Context

**Current Architecture (Baseline - ADR-039):**
- 5 modules: `module-app`, `module-infra`, `module-core`, `module-common`, `module-chaos-test`
- Dependency direction: `module-app → module-infra → module-core → module-common`
- Identified issues: 56 @Configuration classes in module-app, 45 monitoring files, empty repository package
- Target: Reduce module-app files from 342 to < 150, move infrastructure concerns to module-infra

**Refactoring Phases:**
1. **Phase 1:** module-common extraction (Week 1)
2. **Phase 2-A:** Port interfaces (Week 2)
3. **Phase 2-B:** Core extraction (Week 3)
4. **Phase 3:** Infrastructure extraction (Week 4-5)
5. **Phase 4:** Final cleanup (Week 6)

---

## Git Tagging and Version Control

### Pre-Refactoring Tagging

**Procedure:**
```bash
# Tag current state before refactoring
git tag -a "pre-refactor-v1.0" -m "Pre-refactoring baseline for multi-module migration"

# Validate clean working directory
git status --porcelain

# Create milestone branch for rollback reference
git checkout -b rollback-branch-$(date +%Y%m%d)
```

### Rollback Point Tagging

**After Each Phase Completion:**
```bash
# Tag successful phase completion
git tag -a "phase1-completed-v1.0" -m "Phase 1: module-common extraction completed"

# Tag before risky operation
git tag -a "pre-phase2-risky-v1.0" -m "Before risky Phase 2 infrastructure migration"
```

### Rollback Tagging Strategy

| Operation | Command | Purpose |
|-----------|---------|---------|
| **Baseline** | `git tag -a refactoring-baseline-v1.0` | Pre-refactoring snapshot |
| **Phase Checkpoint** | `git tag -a phase-{N}-completed-v1.0` | After successful phase completion |
| **Pre-Risk** | `git tag -a pre-risk-{operation}-v1.0` | Before high-risk operation |
| **Emergency** | `git tag -a emergency-rollback-{timestamp}` | Emergency recovery point |

### Rollback Reference Documentation

**Rollback Points Registry:**
```markdown
# Rollback Points
- refactoring-baseline-v1.0: 2026-02-16 (Pre-refactoring state)
- phase1-completed-v1.0: Phase 1 completion (module-common extracted)
- phase2A-completed-v1.0: Phase 2-A completion (interfaces ported)
- phase2B-completed-v1.0: Phase 2-B completion (core extracted)
- phase3-completed-v1.0: Phase 3 completion (infrastructure extracted)
```

---

## Phase Rollback Procedures

### Phase 1: module-common extraction rollback

**Risk Level:** Low
**Estimated Rollback Time:** 15 minutes

**Triggers for Rollback:**
- Build failures in any module
- Import resolution errors > 10
- Test failures related to common utilities
- Service startup errors

**Rollback Procedure:**
```bash
# 1. Emergency checkout of baseline
git checkout refactoring-baseline-v1.0

# 2. Clean working directory
git clean -fd

# 3. Restore original module structure
./gradlew clean build -x test

# 4. Validate build success
./gradlew build --continue

# 5. Run smoke tests
./gradlew test --tests "*SmokeTest*"

# 6. Tag successful rollback
git tag -a "phase1-rollback-v1.0" -m "Phase 1 rollback completed"
```

**Verification Checklist:**
- [ ] All modules build successfully
- [ ] No compilation errors
- [ ] Service starts without errors
- [ ] Common utilities accessible from all modules
- [ ] Test coverage maintained (> 80%)

### Phase 2-A: Port interfaces rollback

**Risk Level:** Medium
**Estimated Rollback Time:** 30 minutes

**Triggers for Rollback:**
- Interface signature conflicts
- DIP violations detected
- Circular dependency re-emergence
- API compatibility breaks

**Rollback Procedure:**
```bash
# 1. Tag before rollback
git tag -a "pre-phase2A-rollback-v1.0"

# 2. Checkout to Phase 1 completion point
git checkout phase1-completed-v1.0

# 3. Clean and rebuild
git clean -fd
./gradlew clean build -x test

# 4. Restore original interface locations
cp -r backup/module-core/application/port/* module-core/src/main/java/maple/expectation/application/port/

# 5. Fix import statements
find . -name "*.java" -exec sed -i 's/maple\.expectation\.domain\.port\./maple.expectation.application.port./g' {} \;

# 6. Verify interface contracts
./gradlew test --tests "*InterfaceTest*"
```

**Critical Components to Monitor:**
- Interface implementations in module-infra
- DIP compliance checks
- Module dependency direction
- Import statements across all modules

### Phase 2-B: Core extraction rollback

**Risk Level:** High
**Estimated Rollback Time:** 45 minutes

**Triggers for Rollback:**
- Domain logic separation failures
- Business rule inconsistencies
- Service layer breaks
- Performance regression > 20%

**Rollback Procedure:**
```bash
# 1. Emergency baseline restoration
git checkout pre-phase2-risky-v1.0
git reset --hard refactoring-baseline-v1.0

# 2. Clean state completely
git clean -fd
rm -rf build/

# 3. Restore original domain structure
cp -r backup/module-domain/* module-infra/src/main/java/maple/expectation/domain/

# 4. Fix module dependencies
sed -i 's/implementation project(\":module-core\")/implementation project(\":module-infra\")/' module-app/build.gradle

# 5. Restore original service implementations
find . -name "*Service*.java" -exec git checkout refactoring-baseline-v1.0 -- {} \;

# 6. Full build validation
./gradlew clean build
./gradlew integrationTest --continue
```

**Data Integrity Checks:**
- Database schema compatibility
- Entity relationships preserved
- Repository contracts maintained
- Business logic consistency

### Phase 3: Infrastructure extraction rollback

**Risk Level:** High
**Estimated Rollback Time:** 60 minutes

**Triggers for Rollback:**
- Configuration loading failures
- Connection pool exhaustion
- Caching system breaks
- External API integration failures

**Rollback Procedure:**
```bash
# 1. System backup before rollback
docker-compose down
cp -r /var/lib/mysql /tmp/mysql-backup-$(date +%Y%m%d)
cp -r /var/lib/docker/volumes/redis-data/_data /tmp/redis-backup-$(date +%Y%m%d)

# 2. Tag emergency rollback point
git tag -a "emergency-infra-rollback-$(date +%Y%m%d)" -m "Emergency infrastructure rollback"

# 3. Restore original infrastructure package
git checkout pre-refactor-v1.0
cp -r backup/module-infra/infrastructure/* module-infra/src/main/java/maple/expectation/infrastructure/

# 4. Fix @ComponentScan annotations
sed -i 's/@ComponentScan("maple\.expectation\.infrastructure")/@ComponentScan("maple.expectation.config")/' module-app/src/main/java/maple/expectation/config/ApplicationConfig.java

# 5. Restore configuration classes
find module-app/config -name "*Config.java" -exec git checkout refactoring-baseline-v1.0 -- {} \;

# 6. Restart services with original configuration
docker-compose up -d
sleep 30

# 7. Validate infrastructure connectivity
./gradlew test --tests "*InfrastructureIntegrationTest*"
```

**Infrastructure Components to Restore:**
- Redis configuration and clients
- MySQL connection pools
- External API clients
- Caching strategies
- Security filters and interceptors

### Phase 4: Final cleanup rollback

**Risk Level:** Medium
**Estimated Rollback Time:** 20 minutes

**Triggers for Rollback:**
- Performance regression detected
- Memory leaks
- Thread pool exhaustion
- Monitoring system failures

**Rollback Procedure:**
```bash
# 1. Tag pre-cleanup rollback
git tag -a "pre-cleanup-rollback-v1.0"

# 2. Restore to Phase 3 completion
git checkout phase3-completed-v1.0

# 3. Remove cleanup artifacts
find . -name "*.tmp" -delete
find . -name "*.bak" -delete

# 4. Restore original monitoring setup
cp -r backup/monitoring/* module-app/src/main/java/maple/expectation/monitoring/

# 5. Fix monitoring configuration
sed -i 's/prometheus\.client\.url=http:\/\/localhost:9090/prometheus.client.url=http:\/\/localhost:9090/' module-app/src/main/resources/application.yml

# 6. Performance validation
./gradlew loadTest --continue
```

---

## Rollback Verification

### Pre-Rollback Checks

**Before initiating rollback:**
1. **Service Health Check:**
   ```bash
   # Monitor service health
   curl -f http://localhost:8080/actuator/health || exit 1

   # Check database connectivity
   mysql -h localhost -u root -p -e "SELECT 1" || exit 1

   # Verify Redis connectivity
   redis-cli ping || exit 1
   ```

2. **Backup Verification:**
   ```bash
   # Verify git repository state
   git status --porcelain

   # Check backup files
   ls -la /tmp/mysql-backup-*/
   ls -la /tmp/redis-backup-*/
   ```

3. **Rollback Point Validation:**
   ```bash
   # Verify rollback tag exists
   git tag -l "phase1-completed-v1.0"

   # Check commit history
   git log --oneline -5
   ```

### Post-Rollback Verification

**After rollback completion:**
1. **Build Verification:**
   ```bash
   # Full build validation
   ./gradlew clean build --continue

   # Module-specific builds
   ./gradlew :module-app:build
   ./gradlew :module-infra:build
   ./gradlew :module-core:build
   ./gradlew :module-common:build
   ```

2. **Test Verification:**
   ```bash
   # Unit tests
   ./gradlew test --continue

   # Integration tests
   ./gradlew integrationTest --continue

   # Load tests
   ./gradlew loadTest --continue
   ```

3. **Functional Verification:**
   ```bash
   # API endpoints
   curl -f http://localhost:8080/api/v4/characters/test/expectation || exit 1
   curl -f http://localhost:8080/api/v2/characters/test/like || exit 1

   # Health checks
   curl -f http://localhost:8080/actuator/health || exit 1
   ```

### Automated Verification Scripts

**Rollback Verification Script (`scripts/verify-rollback.sh`):**
```bash
#!/bin/bash
set -e

echo "Starting rollback verification..."

# 1. Build verification
echo "1. Verifying builds..."
./gradlew clean build --continue

# 2. Test verification
echo "2. Running tests..."
./gradlew test --continue integrationTest --continue

# 3. API verification
echo "3. Testing APIs..."
curl -f http://localhost:8080/actuator/health
curl -f http://localhost:8080/api/v4/characters/test/expectation
curl -f http://localhost:8080/api/v2/characters/test/like

# 4. Performance check
echo "4. Performance validation..."
./gradlew loadTest --continue --info

echo "Rollback verification completed successfully!"
```

---

## Data Migration Safety

### Database Backup Procedures

**Pre-Refactoring Backup:**
```bash
# Full database backup
mysqldump -h localhost -u root -p maple_expectation > /tmp/mysql-backup-pre-refactor.sql

# Schema backup
mysqldump -h localhost -u root -p --no-data maple_expectation > /tmp/mysql-schema-backup.sql

# Verification
mysql -h localhost -u root -p -e "SELECT COUNT(*) FROM equipment_expectations;" maple_expectation
```

**Rollback Database Recovery:**
```bash
# Restore from backup
mysql -h localhost -u root -p maple_expectation < /tmp/mysql-backup-pre-refactor.sql

# Verify data integrity
mysql -h localhost -u root -p -e "
    SELECT COUNT(*) FROM equipment_expectations;
    SELECT COUNT(*) FROM character_likes;
    SELECT COUNT(*) FROM nexon_characters;
" maple_expectation
```

### Redis Data Safety

**Redis Backup Strategy:**
```bash
# Save Redis data
redis-cli BGSAVE
cp /var/lib/docker/volumes/redis-data/_data/dump.rdb /tmp/redis-backup-pre-refactor.rdb

# Verify backup
redis-cli --rdb /tmp/redis-backup-pre-refactor.rdb --test-memory
```

**Redis Rollback Procedure:**
```bash
# Stop Redis service
docker-compose stop redis

# Replace data file
cp /tmp/redis-backup-pre-refactor.rdb /var/lib/docker/volumes/redis-data/_data/dump.rdb

# Restart Redis
docker-compose start redis

# Verify data
redis-cli ping
redis-cli keys "expectation:*" | wc -l
```

### Configuration Backup

**Configuration Files Backup:**
```bash
# Backup application configuration
cp module-app/src/main/resources/application.yml /tmp/app-config-backup.yml
cp module-app/src/main/resources/application-local.yml /tmp/app-local-config-backup.yml

# Backup infrastructure configuration
cp module-infra/src/main/resources/infrastructure-config.yml /tmp/infra-config-backup.yml

# Module configuration backups
find . -name "build.gradle" -exec cp {} /tmp/ \;
```

### Data Validation After Rollback

**Data Consistency Checks:**
```bash
# Database consistency
mysql -h localhost -u root -p -e "
    -- Check for orphaned records
    SELECT COUNT(*) FROM equipment_expectations e
    LEFT JOIN nexon_characters n ON e.ocid = n.ocid
    WHERE n.ocid IS NULL;

    -- Check like counts integrity
    SELECT COUNT(*) FROM character_likes l
    LEFT JOIN nexon_characters n ON l.ocid = n.ocid
    WHERE n.ocid IS NULL;
" maple_expectation

# Redis consistency
redis-cli --eval /tmp/redis-consistency-check.lua
```

---

## CI/CD Rollback

### Automated Rollback Triggers

**CI Pipeline Monitoring:**
```yaml
# .github/workflows/rollback-monitor.yml
name: Rollback Monitoring
on:
  push:
    paths:
      - 'module-*/**'
      - 'build.gradle'

jobs:
  monitor-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: gradle/actions/setup-gradle@v3
      - name: Monitor build health
        run: |
          if ./gradlew build --continue --info | grep -q "BUILD FAILED"; then
            echo "::error::Build failure detected, triggering rollback"
            curl -X POST -H "Authorization: Bearer ${{ secrets.ROLLBACK_TOKEN }}" \
                 https://api.github.com/repos/${{ github.repository }}/actions/runs \
                 -d '{"workflow_id": "rollback-trigger.yml"}'
          fi
```

### Rollback Automation

**Automated Rollback Workflow:**
```yaml
# .github/workflows/rollback-trigger.yml
name: Automated Rollback
on:
  workflow_dispatch:
    inputs:
      rollback_point:
        description: "Rollback to which phase?"
        required: true
        default: "phase1-completed-v1.0"

jobs:
  rollback:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          ref: ${{ github.event.inputs.rollback_point }}

      - name: Restore dependencies
        run: |
          ./gradlew clean
          ./gradlew dependencies

      - name: Verify rollback
        run: |
          chmod +x scripts/verify-rollback.sh
          ./scripts/verify-rollback.sh

      - name: Update deployment
        run: |
          ./gradlew deploy --info
```

### Rollback Notifications

**Slack Integration:**
```bash
# Rollback notification script
send_rollback_notification() {
    local phase=$1
    local status=$2
    local message="Rollback to ${phase}: ${status}"

    curl -X POST -H 'Content-type: application/json' \
         --data "{\"text\":\"${message}\", \"channel\":\"#refactoring-alerts\"}" \
         https://hooks.slack.com/services/${SLACK_WEBHOOK}
}

# Example usage
send_rollback_notification "phase1-completed-v1.0" "SUCCESS"
```

### Rollback Testing Framework

**Automated Rollback Tests:**
```java
// src/test/java/com/maple/expectation/rollback/RollbackTest.java
class RollbackTest {

    @Test
    void testPhase1Rollback() {
        // Simulate Phase 1 rollback
        GitHelper.restoreToTag("phase1-completed-v1.0");

        // Verify build success
        BuildResult result = GradleRunner.create()
            .withProjectDir(new File("."))
            .withArguments("clean", "build")
            .build();

        assertThat(result.getOutput()).contains("BUILD SUCCESS");
    }

    @Test
    void testInfrastructureRollback() {
        // Test infrastructure component restoration
        InfrastructureComponents components = new InfrastructureComponents();
        components.verifyRedisConnection();
        components.verifyDatabaseConnection();
        components.verifyExternalApiClients();
    }
}
```

---

## Communication Plan

### Team Notification Procedures

**Rollback Decision Matrix:**
| Scenario | Urgency | Notification Channel | Content |
|----------|---------|-------------------|---------|
| **Build Failure** | High | Slack + Email | "Build failure detected, initiating rollback" |
| **Performance Degradation** | Medium | Slack | "Performance regression > 20%, rollback initiated" |
| **Data Integrity Issue** | High | PagerDuty + Email | "Data corruption detected, emergency rollback" |
| **Configuration Error** | Low | Slack | "Configuration rollback required" |

### Stakeholder Communication

**Team Lead Notifications:**
```bash
# Rollback alert script
send_team_alert() {
    local subject="Rollback Alert: $1"
    local message="Rollback initiated due to: $2\nPhase: $3\nEstimated time: $4"

    # Email notification
    echo "$message" | mail -s "$subject" team-lead@company.com

    # Slack notification
    curl -X POST -H 'Content-type: application/json' \
         --data "{\"text\":\"${message}\", \"channel\":\"#architecture-team\"}" \
         https://hooks.slack.com/services/${SLACK_WEBHOOK}
}
```

### Rollback Status Updates

**Status Reporting:**
```bash
# Rollback status script
report_rollback_progress() {
    local phase=$1
    local step=$2
    local status=$3

    echo "Rollback Progress: ${phase} - ${step}: ${status}"

    # Update status in monitoring system
    curl -X PUT "${MONITORING_API}/rollback-status" \
         -H "Content-Type: application/json" \
         -d "{\"phase\":\"${phase}\",\"step\":\"${step}\",\"status\":\"${status}\"}"
}
```

### Post-Rollback Review

**Rollback Debrief Meeting:**
```markdown
# Rollback Review Template

## Date: [Date]
## Phase: [Phase Number]
## Trigger: [Failure Description]
## Rollback Time: [Duration]
## Issues Encountered:

## Root Cause Analysis:
- [ ] Configuration mismatch
- [ ] Dependency resolution failure
- [ ] Data corruption
- [ ] Build system issue
- [ ] Other: [Specify]

## Preventive Actions:
- [ ] Improved testing
- [ ] Better backup procedures
- [ ] Enhanced monitoring
- [ ] Documentation updates
- [ ] Other: [Specify]

## Next Steps:
- [ ] Fix root cause
- [ ] Retry refactoring
- [ ] Alternative approach
- [ ] Cancel refactoring
```

---

## Emergency Procedures

### Immediate Rollback (Critical Failures)

**When to Use Emergency Rollback:**
- Database corruption detected
- System unresponsive for > 5 minutes
- Data loss confirmed
- Security breach detected
- Performance degradation > 50%

**Emergency Rollback Command:**
```bash
# One-click emergency rollback
#!/bin/bash
set -e

echo "🚨 EMERGENCY ROLLBACK INITIATED 🚨"

# 1. Stop all services
docker-compose down

# 2. Restore latest stable backup
git checkout refactoring-baseline-v1.0
cp -r /tmp/mysql-backup-pre-refactor.sql /tmp/mysql-emergency-restore.sql
mysql -h localhost -u root -p maple_expectation < /tmp/mysql-emergency-restore.sql

# 3. Start services in safe mode
docker-compose up -d --force-recreate

# 4. Verify system health
sleep 60
curl -f http://localhost:8080/actuator/health

echo "✅ EMERGENCY ROLLBACK COMPLETED"
```

### Communication Escalation

**Emergency Contact Protocol:**
1. **First 5 minutes:** Notify on-call architect
2. **First 15 minutes:** Notify engineering manager
3. **First 30 minutes:** Notify CTO
4. **First 60 minutes:** Notify stakeholders

**Emergency Notification Script:**
```bash
# emergency-notify.sh
emergency_notify() {
    local severity=$1
    local message=$2

    # PagerDuty
    curl -X POST -H 'Content-Type: application/json' \
         -d "{\"event_type\":\"trigger\",\"priority\":\"${severity}\",\"payload\":{\"message\":\"${message}\"}}" \
         https://events.pagerduty.com/v2/enqueue

    # Slack emergency channel
    curl -X POST -H 'Content-type: application/json' \
         --data "{\"text\":\"🚨 EMERGENCY: ${message}\", \"channel\":\"#emergency-alerts\"}" \
         https://hooks.slack.com/services/${SLACK_WEBHOOK}
}
```

---

## Post-Rollback Analysis

### Success Metrics Tracking

**Rollback Success Criteria:**
```yaml
# rollback-success.yml
metrics:
  - name: "build_success_rate"
    target: "> 95%"
    current: "100%"

  - name: "test_pass_rate"
    target: "> 90%"
    current: "95%"

  - name: "performance_regression"
    target: "< 10%"
    current: "5%"

  - name: "data_integrity"
    target: "100%"
    current: "100%"
```

### Root Cause Analysis

**Rollback RCA Template:**
```markdown
# Rollback Root Cause Analysis

## Event Summary
- **Date:** [Date]
- **Phase:** [Phase Number]
- **Trigger:** [Failure Description]
- **Rollback Time:** [Duration]

## Root Cause Analysis
### Technical Root Causes
- [ ] Configuration error
- [ ] Dependency resolution failure
- [ ] Data migration issue
- [ ] Build system problem
- [ ] Testing gap

### Process Root Causes
- [ ] Inadequate testing
- [ ] Insufficient backup verification
- [ ] Poor monitoring
- [ ] Communication breakdown
- [ ] Planning oversight

## Corrective Actions
### Immediate Actions (Fixed)
- [ ] [Action completed]
- [ ] [Action completed]

### Preventive Actions (Planned)
- [ ] [Action to be taken]
- [ ] [Action to be taken]

## Long-term Improvements
- [ ] [System improvement]
- [ ] [Process improvement]
```

### Continuous Improvement

**Rollback Feedback Loop:**
1. **Weekly Review:** Analyze all rollback attempts
2. **Monthly Analysis:** Identify patterns in rollback triggers
3. **Quarterly Improvement:** Update rollback procedures
4. **Annual Review:** Comprehensive rollback strategy refresh

**Metrics Dashboard:**
```javascript
// Rollback Metrics Dashboard
const dashboard = {
    rollbacks: {
        total: 0,
        successful: 0,
        failed: 0,
        averageTime: "0 minutes"
    },
    phases: {
        "phase1": { risk: "low", rollbacks: 0, successRate: "100%" },
        "phase2A": { risk: "medium", rollbacks: 0, successRate: "100%" },
        "phase2B": { risk: "high", rollbacks: 0, successRate: "100%" },
        "phase3": { risk: "high", rollbacks: 0, successRate: "100%" },
        "phase4": { risk: "medium", rollbacks: 0, successRate: "100%" }
    }
};
```

---

## Documentation References

### Related Documents

| Document | Path | Purpose |
|----------|------|---------|
| [ADR-039](../adr/ADR-039-current-architecture-assessment.md) | `docs/01_Adr/ADR-039-current-architecture-assessment.md` | Current architecture assessment |
| [Refactoring Analysis](../04_Reports/refactoring-analysis.md) | `docs/05_Reports/refactoring-analysis.md` | Pre-refactoring context analysis |
| [Refactoring Completion](../04_Reports/refactoring-completion.md) | `docs/05_Reports/refactoring-completion.md` | Refactoring results and lessons learned |
| [Architecture Guide](../00_Start_Here/architecture.md) | `docs/00_Start_Here/architecture.md` | System architecture overview |
| [CLAUDE.md](../../CLAUDE.md) | `CLAUDE.md` | Project guidelines and rules |

### Quick Reference

**Rollback Commands:**
```bash
# Check rollback points
git tag -l "*phase*-completed*"
git tag -l "*rollback*"

# Perform rollback
git checkout phase1-completed-v1.0
./gradlew clean build

# Verify rollback
./scripts/verify-rollback.sh

# Emergency rollback
./scripts/emergency-rollback.sh
```

**Monitoring Commands:**
```bash
# Service health
curl http://localhost:8080/actuator/health

# Database connectivity
mysql -h localhost -u root -p -e "SELECT 1" maple_expectation

# Redis connectivity
redis-cli ping
```

---

**Document Status:** Active
**Last Updated:** 2026-02-16
**Next Review:** After Phase 1 completion

*This rollback strategy will be updated after each refactoring phase to reflect lessons learned and improve procedures.*