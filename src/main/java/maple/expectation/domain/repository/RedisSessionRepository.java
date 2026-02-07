package maple.expectation.domain.repository;

import java.util.Optional;
import maple.expectation.domain.Session;

/**
 * Redis 기반 세션 저장소 인터페이스
 *
 * <p>세션 구조 (Hash):
 *
 * <ul>
 *   <li>Key: session:{sessionId}
 *   <li>Fields: fingerprint, apiKey, myOcids (JSON), role, createdAt, lastAccessedAt
 *   <li>TTL: 30분 (Sliding Window)
 * </ul>
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@link maple.expectation.repository.v2.RedisSessionRepositoryImpl} - Redisson 기반 구현
 * </ul>
 */
public interface RedisSessionRepository {

  /**
   * 세션을 저장합니다.
   *
   * @param session 저장할 세션
   */
  void save(Session session);

  /**
   * 세션 ID로 세션을 조회합니다.
   *
   * @param sessionId 세션 ID
   * @return 세션 (Optional)
   */
  Optional<Session> findById(String sessionId);

  /**
   * 세션 TTL을 갱신합니다 (Sliding Window).
   *
   * @param sessionId 세션 ID
   * @return 갱신 성공 여부
   */
  boolean refreshTtl(String sessionId);

  /**
   * 세션을 삭제합니다.
   *
   * @param sessionId 세션 ID
   */
  void deleteById(String sessionId);

  /**
   * 세션 존재 여부를 확인합니다.
   *
   * @param sessionId 세션 ID
   * @return 존재 여부
   */
  boolean existsById(String sessionId);
}
