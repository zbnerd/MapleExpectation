package maple.expectation.infrastructure.cache.port;

import java.util.Optional;
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse;

/**
 * Equipment Cache Port (DIP Interface)
 *
 * <p>Infrastructure layer interface for equipment caching operations. This abstraction allows AOP
 * aspects (infrastructure concern) to depend on an interface rather than concrete implementation,
 * following DIP principle.
 *
 * <h3>Dependency Direction:</h3>
 *
 * <pre>
 * module-infra (this interface) ← depended by
 * module-app (EquipmentCacheService implementation)
 * </pre>
 *
 * <h3>Methods Used By:</h3>
 *
 * <ul>
 *   <li>{@code NexonDataCacheAspect} - distributed caching coordination
 * </ul>
 *
 * @see maple.expectation.service.v2.cache.EquipmentCacheService
 */
public interface EquipmentCache {

  /**
   * Retrieve valid cached equipment data (L1 → L2 → Warm-up)
   *
   * @param ocid character OCID
   * @return cached equipment if present and valid
   */
  Optional<EquipmentResponse> getValidCache(String ocid);

  /**
   * Check if negative cache exists for the OCID
   *
   * @param ocid character OCID
   * @return true if negative cache marker exists
   */
  boolean hasNegativeCache(String ocid);

  /**
   * Save equipment data to cache with async DB persistence
   *
   * @param ocid character OCID
   * @param response equipment response to cache
   */
  void saveCache(String ocid, EquipmentResponse response);
}
