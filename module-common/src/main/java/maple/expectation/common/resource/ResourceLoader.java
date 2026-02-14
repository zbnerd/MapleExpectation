package maple.expectation.common.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for loading resources from classpath.
 *
 * <p><strong>SRP:</strong> Single responsibility - resource loading only.
 *
 * <p><strong>Exception Handling:</strong> Converts IOException to RuntimeException
 *
 * <h3>Usage:</h3>
 *
 * <pre>{@code
 * String luaScript = new ResourceLoader().loadResourceAsString("lua/script.lua");
 * }</pre>
 */
public class ResourceLoader {

  /**
   * Load resource from classpath as String.
   *
   * @param path Resource path (e.g., "lua/script.lua")
   * @return Resource content as UTF-8 string
   * @throws IllegalStateException if resource not found or read error occurs
   */
  public String loadResourceAsString(String path) {
    try (InputStream inputStream = getResourceAsStream(path)) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read resource: " + path, e);
    }
  }

  /**
   * Load resource from classpath as InputStream.
   *
   * <p>Caller is responsible for closing the stream.
   *
   * @param path Resource path
   * @return InputStream (caller must close)
   * @throws IllegalStateException if resource not found
   */
  public InputStream loadResourceAsStream(String path) {
    return getResourceAsStream(path);
  }

  /**
   * Get resource stream from classpath.
   *
   * @param path Resource path
   * @return InputStream
   * @throws IllegalStateException if resource not found
   */
  private InputStream getResourceAsStream(String path) {
    InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path);
    if (inputStream == null) {
      throw new IllegalStateException("Required resource not found: " + path);
    }
    return inputStream;
  }
}
