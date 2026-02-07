package maple.expectation.infrastructure.messaging;

import java.util.function.BiConsumer;
import maple.expectation.application.port.MessageTopic;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Redis-backed message topic implementation.
 *
 * <p>Infrastructure adapter for MessageTopic port. Implements pub/sub using Redisson RTopic.
 *
 * @param <T> message type
 */
@Component
public class RedisMessageTopic<T> implements MessageTopic<T> {

  private final RedissonClient redissonClient;
  private final String topicName;

  /**
   * Create a Redis-backed topic.
   *
   * @param redissonClient Redisson client
   * @param topicName topic name prefix
   */
  public RedisMessageTopic(RedissonClient redissonClient, String topicName) {
    this.redissonClient = redissonClient;
    this.topicName = topicName;
  }

  @Override
  public int addListener(Class<T> messageType, BiConsumer<String, T> listener) {
    RTopic topic = redissonClient.getTopic(topicName);
    return topic.addListener(
        messageType,
        (channel, msg) -> listener.accept(channel != null ? channel.toString() : null, msg));
  }

  @Override
  public void removeListener(int listenerId) {
    RTopic topic = redissonClient.getTopic(topicName);
    topic.removeListener(listenerId);
  }

  @Override
  public void publish(String channel, T message) {
    RTopic topic = redissonClient.getTopic(topicName + ":" + channel);
    topic.publish(message);
  }
}
