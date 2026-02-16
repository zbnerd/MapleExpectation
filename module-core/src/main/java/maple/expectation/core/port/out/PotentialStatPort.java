package maple.expectation.core.port.out;

import java.util.Optional;
import maple.expectation.core.domain.model.PotentialStat;

/**
 * Port for retrieving potential stat information.
 *
 * <p>Implemented by module-infra adapters.
 *
 * <p>This interface abstracts the lookup of potential stat data, allowing core business logic to
 * remain independent of the data source.
 */
public interface PotentialStatPort {

  /**
   * Find potential stat by option name.
   *
   * @param optionName the name of the potential option
   * @return Optional containing the potential stat, or empty if not found
   */
  Optional<PotentialStat> findByOptionName(String optionName);

  /**
   * Check if an option name is a valid potential stat.
   *
   * @param optionName the name to check
   * @return true if valid, false otherwise
   */
  boolean isValidOption(String optionName);
}
