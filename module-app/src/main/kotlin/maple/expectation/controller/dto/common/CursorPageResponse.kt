package maple.expectation.controller.dto.common

import org.springframework.data.domain.Slice
import java.util.function.Function

/**
 * Cursor-based Pagination response (#233)
 *
 * <h3>Deep Paging 문제 해결</h3>
 *
 * <p>OFFSET 기반 페이징의 O(n) 성능 문제를 Keyset Pagination으로 해결.
 *
 * <h3>응답 예시</h3>
 *
 * <pre>{@code
 * {
 *   "content": [...],
 *   "nextCursor": 123,
 *   "hasNext": true,
 *   "size": 20
 * }
 * }</pre>
 *
 * @param content current page data
 * @param nextCursor cursor for next page retrieval (last ID)
 * @param hasNext whether next page exists
 * @param size current page size
 * @param T content type
 */
data class CursorPageResponse<T>(
    val content: List<T>,
    val nextCursor: Long?,
    val hasNext: Boolean,
    val size: Int
) {
    companion object {
        /**
         * Create CursorPageResponse from Slice
         *
         * @param slice Spring Data Slice
         * @param idExtractor ID extraction function
         * @param T entity type
         * @return Cursor-based response
         */
        fun <T> from(slice: Slice<T>, idExtractor: (T) -> Long): CursorPageResponse<T> {
            val content = slice.content
            val nextCursor = if (content.isEmpty()) null else idExtractor(content.last())

            return CursorPageResponse(content, nextCursor, slice.hasNext(), content.size)
        }

        /**
         * Create CursorPageResponse with DTO mapping
         *
         * @param slice Spring Data Slice (entity)
         * @param mapper DTO mapping function
         * @param idExtractor ID extraction function from entity
         * @param E entity type
         * @param D DTO type
         * @return Cursor-based response (DTO)
         */
        fun <E, D> fromWithMapping(
            slice: Slice<E>,
            mapper: (E) -> D,
            idExtractor: (E) -> Long
        ): CursorPageResponse<D> {
            val entities = slice.content
            val content = entities.map(mapper)
            val nextCursor = if (entities.isEmpty()) null else idExtractor(entities.last())

            return CursorPageResponse(content, nextCursor, slice.hasNext(), content.size)
        }

        /**
         * Create empty response
         */
        fun <T> empty(): CursorPageResponse<T> = CursorPageResponse(emptyList(), null, false, 0)
    }
}