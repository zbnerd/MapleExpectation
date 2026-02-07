package maple.expectation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centralized timeout configuration.
 *
 * <p>Supports OCP (Open/Closed Principle) by eliminating scattered hard-coded timeout values.
 * Timeouts can be adjusted via configuration without code changes.
 */
@Component
@ConfigurationProperties(prefix = "timeouts")
public class TimeoutProperties {

  /** Equipment leader/follower computation timeout */
  private Duration equipment = Duration.ofSeconds(30);

  /** Nexon API call timeout */
  private Duration apiCall = Duration.ofSeconds(10);

  /** Async operation timeout */
  private Duration async = Duration.ofSeconds(30);

  /** Database query timeout */
  private Duration database = Duration.ofSeconds(5);

  /** Cache operation timeout */
  private Duration cache = Duration.ofSeconds(2);

  public Duration getEquipment() {
    return equipment;
  }

  public void setEquipment(Duration equipment) {
    this.equipment = equipment;
  }

  public Duration getApiCall() {
    return apiCall;
  }

  public void setApiCall(Duration apiCall) {
    this.apiCall = apiCall;
  }

  public Duration getAsync() {
    return async;
  }

  public void setAsync(Duration async) {
    this.async = async;
  }

  public Duration getDatabase() {
    return database;
  }

  public void setDatabase(Duration database) {
    this.database = database;
  }

  public Duration getCache() {
    return cache;
  }

  public void setCache(Duration cache) {
    this.cache = cache;
  }
}
