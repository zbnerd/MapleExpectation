package maple.expectation.repository.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.Session;
import maple.expectation.domain.repository.RedisSessionRepository;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Redis 기반 세션 저장소 구현체
 *
 * <p>세션 구조 (Hash):
 *
 * <ul>
 *   <li>Key: session:{sessionId}
 *   <li>Fields: fingerprint, apiKey, myOcids (JSON), role, createdAt, lastAccessedAt
 *   <li>TTL: 30분 (Sliding Window)
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisSessionRepositoryImpl implements RedisSessionRepository {

  private static final String SESSION_KEY_PREFIX = "session:";
  private static final String FIELD_FINGERPRINT = "fingerprint";
  private static final String FIELD_USER_IGN = "userIgn";
  private static final String FIELD_ACCOUNT_ID = "accountId";
  private static final String FIELD_API_KEY = "apiKey";
  private static final String FIELD_MY_OCIDS = "myOcids";
  private static final String FIELD_ROLE = "role";
  private static final String FIELD_CREATED_AT = "createdAt";
  private static final String FIELD_LAST_ACCESSED_AT = "lastAccessedAt";

  private final RedissonClient redissonClient;
  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;

  @Value("${auth.session.ttl}")
  private long sessionTtlSeconds;

  /**
   * 세션을 저장합니다.
   *
   * @param session 저장할 세션
   */
  public void save(Session session) {
    String key = buildKey(session.sessionId());
    RMap<String, String> map = redissonClient.getMap(key);

    executor.executeVoid(
        () -> {
          map.put(FIELD_FINGERPRINT, session.fingerprint());
          map.put(FIELD_USER_IGN, session.userIgn());
          map.put(FIELD_ACCOUNT_ID, session.accountId());
          map.put(FIELD_API_KEY, session.apiKey());
          map.put(FIELD_MY_OCIDS, serializeOcids(session.myOcids()));
          map.put(FIELD_ROLE, session.role());
          map.put(FIELD_CREATED_AT, session.createdAt().toString());
          map.put(FIELD_LAST_ACCESSED_AT, session.lastAccessedAt().toString());

          // TTL 설정
          map.expire(Duration.ofSeconds(sessionTtlSeconds));
        },
        TaskContext.of("Session", "Save", session.sessionId()));

    log.debug("Session saved: sessionId={}, role={}", session.sessionId(), session.role());
  }

  /**
   * 세션 ID로 세션을 조회합니다.
   *
   * @param sessionId 세션 ID
   * @return 세션 (Optional)
   */
  public Optional<Session> findById(String sessionId) {
    return executor.executeOrDefault(
        () -> doFindById(sessionId),
        Optional.empty(),
        TaskContext.of("Session", "FindById", sessionId));
  }

  private Optional<Session> doFindById(String sessionId) {
    String key = buildKey(sessionId);
    RMap<String, String> map = redissonClient.getMap(key);

    if (!map.isExists()) {
      return Optional.empty();
    }

    String fingerprint = map.get(FIELD_FINGERPRINT);
    if (fingerprint == null) {
      return Optional.empty();
    }

    Session session =
        new Session(
            sessionId,
            fingerprint,
            map.get(FIELD_USER_IGN),
            map.get(FIELD_ACCOUNT_ID),
            map.get(FIELD_API_KEY),
            deserializeOcids(map.get(FIELD_MY_OCIDS)),
            map.get(FIELD_ROLE),
            Instant.parse(map.get(FIELD_CREATED_AT)),
            Instant.parse(map.get(FIELD_LAST_ACCESSED_AT)));

    return Optional.of(session);
  }

  /**
   * 세션 TTL을 갱신합니다 (Sliding Window).
   *
   * @param sessionId 세션 ID
   * @return 갱신 성공 여부
   */
  public boolean refreshTtl(String sessionId) {
    return executor.executeOrDefault(
        () -> {
          String key = buildKey(sessionId);
          RMap<String, String> map = redissonClient.getMap(key);

          if (!map.isExists()) {
            return false;
          }

          map.put(FIELD_LAST_ACCESSED_AT, Instant.now().toString());
          map.expire(Duration.ofSeconds(sessionTtlSeconds));
          return true;
        },
        false,
        TaskContext.of("Session", "RefreshTtl", sessionId));
  }

  /**
   * 세션을 삭제합니다.
   *
   * @param sessionId 세션 ID
   */
  public void deleteById(String sessionId) {
    executor.executeVoid(
        () -> {
          String key = buildKey(sessionId);
          redissonClient.getMap(key).delete();
        },
        TaskContext.of("Session", "Delete", sessionId));

    log.debug("Session deleted: sessionId={}", sessionId);
  }

  /**
   * 세션 존재 여부를 확인합니다.
   *
   * @param sessionId 세션 ID
   * @return 존재 여부
   */
  public boolean existsById(String sessionId) {
    return executor.executeOrDefault(
        () -> {
          String key = buildKey(sessionId);
          return redissonClient.getMap(key).isExists();
        },
        false,
        TaskContext.of("Session", "Exists", sessionId));
  }

  private String buildKey(String sessionId) {
    return SESSION_KEY_PREFIX + sessionId;
  }

  /** OCIDs 직렬화 (CLAUDE.md Section 12 준수: LogicExecutor 패턴) */
  private String serializeOcids(Set<String> ocids) {
    if (ocids == null || ocids.isEmpty()) {
      return "[]";
    }
    return executor.executeOrDefault(
        () -> objectMapper.writeValueAsString(ocids),
        "[]",
        TaskContext.of("Session", "SerializeOcids", String.valueOf(ocids.size())));
  }

  /** OCIDs 역직렬화 (CLAUDE.md Section 12 준수: LogicExecutor 패턴) */
  private Set<String> deserializeOcids(String json) {
    if (json == null || json.isBlank()) {
      return new HashSet<>();
    }
    return executor.executeOrDefault(
        () -> objectMapper.readValue(json, new TypeReference<Set<String>>() {}),
        new HashSet<>(),
        TaskContext.of(
            "Session", "DeserializeOcids", json.length() > 20 ? json.substring(0, 20) : json));
  }
}
