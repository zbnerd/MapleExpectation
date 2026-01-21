package maple.expectation.controller.dto.common;

/**
 * Cursor-based Pagination 요청 (#233)
 *
 * <h3>OFFSET vs Cursor 성능 비교</h3>
 * <ul>
 *   <li>OFFSET 1,000,000: ~1,800ms (1,000,010행 스캔)</li>
 *   <li>Cursor (WHERE id > last): ~10ms (10행만 스캔)</li>
 * </ul>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * // 첫 페이지 조회
 * GET /api/admin/dlq/v2?size=20
 *
 * // 다음 페이지 조회 (응답의 nextCursor 사용)
 * GET /api/admin/dlq/v2?cursor=123&size=20
 * }</pre>
 *
 * @param cursor 마지막으로 조회한 ID (null이면 처음부터)
 * @param size   페이지 크기 (기본 20, 최대 100)
 */
public record CursorPageRequest(
        Long cursor,
        int size
) {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    public static CursorPageRequest of(Long cursor, int size) {
        int validSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new CursorPageRequest(cursor, validSize);
    }

    public static CursorPageRequest firstPage() {
        return new CursorPageRequest(null, DEFAULT_SIZE);
    }

    public static CursorPageRequest firstPage(int size) {
        return of(null, size);
    }
}
