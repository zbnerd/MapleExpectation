package maple.expectation.monitoring.ai.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Z.ai GLM-4.7 Configuration (Issue #251)
 *
 * <h3>Z.ai OpenAI-Compatible API</h3>
 *
 * <ul>
 *   <li>Base URL: https://api.z.ai/api/paas/v4
 *   <li>Model: glm-4.7 (또는 GLM-4.7)
 *   <li>Protocol: OpenAI Chat Completions 호환
 * </ul>
 *
 * <h4>LangChain4j Integration</h4>
 *
 * <pre>
 * OpenAiChatModel.builder()
 *     .baseUrl("https://api.z.ai/api/paas/v4")
 *     .apiKey("${Z_AI_API_KEY}")
 *     .modelName("glm-4.7")
 *     .build()
 * </pre>
 *
 * @see <a href="https://docs.z.ai/api-reference/introduction">Z.ai API Reference</a>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "langchain4j.glm-4.chat-model.api-key")
public class ZAiConfiguration {

  @Value("${langchain4j.glm-4.chat-model.base-url}")
  private String baseUrl;

  @Value("${langchain4j.glm-4.chat-model.api-key:}")
  private String apiKey;

  @Value("${langchain4j.glm-4.chat-model.model-name:glm-4.7}")
  private String modelName;

  @Value("${langchain4j.glm-4.chat-model.timeout:60s}")
  private String timeout;

  @Value("${langchain4j.glm-4.chat-model.log-requests:false}")
  private boolean logRequests;

  @Value("${langchain4j.glm-4.chat-model.log-responses:false}")
  private boolean logResponses;

  /**
   * Z.ai GLM-4.7 ChatLanguageModel (Primary)
   *
   * <p>OpenAI-compatible API를 통해 Z.ai의 GLM-4.7 모델을 사용합니다.
   *
   * <p><strong>주의:</strong> API 키가 미설정이면 빈 문자열로 초기화되며, AiSreService의 Fallback 체인으로 연결됩니다.
   *
   * @return Z.ai GLM-4.7 ChatLanguageModel
   */
  @Bean
  @ConditionalOnProperty(name = "langchain4j.glm-4.chat-model.api-key")
  public ChatLanguageModel zAiChatModel() {
    log.info("[Z.ai] GLM-4.7 모델 초기화: baseUrl={}, model={}", baseUrl, modelName);

    return OpenAiChatModel.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .timeout(java.time.Duration.parse("PT" + timeout))
        .logRequests(logRequests)
        .logResponses(logResponses)
        .build();
  }
}
