package maple.expectation.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Centralized cache type definitions.
 *
 * <p>Supports OCP (Open/Closed Principle) by eliminating scattered hard-coded cache names. New
 * cache types can be added here without modifying client code.
 */
public enum CacheType {
  /** Equipment data cache (5 min TTL) */
  EQUIPMENT("equipment", Duration.ofMinutes(5)),

  /** OCID mapping cache (30 min TTL) */
  OCID("ocidCache", Duration.ofMinutes(30)),

  /** Total expectation cache (5 min TTL) */
  TOTAL_EXPECTATION("totalExpectation", Duration.ofMinutes(5)),

  /** Character basic info cache (15 min TTL) */
  CHARACTER_BASIC("characterBasic", Duration.ofMinutes(15)),

  /** OCID negative cache (30 min TTL) */
  OCID_NEGATIVE("ocidNegativeCache", Duration.ofMinutes(30)),

  /** Like count cache (5 min TTL) */
  LIKE_COUNT("likeCount", Duration.ofMinutes(5));

  private final String name;
  private final Duration ttl;

  CacheType(String name, Duration ttl) {
    this.name = Objects.requireNonNull(name);
    this.ttl = Objects.requireNonNull(ttl);
  }

  public String getName() {
    return name;
  }

  public Duration getTtl() {
    return ttl;
  }

  @Override
  public String toString() {
    return name;
  }
}
