package maple.expectation.error.exception.marker

/**
 * Marker interface for exceptions that should be ignored by circuit breaker.
 *
 * <p>Business logic exceptions (4xx) should implement this interface to prevent
 * circuit breaker from opening due to expected client errors.
 *
 * @see maple.expectation.error.exception.base.ClientBaseException
 */
interface CircuitBreakerIgnoreMarker
