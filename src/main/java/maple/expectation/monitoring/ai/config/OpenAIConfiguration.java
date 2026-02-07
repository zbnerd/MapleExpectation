package maple.expectation.monitoring.ai.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * OpenAI GPT Configuration for AI SRE (Issue #251)
 *
 * <h3>OpenAI API</h3>
 *
 * <ul>
 *   <li>Base URL: https://api.openai.com/v1
 *   <li>Model: gpt-4o-mini (default) or gpt-4o
 *   <li>Protocol: OpenAI Chat Completions API
 * </ul>
 *
 * <h4>LangChain4j Integration</h4>
 *
 * <pre>
 * OpenAiChatModel.builder()
 *     .apiKey("${OPENAI_API_KEY}")
 *     .modelName("gpt-4o-mini")
 *     .timeout(60s)
 *     .build()
 * </pre>
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/chat/create">OpenAI API
 *     Reference</a>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "ai.sre.enabled", havingValue = "true")
public class OpenAIConfiguration {

  @Value("${langchain4j.open-ai.chat-model.api-key:}")
  private String apiKey;

  @Value("${langchain4j.open-ai.chat-model.model-name:gpt-4o-mini}")
  private String modelName;

  @Value("${langchain4j.open-ai.chat-model.timeout:60s}")
  private String timeout;

  @Value("${langchain4j.open-ai.chat-model.log-requests:false}")
  private boolean logRequests;

  @Value("${langchain4j.open-ai.chat-model.log-responses:false}")
  private boolean logResponses;

  /**
   * OpenAI GPT ChatLanguageModel (Primary)
   *
   * <p>OpenAI의 GPT-4o-mini 모델을 사용합니다.
   *
   * <p><strong>주의:</strong> API 키가 미설정이면 빈 문자열로 초기화되며, AiSreService의 Fallback 체인으로 연결됩니다.
   *
   * @return OpenAI GPT ChatLanguageModel
   */
  @Bean
  @Primary
  @ConditionalOnProperty(name = "langchain4j.open-ai.chat-model.api-key", matchIfMissing = false)
  public ChatLanguageModel openAIChatModel() {
    log.info("[OpenAI] GPT 모델 초기화: model={}", modelName);

    return OpenAiChatModel.builder()
        .apiKey(apiKey)
        .modelName(modelName)
        .timeout(java.time.Duration.parse("PT" + timeout))
        .logRequests(logRequests)
        .logResponses(logResponses)
        .build();
  }
}
