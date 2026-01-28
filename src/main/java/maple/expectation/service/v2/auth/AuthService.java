package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.controller.dto.auth.TokenResponse;
import maple.expectation.domain.RefreshToken;
import maple.expectation.domain.Session;
import maple.expectation.external.NexonAuthClient;
import maple.expectation.external.dto.v2.CharacterListResponse;
import maple.expectation.global.error.exception.auth.InvalidApiKeyException;
import maple.expectation.global.error.exception.auth.CharacterNotOwnedException;
import maple.expectation.global.error.exception.auth.SessionNotFoundException;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 인증 서비스 (Facade)
 *
 * <p>로그인 흐름:
 * <ol>
 *   <li>Nexon API로 캐릭터 목록 조회 (API Key 검증)</li>
 *   <li>userIgn이 캐릭터 목록에 있는지 확인 (소유권 검증)</li>
 *   <li>Fingerprint 생성 (HMAC-SHA256)</li>
 *   <li>ADMIN 여부 판별 (fingerprint allowlist)</li>
 *   <li>Redis 세션 생성</li>
 *   <li>JWT 토큰 발급</li>
 *   <li>Refresh Token 발급 (Issue #279)</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final NexonAuthClient nexonAuthClient;
    private final FingerprintGenerator fingerprintGenerator;
    private final SessionService sessionService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminService adminService;
    private final RefreshTokenService refreshTokenService;

    /**
     * 로그인 처리
     *
     * @param request 로그인 요청 (apiKey, userIgn)
     * @return 로그인 응답 (accessToken, expiresIn, role, refreshToken)
     * @throws InvalidApiKeyException    API Key가 유효하지 않은 경우
     * @throws CharacterNotOwnedException 캐릭터가 사용자 소유가 아닌 경우
     */
    public LoginResponse login(LoginRequest request) {
        String apiKey = request.apiKey();
        String userIgn = request.userIgn();

        // 1. Nexon API로 캐릭터 목록 조회
        CharacterListResponse characterList = nexonAuthClient.getCharacterList(apiKey)
            .orElseThrow(InvalidApiKeyException::new);

        // 2. userIgn이 캐릭터 목록에 있는지 확인
        List<CharacterListResponse.CharacterInfo> characters = characterList.getAllCharacters();
        boolean ownsCharacter = characters.stream()
            .anyMatch(c -> c.characterName().equalsIgnoreCase(userIgn));

        if (!ownsCharacter) {
            throw new CharacterNotOwnedException(userIgn);
        }

        // 3. 모든 캐릭터 OCID 수집
        Set<String> myOcids = characters.stream()
            .map(CharacterListResponse.CharacterInfo::ocid)
            .collect(Collectors.toSet());

        // 4. Fingerprint 생성
        String fingerprint = fingerprintGenerator.generate(apiKey);

        // 5. ADMIN 여부 판별 (AdminService에서 Bootstrap + Redis 확인)
        String role = adminService.isAdmin(fingerprint) ? Session.ROLE_ADMIN : Session.ROLE_USER;

        // 6. 세션 생성
        Session session = sessionService.createSession(fingerprint, apiKey, myOcids, role);

        // 7. JWT 발급
        String accessToken = jwtTokenProvider.generateToken(
            session.sessionId(),
            session.fingerprint(),
            session.role()
        );

        // 8. Refresh Token 발급 (Issue #279)
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(
            session.sessionId(),
            session.fingerprint()
        );

        // fingerprint는 로컬에서만 로깅 (운영환경 보안)
        if (log.isDebugEnabled()) {
            log.debug("Login successful: userIgn={}, role={}, fingerprint={}", userIgn, role, fingerprint);
        } else {
            log.info("Login successful: userIgn={}, role={}", userIgn, role);
        }

        return LoginResponse.of(
            accessToken,
            jwtTokenProvider.getExpirationSeconds(),
            role,
            fingerprint,
            refreshToken.refreshTokenId(),
            refreshTokenService.getExpirationSeconds()
        );
    }

    /**
     * 로그아웃 처리
     *
     * @param sessionId 세션 ID
     */
    public void logout(String sessionId) {
        // Refresh Token 삭제 (Issue #279)
        refreshTokenService.deleteBySessionId(sessionId);
        // 세션 삭제
        sessionService.deleteSession(sessionId);
        log.info("Logout successful: sessionId={}", sessionId);
    }

    /**
     * Token Refresh 처리 (Issue #279)
     *
     * <p>Token Rotation 패턴:
     * <ol>
     *   <li>기존 Refresh Token 검증</li>
     *   <li>연결된 세션 유효성 확인</li>
     *   <li>새 Access Token + Refresh Token 발급</li>
     *   <li>기존 Refresh Token 무효화</li>
     * </ol>
     * </p>
     *
     * @param refreshTokenId 기존 Refresh Token ID
     * @return 새 TokenResponse (accessToken, refreshToken)
     * @throws SessionNotFoundException 세션이 만료된 경우
     */
    public TokenResponse refresh(String refreshTokenId) {
        // 1. Token Rotation (기존 토큰 무효화 + 새 토큰 발급)
        RefreshToken newRefreshToken = refreshTokenService.rotateRefreshToken(refreshTokenId);

        // 2. 세션 유효성 확인
        Session session = sessionService.getSession(newRefreshToken.sessionId())
            .orElseThrow(() -> {
                // 세션이 만료되었으면 새로 발급된 Refresh Token도 정리
                refreshTokenService.deleteBySessionId(newRefreshToken.sessionId());
                return new SessionNotFoundException();
            });

        // 3. 세션 TTL 갱신 (Sliding Window)
        sessionService.refreshSession(session.sessionId());

        // 4. 새 Access Token 발급
        String newAccessToken = jwtTokenProvider.generateToken(
            session.sessionId(),
            session.fingerprint(),
            session.role()
        );

        log.info("Token refreshed: sessionId={}, newRefreshTokenId={}",
                 session.sessionId(), newRefreshToken.refreshTokenId());

        return TokenResponse.of(
            newAccessToken,
            jwtTokenProvider.getExpirationSeconds(),
            newRefreshToken.refreshTokenId(),
            refreshTokenService.getExpirationSeconds()
        );
    }
}
