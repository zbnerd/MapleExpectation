package maple.expectation.core.domain.model;

import java.time.LocalDateTime;

/**
 * Item price domain model.
 *
 * <p>Represents the market price of an item from Nexon's API.
 *
 * <p>Pure domain model - no external dependencies.
 *
 * @param itemId the unique item identifier
 * @param itemName the name of the item
 * @param price the current market price
 * @param updatedAt the timestamp when the price was last updated
 */
public record ItemPrice(Long itemId, String itemName, Long price, LocalDateTime updatedAt) {
  /** Validates the item price data. */
  public ItemPrice {
    if (itemId == null || itemId <= 0) {
      throw new IllegalArgumentException("itemId must be positive");
    }
    if (itemName == null || itemName.isBlank()) {
      throw new IllegalArgumentException("itemName cannot be null or blank");
    }
    if (price == null || price < 0) {
      throw new IllegalArgumentException("price cannot be negative");
    }
    if (updatedAt == null) {
      throw new IllegalArgumentException("updatedAt cannot be null");
    }
  }

  /** Create an item price with current timestamp. */
  public static ItemPrice of(Long itemId, String itemName, Long price) {
    return new ItemPrice(itemId, itemName, price, LocalDateTime.now());
  }

  /**
   * Check if the price data is fresh (within specified duration).
   *
   * @param hours the maximum age in hours
   * @return true if price data is fresh, false otherwise
   */
  public boolean isFreshWithinHours(long hours) {
    return updatedAt != null && updatedAt.plusHours(hours).isAfter(LocalDateTime.now());
  }
}
