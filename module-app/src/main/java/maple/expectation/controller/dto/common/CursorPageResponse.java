package maple.expectation.controller.dto.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Slice;

/**
 * Cursor-based Pagination 응답 (#233)
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
 * @param content 현재 페이지 데이터
 * @param nextCursor 다음 페이지 조회용 커서 (마지막 ID)
 * @param hasNext 다음 페이지 존재 여부
 * @param size 현재 페이지 크기
 * @param <T> 컨텐츠 타입
 */
public record CursorPageResponse<T>(List<T> content, Long nextCursor, boolean hasNext, int size) {
  /**
   * Slice에서 CursorPageResponse 생성
   *
   * @param slice Spring Data Slice
   * @param idExtractor ID 추출 함수
   * @param <T> 엔티티 타입
   * @return Cursor 기반 응답
   */
  public static <T> CursorPageResponse<T> from(Slice<T> slice, Function<T, Long> idExtractor) {
    List<T> content = slice.getContent();
    Long nextCursor = content.isEmpty() ? null : idExtractor.apply(content.get(content.size() - 1));

    return new CursorPageResponse<>(content, nextCursor, slice.hasNext(), content.size());
  }

  /**
   * DTO 매핑과 함께 CursorPageResponse 생성
   *
   * @param slice Spring Data Slice (엔티티)
   * @param mapper DTO 매핑 함수
   * @param idExtractor 엔티티에서 ID 추출 함수
   * @param <E> 엔티티 타입
   * @param <D> DTO 타입
   * @return Cursor 기반 응답 (DTO)
   */
  public static <E, D> CursorPageResponse<D> fromWithMapping(
      Slice<E> slice, Function<E, D> mapper, Function<E, Long> idExtractor) {
    List<E> entities = slice.getContent();
    List<D> content = entities.stream().map(mapper).toList();
    Long nextCursor =
        entities.isEmpty() ? null : idExtractor.apply(entities.get(entities.size() - 1));

    return new CursorPageResponse<>(content, nextCursor, slice.hasNext(), content.size());
  }

  /** 빈 응답 생성 */
  public static <T> CursorPageResponse<T> empty() {
    return new CursorPageResponse<>(List.of(), null, false, 0);
  }
}
