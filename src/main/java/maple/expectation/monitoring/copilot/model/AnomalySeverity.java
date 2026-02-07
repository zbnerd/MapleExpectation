package maple.expectation.monitoring.copilot.model;

/** Anomaly Severity Levels */
public enum AnomalySeverity {
  /** Warning level - exceeds warning threshold */
  WARNING,

  /** Critical level - exceeds critical threshold or extreme Z-score */
  CRITICAL
}
