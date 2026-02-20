# Grafana Performance Comparison Panel

**Image Description**: This comparison panel shows performance metrics across all 4 k6 benchmark configurations.

**Panel Configuration**:
- Layout: 2x2 grid layout
- Time Range: 2025-06-20, 16:00-17:00 (1 hour test)
- Refresh: 30 seconds

## Top Left Panel: RPS Comparison
**Chart Type**: Bar Chart
**Metrics**:
- Baseline (100 RPS): 102.3 avg RPS, 100% success rate
- Aggressive (200 RPS): 198.7 avg RPS, 95% success rate
- Peak (300 RPS): 295.2 avg RPS, 90% success rate
- Extreme (400 RPS): 387.4 avg RPS, 85% success rate

**Key Insights**:
- Linear scalability up to 300 RPS
- Diminishing returns at 400 RPS
- Success rate drops significantly beyond 300 RPS

## Top Right Panel: Latency Distribution
**Chart Type**: Line Chart (Multi-series)
**Metrics**:
- P50 latency: Baseline=45ms, Aggressive=78ms, Peak=142ms, Extreme=210ms
- P90 latency: Baseline=95ms, Aggressive=165ms, Peak=305ms, Extreme=425ms
- P95 latency: Baseline=125ms, Aggressive=210ms, Peak=380ms, Extreme=542ms
- P99 latency: Baseline=187ms, Aggressive=342ms, Peak=678ms, Extreme=895ms

**Key Insights**:
- Latency increases non-linearly with load
- P99 shows most dramatic degradation
- 400 RPS configuration exceeds acceptable latency thresholds

## Bottom Left Panel: Resource Utilization
**Chart Type**: Stacked Area Chart
**Metrics**:
- CPU Usage: Baseline=35%, Aggressive=58%, Peak=82%, Extreme=95%
- Memory Usage: Baseline=2.1GB, Aggressive=4.2GB, Peak=6.3GB, Extreme=8.1GB
- Network I/O: Baseline=45Mbps, Aggressive=90Mbps, Peak=135Mbps, Extreme=180Mbps

**Key Insights**:
- CPU becomes bottleneck at 400 RPS (95% utilization)
- Memory scales linearly with load
- Network bandwidth shows good headroom

## Bottom Right Panel: Cost Efficiency
**Chart Type**: Scatter Plot
**X-axis**: RPS (requests per second)
**Y-axis**: Cost per RPS ($)
**Data Points**:
- Baseline: 100 RPS at $2.35/RPS
- Aggressive: 200 RPS at $1.34/RPS  
- Peak: 300 RPS at $1.18/RPS
- Extreme: 400 RPS at $0.89/RPS

**Key Insights**:
- Best efficiency at 300 RPS ($1.18/RPS)
- Diminishing returns beyond 300 RPS
- Risk of failure increases beyond optimal point

**Overall Recommendations**:
1. **Sweet Spot**: 200-300 RPS range offers best balance
2. **Scaling**: Auto-scale between 4-8 instances
3. **Cost Optimization**: Target $1.20/RPS or better
4. **SLA Threshold**: Maintain P95 < 200ms