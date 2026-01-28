package maple.expectation.repository.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.RefreshToken;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 기반 Refresh Token 저장소 (Issue #279)
 *
 * <p>저장 구조:
 * <ul>
 *   <li>Token: refresh:{refreshTokenId} → JSON (String)</li>
 *   <li>Family Index: refresh:family:{familyId} → Set&lt;refreshTokenId&gt;</li>
 *   <li>Session Index: refresh:session:{sessionId} → Set&lt;refreshTokenId&gt;</li>
 * </ul>
 * </p>
 *
 * <p>TTL 정책:
 * <ul>
 *   <li>Token TTL: 7일 (auth.refresh-token.expiration)</li>
 *   <li>Family/Session Index TTL: 7일 (토큰과 동일)</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisRefreshTokenRepository {

    private static final String KEY_PREFIX = "refresh:";
    private static final String FAMILY_KEY_PREFIX = "refresh:family:";
    private static final String SESSION_KEY_PREFIX = "refresh:session:";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    @Value("${auth.refresh-token.expiration}")
    private long refreshTokenTtlSeconds;

    /**
     * Refresh Token 저장
     *
     * @param token 저장할 Refresh Token
     */
    public void save(RefreshToken token) {
        executor.executeVoid(() -> {
            String key = buildTokenKey(token.refreshTokenId());
            String json = serializeToken(token);

            // 1. 토큰 저장
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(json, Duration.ofSeconds(refreshTokenTtlSeconds));

            // 2. Family Index에 추가 (탈취 감지 시 일괄 삭제용)
            String familyKey = buildFamilyKey(token.familyId());
            RSet<String> familySet = redissonClient.getSet(familyKey);
            familySet.add(token.refreshTokenId());
            familySet.expire(Duration.ofSeconds(refreshTokenTtlSeconds));

            // 3. Session Index에 추가 (로그아웃 시 일괄 삭제용)
            String sessionKey = buildSessionKey(token.sessionId());
            RSet<String> sessionSet = redissonClient.getSet(sessionKey);
            sessionSet.add(token.refreshTokenId());
            sessionSet.expire(Duration.ofSeconds(refreshTokenTtlSeconds));

        }, TaskContext.of("RefreshToken", "Save", token.refreshTokenId()));

        log.debug("RefreshToken saved: tokenId={}, familyId={}",
                  token.refreshTokenId(), token.familyId());
    }

    /**
     * Refresh Token 조회
     *
     * @param refreshTokenId Refresh Token ID
     * @return RefreshToken (Optional)
     */
    public Optional<RefreshToken> findById(String refreshTokenId) {
        return executor.executeOrDefault(
            () -> doFindById(refreshTokenId),
            Optional.empty(),
            TaskContext.of("RefreshToken", "FindById", refreshTokenId)
        );
    }

    private Optional<RefreshToken> doFindById(String refreshTokenId) {
        String key = buildTokenKey(refreshTokenId);
        RBucket<String> bucket = redissonClient.getBucket(key);
        String json = bucket.get();

        if (json == null) {
            return Optional.empty();
        }

        return Optional.of(deserializeToken(json));
    }

    /**
     * Refresh Token 사용 처리 (Token Rotation)
     *
     * <p>기존 토큰의 used 필드를 true로 설정하여 재사용 감지 가능하게 함</p>
     *
     * @param refreshTokenId Refresh Token ID
     */
    public void markAsUsed(String refreshTokenId) {
        executor.executeVoid(() -> {
            String key = buildTokenKey(refreshTokenId);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();

            if (json != null) {
                RefreshToken token = deserializeToken(json);
                RefreshToken usedToken = token.markAsUsed();
                bucket.set(serializeToken(usedToken), bucket.remainTimeToLive(),
                          java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }, TaskContext.of("RefreshToken", "MarkAsUsed", refreshTokenId));

        log.debug("RefreshToken marked as used: tokenId={}", refreshTokenId);
    }

    /**
     * Family 전체 무효화 (탈취 감지 시)
     *
     * @param familyId Token Family ID
     */
    public void deleteByFamilyId(String familyId) {
        executor.executeVoid(() -> {
            String familyKey = buildFamilyKey(familyId);
            RSet<String> familySet = redissonClient.getSet(familyKey);
            Set<String> tokenIds = familySet.readAll();

            // Family에 속한 모든 토큰 삭제
            for (String tokenId : tokenIds) {
                String tokenKey = buildTokenKey(tokenId);
                redissonClient.getBucket(tokenKey).delete();
            }

            // Family Index 삭제
            familySet.delete();

        }, TaskContext.of("RefreshToken", "DeleteByFamily", familyId));

        log.warn("Token family invalidated (possible token theft): familyId={}", familyId);
    }

    /**
     * 세션의 모든 Refresh Token 삭제 (로그아웃 시)
     *
     * @param sessionId 세션 ID
     */
    public void deleteBySessionId(String sessionId) {
        executor.executeVoid(() -> {
            String sessionKey = buildSessionKey(sessionId);
            RSet<String> sessionSet = redissonClient.getSet(sessionKey);
            Set<String> tokenIds = sessionSet.readAll();

            // 세션에 연결된 모든 토큰 삭제
            for (String tokenId : tokenIds) {
                String tokenKey = buildTokenKey(tokenId);
                RBucket<String> bucket = redissonClient.getBucket(tokenKey);
                String json = bucket.get();

                if (json != null) {
                    RefreshToken token = deserializeToken(json);
                    // Family Index에서도 제거
                    String familyKey = buildFamilyKey(token.familyId());
                    redissonClient.getSet(familyKey).remove(tokenId);
                }

                bucket.delete();
            }

            // Session Index 삭제
            sessionSet.delete();

        }, TaskContext.of("RefreshToken", "DeleteBySession", sessionId));

        log.debug("RefreshTokens deleted for session: sessionId={}", sessionId);
    }

    /**
     * 단일 Refresh Token 삭제
     *
     * @param refreshTokenId Refresh Token ID
     */
    public void deleteById(String refreshTokenId) {
        executor.executeVoid(() -> {
            String tokenKey = buildTokenKey(refreshTokenId);
            redissonClient.getBucket(tokenKey).delete();
        }, TaskContext.of("RefreshToken", "DeleteById", refreshTokenId));
    }

    private String buildTokenKey(String refreshTokenId) {
        return KEY_PREFIX + refreshTokenId;
    }

    private String buildFamilyKey(String familyId) {
        return FAMILY_KEY_PREFIX + familyId;
    }

    private String buildSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String serializeToken(RefreshToken token) {
        return executor.execute(
            () -> objectMapper.writeValueAsString(token),
            TaskContext.of("RefreshToken", "Serialize", token.refreshTokenId())
        );
    }

    private RefreshToken deserializeToken(String json) {
        return executor.execute(
            () -> objectMapper.readValue(json, RefreshToken.class),
            TaskContext.of("RefreshToken", "Deserialize",
                          json.length() > 30 ? json.substring(0, 30) : json)
        );
    }
}
