package maple.expectation.infrastructure.persistence.repository;

import java.util.List;
import maple.expectation.domain.nexon.NexonApiCharacterData;

/**
 * Custom interface fragment for NexonCharacterRepository batch operations.
 *
 * <p><strong>Pattern:</strong> Spring Data JPA fragment pattern for custom implementations. This
 * interface is implemented by NexonCharacterRepositoryImpl and merged into
 * NexonCharacterRepository.
 *
 * <p><strong>P1 Fix:</strong> Provides JdbcTemplate-based batch upsert to properly bind
 * List&lt;Entity&gt; parameters, which Spring Data JPA's @Query cannot handle for native INSERT.
 *
 * @see NexonCharacterRepository
 * @see NexonCharacterRepositoryImpl
 */
public interface NexonCharacterRepositoryCustom {

  /**
   * Batch upsert operation using JdbcTemplate for proper parameter binding.
   *
   * <p><strong>Implementation:</strong> Uses PreparedStatement with indexed parameters to avoid the
   * named parameter binding issue with @Query annotation.
   *
   * @param dataList List of character data to upsert
   * @return Number of affected rows
   */
  int batchUpsert(List<NexonApiCharacterData> dataList);
}
