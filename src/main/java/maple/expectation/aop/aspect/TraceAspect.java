package maple.expectation.aop.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Aspect
@Component
public class TraceAspect {

    // 호출 깊이를 스레드별로 저장 (들여쓰기용)
    private final ThreadLocal<Integer> depthHolder = ThreadLocal.withInitial(() -> 0);

    // @TraceLog가 붙은 메서드 OR @TraceLog가 붙은 클래스 내부의 메서드 대상
    @Pointcut("@annotation(maple.expectation.aop.annotation.TraceLog) || @within(maple.expectation.aop.annotation.TraceLog)")
    public void traceTarget() {}

    @Around("traceTarget()")
    public Object doTrace(ProceedingJoinPoint joinPoint) throws Throwable {
        int depth = depthHolder.get();
        String indent = getIndent(depth);
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        
        // 매개변수 값 가져오기 (배열이나 리스트도 보기 좋게 변환)
        String args = Arrays.stream(joinPoint.getArgs())
                .map(arg -> {
                    if (arg == null) return "null";
                    if (arg instanceof byte[]) return "byte[" + ((byte[]) arg).length + "]"; // 바이트 배열은 길이만
                    return arg.toString(); // 필요 시 arg.toString()이 너무 길면 자르는 로직 추가 가능
                })
                .collect(Collectors.joining(", "));

        // ▶ 시작 로그
        log.info("{}--> [START] {}.{}(args: [{}])", indent, className, methodName, args);

        // 깊이 증가
        depthHolder.set(depth + 1);
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        Object result = null;
        try {
            // 실제 메서드 실행
            result = joinPoint.proceed();
            return result;
        } catch (Throwable e) {
            // 예외 발생 시 로그
            log.error("{}<X- [EXCEPTION] {}.{} throws {}", indent, className, methodName, e.getClass().getSimpleName());
            throw e;
        } finally {
            stopWatch.stop();
            // 깊이 원복
            depthHolder.set(depth);
            
            // 리턴값 문자열 처리 (너무 길면 요약)
            String resultString = "void";
            if (result != null) {
                resultString = result.toString();
                if (result instanceof java.util.List) {
                    resultString = "List(size=" + ((java.util.List<?>) result).size() + ")";
                } else if (resultString.length() > 100) {
                    resultString = resultString.substring(0, 100) + "...";
                }
            }

            // ◀ 종료 로그
            log.info("{}<-- [END] {}.{} (Return: {}) [{}ms]", 
                    indent, className, methodName, resultString, stopWatch.getTotalTimeMillis());
        }
    }

    // 깊이에 따른 화살표 생성 (예: | |--> )
    private String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("|  ");
        }
        return sb.toString();
    }
}