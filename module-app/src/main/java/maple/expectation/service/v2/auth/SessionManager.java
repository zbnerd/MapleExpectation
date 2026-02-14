package maple.expectation.service.v2.auth;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.Session;
import maple.expectation.error.exception.auth.SessionNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 세션 관리자
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>세션 생성 (fingerprint, userIgn, accountId, apiKey, myOcids, role)
 *   <li>세션 조회 (존재 확인 시 TTL 갱신)
 *   <li>세션 TTL 갱신 (Sliding Window)
 *   <li>세션 삭제 (로그아웃)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {

  private final SessionService sessionService;

  /**
   * 새 세션을 생성합니다.
   *
   * @param fingerprint API Key의 HMAC-SHA256 해시
   * @param userIgn 로그인 캐릭터명
   * @param accountId 넥슨 계정 식별자
   * @param apiKey Nexon API Key
   * @param myOcids 사용자가 소유한 캐릭터 OCID 목록
   * @param role 권한 (USER 또는 ADMIN)
   * @return 생성된 세션
   */
  public Session createSession(
      String fingerprint,
      String userIgn,
      String accountId,
      String apiKey,
      Set<String> myOcids,
      String role) {
    log.debug("Creating session: userIgn={}, role={}", userIgn, role);

    Session session =
        sessionService.createSession(fingerprint, userIgn, accountId, apiKey, myOcids, role);

    log.info(
        "Session created: sessionId={}, userIgn={}, role={}", session.sessionId(), userIgn, role);
    return session;
  }

  /**
   * 세션을 조회하고 TTL을 갱신합니다 (Sliding Window).
   *
   * @param sessionId 세션 ID
   * @return 세션
   * @throws SessionNotFoundException 세션이 존재하지 않는 경우
   */
  public Session getAndRefreshSession(String sessionId) {
    log.debug("Refreshing session TTL: sessionId={}", sessionId);

    return sessionService
        .getSessionAndRefresh(sessionId)
        .orElseThrow(SessionNotFoundException::new);
  }

  /**
   * 세션을 조회합니다 (TTL 갱신 없음).
   *
   * @param sessionId 세션 ID
   * @return 세션
   * @throws SessionNotFoundException 세션이 존재하지 않는 경우
   */
  public Session getSession(String sessionId) {
    return sessionService.getSession(sessionId).orElseThrow(SessionNotFoundException::new);
  }

  /**
   * 세션 TTL을 갱신합니다 (Token Refresh 시 사용).
   *
   * @param sessionId 세션 ID
   */
  public void refreshSessionTtl(String sessionId) {
    boolean refreshed = sessionService.refreshSession(sessionId);

    if (!refreshed) {
      throw new SessionNotFoundException();
    }

    log.debug("Session TTL refreshed: sessionId={}", sessionId);
  }

  /**
   * 세션을 삭제합니다 (로그아웃).
   *
   * @param sessionId 세션 ID
   */
  public void deleteSession(String sessionId) {
    sessionService.deleteSession(sessionId);
    log.info("Session deleted: sessionId={}", sessionId);
  }
}
