@file:JvmName("CircuitBreakerRecordMarker")
package maple.expectation.error.exception.marker

/**
 * Marker interface for exceptions that should trigger circuit breaker recording.
 *
 * <p>System/infrastructure exceptions (5xx) should implement this interface to
 * ensure circuit breaker tracks failures and opens when threshold is reached.
 *
 * @see maple.expectation.error.exception.base.ServerBaseException
 */
interface CircuitBreakerRecordMarker
