package maple.expectation.service.v5;

import maple.expectation.service.v5.event.MongoSyncEventPublisherInterface;
import maple.expectation.service.v5.event.MongoSyncEventPublisherStub;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * V5 CQRS Test Configuration
 *
 * <p>Provides test-specific beans for V5 integration tests.
 *
 * <ul>
 *   <li>MongoSyncEventPublisherStub: Stub implementation that doesn't require Redis
 *   <li>Mock MongoTemplate: For MongoDB operations without actual MongoDB instance
 * </ul>
 */
@TestConfiguration
public class V5TestConfiguration {

  /**
   * Stub implementation of MongoSyncEventPublisher for testing.
   *
   * <p>This stub doesn't actually publish to Redis Stream, allowing tests to run without Redis
   * infrastructure.
   */
  @Bean
  @Primary
  public MongoSyncEventPublisherInterface mongoSyncEventPublisherStub() {
    return new MongoSyncEventPublisherStub();
  }
}
