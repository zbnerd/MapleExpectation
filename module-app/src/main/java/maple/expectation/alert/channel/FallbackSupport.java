package maple.expectation.alert.channel;

/**
 * Fallback Support Interface
 *
 * <p>Provides fallback chaining capability for alert channels
 *
 * <p>When primary channel fails, automatically try fallback channel
 *
 * @author ADR-0345
 * @since 2025-02-12
 */
public interface FallbackSupport extends AlertChannel {

  /**
   * Set fallback channel to try when this channel fails
   *
   * @param fallback Fallback channel
   */
  void setFallback(AlertChannel fallback);
}
