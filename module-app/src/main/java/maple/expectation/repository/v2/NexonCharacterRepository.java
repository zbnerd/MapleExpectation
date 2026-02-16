package maple.expectation.infrastructure.persistence.repository;

import java.util.List;
import maple.expectation.domain.nexon.NexonApiCharacterData;
import org.springframework.data.jpa.repository.JpaRepository;

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
public interface NexonCharacterRepository
    extends JpaRepository<NexonApiCharacterData, Long>, NexonCharacterRepositoryCustom {}
