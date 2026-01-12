package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.Session;
import maple.expectation.repository.v2.RedisSessionRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 세션 관리 서비스
 *
 * <p>책임:
 * <ul>
 *   <li>세션 생성 (UUID + fingerprint + apiKey + myOcids)</li>
 *   <li>세션 조회 및 TTL 갱신 (Sliding Window)</li>
 *   <li>세션 삭제 (로그아웃)</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RedisSessionRepository sessionRepository;

    /**
     * 새 세션을 생성합니다.
     *
     * @param fingerprint API Key의 HMAC-SHA256 해시
     * @param apiKey      Nexon API Key
     * @param myOcids     사용자가 소유한 캐릭터 OCID 목록
     * @param role        권한 (USER 또는 ADMIN)
     * @return 생성된 세션
     */
    public Session createSession(String fingerprint, String apiKey, Set<String> myOcids, String role) {
        String sessionId = UUID.randomUUID().toString();
        Session session = Session.create(sessionId, fingerprint, apiKey, myOcids, role);

        sessionRepository.save(session);
        log.info("Session created: sessionId={}, role={}", sessionId, role);

        return session;
    }

    /**
     * 세션을 조회하고 TTL을 갱신합니다 (Sliding Window).
     *
     * @param sessionId 세션 ID
     * @return 세션 (Optional)
     */
    public Optional<Session> getSessionAndRefresh(String sessionId) {
        Optional<Session> sessionOpt = sessionRepository.findById(sessionId);

        if (sessionOpt.isPresent()) {
            sessionRepository.refreshTtl(sessionId);
            log.debug("Session TTL refreshed: sessionId={}", sessionId);
        }

        return sessionOpt;
    }

    /**
     * 세션을 조회합니다 (TTL 갱신 없음).
     *
     * @param sessionId 세션 ID
     * @return 세션 (Optional)
     */
    public Optional<Session> getSession(String sessionId) {
        return sessionRepository.findById(sessionId);
    }

    /**
     * 세션을 삭제합니다 (로그아웃).
     *
     * @param sessionId 세션 ID
     */
    public void deleteSession(String sessionId) {
        sessionRepository.deleteById(sessionId);
        log.info("Session deleted: sessionId={}", sessionId);
    }

    /**
     * 세션 존재 여부를 확인합니다.
     *
     * @param sessionId 세션 ID
     * @return 존재 여부
     */
    public boolean existsSession(String sessionId) {
        return sessionRepository.existsById(sessionId);
    }
}
