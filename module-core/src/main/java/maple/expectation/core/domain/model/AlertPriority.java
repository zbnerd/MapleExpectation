package maple.expectation.core.domain.model;

/**
 * Alert priority enum.
 *
 * <p>Represents the priority level of an alert.
 *
 * <p>Pure domain model - no external dependencies.
 */
public enum AlertPriority {
  /** High priority - critical issues requiring immediate attention */
  HIGH,

  /** Medium priority - important issues that should be addressed soon */
  MEDIUM,

  /** Low priority - informational messages */
  LOW
}
