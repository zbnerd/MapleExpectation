package maple.expectation.aop.aspect;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class BufferedLikeAspect {

    private final LikeBufferStorage likeBufferStorage;

    @Around("@annotation(maple.expectation.aop.annotation.BufferedLike)")
    public Object doBuffer(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 첫 번째 인자(userIgn) 가져오기
        String userIgn = (String) joinPoint.getArgs()[0];

        // 2. [핵심] 실제 DB 반영 로직을 실행하지 않고 버퍼만 증가시킴
        likeBufferStorage.getCounter(userIgn).incrementAndGet();
        
        log.debug("📥 [AOP Buffering] 좋아요 요청이 버퍼에 기록되었습니다: {}", userIgn);

        // 3. proceed()를 호출하지 않으므로 DatabaseLikeProcessor 로직은 스킵됨!
        return null; 
    }
}