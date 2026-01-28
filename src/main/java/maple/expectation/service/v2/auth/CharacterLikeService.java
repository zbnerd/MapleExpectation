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
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import maple.expectation.service.v2.cache.LikeRelationBufferStrategy;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import org.springframework.lang.Nullable;
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
 * <h3>Issue #278: Scale-out 실시간 동기화</h3>
 * <p>좋아요 토글 후 Pub/Sub 이벤트 발행 → 다른 인스턴스의 L1 캐시 무효화</p>
 *
 * <p>CLAUDE.md 섹션 17 준수: TieredCache 패턴, Graceful Degradation</p>
 */
@Slf4j
@Service
public class CharacterLikeService {

    private final LikeRelationBufferStrategy likeRelationBuffer;
    private final LikeBufferStrategy likeBufferStrategy;
    private final CharacterLikeRepository characterLikeRepository;
    private final OcidResolver ocidResolver;
    private final LikeProcessor likeProcessor;
    private final LogicExecutor executor;

    /**
     * Issue #278: 실시간 동기화 이벤트 발행자
     * <p>Optional 의존성: like.realtime.enabled=false 시 null</p>
     */
    @Nullable
    private final LikeEventPublisher likeEventPublisher;

    /**
     * 생성자 (LikeEventPublisher는 Optional 의존성)
     */
    public CharacterLikeService(
            LikeRelationBufferStrategy likeRelationBuffer,
            LikeBufferStrategy likeBufferStrategy,
            CharacterLikeRepository characterLikeRepository,
            OcidResolver ocidResolver,
            LikeProcessor likeProcessor,
            LogicExecutor executor,
            @Nullable LikeEventPublisher likeEventPublisher
    ) {
        this.likeRelationBuffer = likeRelationBuffer;
        this.likeBufferStrategy = likeBufferStrategy;
        this.characterLikeRepository = characterLikeRepository;
        this.ocidResolver = ocidResolver;
        this.likeProcessor = likeProcessor;
        this.executor = executor;
        this.likeEventPublisher = likeEventPublisher;
    }

    /**
     * 좋아요 토글 결과 DTO
     *
     * @param liked        토글 후 좋아요 상태 (true: 좋아요됨, false: 취소됨)
     * @param bufferDelta  현재 버퍼의 delta 값 (DB 반영 전)
     */
    public record LikeToggleResult(boolean liked, long bufferDelta) {}

    /**
     * 캐릭터 좋아요 토글 (좋아요 ↔ 취소)
     *
     * <p>흐름 (DB 호출 0회):
     * <ol>
     *   <li>OCID 조회 (캐싱됨)</li>
     *   <li>Self-Like 검증 (메모리)</li>
     *   <li>현재 좋아요 상태 확인</li>
     *   <li>토글: 좋아요 추가 또는 취소</li>
     *   <li>버퍼의 현재 delta 조회 (원자적)</li>
     * </ol>
     * </p>
     *
     * @param targetUserIgn 대상 캐릭터 닉네임
     * @param user          인증된 사용자
     * @return 토글 결과 (liked, bufferDelta)
     * @throws SelfLikeNotAllowedException 자신의 캐릭터에 좋아요 시도
     */
    @ObservedTransaction("service.v2.auth.CharacterLikeService.toggleLike")
    public LikeToggleResult toggleLike(String targetUserIgn, AuthenticatedUser user) {
        String cleanIgn = targetUserIgn.trim();
        TaskContext context = TaskContext.of("Like", "Toggle", cleanIgn);

        return executor.execute(() -> doToggleLike(cleanIgn, user), context);
    }

    private LikeToggleResult doToggleLike(String targetUserIgn, AuthenticatedUser user) {
        // 1. 대상 캐릭터의 OCID 조회 (캐싱됨)
        String targetOcid = resolveOcid(targetUserIgn);

        // 2. Self-Like 검증 (메모리)
        validateNotSelfLike(user.myOcids(), targetOcid);

        // 3. 현재 좋아요 상태 확인
        boolean currentlyLiked = checkLikeStatus(targetOcid, user.fingerprint());

        boolean liked;
        Long newDelta;
        if (currentlyLiked) {
            // 4a. 좋아요 취소
            removeFromBuffer(targetOcid, user.fingerprint());
            newDelta = likeProcessor.processUnlike(targetUserIgn);
            log.info("Unlike buffered: targetIgn={}, fingerprint={}..., newDelta={}",
                    targetUserIgn, user.fingerprint().substring(0, 8), newDelta);
            liked = false;
        } else {
            // 4b. 좋아요 추가
            addToBuffer(targetOcid, user.fingerprint());
            newDelta = likeProcessor.processLike(targetUserIgn);
            log.info("Like buffered: targetIgn={}, fingerprint={}..., newDelta={}",
                    targetUserIgn, user.fingerprint().substring(0, 8), newDelta);
            liked = true;
        }

        // increment가 반환한 새 delta 직접 사용 (별도 get 불필요)
        long delta = (newDelta != null) ? newDelta : 0L;

        // 5. Issue #278: Scale-out 실시간 동기화 이벤트 발행
        publishLikeEvent(targetUserIgn, delta, liked);

        return new LikeToggleResult(liked, delta);
    }

    /**
     * Scale-out 실시간 동기화 이벤트 발행 (Issue #278)
     *
     * <p>다른 인스턴스의 L1 캐시 무효화를 위한 Pub/Sub 이벤트 발행</p>
     * <p>likeEventPublisher가 null이면 단일 인스턴스 모드 (이벤트 발행 스킵)</p>
     * <p>이벤트 발행 실패 시에도 좋아요 기능은 정상 동작 (Graceful Degradation)</p>
     *
     * @param targetUserIgn 대상 캐릭터 닉네임 (캐시 키)
     * @param newDelta      버퍼의 새 delta 값
     * @param liked         좋아요 상태 (true: LIKE, false: UNLIKE)
     */
    private void publishLikeEvent(String targetUserIgn, long newDelta, boolean liked) {
        if (likeEventPublisher == null) {
            return;
        }

        if (liked) {
            likeEventPublisher.publishLike(targetUserIgn, newDelta);
        } else {
            likeEventPublisher.publishUnlike(targetUserIgn, newDelta);
        }
    }

    /**
     * 캐릭터에 좋아요를 누릅니다.
     *
     * @deprecated 토글 방식의 {@link #toggleLike} 사용 권장
     */
    @Deprecated
    @ObservedTransaction("service.v2.auth.CharacterLikeService.likeCharacter")
    public void likeCharacter(String targetUserIgn, AuthenticatedUser user) {
        String cleanIgn = targetUserIgn.trim();
        TaskContext context = TaskContext.of("Like", "Process", cleanIgn);

        executor.executeVoid(() -> doLikeCharacter(cleanIgn, user), context);
    }

    @Deprecated
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
     * 현재 좋아요 상태 확인 (L1 → L2 → DB)
     */
    private boolean checkLikeStatus(String targetOcid, String fingerprint) {
        // L1/L2 버퍼 확인
        Boolean existsInBuffer = likeRelationBuffer.exists(fingerprint, targetOcid);
        log.info("🔍 [LikeStatus] Buffer check: fingerprint={}..., targetOcid={}, existsInBuffer={}",
                fingerprint.substring(0, 8), targetOcid, existsInBuffer);

        if (existsInBuffer != null && existsInBuffer) {
            log.info("✅ [LikeStatus] Found in buffer");
            return true;
        }

        // DB 확인
        boolean existsInDb = characterLikeRepository.existsByTargetOcidAndLikerFingerprint(targetOcid, fingerprint);
        log.info("🔍 [LikeStatus] DB check: existsInDb={}", existsInDb);

        return existsInDb;
    }

    /**
     * L1/L2 버퍼에 관계 추가 (토글용 - 예외 없음)
     */
    private void addToBuffer(String targetOcid, String fingerprint) {
        Boolean isNew = likeRelationBuffer.addRelation(fingerprint, targetOcid);

        if (isNew == null) {
            // Redis 장애 시에도 진행 (배치에서 복구)
            log.warn("⚠️ [LikeService] Redis 장애로 관계 버퍼링 스킵");
        }
    }

    /**
     * 좋아요 관계 삭제 (버퍼 + DB)
     *
     * <p>Write-Behind 패턴에서 삭제는 즉시 처리:
     * <ul>
     *   <li>버퍼에서 삭제 (pending 동기화 방지)</li>
     *   <li>DB에서도 삭제 (이미 동기화된 경우)</li>
     * </ul>
     * </p>
     */
    private void removeFromBuffer(String targetOcid, String fingerprint) {
        // 1. 버퍼에서 삭제
        Boolean removed = likeRelationBuffer.removeRelation(fingerprint, targetOcid);

        if (removed == null) {
            log.warn("⚠️ [LikeService] Redis 장애로 버퍼 삭제 스킵");
        }

        // 2. DB에서도 삭제 (이미 동기화된 경우)
        executor.executeVoid(
                () -> characterLikeRepository.deleteByTargetOcidAndLikerFingerprint(targetOcid, fingerprint),
                TaskContext.of("Like", "DeleteFromDb", targetOcid)
        );
    }

    /**
     * L1/L2 버퍼에 관계 추가 (중복 시 예외)
     *
     * @deprecated 토글 방식의 {@link #addToBuffer} 사용 권장
     */
    @Deprecated
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
