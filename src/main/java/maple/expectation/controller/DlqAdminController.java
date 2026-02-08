package maple.expectation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import maple.expectation.controller.dto.common.CursorPageRequest;
import maple.expectation.controller.dto.common.CursorPageResponse;
import maple.expectation.controller.dto.dlq.DlqDetailResponse;
import maple.expectation.controller.dto.dlq.DlqEntryResponse;
import maple.expectation.controller.dto.dlq.DlqReprocessResult;
import maple.expectation.service.v2.donation.outbox.DlqAdminService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * DLQ 관리 API (Admin 전용)
 *
 * <h3>엔드포인트</h3>
 *
 * <ul>
 *   <li>GET /api/admin/dlq - DLQ 목록 조회 (페이징)
 *   <li>GET /api/admin/dlq/{id} - DLQ 상세 조회
 *   <li>POST /api/admin/dlq/{id}/reprocess - DLQ 재처리
 *   <li>DELETE /api/admin/dlq/{id} - DLQ 폐기
 *   <li>GET /api/admin/dlq/count - DLQ 총 건수
 * </ul>
 *
 * <h3>보안</h3>
 *
 * <p>SecurityConfig에서 ADMIN 권한만 접근 가능하도록 설정
 *
 * @see DlqAdminService
 */
@Tag(name = "DLQ Admin", description = "Dead Letter Queue 관리 API (Admin 전용)")
@RestController
@RequestMapping("/api/admin/dlq")
@RequiredArgsConstructor
public class DlqAdminController {

  private final DlqAdminService dlqAdminService;

  /** DLQ 목록 조회 (페이징) */
  @Operation(summary = "DLQ 목록 조회", description = "최신순으로 DLQ 항목 목록을 조회합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회 성공"),
    @ApiResponse(responseCode = "403", description = "권한 없음")
  })
  @GetMapping
  public CompletableFuture<
          ResponseEntity<maple.expectation.global.response.ApiResponse<Page<DlqEntryResponse>>>>
      findAll(
          @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page,
          @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "20") int size) {

    return CompletableFuture.supplyAsync(
        () -> {
          Page<DlqEntryResponse> result = dlqAdminService.findAll(page, size);
          return ResponseEntity.ok(maple.expectation.global.response.ApiResponse.success(result));
        });
  }

  /** DLQ 상세 조회 */
  @Operation(summary = "DLQ 상세 조회", description = "특정 DLQ 항목의 상세 정보를 조회합니다 (전체 payload 포함).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회 성공"),
    @ApiResponse(responseCode = "404", description = "DLQ 항목 없음"),
    @ApiResponse(responseCode = "403", description = "권한 없음")
  })
  @GetMapping("/{id}")
  public CompletableFuture<
          ResponseEntity<maple.expectation.global.response.ApiResponse<DlqDetailResponse>>>
      findById(@Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          DlqDetailResponse result = dlqAdminService.findById(id);
          return ResponseEntity.ok(maple.expectation.global.response.ApiResponse.success(result));
        });
  }

  /** DLQ 재처리 (Outbox로 복원) */
  @Operation(summary = "DLQ 재처리", description = "DLQ 항목을 Outbox로 복원하여 재처리합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "재처리 성공"),
    @ApiResponse(responseCode = "404", description = "DLQ 항목 없음"),
    @ApiResponse(responseCode = "409", description = "이미 재처리됨"),
    @ApiResponse(responseCode = "403", description = "권한 없음")
  })
  @PostMapping("/{id}/reprocess")
  public CompletableFuture<
          ResponseEntity<maple.expectation.global.response.ApiResponse<DlqReprocessResult>>>
      reprocess(@Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          DlqReprocessResult result = dlqAdminService.reprocess(id);
          return ResponseEntity.ok(maple.expectation.global.response.ApiResponse.success(result));
        });
  }

  /** DLQ 폐기 (삭제) */
  @Operation(summary = "DLQ 폐기", description = "복구 불가능한 DLQ 항목을 삭제합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "폐기 성공"),
    @ApiResponse(responseCode = "404", description = "DLQ 항목 없음"),
    @ApiResponse(responseCode = "403", description = "권한 없음")
  })
  @DeleteMapping("/{id}")
  public CompletableFuture<ResponseEntity<maple.expectation.global.response.ApiResponse<String>>>
      discard(@Parameter(description = "DLQ ID") @PathVariable Long id) {

    return CompletableFuture.supplyAsync(
        () -> {
          dlqAdminService.discard(id);
          return ResponseEntity.ok(
              maple.expectation.global.response.ApiResponse.success(
                  "DLQ entry discarded successfully: " + id));
        });
  }

  /** DLQ 총 건수 조회 */
  @Operation(summary = "DLQ 총 건수", description = "현재 DLQ에 쌓인 총 항목 수를 조회합니다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping("/count")
  public CompletableFuture<ResponseEntity<maple.expectation.global.response.ApiResponse<Long>>>
      count() {
    return CompletableFuture.supplyAsync(
        () -> {
          long count = dlqAdminService.count();
          return ResponseEntity.ok(maple.expectation.global.response.ApiResponse.success(count));
        });
  }

  // ========== Cursor-based Pagination (#233) ==========

  /**
   * DLQ 목록 조회 (Cursor-based Pagination)
   *
   * <h3>Deep Paging 문제 해결</h3>
   *
   * <p>기존 OFFSET 기반 페이징의 O(n) 성능 문제를 Keyset Pagination으로 해결.
   *
   * <h4>사용 예시</h4>
   *
   * <pre>
   * // 첫 페이지
   * GET /api/admin/dlq/v2?size=20
   *
   * // 다음 페이지 (응답의 nextCursor 사용)
   * GET /api/admin/dlq/v2?cursor=123&amp;size=20
   * </pre>
   */
  @Operation(
      summary = "DLQ 목록 조회 (Cursor 방식)",
      description = "Cursor-based Pagination으로 DLQ 목록을 조회합니다. Deep Paging에서도 O(1) 성능을 보장합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "조회 성공"),
    @ApiResponse(responseCode = "403", description = "권한 없음")
  })
  @GetMapping("/v2")
  public CompletableFuture<
          ResponseEntity<
              maple.expectation.global.response.ApiResponse<CursorPageResponse<DlqEntryResponse>>>>
      findAllByCursor(
          @Parameter(description = "이전 페이지의 마지막 ID (첫 페이지는 생략)") @RequestParam(required = false)
              Long cursor,
          @Parameter(description = "페이지 크기 (최대 100)") @RequestParam(defaultValue = "20") int size) {
    return CompletableFuture.supplyAsync(
        () -> {
          CursorPageRequest request = CursorPageRequest.of(cursor, size);
          CursorPageResponse<DlqEntryResponse> result = dlqAdminService.findAllByCursor(request);
          return ResponseEntity.ok(maple.expectation.global.response.ApiResponse.success(result));
        });
  }
}
