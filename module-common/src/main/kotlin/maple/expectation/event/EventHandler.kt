package maple.expectation.event

import kotlin.reflect.KClass

/**
 * Marks a method as an event handler for specific event types.
 *
 * <p><strong>Usage:</strong>
 *
 * ```kotlin
 * @Component
 * class LikeEventHandler {
 *
 *   @EventHandler(LikeReceivedEvent::class)
 *   fun handleLikeReceived(event: LikeReceivedEvent) {
 *     // Handle event
 *   }
 * }
 * ```
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Event Type Routing: Methods are automatically registered based on eventType parameter
 *   <li>Async Execution: Handlers run on Virtual Threads by default (Java 21)
 *   <li>Method Signature: Single parameter matching the event type
 * </ul>
 *
 * <h3>CLAUDE.md Section 4 Compliance:</h3>
 *
 * <ul>
 *   <li><b>Strategy Pattern:</b> Different handlers for different event types
 *   <li><b>Template Method:</b> EventDispatcher orchestrates handler execution
 * </ul>
 *
 * <h3>CLAUDE.md Section 21 Compliance:</h3>
 *
 * <ul>
 *   <li><b>Async Non-Blocking:</b> Handlers execute on Virtual Threads
 *   <li><b>Backpressure:</b> ExecutorService queue limits unbounded processing
 * </ul>
 *
 * @see maple.expectation.event.EventDispatcher
 * @since 1.0.0
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class EventHandler(
    /**
     * Event type this handler processes.
     *
     * <p>Must match the single method parameter type.
     *
     * @return Event class to handle
     */
    val eventType: KClass<*>,

    /**
     * Whether to execute handler asynchronously.
     *
     * <p>Default: `true` (Virtual Threads)
     *
     * <p>Set to `false` for synchronous handlers (e.g., transactional operations).
     *
     * @return true if async execution (Virtual Thread), false if synchronous
     */
    val async: Boolean = true
)
