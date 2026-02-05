# Operations Documentation

This directory contains operational documentation for Maple Expectation.

## Documentation Structure

```
docs/04_Operations/
├── observability.md           # Comprehensive observability guide
├── README.md                  # This file
└── (future operational guides)
```

## Quick Reference

### Observability Stack

- **Prometheus**: Metrics collection and alerting
- **Grafana**: Visualization and dashboards
- **Alertmanager**: Alert routing and notifications
- **Loki**: Log aggregation

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

### Critical Metrics to Monitor

1. **Application Health**
   - HTTP error rates
   - Response times (p50, p95, p99)
   - Active users

2. **Resource Utilization**
   - CPU usage
   - Memory usage
   - Disk usage
   - Network I/O

3. **Business Metrics**
   - Cache hit rates
   - Equipment processing rates
   - API success rates

4. **System Components**
   - Database connection pools
   - Redis performance
   - Circuit breaker states

## Alert Configuration

### Default Alert Thresholds

- **CPU**: >80% for 5 minutes
- **Memory**: >90% for 5 minutes
- **Error Rate**: >5% for 2 minutes
- **Response Time**: >1s for 5 minutes
- **Cache Hit Rate**: <70% for 10 minutes

### Alert Channels

- **Critical**: Email + Slack
- **Warning**: Email
- **Info**: Email

## Maintenance

### Daily Checks

- Review Grafana dashboards
- Check alert notifications
- Verify metrics collection
- Review application logs

### Weekly Maintenance

- Review alert rules
- Update dashboard configurations
- Check resource utilization trends
- Review backup status

### Monthly Tasks

- Review alert effectiveness
- Update dashboards as needed
- Review security configurations
- Plan capacity upgrades

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

## Backup and Recovery

### Data Backup

```bash
# Backup data volumes
docker run --rm -v prometheus_data:/prometheus -v grafana_data:/var/lib/grafana \
  -v $(pwd)/backup:/backup alpine tar cvf /backup/obs_backup.tar /prometheus /var/lib/grafana
```

### Recovery

```bash
# Restore from backup
docker run --rm -v prometheus_data:/prometheus -v grafana_data:/var/lib/grafana \
  -v $(pwd)/backup:/backup alpine tar xvf /backup/obs_backup.tar -C /
```

## Contact

For operational issues:
- **DevOps Team**: devops@maple-expectation.com
- **Development Team**: dev@maple-expectation.com

---

*Last Updated: 2026-02-06*
*Version: 1.0*