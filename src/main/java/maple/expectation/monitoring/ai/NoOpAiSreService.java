package maple.expectation.monitoring.ai;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.monitoring.ai.AiSreService.AiAnalysisResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * AI SRE 비활성화 시 No-op 구현체 (Issue #251)
 *
 * <p>ai.sre.enabled=false 일 때 사용되는 스텁 구현체입니다.</p>
 * <p>AI 분석 없이 기본 알림만 전송하도록 합니다.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.sre.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAiSreService {

    /**
     * AI 분석 비활성화 상태 - 항상 empty 반환
     */
    public CompletableFuture<Optional<AiAnalysisResult>> analyzeErrorAsync(Throwable exception) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * AI 분석 비활성화 상태 - 항상 empty 반환
     */
    public Optional<AiAnalysisResult> analyzeError(Throwable exception) {
        log.debug("[NoOpAiSre] AI SRE 비활성화 상태 - 분석 스킵: {}", exception.getClass().getSimpleName());
        return Optional.empty();
    }
}
