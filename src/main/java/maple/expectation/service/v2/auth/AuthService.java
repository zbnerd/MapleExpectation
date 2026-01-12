package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.controller.dto.auth.LoginRequest;
import maple.expectation.controller.dto.auth.LoginResponse;
import maple.expectation.domain.Session;
import maple.expectation.external.NexonAuthClient;
import maple.expectation.external.dto.v2.CharacterListResponse;
import maple.expectation.global.error.exception.auth.InvalidApiKeyException;
import maple.expectation.global.error.exception.auth.CharacterNotOwnedException;
import maple.expectation.global.security.FingerprintGenerator;
import maple.expectation.global.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
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

    @Value("${auth.admin.allowlist:}")
    private String adminAllowlist;

    /**
     * 로그인 처리
     *
     * @param request 로그인 요청 (apiKey, userIgn)
     * @return 로그인 응답 (accessToken, expiresIn, role)
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

        // 5. ADMIN 여부 판별
        String role = isAdmin(fingerprint) ? Session.ROLE_ADMIN : Session.ROLE_USER;

        // 6. 세션 생성
        Session session = sessionService.createSession(fingerprint, apiKey, myOcids, role);

        // 7. JWT 발급
        String accessToken = jwtTokenProvider.generateToken(
            session.sessionId(),
            session.fingerprint(),
            session.role()
        );

        log.info("Login successful: userIgn={}, role={}", userIgn, role);

        return LoginResponse.of(
            accessToken,
            jwtTokenProvider.getExpirationSeconds(),
            role
        );
    }

    /**
     * 로그아웃 처리
     *
     * @param sessionId 세션 ID
     */
    public void logout(String sessionId) {
        sessionService.deleteSession(sessionId);
        log.info("Logout successful: sessionId={}", sessionId);
    }

    /**
     * ADMIN 여부 판별 (fingerprint allowlist 기반)
     */
    private boolean isAdmin(String fingerprint) {
        if (adminAllowlist == null || adminAllowlist.isBlank()) {
            return false;
        }

        Set<String> allowedFingerprints = Arrays.stream(adminAllowlist.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        return allowedFingerprints.contains(fingerprint);
    }
}
