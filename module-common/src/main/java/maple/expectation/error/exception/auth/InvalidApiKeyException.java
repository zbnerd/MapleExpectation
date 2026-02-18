package maple.expectation.error.exception.auth;

/**
 * Invalid API key exception
 *
 * <p>Thrown when an API key is invalid, expired, or malformed.
 */
public class InvalidApiKeyException extends RuntimeException {
  public InvalidApiKeyException(String apiKey) {
    super("Invalid API key: " + apiKey);
  }

  public InvalidApiKeyException(String apiKey, Throwable cause) {
    super("Invalid API key: " + apiKey, cause);
  }
}
