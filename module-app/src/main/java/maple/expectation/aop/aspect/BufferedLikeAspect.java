package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.cache.LikeBufferStrategy;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 좋아요 버퍼링 AOP (Issue #285: P1-13 구체 의존 제거)
 *
 * <p>LikeBufferStrategy 인터페이스에 의존하여 In-Memory/Redis 모드 모두 지원
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class BufferedLikeAspect {

  private final LikeBufferStrategy likeBufferStrategy;

  @Around("@annotation(maple.expectation.aop.annotation.BufferedLike) && args(userIgn, ..)")
  public Object doBuffer(ProceedingJoinPoint joinPoint, String userIgn) throws Throwable {
    likeBufferStrategy.increment(userIgn, 1);
    log.debug("[AOP Buffering] Like request buffered: {}", userIgn);
    return null;
  }
}
