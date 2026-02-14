package maple.expectation.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler for specific event types.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>
 * &#64;Component
 * public class LikeEventHandler {
 *
 *   &#64;EventHandler(LikeReceivedEvent.class)
 *   public void handleLikeReceived(LikeReceivedEvent event) {
 *     // Handle event
 *   }
 * }
 * </pre>
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
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventHandler {

  /**
   * Event type this handler processes.
   *
   * <p>Must match the single method parameter type.
   *
   * @return Event class to handle
   */
  Class<?> eventType();

  /**
   * Whether to execute handler asynchronously.
   *
   * <p>Default: {@code true} (Virtual Threads)
   *
   * <p>Set to {@code false} for synchronous handlers (e.g., transactional operations).
   *
   * @return true if async execution (Virtual Thread), false if synchronous
   */
  boolean async() default true;
}
