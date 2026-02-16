package maple.expectation.core.port.out;

import java.util.List;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;

/**
 * Port for retrieving cube probability rates.
 *
 * <p>Implemented by module-infra adapters (e.g., CSV-based repository).
 *
 * <p>This interface abstracts the data source for cube rate data, allowing core business logic to
 * remain independent of infrastructure.
 *
 * @see maple.expectation.core.domain.model.CubeRate
 * @see maple.expectation.core.domain.model.CubeType
 */
public interface CubeRatePort {

  /**
   * Find cube rates for a specific cube type.
   *
   * @param type the cube type (BLACK, RED, ADDITIONAL)
   * @return list of cube rates for the given type, or empty list if not found
   */
  List<CubeRate> findByCubeType(CubeType type);

  /**
   * Find all available cube rates.
   *
   * @return list of all cube rates
   */
  List<CubeRate> findAll();
}
