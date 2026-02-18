# Operations Documentation

This directory contains operational documentation for Maple Expectation.

## Documentation Structure

```
docs/04_Operations/
├── observability.md           # Comprehensive observability guide
├── security.md                # Security considerations and access control
├── runbook.md                 # Operational procedures and troubleshooting
├── on-call-checklist.md       # Daily/weekly maintenance checklist
└── README.md                  # This file
```

## Overview

**Evidence ID**: EVD-OP001
**Code Anchor**: COD-OP001 (README.md)
**Last Updated**: 2026-02-06
**Version**: 2.0

This directory provides comprehensive operational guidance for running Maple Expectation in production, including monitoring, security, runbooks, and on-call procedures.

## Quick Reference

### Observability Stack (Evidence: EVD-OP002, EVD-OP003)

| Component | Purpose | URL | Health Check |
|-----------|---------|-----|--------------|
| **Prometheus** | Metrics collection and alerting | http://localhost:9090 | `curl http://localhost:9090/-/ready` |
| **Grafana** | Visualization and dashboards | http://localhost:3000 | `curl http://localhost:3000/api/health` |
| **Alertmanager** | Alert routing and notifications | http://localhost:9093 | `curl http://localhost:9093/-/ready` |
| **Loki** | Log aggregation | http://localhost:3100 | `curl http://localhost:3100/ready` |

### Monitoring Dashboards (Evidence: EVD-OP004)

- **System Overview**: http://localhost:3000/d/system-overview
  - CPU, Memory, Disk, Network metrics
  - JVM heap and GC metrics
  - Thread pool utilization

- **Business Metrics**: http://localhost:3000/d/business-metrics
  - Cache hit rates (L1/L2)
  - Equipment processing rates
  - API success rates
  - Outbox queue size

- **Chaos Test Dashboard**: http://localhost:3000/d/chaos-tests
  - Nightmare test results (N01-N18)
  - Circuit breaker states
  - Recovery time metrics

### Quick Commands

```bash
# Start full stack (MySQL, Redis, App, Observability)
docker-compose up -d

# Start only observability stack
docker-compose -f docker-compose.observability.yml up -d

# Check service status
docker-compose ps

# View logs
docker-compose logs -f

# Stop all services
docker-compose down
```

### Service URLs

- **Application**: http://localhost:8080
- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Alertmanager**: http://localhost:9093
- **Loki**: http://localhost:3100

## Key Files

### Configuration Files

- `docker-compose.yml` - Main application stack
- `docker-compose.observability.yml` - Observability stack
- `docker/prometheus/prometheus.yml` - Prometheus configuration
- `docker/alertmanager/alertmanager.yml` - Alertmanager configuration
- `docker/grafana/provisioning/` - Grafana configurations

### Application Configuration

- `src/main/resources/application.yml` - Spring Boot configuration
- `src/main/resources/application-{profile}.yml` - Environment-specific configs

## Monitoring Targets

### Critical Metrics to Monitor (Evidence: EVD-OP005)

#### 1. Application Health (Code Anchor: COD-OP007)

```bash
# HTTP error rates (target: < 1%)
curl -s http://localhost:9090/api/v1/query?query=rate(http_server_requests_seconds_count{status=~"5.."}[5m]) | jq '.data.result[0].value[1]'

# Response times (p95 target: < 1s)
curl -s http://localhost:9090/api/v1/query?query=histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) | jq '.data.result[0].value[1]'

# Active users
curl -s http://localhost:9090/api/v1/query?query=maple_active_users | jq '.data.result[0].value[1]'
```

#### 2. Resource Utilization (Code Anchor: COD-OP008)

```bash
# CPU usage (target: < 80%)
curl -s http://localhost:9090/api/v1/query?query=rate(process_cpu_usage[5m]) | jq '.data.result[0].value[1]'

# Memory usage (target: < 90%)
curl -s http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} | jq '.data.result[0].value[1]'

# Disk usage (target: < 90%)
df -h | grep -E "/$|/var/lib/mysql"
```

#### 3. Business Metrics (Code Anchor: COD-OP009)

```bash
# Cache hit rate (target: > 85%)
curl -s http://localhost:9090/api/v1/query?query=rate(maple_cache_hits_total[5m]) / (rate(maple_cache_hits_total[5m]) + rate(maple_cache_misses_total[5m])) | jq '.data.result[0].value[1]'

# Equipment processing rate
curl -s http://localhost:9090/api/v1/query?query=rate(maple_equipment_processed_total[5m]) | jq '.data.result[0].value[1]'

# Outbox queue size (target: < 1000)
curl -s http://localhost:9090/api/v1/query?query=maple_sync_queue_size | jq '.data.result[0].value[1]'
```

#### 4. System Components (Code Anchor: COD-OP010)

```bash
# Database connection pool usage (target: < 80%)
curl -s http://localhost:9090/api/v1/query?query=hikaricp_connections_active / hikaricp_connections_max | jq '.data.result[0].value[1]'

# Redis latency (target: < 100ms)
curl -s http://localhost:9090/api/v1/query?query=redis_command_duration_seconds{quantile="0.95"} | jq '.data.result[0].value[1]'

# Circuit breaker states (0=closed, 1=open, 2=half_open)
curl -s http://localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state | jq '.data.result[]'
```

### Fail If Wrong

This section is invalidated if:
- [ ] Any monitoring dashboard is unreachable
- [ ] Critical metrics exceed thresholds
- [ ] Health checks return non-200 status
- [ ] Verification commands cannot execute

## Alert Configuration (Evidence: EVD-OP006)

### Default Alert Thresholds (Code Anchor: COD-OP011)

| Alert | Threshold | Duration | Severity | Action |
|-------|-----------|----------|----------|--------|
| **High CPU** | > 80% | 5 minutes | Warning | Review metrics, scale if needed |
| **Critical CPU** | > 95% | 2 minutes | Critical | Immediate investigation |
| **High Memory** | > 90% | 5 minutes | Warning | Check heap dump, review GC |
| **Critical Memory** | > 95% | 2 minutes | Critical | Restart pending, scale up |
| **Error Rate Spike** | > 5% | 2 minutes | Critical | Check logs, verify dependencies |
| **High Response Time** | > 1s (p95) | 5 minutes | Warning | Review database queries, cache |
| **Cache Hit Rate Drop** | < 70% | 10 minutes | Warning | Review cache configuration |
| **Circuit Breaker Open** | state = open | 1 minute | Critical | Check downstream services |

### Alert Channels (Code Anchor: COD-OP012)

```bash
# Test alert notification (Critical)
curl -X POST http://localhost:9093/api/v1/alerts -d '[
  {
    "labels": {
      "alertname": "TestCriticalAlert",
      "severity": "critical",
      "instance": "maple-expectation:8080"
    },
    "annotations": {
      "description": "This is a test critical alert",
      "summary": "Test alert verification"
    }
  }
]'

# Verify Alertmanager configuration
curl -s http://localhost:9093/api/v1/status/config | jq '.data'
```

### Alert Verification Commands (Evidence: EVD-OP007)

```bash
# Check active alerts
curl -s http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.state=="firing")'

# Verify alert rules are loaded
curl -s http://localhost:9090/api/v1/rules | jq '.data.groups[].rules[] | select(.type=="alerting")'

# Check notification history
docker logs alertmanager_container 2>&1 | grep "Notify" | tail -20
```

### Fail If Wrong

This section is invalidated if:
- [ ] Alert thresholds are not configured
- [ ] Alert notification channels are not working
- [ ] Verification commands show firing alerts
- [ ] Alertmanager config is invalid

## Maintenance (Evidence: EVD-OP008)

### Daily Checks (Code Anchor: COD-OP013)

- [ ] Review Grafana dashboards for anomalies
  ```bash
  # Quick health check
  curl -s http://localhost:8080/actuator/health | jq '.status'
  # Expected: "UP"
  ```

- [ ] Check alert notifications (should be minimal)
  ```bash
  # Check recent alerts
  curl -s http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.state=="firing") | .labels'
  # Expected: Empty or known maintenance alerts
  ```

- [ ] Verify metrics collection
  ```bash
  # Verify Prometheus scraping
  curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'
  # Expected: All targets "up"
  ```

- [ ] Review application logs for errors
  ```bash
  # Check for errors in last hour
  docker logs maple_expectation_container 2>&1 | grep -i "error" | since $(date -d '1 hour ago' +%s)
  # Expected: No critical errors
  ```

### Weekly Maintenance (Code Anchor: COD-OP014)

- [ ] Review alert rules effectiveness
  ```bash
  # Check alert firing history
  curl -s http://localhost:9090/api/v1/query_range?query=ALERTS{alertstate="firing"}&start=$(date -d '7 days ago' +%s)&end=$(date +%s)&step=1h | jq '.data.result[]'
  # Action: Tune thresholds if too many false positives/negatives
  ```

- [ ] Update dashboard configurations (if needed)
  ```bash
  # Export current dashboards for version control
  curl -s http://localhost:3000/api/search | jq '.[] | select(.type=="dash-db") | .uid' | \
    xargs -I {} curl -s http://localhost:3000/api/dashboards/uid/{} | jq '.'
  # Commit changes to repository
  ```

- [ ] Check resource utilization trends
  ```bash
  # Query 7-day trend
  curl -s "http://localhost:9090/api/v1/query_range?query=rate(process_cpu_usage[5m])&start=$(date -d '7 days ago' +%s)&end=$(date +%s)&step=1d" | jq '.data.result[0].values[-1]'
  # Action: Plan capacity upgrades if trend > 80%
  ```

- [ ] Review backup status
  ```bash
  # Verify recent backups exist
  ls -lth backup/ | head -5
  # Expected: Daily backups for last 5 days
  ```

### Monthly Tasks (Code Anchor: COD-OP015)

- [ ] Review alert effectiveness and tune thresholds
- [ ] Update dashboards based on new requirements
- [ ] Review security configurations and access logs
- [ ] Plan capacity upgrades based on trends
- [ ] Review and update runbooks
- [ ] Conduct disaster recovery drill

### Fail If Wrong

This section is invalidated if:
- [ ] Daily check commands fail
- [ ] Health check returns non-UP status
- [ ] Metrics collection is not working
- [ ] Backups are missing or incomplete

## Troubleshooting

### Common Issues

1. **Service not starting**
   - Check port conflicts
   - Verify Docker daemon is running
   - Check resource availability

2. **Metrics not appearing**
   - Verify application is running
   - Check Prometheus scraping configuration
   - Verify firewall settings

3. **Alerts not firing**
   - Check Alertmanager configuration
   - Verify alert rules
   - Check notification channels

### Log Locations

- Application logs: `docker/logs/`
- MySQL logs: `docker/logs/mysql/`
- Prometheus logs: Container stdout
- Grafana logs: Container stdout

## Security Considerations

### Default Credentials

- Change default passwords in production
- Use environment variables for sensitive data
- Enable SSL/TLS for external access

### Network Security

- Restrict port access
- Use Docker networks for isolation
- Implement proper firewall rules

## Backup and Recovery (Evidence: EVD-OP009)

### Data Backup (Code Anchor: COD-OP016)

**Backup Schedule**: Daily at 2 AM UTC
**Retention**: 30 days (local), 1 year (off-site)

```bash
# Backup observability stack data (Evidence: EVD-OP010)
./scripts/backup_observability.sh

# Manual backup command
docker run --rm \
  -v prometheus_data:/prometheus \
  -v grafana_data:/var/lib/grafana \
  -v loki_data:/loki \
  -v alertmanager_data:/alertmanager \
  -v $(pwd)/backup:/backup \
  alpine tar cvf \
  /backup/observability_backup_$(date +%Y%m%d_%H%M%S).tar \
  /prometheus /var/lib/grafana /loki /alertmanager

# Verify backup integrity
tar -tf backup/observability_backup_*.tar | wc -l
# Expected: > 1000 files
```

### Backup Verification (Evidence: EVD-OP011)

```bash
# Check backup schedule (Cron)
crontab -l | grep backup
# Expected: 0 2 * * * /path/to/backup_observability.sh

# Verify recent backups exist
ls -lth backup/observability_backup_*.tar | head -7
# Expected: Daily backups for last 7 days

# Check backup size (should be consistent)
du -sh backup/observability_backup_*.tar | tail -1
# Expected: ~500MB (varies by usage)
```

### Recovery (Code Anchor: COD-OP017)

```bash
# Stop services
docker-compose -f docker-compose.observability.yml down

# Restore from backup (Evidence: EVD-OP012)
docker run --rm \
  -v prometheus_data:/prometheus \
  -v grafana_data:/var/lib/grafana \
  -v loki_data:/loki \
  -v alertmanager_data:/alertmanager \
  -v $(pwd)/backup:/backup \
  alpine sh -c "cd / && tar xvf /backup/observability_backup_YYYYMMDD_HHMMSS.tar"

# Restart services
docker-compose -f docker-compose.observability.yml up -d

# Verify recovery
curl -s http://localhost:9090/-/ready && echo "Prometheus OK"
curl -s http://localhost:3000/api/health && echo "Grafana OK"
# Expected: All services healthy
```

### Disaster Recovery Drill (Evidence: EVD-OP013)

**Frequency**: Quarterly
**Evidence Location**: `/home/maple/MapleExpectation/docs/05_Reports/DR_DRILL_Q1_2026.md`

```bash
# Run disaster recovery test
./scripts/test_disaster_recovery.sh

# Test checklist:
# - [ ] Backup created successfully
# - [ ] Backup verified for integrity
# - [ ] Services stopped
# - [ ] Data restored from backup
# - [ ] Services restarted successfully
# - [ ] Health checks pass
# - [ ] Metrics and logs available
# - [ ] Alerts working correctly
```

### Fail If Wrong

This section is invalidated if:
- [ ] Backup files are missing or corrupted
- [ ] Backup schedule is not configured
- [ ] Recovery procedure fails
- [ ] DR drill not documented

## Contact and Escalation (Evidence: EVD-OP014)

### On-Call Rotation (Code Anchor: COD-OP018)

| Role | Contact | Hours | Escalation |
|------|---------|-------|------------|
| **L1 - On-Call Engineer** | on-call@maple-expectation.com | 24/7 | L2 after 30 min |
| **L2 - Senior DevOps** | senior-devops@maple-expectation.com | 24/7 | L3 after 1 hour |
| **L3 - Engineering Lead** | eng-lead@maple-expectation.com | Business hours | - |

### Escalation Path (Evidence: EVD-OP015)

```
┌─────────────────┐
│ L1: On-Call     │
│ Engineer        │
└────────┬────────┘
         │ (30 min no response)
         ▼
┌─────────────────┐
│ L2: Senior      │
│ DevOps          │
└────────┬────────┘
         │ (1 hour no response)
         ▼
┌─────────────────┐
│ L3: Engineering │
│ Lead            │
└─────────────────┘
```

### Communication Channels (Code Anchor: COD-OP019)

- **Slack**: #maple-expectation-ops (Primary)
- **Email**: ops@maple-expectation.com (Incident documentation)
- **PagerDuty**: On-call scheduling and alerting

### Incident Command System (Evidence: EVD-OP016)

| Role | Responsibilities |
|------|------------------|
| **Incident Commander** | Coordinate response, communicate updates |
| **Technical Lead** | Investigate root cause, implement fix |
| **Communications Lead** | Update stakeholders, manage announcements |
| **Scribe** | Document timeline, actions, decisions |

## Related Documentation (Evidence: EVD-OP017)

- [Observability Guide](/home/maple/MapleExpectation/docs/04_Operations/observability.md) - Comprehensive monitoring setup
- [Security Considerations](/home/maple/MapleExpectation/docs/04_Operations/security.md) - Security policies and access control
- [On-Call Checklist](/home/maple/MapleExpectation/docs/05_Guides/ON_CALL_CHECKLIST.md) - Daily/weekly procedures
- [Runbook](/home/maple/MapleExpectation/docs/04_Operations/runbook.md) - Incident response procedures
- [Chaos Engineering](/home/maple/MapleExpectation/docs/02_Chaos_Engineering/) - N01-N18 nightmare scenarios

## Fail If Wrong (Evidence: EVD-OP018)

This document is invalidated if:
- [ ] Monitoring dashboards are unreachable
- [ ] Critical metrics exceed thresholds without action
- [ ] Alert channels are not working
- [ ] Backup procedures fail
- [ ] Escalation path is not defined
- [ ] Contact information is outdated

## Verification Commands (Evidence: EVD-OP019)

```bash
# Verify all monitoring components are healthy
./scripts/verify_monitoring_stack.sh

# Check all critical metrics are within thresholds
./scripts/check_critical_metrics.sh

# Verify backups are recent
./scripts/verify_backups.sh

# Test alert notification
./scripts/test_alert.sh critical
```

---

**Last Updated**: 2026-02-06
**Version**: 2.0
**Review Schedule**: Monthly
**Next Review**: 2026-03-06

**Evidence IDs**: EVD-OP001 ~ EVD-OP019
**Code Anchors**: COD-OP001 ~ COD-OP019