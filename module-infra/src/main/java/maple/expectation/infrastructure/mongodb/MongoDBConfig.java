package maple.expectation.infrastructure.mongodb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * V5 CQRS: MongoDB Configuration
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Enable MongoDB repositories
 *   <li>Configure MongoDB template settings
 *   <li>Set up index management
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * Only active when v5.enabled=true
 */
@Configuration
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
@EnableMongoRepositories(basePackages = "maple.expectation.infrastructure.mongodb")
public class MongoDBConfig {

  // MongoDB auto-configuration will handle connection settings
  // from spring.data.mongodb.* properties in application.yml

  // TODO: Add MongoDB health check indicators
  // TODO: Add MongoDB metrics (connection pool, query latency)
}
