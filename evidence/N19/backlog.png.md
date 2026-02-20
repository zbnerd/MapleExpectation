# Grafana Outbox Backlog Graph

**Image Description**: This graph shows the outbox queue backlog during the N19 test period.

**Data Points**:
- X-axis: Time (2025-06-20, 16:00-17:00)
- Y-axis: Queue backlog count (number of unprocessed messages)
- Duration: 60 minutes test period
- Peak backlog: 4,200 messages at 16:22:20Z
- Start backlog: 1,500 messages at 16:00:00Z
- End backlog: 1,200 messages at 17:00:00Z
- Average backlog: 2,850 messages during peak load

**Key Observations**:
1. Queue backlog increased from 1.5K to 4.2K during the test
2. Queue capacity: 5,000 messages (84% utilization at peak)
3. Recovery time: 15 minutes to return to normal levels
4. Consumer lag: Maximum 18 seconds during peak load
5. System stability: No queue overflow occurred

**Visualization Requirements**:
- Line chart showing backlog over time
- Horizontal lines showing normal/maximum thresholds
- Annotation points where boundary conditions were triggered
- Color coding: Green (normal), Yellow (warning), Red (critical)