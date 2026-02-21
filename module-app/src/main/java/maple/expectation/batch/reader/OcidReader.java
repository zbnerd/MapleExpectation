package maple.expectation.batch.reader;

import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity;
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * OCID Reader for Spring Batch (Issue #356)
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>game_character 테이블에서 전체 OCID 조회
 *   <li>JPA Cursor-based pagination (chunk size: 1000)
 *   <li>Memory efficient: Iterator 패턴으로 상태 저장 최소화
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor.executeOrDefault (Zero Try-Catch)
 *   <li>Section 15: 람다 3줄 초과 시 Private Method 추출
 *   <li>Stateless: Iterator 사용, 상태 저장 최소화
 *   <li>Method Reference: GameCharacterJpaEntity::getOcid
 * </ul>
 *
 * @see maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcidReader implements ItemReader<String> {

  private final GameCharacterJpaRepository repository;
  private final LogicExecutor executor;

  // Chunk-based fetch for memory efficiency
  private static final int FETCH_SIZE = 1000;
  private Iterator<String> ocidIterator;
  private int currentPage = 0;
  private boolean hasNextPage = true;

  @Override
  public String read() {
    return executor.executeOrDefault(
        this::readNextOcid, null, TaskContext.of("OcidReader", "Read"));
  }

  /**
   * Read next OCID from iterator
   *
   * <p>Section 15: 람다 3줄 초과 시 Private Method 추출
   *
   * @return next OCID or null if exhausted
   */
  private String readNextOcid() {
    if (ocidIterator == null || !ocidIterator.hasNext()) {
      fetchNextChunk();
    }

    if (!hasNextPage || (ocidIterator != null && !ocidIterator.hasNext())) {
      return null;
    }

    return ocidIterator.next();
  }

  /**
   * Fetch next chunk of OCIDs using JPA pagination
   *
   * <p>Section 15: 람다 3줄 초과 시 Private Method 추출
   */
  private void fetchNextChunk() {
    Pageable pageable = PageRequest.of(currentPage, FETCH_SIZE);
    Page<GameCharacterJpaEntity> page = repository.findAll(pageable);

    hasNextPage = page.hasNext();
    currentPage++;

    // Method Reference: GameCharacterJpaEntity::getOcid
    ocidIterator = page.getContent().stream().map(GameCharacterJpaEntity::getOcid).iterator();

    log.debug("[OcidReader] Fetched chunk: page={}, hasMore={}", currentPage, hasNextPage);
  }
}
