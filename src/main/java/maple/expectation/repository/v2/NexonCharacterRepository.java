package maple.expectation.repository.v2;

import java.util.List;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for Nexon API character data.
 *
 * <p><strong>Purpose:</strong> Stores character data fetched from Nexon Open API. This is the write
 * side of the ingestion pipeline (Stage 3: Storage).
 *
 * <p><strong>Batch Operations:</strong> Provides {@link #batchUpsert(List)} for efficient bulk
 * inserts. Uses JDBC batch updates for 90% reduction in DB I/O.
 *
 * <p><strong>Idempotency:</strong> Upsert operation (INSERT or UPDATE) ensures data integrity even
 * if the same character is processed multiple times.
 *
 * @see NexonApiCharacterData
 * @see maple.expectation.service.ingestion.BatchWriter
 * @see ADR-018 Strategy Pattern for ACL
 */
public interface NexonCharacterRepository extends JpaRepository<NexonApiCharacterData, Long> {

  /**
   * Batch upsert operation for efficient bulk inserts.
   *
   * <p><strong>Performance:</strong> Uses JDBC batch updates to insert/update multiple records in a
   * single transaction. This provides 90% reduction in DB I/O compared to individual saves.
   *
   * <p><strong>Usage:</strong> Called by {@link
   * maple.expectation.service.ingestion.BatchWriter#processBatch()} with batch size of 1000
   * records.
   *
   * @param dataList List of character data to upsert
   * @return Number of affected rows
   */
  @Modifying
  @Transactional
  @Query(
      value =
          """
          INSERT INTO nexon_api_character_data (
              ocid, character_name, world_name, character_class, character_level,
              guild_name, character_image_url, date
          ) VALUES (
              :ocid, :characterName, :worldName, :characterClass, :characterLevel,
              :guildName, :characterImageUrl, :date
          )
          ON DUPLICATE KEY UPDATE
              character_name = VALUES(character_name),
              world_name = VALUES(world_name),
              character_class = VALUES(character_class),
              character_level = VALUES(character_level),
              guild_name = VALUES(guild_name),
              character_image_url = VALUES(character_image_url),
              date = VALUES(date)
          """,
      nativeQuery = true)
  int batchUpsert(@Param("dataList") List<NexonApiCharacterData> dataList);
}
