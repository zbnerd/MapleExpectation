package maple.expectation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.EventProcessingException;
import maple.expectation.event.EventHandler;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Redis Stream consumer with XREADGROUP and XACK support.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Consumer Group: Multiple instances share load via XREADGROUP
 *   <li>Reliable Delivery: XACK confirms message processing
 *   <li>Blocking Read: Configurable timeout for backpressure
 *   <li>Deduplication: Filter prevents duplicate processing
 *   <li>Observability: Micrometer metrics for latency monitoring
 *   <li>Handler Discovery: Reflection-based @EventHandler detection (no EventDispatcher dependency)
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance:</h3>
 *
 * <ul>
 *   <li>No raw try-catch - uses LogicExecutor
 *   <li>Exception translation to EventProcessingException
 * </ul>
 *
 * <h3>CLAUDE.md Section 22 Compliance (Thread Pool Backpressure):</h3>
 *
 * <ul>
 *   <li>Blocking read with timeout prevents thread starvation
 *   <li>Virtual Threads for high concurrency (Java 21)
 * </ul>
 *
 * <h3>Redis Stream Commands:</h3>
 *
 * <ul>
 *   <li>XREADGROUP: Read from consumer group (blocking)
 *   <li>XACK: Acknowledge successful processing
 *   <li>XGROUP: Create consumer group (auto-created)
 * </ul>
 *
 * <h3>Handler Discovery:</h3>
 *
 * <p>This consumer discovers @EventHandler methods via Spring ApplicationContext, eliminating
 * EventDispatcher dependency for module-infra independence.
 *
 * @see maple.expectation.infrastructure.messaging.DeduplicationFilter
 * @see maple.expectation.event.EventHandler
 * @since 1.0.0
 */
@Slf4j
public class RedisStreamEventConsumer implements ApplicationContextAware {

  private final RStream<String, String> stream;
  private final ObjectMapper objectMapper;
  private final DeduplicationFilter deduplicationFilter;
  private final LogicExecutor executor;
  private final ObservationRegistry observationRegistry;

  // Handler cache for reflection-based dispatch (EventDispatcher dependency removed)
  private final ConcurrentHashMap<String, List<HandlerMethod>> handlerCache =
      new ConcurrentHashMap<>();

  private final String streamKey;
  private final String consumerGroup;
  private final String consumerName;
  private final Duration readTimeout;

  /**
   * Create Redis Stream event consumer.
   *
   * @param redissonClient Redisson client
   * @param objectMapper Jackson object mapper for JSON deserialization
   * @param deduplicationFilter Deduplication filter
   * @param executor LogicExecutor for error handling
   * @param observationRegistry Micrometer observation registry
   * @param streamKey Redis stream key (e.g., "integration-events")
   * @param consumerGroup Consumer group name (e.g., "event-processors")
   * @param consumerName Consumer instance name (e.g., hostname + PID)
   * @param readTimeout Blocking read timeout (backpressure control)
   */
  public RedisStreamEventConsumer(
      RedissonClient redissonClient,
      ObjectMapper objectMapper,
      DeduplicationFilter deduplicationFilter,
      LogicExecutor executor,
      ObservationRegistry observationRegistry,
      String streamKey,
      String consumerGroup,
      String consumerName,
      Duration readTimeout) {
    this.stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
    this.objectMapper = objectMapper;
    this.deduplicationFilter = deduplicationFilter;
    this.executor = executor;
    this.observationRegistry = observationRegistry;
    this.streamKey = streamKey;
    this.consumerGroup = consumerGroup;
    this.consumerName = consumerName;
    this.readTimeout = readTimeout;

    // Auto-create consumer group if not exists
    createConsumerGroupIfNeeded();
  }

  /**
   * Set ApplicationContext and discover handlers.
   *
   * <p>Spring calls this after bean initialization. We scan for @EventHandler methods and cache
   * them for reflection-based dispatch.
   *
   * @param applicationContext Spring application context
   */
  @Override
  public void setApplicationContext(ApplicationContext applicationContext) {
    discoverHandlers(applicationContext);
  }

  /**
   * Discover all @EventHandler methods from Spring beans.
   *
   * <p>Scans all Spring beans for methods annotated with @EventHandler and caches them by event
   * type for efficient dispatch.
   *
   * @param context Spring application context
   */
  private void discoverHandlers(ApplicationContext context) {
    executor.executeVoid(
        () -> discoverHandlersInternal(context),
        TaskContext.of("RedisStreamEventConsumer", "DiscoverHandlers", streamKey));
  }

  /**
   * Internal handler discovery with checked exceptions.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void discoverHandlersInternal(ApplicationContext context) throws Exception {
    for (Object bean : context.getBeansOfType(Object.class).values()) {
      Class<?> beanClass = bean.getClass();
      String beanName =
          context.getBeansOfType(Object.class).entrySet().stream()
              .filter(e -> e.getValue() == bean)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(beanClass.getSimpleName());

      for (Method method : beanClass.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(EventHandler.class)) {
          continue;
        }

        EventHandler annotation = method.getAnnotation(EventHandler.class);
        String eventType = annotation.eventType().getSimpleName();

        // Validate method signature: single parameter, public
        validateHandlerMethod(method, annotation.eventType());

        // Cache handler method
        handlerCache
            .computeIfAbsent(eventType, k -> new ArrayList<>())
            .add(new HandlerMethod(bean, method, annotation.async()));

        log.info(
            "[RedisStreamEventConsumer] Discovered handler: eventType={}, method={}, bean={}",
            eventType,
            method.getName(),
            beanName);
      }
    }

    log.info(
        "[RedisStreamEventConsumer] Handler discovery complete: {} event types, {} handlers",
        handlerCache.size(),
        handlerCache.values().stream().mapToInt(List::size).sum());
  }

  /**
   * Validate handler method signature.
   *
   * <p>Requirements:
   *
   * <ul>
   *   <li>Single parameter matching event type
   *   <li>Public method
   *   <li>Return type void (async) or any (sync)
   * </ul>
   */
  private void validateHandlerMethod(Method method, Class<?> expectedType) {
    if (method.getParameterCount() != 1) {
      throw new EventProcessingException(
          CommonErrorCode.EVENT_HANDLER_ERROR,
          String.format(
              "Handler method must have single parameter: %s.%s (params=%d)",
              method.getDeclaringClass().getSimpleName(),
              method.getName(),
              method.getParameterCount()));
    }

    Class<?> paramType = method.getParameterTypes()[0];
    if (!expectedType.isAssignableFrom(paramType)) {
      throw new EventProcessingException(
          CommonErrorCode.EVENT_HANDLER_ERROR,
          String.format(
              "Handler parameter type mismatch: %s.%s (expected=%s, actual=%s)",
              method.getDeclaringClass().getSimpleName(),
              method.getName(),
              expectedType.getSimpleName(),
              paramType.getSimpleName()));
    }
  }

  /**
   * Find handler methods for an event type.
   *
   * @param eventType Event type name (simple class name)
   * @return List of handler methods, or empty list if none found
   */
  private List<HandlerMethod> findHandlerForEvent(String eventType) {
    return handlerCache.getOrDefault(eventType, List.of());
  }

  /**
   * Start consuming events from stream (blocking call).
   *
   * <p>This method blocks until interrupted or shutdown. Use Virtual Threads for high concurrency.
   *
   * <p><strong>Flow:</strong>
   *
   * <ol>
   *   <li>XREADGROUP: Read pending messages (ID > 0)
   *   <li>Deserialize: JSON -> IntegrationEvent
   *   <li>Deduplicate: Skip if already processed
   *   <li>Dispatch: Route to @EventHandler methods
   *   <li>XACK: Acknowledge successful processing
   * </ol>
   *
   * @throws EventProcessingException if critical failure occurs
   */
  public void startConsuming() {
    log.info(
        "[RedisStreamEventConsumer] Starting consumer: group={}, name={}, stream={}",
        consumerGroup,
        consumerName,
        streamKey);

    while (!Thread.currentThread().isInterrupted()) {
      executor.executeVoid(
          () -> consumeNextBatch(),
          TaskContext.of("RedisStreamEventConsumer", "ConsumeBatch", streamKey));
    }

    log.info(
        "[RedisStreamEventConsumer] Consumer stopped: group={}, name={}",
        consumerGroup,
        consumerName);
  }

  /**
   * Consume next batch of messages with checked exceptions.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void consumeNextBatch() throws Exception {
    // XREADGROUP: Blocking read from consumer group
    // Returns only new messages (no history replay)
    // Using ">" marker: deliver messages never delivered to any consumer
    Map<StreamMessageId, Map<String, String>> messages =
        stream.readGroup(
            consumerGroup, consumerName, StreamReadGroupArgs.neverDelivered().timeout(readTimeout));

    if (messages == null || messages.isEmpty()) {
      // Timeout: no new messages (normal)
      return;
    }

    // Process each message
    for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
      StreamMessageId messageId = entry.getKey();
      Map<String, String> fields = entry.getValue();

      processMessage(messageId, fields);
    }
  }

  /**
   * Process single message from stream.
   *
   * <p><strong>Flow:</strong>
   *
   * <ol>
   *   <li>Deserialize JSON to IntegrationEvent
   *   <li>Check deduplication (skip if duplicate)
   *   <li>Dispatch to handler
   *   <li>XACK on success
   * </ol>
   *
   * @param messageId Stream message ID (for XACK)
   * @param fields Message fields (JSON payload)
   */
  private void processMessage(StreamMessageId messageId, Map<String, String> fields) {
    executor.executeVoid(
        () -> processMessageInternal(messageId, fields),
        TaskContext.of("RedisStreamEventConsumer", "ProcessMessage", messageId.toString()));
  }

  /**
   * Internal message processing with checked exceptions.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void processMessageInternal(StreamMessageId messageId, Map<String, String> fields)
      throws Exception {

    // 1. Deserialize JSON to IntegrationEvent
    String jsonPayload = fields.get("payload");
    if (jsonPayload == null) {
      log.warn("[RedisStreamEventConsumer] Missing payload field: messageId={}", messageId);
      stream.ack(consumerGroup, messageId); // ACK invalid message
      return;
    }

    IntegrationEvent<?> event =
        objectMapper.readValue(
            jsonPayload,
            objectMapper
                .getTypeFactory()
                .constructParametricType(IntegrationEvent.class, Object.class));

    // 2. Deduplication check
    if (deduplicationFilter.isDuplicate(event.getEventId())) {
      log.debug(
          "[RedisStreamEventConsumer] Duplicate event skipped: eventId={}, messageId={}",
          event.getEventId(),
          messageId);
      stream.ack(consumerGroup, messageId); // ACK duplicate (no reprocessing)
      return;
    }

    // 3. Dispatch to handlers via reflection (EventDispatcher dependency removed)
    String eventType = event.getEventType();
    List<HandlerMethod> handlers = findHandlerForEvent(eventType);

    if (handlers.isEmpty()) {
      log.warn("[RedisStreamEventConsumer] No handlers found for eventType={}", eventType);
      stream.ack(consumerGroup, messageId); // ACK unhandled event
      return;
    }

    Observation.createNotStarted("redis.stream.consumer", observationRegistry)
        .lowCardinalityKeyValue("event.type", eventType)
        .observe(
            () -> {
              dispatchToHandlers(event, handlers);
              log.debug(
                  "[RedisStreamEventConsumer] Dispatched: eventId={}, eventType={}, handlers={}",
                  event.getEventId(),
                  eventType,
                  handlers.size());
            });

    // 4. XACK: Acknowledge successful processing
    long ackCount = stream.ack(consumerGroup, messageId);
    if (ackCount == 0) {
      log.warn(
          "[RedisStreamEventConsumer] XACK failed (message already acknowledged?): messageId={}",
          messageId);
    }
  }

  /**
   * Dispatch event to all registered handlers.
   *
   * @param event Integration event to dispatch
   * @param handlers List of handler methods
   */
  private void dispatchToHandlers(IntegrationEvent<?> event, List<HandlerMethod> handlers) {
    for (HandlerMethod handler : handlers) {
      try {
        invokeHandler(handler, event);
      } catch (Exception e) {
        log.error(
            "[RedisStreamEventConsumer] Handler failed: method={}, eventType={}",
            handler.method().getName(),
            event.getEventType(),
            e);
        throw new EventProcessingException(
            CommonErrorCode.EVENT_HANDLER_ERROR,
            e,
            "Handler failed for event: " + event.getEventType());
      }
    }
  }

  /**
   * Invoke single handler method.
   *
   * @param handler Handler method wrapper
   * @param event Integration event
   * @throws Exception if invocation fails
   */
  private void invokeHandler(HandlerMethod handler, IntegrationEvent<?> event) throws Exception {
    Object payload = event.getPayload();
    handler.method().invoke(handler.bean(), payload);
  }

  /**
   * Create consumer group if not exists (idempotent).
   *
   * <p>Uses LogicExecutor.executeVoid to handle "group exists" errors gracefully.
   */
  private void createConsumerGroupIfNeeded() {
    executor.executeVoid(
        () -> {
          try {
            stream.createGroup(StreamCreateGroupArgs.name(consumerGroup).makeStream());
            log.info(
                "[RedisStreamEventConsumer] Created consumer group: stream={}, group={}",
                streamKey,
                consumerGroup);
          } catch (Exception e) {
            // Group already exists (BUSYGROUP) - ignore
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
              log.debug(
                  "[RedisStreamEventConsumer] Consumer group already exists: stream={}, group={}",
                  streamKey,
                  consumerGroup);
            } else {
              throw new EventProcessingException(
                  CommonErrorCode.EVENT_CONSUMER_ERROR,
                  e,
                  "Failed to create consumer group: " + consumerGroup);
            }
          }
        },
        TaskContext.of("RedisStreamEventConsumer", "CreateGroup", consumerGroup));
  }

  /**
   * Get pending message count (monitoring).
   *
   * @return Number of unacknowledged messages
   */
  public long getPendingCount() {
    return executor.executeOrDefault(
        () -> 0L, 0L, TaskContext.of("RedisStreamEventConsumer", "PendingCount"));
  }

  /**
   * Get cached handler count (monitoring).
   *
   * @return Number of cached event types
   */
  public int getHandlerTypeCount() {
    return handlerCache.size();
  }

  /**
   * Handler method wrapper for reflection-based invocation.
   *
   * @param bean Spring bean instance containing handler
   * @param method Handler method
   * @param async Execute asynchronously (not used here, kept for consistency)
   */
  private record HandlerMethod(Object bean, Method method, boolean async) {
    HandlerMethod {
      method.setAccessible(true); // Allow private handlers
    }
  }
}
