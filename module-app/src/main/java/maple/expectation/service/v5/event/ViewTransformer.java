package maple.expectation.service.v5.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.CostBreakdownDto;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.ItemExpectationV4;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.event.ExpectationCalculationCompletedEvent;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.mongodb.CharacterValuationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.CostBreakdownView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.ItemExpectationView;
import maple.expectation.infrastructure.mongodb.CharacterValuationView.PresetView;
import org.springframework.stereotype.Service;

/**
 * V5 CQRS: Transformer to convert RDB entities to MongoDB documents.
 *
 * <h3>Responsibilities</h3>
 *
 * <ul>
 *   <li>Transform V4 DTO responses to MongoDB CharacterValuationView
 *   <li>Handle BigDecimal to Long conversion (mesos units)
 *   <li>Build deterministic IDs for idempotent upserts
 * </ul>
 *
 * <h3>Design Principles</h3>
 *
 * <p><b>Section 12 (Zero Try-Catch):</b> All exceptions propagated via LogicExecutor.
 *
 * <p><b>Section 15 (Lambda Hell Prevention):</b> Complex transformations extracted to private
 * methods.
 *
 * <h3>Idempotency</h3>
 *
 * <p>Uses deterministic document ID ({@code userIgn:taskId}) to ensure Redis Stream at-least-once
 * delivery results in MongoDB upserts (no duplicates).
 *
 * @since 5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewTransformer {

  private final LogicExecutor executor;
  private final ObjectMapper objectMapper;

  /**
   * Transform ExpectationCalculationCompletedEvent to CharacterValuationView.
   *
   * <p><b>Idempotency:</b> Uses deterministic document ID based on taskId to prevent duplicates on
   * Redis Stream re-delivery.
   *
   * @param event Calculation completion event from Redis Stream
   * @return MongoDB view document for upsert
   */
  public CharacterValuationView toDocument(ExpectationCalculationCompletedEvent event) {
    return executor.executeOrDefault(
        () -> toDocumentInternal(event),
        createEmptyView(event),
        TaskContext.of("ViewTransformer", "ToDocument", event.getTaskId()));
  }

  /**
   * Internal transformation with checked exceptions.
   *
   * <p>Extracted for LogicExecutor pattern (Section 12).
   */
  private CharacterValuationView toDocumentInternal(ExpectationCalculationCompletedEvent event)
      throws Exception {
    // Deterministic ID for idempotency: "userIgn:taskId"
    String deterministicId = buildDeterministicId(event.getUserIgn(), event.getTaskId());

    // Parse payload JSON to extract full V4 response data
    List<PresetView> presetViews = extractPresetViews(event.getPayload());

    return CharacterValuationView.builder()
        .id(deterministicId)
        .userIgn(event.getUserIgn())
        .characterOcid(event.getCharacterOcid())
        .characterClass(event.getCharacterClass())
        .characterLevel(event.getCharacterLevel())
        .totalExpectedCost(parseSafely(() -> Integer.parseInt(event.getTotalExpectedCost()), 0))
        .maxPresetNo(event.getMaxPresetNo())
        .calculatedAt(parseInstant(event.getCalculatedAt()))
        .lastApiSyncAt(Instant.now())
        .version(parseSafely(() -> Long.parseLong(event.getTaskId()), 0L))
        .fromCache(false)
        .presets(presetViews)
        .build();
  }

  /**
   * Extract and transform preset views from V4 response JSON payload.
   *
   * <p><b>Fault Tolerance:</b> If payload parsing fails, returns empty list to prevent sync
   * failure. This allows graceful degradation - MongoDB will have minimal data but sync continues.
   *
   * @param payloadJson JSON string of EquipmentExpectationResponseV4
   * @return List of preset views (empty if parsing fails)
   */
  private List<PresetView> extractPresetViews(String payloadJson) {
    return executor.executeOrDefault(
        () -> extractPresetViewsInternal(payloadJson),
        List.of(),
        TaskContext.of("ViewTransformer", "ExtractPresets"));
  }

  /** Internal preset extraction with checked exceptions. */
  private List<PresetView> extractPresetViewsInternal(String payloadJson) throws Exception {
    if (payloadJson == null || payloadJson.isBlank()) {
      log.debug("[ViewTransformer] Empty payload, using empty presets");
      return List.of();
    }

    EquipmentExpectationResponseV4 v4Response =
        parseSafely(
            () -> objectMapper.readValue(payloadJson, EquipmentExpectationResponseV4.class), null);

    if (v4Response == null || v4Response.getPresets() == null) {
      return List.of();
    }

    return v4Response.getPresets().stream().map(this::toPresetView).collect(Collectors.toList());
  }

  /**
   * Transform V4 PresetExpectation to MongoDB PresetView.
   *
   * <p>Extracted method to avoid lambda hell (Section 15).
   */
  private PresetView toPresetView(PresetExpectation preset) {
    return PresetView.builder()
        .presetNo(preset.getPresetNo())
        .totalExpectedCost(toLong(preset.getTotalExpectedCost()))
        .totalCostText(preset.getTotalCostText())
        .costBreakdown(toCostBreakdownView(preset.getCostBreakdown()))
        .items(toItemViews(preset.getItems()))
        .build();
  }

  /**
   * Transform V4 CostBreakdownDto to MongoDB CostBreakdownView.
   *
   * <p><b>Note:</b> V4 uses BigDecimal, MongoDB view uses Long (mesos units).
   */
  private CostBreakdownView toCostBreakdownView(CostBreakdownDto breakdown) {
    if (breakdown == null) {
      return CostBreakdownView.builder().build();
    }

    return CostBreakdownView.builder()
        .blackCubeCost(toLong(breakdown.getBlackCubeCost()))
        .redCubeCost(toLong(breakdown.getRedCubeCost()))
        .additionalCubeCost(toLong(breakdown.getAdditionalCubeCost()))
        .starforceCost(toLong(breakdown.getStarforceCost()))
        .flameCost(0L) // V4 doesn't include flame cost in total breakdown
        .build();
  }

  /**
   * Transform V4 ItemExpectationV4 list to MongoDB ItemExpectationView list.
   *
   * <p>Extracted method to avoid lambda hell (Section 15).
   */
  private List<ItemExpectationView> toItemViews(List<ItemExpectationV4> items) {
    if (items == null) {
      return List.of();
    }

    return items.stream()
        .map(
            item ->
                ItemExpectationView.builder()
                    .itemName(item.getItemName())
                    .expectedCost(toLong(item.getExpectedCost()))
                    .costText(item.getExpectedCostText())
                    .build())
        .collect(Collectors.toList());
  }

  /**
   * Build deterministic ID for idempotent upsert.
   *
   * <p>Format: {@code userIgn:taskId}
   *
   * <p>This ensures that Redis Stream at-least-once delivery (duplicates possible) results in
   * MongoDB upserts updating the same document instead of creating duplicates.
   */
  private String buildDeterministicId(String userIgn, String taskId) {
    return userIgn + ":" + taskId;
  }

  /**
   * Convert BigDecimal to Long (mesos units).
   *
   * <p>Null-safe conversion.
   */
  private Long toLong(BigDecimal value) {
    return value != null ? value.longValue() : 0L;
  }

  /**
   * Parse Instant from string safely.
   *
   * <p>Returns {@link Instant#EPOCH} if parsing fails.
   */
  private Instant parseInstant(String instantStr) {
    return parseSafely(() -> Instant.parse(instantStr), Instant.EPOCH);
  }

  /**
   * Generic safe parser with fallback value.
   *
   * <p>Used to prevent cascading failures from bad data in events.
   *
   * <p>Uses LogicExecutor.executeOrDefault() for Section 12 compliance.
   *
   * @param supplier Parser logic that may throw
   * @param defaultValue Fallback value if parsing fails
   * @param <T> Return type
   * @return Parsed value or default
   */
  private <T> T parseSafely(ParseSupplier<T> supplier, T defaultValue) {
    return executor.executeOrDefault(
        supplier::get, defaultValue, TaskContext.of("ViewTransformer", "ParseSafely"));
  }

  /**
   * Create empty view for fault tolerance.
   *
   * <p>Used when transformation fails to prevent sync pipeline from blocking.
   */
  private CharacterValuationView createEmptyView(ExpectationCalculationCompletedEvent event) {
    return CharacterValuationView.builder()
        .id(buildDeterministicId(event.getUserIgn(), event.getTaskId()))
        .userIgn(event.getUserIgn())
        .characterOcid(event.getCharacterOcid())
        .characterClass(event.getCharacterClass())
        .characterLevel(event.getCharacterLevel())
        .totalExpectedCost(0)
        .maxPresetNo(event.getMaxPresetNo())
        .calculatedAt(Instant.EPOCH)
        .lastApiSyncAt(Instant.now())
        .version(0L)
        .fromCache(false)
        .presets(List.of())
        .build();
  }

  /**
   * Functional interface for safe parsing.
   *
   * <p>Extracted to reduce lambda verbosity.
   */
  @FunctionalInterface
  private interface ParseSupplier<T> {
    T get() throws Exception;
  }
}
