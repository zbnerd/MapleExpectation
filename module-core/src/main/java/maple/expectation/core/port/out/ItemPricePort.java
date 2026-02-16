package maple.expectation.core.port.out;

import java.util.Optional;
import maple.expectation.core.domain.model.ItemPrice;

/**
 * Port for retrieving item price data from Nexon API.
 *
 * <p>Implemented by module-infra adapters (external API clients).
 *
 * <p>This interface abstracts the external API call for item prices, allowing core business logic
 * to remain independent of API implementation.
 */
public interface ItemPricePort {

  /**
   * Find item price by item ID.
   *
   * @param itemId the unique item identifier
   * @return Optional containing the item price, or empty if not found
   */
  Optional<ItemPrice> findByItemId(Long itemId);

  /**
   * Find item price by item name.
   *
   * @param itemName the name of the item
   * @return Optional containing the item price, or empty if not found
   */
  Optional<ItemPrice> findByItemName(String itemName);
}
