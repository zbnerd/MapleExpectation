package maple.expectation.core.port.out;

/**
 * Message queue for async task processing.
 *
 * <p>Domain port for queue-based messaging. Abstraction over Redis queues, Kafka queues, etc.
 * Supports DIP (Dependency Inversion Principle) by allowing business logic to depend on this
 * interface instead of concrete Redis APIs.
 *
 * @param <T> message type
 */
public interface MessageQueue<T> {

  /**
   * Offer a message to the queue (non-blocking).
   *
   * @param message message to queue
   * @return true if message was enqueued, false if queue is full
   */
  boolean offer(T message);

  /**
   * Poll a message from the queue (blocking).
   *
   * @return dequeued message, or null if interrupted
   */
  T poll();

  /**
   * Get current queue size.
   *
   * @return approximate size
   */
  int size();
}
