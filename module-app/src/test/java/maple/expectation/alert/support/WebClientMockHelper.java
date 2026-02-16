package maple.expectation.alert.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Helper utility for creating WebClient mocks in tests.
 *
 * <p>Reduces ~150 lines of boilerplate across alert integration tests by providing
 * pre-configured mock chains for common WebClient scenarios.
 */
public final class WebClientMockHelper {

  private WebClientMockHelper() {
    // Utility class - prevent instantiation
  }

  /**
   * Creates a successful WebClient mock chain.
   *
   * @return WebClient mock configured for successful responses
   */
  public static WebClient successfulWebClient() {
    WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
    when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.ok().build()));

    WebClient webClient = mock(WebClient.class);
    when(webClient.post()).thenReturn(requestBodyUriSpec);
    return webClient;
  }

  /**
   * Creates a WebClient mock chain with a specific response status.
   *
   * @param statusCode HTTP status code to return
   * @return WebClient mock configured for the specified status
   */
  public static WebClient webClientWithStatus(int statusCode) {
    WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
    when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenReturn(Mono.just(org.springframework.http.ResponseEntity.status(statusCode).build()));

    WebClient webClient = mock(WebClient.class);
    when(webClient.post()).thenReturn(requestBodyUriSpec);
    return webClient;
  }

  /**
   * Creates a WebClient mock that throws WebClientResponseException.
   *
   * @param statusCode HTTP status code for the exception
   * @param statusText Status text for the exception
   * @return WebClient mock configured to throw exception
   */
  public static WebClient webClientThatThrows(int statusCode, String statusText) {
    WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
    WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
    WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

    when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
    when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
    when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity())
        .thenThrow(
            new org.springframework.web.reactive.function.client.WebClientResponseException(
                statusCode, statusText, null, null, null));

    WebClient webClient = mock(WebClient.class);
    when(webClient.post()).thenReturn(requestBodyUriSpec);
    return webClient;
  }
}
