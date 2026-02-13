package maple.expectation.service.v2.auth;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.annotation.ObservedTransaction;
import maple.expectation.global.error.exception.auth.SelfLikeNotAllowedException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.queue.like.AtomicLikeToggleExecutor;
import maple.expectation.infrastructure.queue.like.AtomicLikeToggleExecutor.ToggleResult;
import maple.expectation.infrastructure.security.AuthenticatedUser;
import maple.expectation.repository.v2.CharacterLikeRepository;
import maple.expectation.service.v2.GameCharacterService;
import maple.expectation.service.v2.LikeProcessor;
import maple.expectation.service.v2.OcidResolver;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import maple.expectation.service.v2.cache.LikeRelationBufferStrategy;
import maple.expectation.service.v2.like.realtime.LikeEventPublisher;
import maple.expectation.util.StringMaskingUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * 인증된 사용자의 좋아요 서비스 (Atomic Toggle 패턴)
 *
 * <h3>Issue #285: P0/P1 전면 리팩토링</h3>
 *
 * <ul>
 *   <li>P0-1: TOCTOU Race -> Lua Script Atomic Toggle
 *   <li>P0-2: Non-atomic dual write -> 단일 Lua Script
 *   <li>P0-3: Unlike 3-way non-atomic -> Lua Script + DB DELETE 비동기화
 *   <li>P0-4: Controller double-read -> Service에서 likeCount 직접 반환
 *   <li>P0-5: Sync DB DELETE -> batch scheduler 위임
 *   <li>P0-8: Layer violation -> Controller에서 비즈니스 로직 제거
 *   <li>P1-14: @Deprecated 삭제 (CLAUDE.md Section 5)
 * </ul>
 *
 * <h3>요청 처리 시 DB 호출: 0회 (Redis만 사용)</h3>
 *
 * <p>스케줄러가 배치로 DB에 동기화합니다.
 *
 * <h3>CLAUDE.md 준수</h3>
 *
 * <ul>
 *   <li>Section 5: No Deprecated
 *   <li>Section 12: LogicExecutor 패턴
 *   <li>Section 15: 람다 3줄 이내
 * </ul>
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
  private final GameCharacterService gameCharacterService;

  /** Issue #285: Lua Script Atomic Toggle (Redis 모드에서만 non-null) */
  @Nullable private final AtomicLikeToggleExecutor atomicToggle;

  /** Issue #278: 실시간 동기화 이벤트 발행자 */
  @Nullable private final LikeEventPublisher likeEventPublisher;

  public CharacterLikeService(
      LikeRelationBufferStrategy likeRelationBuffer,
      LikeBufferStrategy likeBufferStrategy,
      CharacterLikeRepository characterLikeRepository,
      OcidResolver ocidResolver,
      LikeProcessor likeProcessor,
      LogicExecutor executor,
      GameCharacterService gameCharacterService,
      @Nullable AtomicLikeToggleExecutor atomicToggle,
      @Nullable LikeEventPublisher likeEventPublisher) {
    this.likeRelationBuffer = likeRelationBuffer;
    this.likeBufferStrategy = likeBufferStrategy;
    this.characterLikeRepository = characterLikeRepository;
    this.ocidResolver = ocidResolver;
    this.likeProcessor = likeProcessor;
    this.executor = executor;
    this.gameCharacterService = gameCharacterService;
    this.atomicToggle = atomicToggle;
    this.likeEventPublisher = likeEventPublisher;
  }

  /**
   * 좋아요 토글 결과 DTO
   *
   * @param liked 토글 후 좋아요 상태 (true: 좋아요됨, false: 취소됨)
   * @param bufferDelta 현재 버퍼의 delta 값 (DB 반영 전)
   * @param likeCount 실시간 좋아요 수 (DB + buffer delta)
   */
  public record LikeToggleResult(boolean liked, long bufferDelta, long likeCount) {}

  /**
   * 캐릭터 좋아요 토글 (좋아요 <-> 취소)
   *
   * <h3>Issue #285 개선사항</h3>
   *
   * <ul>
   *   <li>Redis 모드: Lua Script Atomic Toggle (TOCTOU 원천 차단)
   *   <li>In-Memory 모드: 기존 Check-Then-Act (단일 인스턴스 안전)
   *   <li>likeCount를 Service에서 직접 계산 (Controller 비즈니스 로직 제거)
   *   <li>DB DELETE 제거 (batch scheduler 위임)
   * </ul>
   *
   * @param targetUserIgn 대상 캐릭터 닉네임
   * @param user 인증된 사용자
   * @return 토글 결과 (liked, bufferDelta, likeCount)
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

    // 3. 원자적 토글 실행 (Redis Lua Script 또는 In-Memory Fallback)
    boolean liked;
    long newDelta;

    // P2 Fix: null accountId fallback (pre-deploy sessions)
    String accountId = user.accountId();
    String effectiveAccountId = (accountId != null) ? accountId : user.fingerprint();

    if (accountId == null) {
      log.warn("[P2] Pre-deploy session detected: using fingerprint as accountId fallback");
    }

    if (atomicToggle != null) {
      // Redis 모드: Lua Script Atomic Toggle (P0-1/P0-2/P0-3 해결)
      ToggleResult result = executeAtomicToggle(effectiveAccountId, targetOcid, targetUserIgn);
      liked = result.liked();
      newDelta = result.newDelta();
    } else {
      // In-Memory 모드: 기존 로직 (단일 인스턴스에서 안전)
      LegacyToggleResult result =
          executeLegacyToggle(targetUserIgn, targetOcid, effectiveAccountId);
      liked = result.liked;
      newDelta = result.newDelta;
    }

    log.info(
        "{} buffered: targetIgn={}, accountId={}, newDelta={}",
        liked ? "Like" : "Unlike",
        targetUserIgn,
        StringMaskingUtils.maskAccountId(effectiveAccountId),
        newDelta);

    // 4. Scale-out 실시간 동기화 이벤트 발행 (Issue #278)
    publishLikeEvent(targetUserIgn, newDelta, liked);

    // 5. likeCount 계산 (P0-4 해결: Service에서 직접 반환)
    long likeCount = calculateEffectiveLikeCount(targetUserIgn, newDelta);

    return new LikeToggleResult(liked, newDelta, likeCount);
  }

  /**
   * Lua Script Atomic Toggle 실행 (Redis 모드)
   *
   * <p>DB에 이미 동기화된 관계도 확인합니다. Redis에 없고 DB에 있는 경우, DB 상태를 기반으로 unlike 처리.
   */
  private ToggleResult executeAtomicToggle(String accountId, String targetOcid, String userIgn) {
    ToggleResult result = atomicToggle.toggle(accountId, targetOcid, userIgn);

    if (result == null) {
      // Redis 장애 -> DB Fallback (Graceful Degradation)
      log.warn("[LikeService] Redis failure, DB fallback for toggle");
      return executeDbFallbackToggle(accountId, targetOcid, userIgn);
    }

    return result;
  }

  /** Redis 장애 시 DB Fallback Toggle */
  private ToggleResult executeDbFallbackToggle(
      String accountId, String targetOcid, String userIgn) {
    boolean existsInDb =
        characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, accountId);

    if (existsInDb) {
      // DB에 있으면 unlike 처리 (DB DELETE는 batch로)
      Long delta = likeProcessor.processUnlike(userIgn);
      return new ToggleResult(false, delta != null ? delta : 0L);
    } else {
      // DB에 없으면 like 처리
      Long delta = likeProcessor.processLike(userIgn);
      return new ToggleResult(true, delta != null ? delta : 0L);
    }
  }

  /** In-Memory 모드 Toggle (단일 인스턴스 전용) */
  private LegacyToggleResult executeLegacyToggle(
      String userIgn, String targetOcid, String accountId) {
    boolean currentlyLiked = checkLikeStatus(targetOcid, accountId);

    if (currentlyLiked) {
      likeRelationBuffer.removeRelation(accountId, targetOcid);
      Long delta = likeProcessor.processUnlike(userIgn);
      return new LegacyToggleResult(false, delta != null ? delta : 0L);
    } else {
      likeRelationBuffer.addRelation(accountId, targetOcid);
      Long delta = likeProcessor.processLike(userIgn);
      return new LegacyToggleResult(true, delta != null ? delta : 0L);
    }
  }

  private record LegacyToggleResult(boolean liked, long newDelta) {}

  /**
   * 실시간 좋아요 수 계산 (P0-4/P0-6 해결)
   *
   * <p>DB likeCount + buffer delta를 Service에서 직접 계산합니다. Controller가 별도로 JOIN FETCH할 필요 없음.
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @param newDelta 원자적 토글에서 반환된 새 delta
   * @return 실시간 좋아요 수 (음수 방지)
   */
  private long calculateEffectiveLikeCount(String userIgn, long newDelta) {
    long dbCount =
        gameCharacterService.getCharacterIfExist(userIgn).map(gc -> gc.getLikeCount()).orElse(0L);
    return Math.max(0, dbCount + newDelta);
  }

  /**
   * 실시간 좋아요 수 조회 (Like Status API용)
   *
   * <p>P0-8 해결: Controller에서 이동한 비즈니스 로직
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @return 실시간 좋아요 수 (DB + buffer delta)
   */
  public long getEffectiveLikeCount(String userIgn) {
    long dbCount =
        gameCharacterService
            .getCharacterIfExist(userIgn.trim())
            .map(gc -> gc.getLikeCount())
            .orElse(0L);
    Long bufferDelta = likeBufferStrategy.get(userIgn.trim());
    long delta = (bufferDelta != null) ? bufferDelta : 0L;
    return Math.max(0, dbCount + delta);
  }

  /** Scale-out 실시간 동기화 이벤트 발행 (Issue #278) */
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

  /** 현재 좋아요 상태 확인 (Buffer -> DB Fallback) */
  private boolean checkLikeStatus(String targetOcid, String accountId) {
    return resolveRelationStatus(accountId, targetOcid);
  }

  /** userIgn -> OCID 변환 (캐싱됨) */
  private String resolveOcid(String userIgn) {
    return ocidResolver.resolve(userIgn);
  }

  /**
   * Self-Like 검증
   *
   * @param myOcids 사용자가 소유한 캐릭터 OCID 목록
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
   * 특정 캐릭터에 대한 좋아요 여부 확인 (Buffer -> DB)
   *
   * @param targetUserIgn 대상 캐릭터 닉네임
   * @param accountId 넥슨 계정 식별자
   * @return 좋아요 여부
   */
  public boolean hasLiked(String targetUserIgn, String accountId) {
    String targetOcid = resolveOcid(targetUserIgn.trim());
    return resolveRelationStatus(accountId, targetOcid);
  }

  /**
   * 3단계 좋아요 상태 판정
   *
   * <ol>
   *   <li>Redis relations SET에 있으면 → liked (확정)
   *   <li>Redis unliked SET에 있으면 → unliked (확정)
   *   <li>둘 다 없으면 → cold start, DB fallback
   * </ol>
   */
  private boolean resolveRelationStatus(String accountId, String targetOcid) {
    // 1. Redis relations SET 확인
    Boolean existsInRelations = likeRelationBuffer.exists(accountId, targetOcid);
    if (existsInRelations != null && existsInRelations) {
      return true;
    }

    // 2. Redis unliked SET 확인 (명시적 unlike 추적)
    Boolean existsInUnliked = likeRelationBuffer.existsInUnliked(accountId, targetOcid);
    if (existsInUnliked != null && existsInUnliked) {
      return false;
    }

    // 3. 둘 다 없음 → cold start 또는 Redis 장애 → DB fallback
    return characterLikeRepository.existsByTargetOcidAndLikerAccountId(targetOcid, accountId);
  }
}
