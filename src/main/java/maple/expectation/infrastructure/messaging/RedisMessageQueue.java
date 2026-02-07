package maple.expectation.infrastructure.messaging;

import maple.expectation.application.port.MessageQueue;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Redis-backed message queue implementation.
 *
 * <p>Infrastructure adapter for MessageQueue port. Implements queue using Redisson
 * RBlockingQueue.
 *
 * @param <T> message type
 */
@Component
public class RedisMessageQueue<T> implements MessageQueue<T> {

  private final RBlockingQueue<T> queue;

  /**
   * Create a Redis-backed queue.
   *
   * @param redissonClient Redisson client
   * @param queueName queue name
   */
  public RedisMessageQueue(RedissonClient redissonClient, String queueName) {
    this.queue = redissonClient.getBlockingQueue(queueName);
  }

  @Override
  public boolean offer(T message) {
    return queue.offer(message);
  }

  @Override
  public T poll() {
    try {
      return queue.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  @Override
  public int size() {
    return queue.size();
  }
}
