package maple.expectation.service.v2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캐릭터 비동기 처리 서비스
 *
 * <p>책임:
 *
 * <ul>
 *   <li>캐릭터 기본 정보 비동기 DB 저장
 *   <li>@Async AOP 프록시 활성화를 위해 별도 빈으로 분리
 * </ul>
 *
 * <p>Note: GameCharacterService에서 self-invocation 문제를 방지하기 위해 독립 빈으로 운영
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterAsyncService {

  private final GameCharacterRepository gameCharacterRepository;
  private final LogicExecutor executor;

  /**
   * 캐릭터 기본 정보 비동기 저장 (DB + 캐시 갱신)
   *
   * <p>expectation-sequence-diagram Phase 7: 비동기 DB 저장 (Background)
   *
   * <p>별도 빈으로 분리하여 @Async AOP 프록시 활성화
   */
  @Async
  @Transactional
  public void saveCharacterBasicInfoAsync(GameCharacter character) {
    executor.executeVoid(
        () -> {
          // DB 저장
          gameCharacterRepository.save(character);
          log.info("✅ [Async] 캐릭터 기본 정보 DB 저장 완료: {}", character.getUserIgn());
        },
        TaskContext.of("DB", "SaveBasicInfoAsync", character.getUserIgn()));
  }
}
