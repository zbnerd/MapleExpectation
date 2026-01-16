package maple.expectation.service.v2.impl;

import maple.expectation.aop.annotation.BufferedLike;
import maple.expectation.service.v2.LikeProcessor;
import org.springframework.stereotype.Component;

/**
 * 좋아요 처리 구현체 (Buffer-First Pattern)
 *
 * <h3>Issue #119 해결: 순환 참조 제거 및 Dead Code 정리</h3>
 *
 * <h4>설계 의도</h4>
 * <p>{@code @BufferedLike} AOP가 요청을 가로채어 Redis 버퍼에 저장합니다.
 * AOP에서 proceed()를 호출하지 않으므로 메서드 본문은 실행되지 않습니다.</p>
 *
 * <h4>실제 DB 반영</h4>
 * <p>버퍼의 데이터는 {@link maple.expectation.service.v2.LikeSyncService}가
 * 스케줄러를 통해 배치로 DB에 반영합니다.</p>
 *
 * <h4>변경 사항 (Issue #119)</h4>
 * <ul>
 *   <li>{@code @Lazy} 제거: 순환 참조의 근본 원인 해결 (DIP 준수)</li>
 *   <li>GameCharacterService 의존성 제거: AOP가 처리하므로 불필요</li>
 *   <li>{@code @Transactional} 제거: 메서드 본문이 실행되지 않으므로 불필요</li>
 * </ul>
 *
 * @see BufferedLike
 * @see maple.expectation.aop.aspect.BufferedLikeAspect
 */
@Component
public class DatabaseLikeProcessor implements LikeProcessor {

    @Override
    @BufferedLike
    public void processLike(String userIgn) {
        // AOP intercepts and buffers - method body never executes (by design)
        // BufferedLikeAspect가 요청을 가로채어 버퍼에 추가하고 반환
        // proceed()를 호출하지 않으므로 이 본문은 실행되지 않음
    }
}
