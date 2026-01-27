package maple.expectation.service.v2.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.error.exception.auth.DuplicateLikeException;
import maple.expectation.global.error.exception.auth.SelfLikeNotAllowedException;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.security.AuthenticatedUser;
import maple.expectation.repository.v2.CharacterLikeRepository;
import maple.expectation.service.v2.LikeProcessor;
import maple.expectation.service.v2.OcidResolver;
import maple.expectation.service.v2.cache.LikeRelationBufferStrategy;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 인증된 사용자의 좋아요 서비스 (버퍼링 패턴)
 *
 * <p>기존 LikeSyncService와 동일한 L1/L2/L3 계층 구조:
 * <ul>
 *   <li>L1 (Caffeine): 로컬 빠른 중복 체크</li>
 *   <li>L2 (Redis RSet): 분산 환경 중복 체크 + 버퍼링</li>
 *   <li>L3 (DB): 배치 동기화로 영구 저장</li>
 * </ul>
 * </p>
 *
 * <p>요청 처리 시 DB 호출: 0회 (Redis만 사용)
 * 스케줄러가 배치로 DB에 동기화</p>
 *
 * <p>CLAUDE.md 섹션 17 준수: TieredCache 패턴, Graceful Degradation</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterLikeService {

    private final LikeRelationBufferStrategy likeRelationBuffer;
    private final CharacterLikeRepository characterLikeRepository;
    private final OcidResolver ocidResolver;
    private final LikeProcessor likeProcessor;
    private final LogicExecutor executor;

    /**
     * 캐릭터에 좋아요를 누릅니다.
     *
     * <p>흐름 (DB 호출 0회):
     * <ol>
     *   <li>OCID 조회 (캐싱됨)</li>
     *   <li>Self-Like 검증 (메모리)</li>
     *   <li>L1/L2 중복 검사 + 버퍼 등록</li>
     *   <li>likeCount 버퍼 증가 (@BufferedLike)</li>
     * </ol>
     * </p>
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @param user          인증된 사용자
     * @throws SelfLikeNotAllowedException 자신의 캐릭터에 좋아요 시도
     * @throws DuplicateLikeException      이미 좋아요를 누른 경우
     */
    @ObservedTransaction("service.v2.auth.CharacterLikeService.likeCharacter")
    public void likeCharacter(String targetUserIgn, AuthenticatedUser user) {
        String cleanIgn = targetUserIgn.trim();
        TaskContext context = TaskContext.of("Like", "Process", cleanIgn);

        executor.executeVoid(() -> doLikeCharacter(cleanIgn, user), context);
    }

    private void doLikeCharacter(String targetUserIgn, AuthenticatedUser user) {
        // 1. 대상 캐릭터의 OCID 조회 (캐싱됨)
        String targetOcid = resolveOcid(targetUserIgn);

        // 2. Self-Like 검증 (메모리)
        validateNotSelfLike(user.myOcids(), targetOcid);

        // 3. L1/L2 중복 검사 + 버퍼 등록 (DB 호출 없음!)
        addToBufferOrThrow(targetOcid, user.fingerprint());

        // 4. likeCount 버퍼 증가 (@BufferedLike → Caffeine)
        likeProcessor.processLike(targetUserIgn);

        log.info("Like buffered: targetIgn={}, fingerprint={}...",
                targetUserIgn, user.fingerprint().substring(0, 8));
    }

    /**
     * L1/L2 버퍼에 관계 추가 (중복 시 예외)
     *
     * <p>Graceful Degradation:
     * Redis 장애 시 DB Fallback으로 처리</p>
     */
    private void addToBufferOrThrow(String targetOcid, String fingerprint) {
        Boolean isNew = likeRelationBuffer.addRelation(fingerprint, targetOcid);

        if (isNew == null) {
            // Redis 장애 → DB Fallback
            log.warn("⚠️ [LikeService] Redis 장애, DB Fallback 사용");
            handleRedisFailureFallback(targetOcid, fingerprint);
            return;
        }

        if (!isNew) {
            log.debug("Duplicate like detected: targetOcid={}", targetOcid);
            throw new DuplicateLikeException();
        }
    }

    /**
     * Redis 장애 시 DB Fallback
     * CLAUDE.md 섹션 17: Graceful Degradation
     */
    private void handleRedisFailureFallback(String targetOcid, String fingerprint) {
        // DB에서 중복 확인
        boolean exists = characterLikeRepository.existsByTargetOcidAndLikerFingerprint(targetOcid, fingerprint);
        if (exists) {
            throw new DuplicateLikeException();
        }
        // Redis 장애 상태에서는 관계를 임시로 저장하지 않음
        // 스케줄러가 복구 후 처리
        log.warn("⚠️ [LikeService] Redis 장애로 관계 버퍼링 스킵 (likeCount만 증가)");
    }

    /**
     * userIgn → OCID 변환 (DB 조회, NexonAPI 호출 없음)
     */
    private String resolveOcid(String userIgn) {
        return ocidResolver.resolve(userIgn);
    }

    /**
     * Self-Like 검증
     *
     * @param myOcids    사용자가 소유한 캐릭터 OCID 목록
     * @param targetOcid 대상 캐릭터 OCID
     * @throws SelfLikeNotAllowedException 자신의 캐릭터인 경우
     */
    private void validateNotSelfLike(Set<String> myOcids, String targetOcid) {
        if (myOcids != null && myOcids.contains(targetOcid)) {
            log.warn("Self-like attempt detected: targetOcid={}", targetOcid);
            throw new SelfLikeNotAllowedException();
        }
    }

    /**
     * 특정 캐릭터에 대한 좋아요 여부 확인 (L1 → L2 → DB)
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @param fingerprint   계정의 fingerprint
     * @return 좋아요 여부
     */
    public boolean hasLiked(String targetUserIgn, String fingerprint) {
        String targetOcid = resolveOcid(targetUserIgn.trim());

        // L1/L2 체크
        Boolean existsInBuffer = likeRelationBuffer.exists(fingerprint, targetOcid);
        if (existsInBuffer != null && existsInBuffer) {
            return true;
        }

        // L3 (DB) 체크 - 이미 동기화된 데이터
        return characterLikeRepository.existsByTargetOcidAndLikerFingerprint(targetOcid, fingerprint);
    }
}
