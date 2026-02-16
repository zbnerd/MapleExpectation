package maple.expectation.infrastructure.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * V5 CQRS: MongoDB Configuration
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Enable MongoDB repositories
 *   <li>Configure MongoDB template settings
 *   <li>Set up TTL index for 24-hour automatic expiry
 *   <li>Configure health check indicators
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * Only active when v5.enabled=true
 *
 * <h3>TTL Index</h3>
 *
 * <p>Creates a TTL index on {@code calculatedAt} field with 24-hour expiry. This ensures stale data
 * is automatically removed without manual invalidation.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "v5.enabled", havingValue = "true", matchIfMissing = false)
@EnableMongoRepositories(basePackages = "maple.expectation.infrastructure.mongodb")
public class MongoDBConfig {

  private final MongoClient mongoClient;
  private final MongoTemplate mongoTemplate;

  private static final String COLLECTION_NAME = "character_valuation_views";
  private static final String CALCULATED_AT_FIELD = "calculatedAt";
  private static final long TTL_SECONDS = TimeUnit.HOURS.toSeconds(24);

  /**
   * Create TTL index on startup if it doesn't exist.
   *
   * <p>This ensures documents expire after 24 hours automatically.
   */
  @PostConstruct
  @SuppressWarnings("deprecation")
  public void ensureTTLIndex() {
    try {
      IndexOperations indexOps = mongoTemplate.indexOps(CharacterValuationView.class);

      // Create TTL index on calculatedAt field
      Index ttlIndex =
          new Index()
              .on(CALCULATED_AT_FIELD, Sort.Direction.ASC)
              .expire(TTL_SECONDS)
              .named("_ttl_calculatedAt_");

      indexOps.ensureIndex(ttlIndex);

      log.info(
          "[MongoDB] TTL index created on {}.{} ({} seconds)",
          COLLECTION_NAME,
          CALCULATED_AT_FIELD,
          TTL_SECONDS);

    } catch (Exception e) {
      log.error("[MongoDB] Failed to create TTL index", e);
      throw new IllegalStateException("Failed to create MongoDB TTL index", e);
    }
  }

  /**
   * Verify MongoDB connection health.
   *
   * @return true if connection is healthy
   */
  public boolean isHealthy() {
    try {
      String databaseName = mongoTemplate.getDb().getName();
      MongoDatabase database = mongoClient.getDatabase(databaseName);
      database.runCommand(new org.bson.BsonDocument("ping", new org.bson.BsonInt32(1)));
      return true;
    } catch (Exception e) {
      log.error("[MongoDB] Health check failed", e);
      return false;
    }
  }
}
