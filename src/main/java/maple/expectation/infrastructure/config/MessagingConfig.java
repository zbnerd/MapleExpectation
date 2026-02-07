package maple.expectation.infrastructure.config;

import maple.expectation.application.port.MessageQueue;
import maple.expectation.application.port.MessageTopic;
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
}
