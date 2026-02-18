package maple.expectation.event

/**
 * Event processing priority levels.
 *
 * <p>Defines thread pool separation strategy for event processing:
 *
 * <ul>
 *   <li>HIGH: Critical events (alerts, health checks) - dedicated thread pool
 *   <li>LOW: Background events (analytics, logging) - separate thread pool
 * </ul>
 *
 * <p><strong>SOLID Compliance:</strong>
 *
 * <ul>
 *   <li><b>SRP:</b> Single responsibility - priority classification
 *   <li><b>OCP:</b> Closed for modification (enum sealed)
 * </ul>
 *
 * @since 1.0.0
 */
enum class EventPriority {
    /** High priority - critical events requiring immediate processing */
    HIGH,

    /** Low priority - background events that can be deferred */
    LOW
}
