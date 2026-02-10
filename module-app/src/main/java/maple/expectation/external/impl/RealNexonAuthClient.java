package maple.expectation.external.impl;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.config.TimeoutProperties;
import maple.expectation.external.NexonAuthClient;
import maple.expectation.external.dto.v2.CharacterListResponse;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * 인증용 Nexon API 클라이언트 구현체
 *
 * <p>BYOK (Bring Your Own Key) 방식으로 사용자의 API Key를 직접 사용합니다.
 *
 * <p>보안 고려사항:
 *
 * <ul>
 *   <li>사용자의 API Key는 로그에 절대 남기지 않음
 *   <li>타임아웃 설정으로 무한 대기 방지
 *   <li>예외 발생 시 안전하게 Optional.empty() 반환 (LogicExecutor 패턴)
 * </ul>
 *
 * <p>CLAUDE.md 섹션 12 준수: try-catch 대신 WebClient.onErrorResume() + LogicExecutor.executeOrDefault()
 * 사용
 *
 * <p>onErrorResume vs onStatus:
 *
 * <ul>
 *   <li>onStatus: 상태 코드만 확인 가능, 응답 본문 접근 불가
 *   <li>onErrorResume: WebClientResponseException으로 상태 코드 + 응답 본문 모두 접근 가능
 *   <li>디버깅을 위해 Nexon API의 실제 에러 메시지 로깅 필요 → onErrorResume 사용
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealNexonAuthClient implements NexonAuthClient {

  private static final String CHARACTER_LIST_PATH = "/maplestory/v1/character/list";

  private final WebClient mapleWebClient;
  private final LogicExecutor executor;
  private final TimeoutProperties timeoutProperties;

  @Override
  public Optional<CharacterListResponse> getCharacterList(String apiKey) {
    return executor.executeOrDefault(
        () -> doGetCharacterList(apiKey),
        Optional.empty(),
        TaskContext.of("NexonAuth", "CharacterList", "auth"));
  }

  /**
   * WebClient.onErrorResume()을 활용하여 try-catch 없이 에러 처리
   *
   * <p>패턴 설명:
   *
   * <ul>
   *   <li>4xx 에러: Nexon API 에러 메시지 로깅 후 Mono.empty() 반환
   *   <li>5xx 에러: 상위로 전파 (서킷브레이커 동작)
   *   <li>성공: 응답 본문 반환
   * </ul>
   */
  private Optional<CharacterListResponse> doGetCharacterList(String apiKey) {
    log.debug("[NexonAuth] Calling character/list API");

    CharacterListResponse response =
        mapleWebClient
            .get()
            .uri(CHARACTER_LIST_PATH)
            .header("x-nxopen-api-key", apiKey)
            .retrieve()
            .bodyToMono(CharacterListResponse.class)
            .onErrorResume(
                WebClientResponseException.class,
                ex -> {
                  if (ex.getStatusCode().is4xxClientError()) {
                    // 4xx: Invalid API Key 등 클라이언트 에러 → 실제 에러 본문 로깅
                    log.warn(
                        "[NexonAuth] API Call Failed. Status: {}, Body: {}",
                        ex.getStatusCode(),
                        ex.getResponseBodyAsString());
                    return Mono.empty();
                  }
                  // 5xx: 서버 에러는 상위 전파 (서킷브레이커 동작)
                  return Mono.error(ex);
                })
            .timeout(timeoutProperties.getApiCall())
            .block();

    return Optional.ofNullable(response)
        .filter(r -> r.accountList() != null && !r.accountList().isEmpty())
        .map(
            r -> {
              log.info("[NexonAuth] Retrieved {} characters", r.getAllCharacters().size());
              return r;
            });
  }

  @Override
  public boolean validateApiKey(String apiKey) {
    return getCharacterList(apiKey).isPresent();
  }
}
