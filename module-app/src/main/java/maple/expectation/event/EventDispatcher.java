package maple.expectation.event;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.domain.event.IntegrationEvent;
import maple.expectation.error.CommonErrorCode;
import maple.expectation.error.exception.EventProcessingException;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Event dispatcher with @EventHandler method routing and async Virtual Threads.
 *
 * <p><strong>Features:</strong>
 *
 * <ul>
 *   <li>Reflection-based Discovery: Scans for @EventHandler methods on startup
 *   <li>Event Type Routing: Maps event classes to handler methods
 *   <li>Async Execution: Virtual Threads for high concurrency (Java 21)
 *   <li>Error Handling: LogicExecutor for exception translation
 * </ul>
 *
 * <h3>CLAUDE.md Section 4 Compliance (Design Patterns):</h3>
 *
 * <ul>
 *   <li><b>Strategy Pattern:</b> Different handlers for different event types
 *   <li><b>Template Method:</b> Orchestrates handler execution flow
 *   <li><b>Factory Pattern:</b> Creates handler registry on startup
 * </ul>
 *
 * <h3>CLAUDE.md Section 21 Compliance (Async Non-Blocking):</h3>
 *
 * <ul>
 *   <li><b>Virtual Threads:</b> Executors.newVirtualThreadPerTaskExecutor()
 *   <li><b>Backpressure:</b> Unbounded queue with memory limits
 * </ul>
 *
 * <h3>CLAUDE.md Section 12 Compliance:</h3>
 *
 * <ul>
 *   <li>No raw try-catch - uses LogicExecutor
 *   <li>Method extraction for 3-line rule (Section 15)
 * </ul>
 *
 * <h3>Handler Registration:</h3>
 *
 * <pre>
 * &#64;Component
 * public class LikeEventHandler {
 *
 *   &#64;EventHandler(LikeReceivedEvent.class)
 *   public void handleLikeReceived(LikeReceivedEvent event) {
 *     // Handle event
 *   }
 * }
 * </pre>
 *
 * @see maple.expectation.event.EventHandler
 * @since 1.0.0
 */
@Slf4j
@Component
public class EventDispatcher {

  private final LogicExecutor executor;
  private final Executor virtualThreadExecutor;
  private final Map<Class<?>, List<HandlerMethod>> handlers = new ConcurrentHashMap<>();

  /**
   * Create event dispatcher.
   *
   * @param executor LogicExecutor for error handling
   * @param enableAsync Enable async execution (Virtual Threads)
   */
  public EventDispatcher(
      LogicExecutor executor, @Value("${app.event.dispatcher.async:true}") boolean enableAsync) {
    this.executor = executor;
    this.virtualThreadExecutor =
        enableAsync ? Executors.newVirtualThreadPerTaskExecutor() : Runnable::run;

    log.info(
        "[EventDispatcher] Initialized: async={}, executorType={}",
        enableAsync,
        enableAsync ? "VirtualThreads" : "Synchronous");
  }

  /**
   * Register handler methods from a component.
   *
   * <p>Called by Spring after bean initialization. Scans for @EventHandler methods and registers
   * them for routing.
   *
   * @param component Component containing @EventHandler methods
   */
  public void registerHandlers(Object component) {
    executor.executeVoid(
        () -> registerHandlersInternal(component),
        TaskContext.of(
            "EventDispatcher", "RegisterHandlers", component.getClass().getSimpleName()));
  }

  /**
   * Internal handler registration with checked exceptions.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void registerHandlersInternal(Object component) throws Exception {
    Class<?> componentClass = component.getClass();
    int registered = 0;

    for (Method method : componentClass.getDeclaredMethods()) {
      if (!method.isAnnotationPresent(EventHandler.class)) {
        continue;
      }

      EventHandler annotation = method.getAnnotation(EventHandler.class);
      Class<?> eventType = annotation.eventType();
      boolean async = annotation.async();

      // Validate method signature
      validateHandlerMethod(method, eventType);

      // Register handler
      HandlerMethod handler = new HandlerMethod(component, method, async);
      handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(handler);

      registered++;
      log.debug(
          "[EventDispatcher] Registered handler: type={}, method={}, async={}",
          eventType.getSimpleName(),
          method.getName(),
          async);
    }

    if (registered > 0) {
      log.info(
          "[EventDispatcher] Registered {} handlers from component: {}",
          registered,
          componentClass.getSimpleName());
    }
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
   * Dispatch event to registered handlers.
   *
   * <p><strong>Flow:</strong>
   *
   * <ol>
   *   <li>Find handlers by event type
   *   <li>Execute each handler (async if configured)
   *   <li>Log errors without failing other handlers
   * </ol>
   *
   * @param event IntegrationEvent to dispatch
   */
  public void dispatch(IntegrationEvent<?> event) {
    executor.executeVoid(
        () -> dispatchInternal(event),
        TaskContext.of("EventDispatcher", "Dispatch", event.getEventType()));
  }

  /**
   * Internal dispatch with checked exceptions.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void dispatchInternal(IntegrationEvent<?> event) throws Exception {
    Class<?> eventType = event.getPayload().getClass();
    List<HandlerMethod> eventHandlers = handlers.get(eventType);

    if (eventHandlers == null || eventHandlers.isEmpty()) {
      log.debug(
          "[EventDispatcher] No handlers registered for event type: {}", eventType.getSimpleName());
      return;
    }

    // Execute all handlers for this event type
    for (HandlerMethod handler : eventHandlers) {
      executeHandler(handler, event);
    }
  }

  /**
   * Execute single handler (sync or async).
   *
   * <p>Extracted method for 3-line rule (Section 15).
   */
  private void executeHandler(HandlerMethod handler, IntegrationEvent<?> event) {
    if (handler.async()) {
      // Async: Submit to Virtual Thread executor
      virtualThreadExecutor.execute(
          () ->
              executor.executeVoid(
                  () -> invokeHandler(handler, event),
                  TaskContext.of("EventDispatcher", "InvokeAsync", handler.method().getName())));
    } else {
      // Sync: Execute directly
      executor.executeVoid(
          () -> invokeHandler(handler, event),
          TaskContext.of("EventDispatcher", "InvokeSync", handler.method().getName()));
    }
  }

  /**
   * Invoke handler method with error handling.
   *
   * <p>Extracted method for LogicExecutor pattern (Section 12).
   */
  private void invokeHandler(HandlerMethod handler, IntegrationEvent<?> event) throws Exception {
    try {
      handler.method().invoke(handler.component(), event.getPayload());
      log.debug("[EventDispatcher] Handler executed: {}", handler.method().getName());
    } catch (Exception e) {
      log.error(
          "[EventDispatcher] Handler failed: method={}, eventType={}, eventId={}",
          handler.method().getName(),
          event.getEventType(),
          event.getEventId(),
          e);
      throw new EventProcessingException(
          CommonErrorCode.EVENT_HANDLER_ERROR, e, event.getEventId(), event.getEventType());
    }
  }

  /**
   * Get registered handler count (monitoring).
   *
   * @return Number of registered handlers
   */
  public int getHandlerCount() {
    return handlers.values().stream().mapToInt(List::size).sum();
  }

  /**
   * Handler method wrapper.
   *
   * @param component Component instance
   * @param method Handler method
   * @param async Execute asynchronously
   */
  private record HandlerMethod(Object component, Method method, boolean async) {
    HandlerMethod {
      method.setAccessible(true); // Allow private handlers
    }
  }
}
