package maple.expectation.error

/**
 * Error code interface defining the contract for all error codes
 */
interface ErrorCode {
    val code: String
    val message: String
    val statusCode: Int
}