package maple.expectation.infrastructure.config;

import maple.expectation.common.resource.ResourceLoader;
import maple.expectation.core.port.out.MessageQueue;
import maple.expectation.core.port.out.MessageTopic;
import maple.expectation.infrastructure.messaging.RedisMessageQueue;
import maple.expectation.infrastructure.messaging.RedisMessageTopic;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging infrastructure configuration.
 *
 * <p>Configures MessageTopic and MessageQueue beans using Redisson.
 */
@Configuration
public class MessagingConfig {

  @Bean
  public MessageTopic<String> characterEventTopic(RedissonClient redissonClient) {
    return new RedisMessageTopic<>(redissonClient, "char_event");
  }

  @Bean
  public MessageQueue<String> characterJobQueue(RedissonClient redissonClient) {
    return new RedisMessageQueue<>(redissonClient, "character_job_queue");
  }

  @Bean
  public MessageQueue<String> nexonDataQueue(RedissonClient redissonClient) {
    return new RedisMessageQueue<>(redissonClient, "nexon-data");
  }

  /**
   * Event queue for RedisEventPublisher to publish IntegrationEvent messages.
   *
   * <p>Separate from characterJobQueue and nexonDataQueue to avoid ambiguity. This queue is
   * specifically for domain events published through EventPublisher interface.
   */
  @Bean("integrationEventQueue")
  public MessageQueue<String> integrationEventQueue(RedissonClient redissonClient) {
    return new RedisMessageQueue<>(redissonClient, "integration_event_queue");
  }

  /**
   * ResourceLoader bean for loading classpath resources. Required by TwoBucketRateLimiter for
   * loading Lua scripts.
   */
  @Bean
  public ResourceLoader resourceLoader() {
    return new ResourceLoader();
  }
}
