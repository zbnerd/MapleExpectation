package maple.expectation.infrastructure.aop.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * SkipEquipmentL2CacheContext 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>검증 대상</h3>
 *
 * <ul>
 *   <li>V5: ThreadLocal → MDC 마이그레이션
 *   <li>B1: "진짜 restore" 패턴 - prev==null이면 MDC.remove()
 *   <li>B2: snapshot()/restore() API를 통한 async 전파
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): MDC 기반 동작 검증
 *   <li>Purple (Auditor): 기존 API 100% 호환성 검증
 * </ul>
 */
class SkipEquipmentL2CacheContextTest {

  @AfterEach
  void cleanup() {
    // 테스트 후 MDC 상태 정리
    SkipEquipmentL2CacheContext.restore((String) null);
  }

  // ==================== 기본 동작 ====================

  @Nested
  @DisplayName("기본 동작")
  class BasicBehavior {

    @Test
    @DisplayName("초기 상태에서 enabled()는 false")
    void enabled_shouldReturnFalse_whenNotSet() {
      // when & then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
    }

    @Test
    @DisplayName("withSkip() 블록 내에서 enabled()는 true")
    void enabled_shouldReturnTrue_insideWithSkipBlock() throws Exception {
      // when
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        // then
        assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();
      }
    }

    @Test
    @DisplayName("withSkip() 블록 종료 후 enabled()는 false로 복원")
    void enabled_shouldReturnFalse_afterWithSkipBlock() throws Exception {
      // given
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();

      // when
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();
      }

      // then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
    }
  }

  // ==================== B1: 진짜 restore 패턴 ====================

  @Nested
  @DisplayName("B1: 진짜 restore 패턴 (prev==null이면 remove)")
  class B1_TrueRestorePattern {

    @Test
    @DisplayName("중첩 withSkip() 호출 시 외부 블록의 상태로 복원")
    void nestedWithSkip_shouldRestoreToPreviousState() throws Exception {
      // given: 초기 상태 false
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();

      // when: 첫 번째 블록
      try (var outer = SkipEquipmentL2CacheContext.withSkip()) {
        assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();

        // when: 중첩된 두 번째 블록
        try (var inner = SkipEquipmentL2CacheContext.withSkip()) {
          assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();
        }

        // then: 내부 블록 종료 후 여전히 true (외부 블록의 상태)
        assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();
      }

      // then: 외부 블록 종료 후 false로 복원
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
    }

    @Test
    @DisplayName("restore(null)은 MDC를 완전히 제거")
    void restore_withNull_shouldRemoveMdc() {
      // given: 플래그 설정
      SkipEquipmentL2CacheContext.restore("true");
      assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();

      // when: null로 복원
      SkipEquipmentL2CacheContext.restore((String) null);

      // then: 완전히 제거됨
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
      assertThat(SkipEquipmentL2CacheContext.snapshot()).isNull();
    }
  }

  // ==================== B2: snapshot/restore API ====================

  @Nested
  @DisplayName("B2: snapshot/restore API (async 전파용)")
  class B2_SnapshotRestoreApi {

    @Test
    @DisplayName("snapshot()은 현재 상태를 캡처")
    void snapshot_shouldCaptureCurrentState() throws Exception {
      // given: 초기 상태
      assertThat(SkipEquipmentL2CacheContext.snapshot()).isNull();

      // when: withSkip() 내에서 snapshot
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        String snap = SkipEquipmentL2CacheContext.snapshot();

        // then: V5 - MDC 값은 "true"
        assertThat(snap).isEqualTo("true");
      }
    }

    @Test
    @DisplayName("restore()는 이전 상태로 복원")
    void restore_shouldSetToGivenValue() {
      // given
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();

      // when: "true"로 설정
      SkipEquipmentL2CacheContext.restore("true");

      // then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();

      // when: null로 복원 (MDC 제거)
      SkipEquipmentL2CacheContext.restore((String) null);

      // then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
    }

    @Test
    @DisplayName("다른 스레드로 컨텍스트 전파 - snapshot/restore 패턴")
    void snapshotRestore_shouldPropagateToOtherThread() throws Exception {
      // given
      AtomicBoolean workerResult = new AtomicBoolean(false);
      CountDownLatch latch = new CountDownLatch(1);

      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        // 메인 스레드에서 snapshot
        String snap = SkipEquipmentL2CacheContext.snapshot();
        assertThat(snap).isEqualTo("true");

        // when: 다른 스레드에서 restore 후 확인
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(
            () -> {
              String before = SkipEquipmentL2CacheContext.snapshot();
              SkipEquipmentL2CacheContext.restore(snap);
              try {
                workerResult.set(SkipEquipmentL2CacheContext.enabled());
              } finally {
                SkipEquipmentL2CacheContext.restore(before);
                latch.countDown();
              }
            });

        // then
        latch.await(5, TimeUnit.SECONDS);
        assertThat(workerResult.get()).isTrue();

        // CLAUDE.md Section 23: shutdown() 후 awaitTermination() 필수
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
      }
    }

    @Test
    @DisplayName("스레드풀에서 컨텍스트 누수 방지 - before로 원복")
    void snapshotRestore_shouldPreventLeakInThreadPool() throws Exception {
      // given
      AtomicReference<String> afterTaskSnapshot = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);

      ExecutorService executor = Executors.newSingleThreadExecutor();

      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        String snap = SkipEquipmentL2CacheContext.snapshot();

        // when: 워커에서 작업 후 원복
        executor.submit(
            () -> {
              String before = SkipEquipmentL2CacheContext.snapshot();
              SkipEquipmentL2CacheContext.restore(snap);
              try {
                // 작업 수행 (enabled() == true)
                assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();
              } finally {
                SkipEquipmentL2CacheContext.restore(before);
              }
            });

        // 같은 스레드에서 다음 작업 - 이전 작업의 컨텍스트가 남아있으면 안됨
        executor.submit(
            () -> {
              afterTaskSnapshot.set(SkipEquipmentL2CacheContext.snapshot());
              latch.countDown();
            });
      }

      // then: 원복되어 null이어야 함
      latch.await(5, TimeUnit.SECONDS);
      assertThat(afterTaskSnapshot.get()).isNull();

      // CLAUDE.md Section 23: shutdown() 후 awaitTermination() 필수
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  // ==================== V5: MDC 기반 추가 테스트 ====================

  @Nested
  @DisplayName("V5: MDC 기반 기능")
  class V5_MdcBased {

    @Test
    @DisplayName("MDC에 skipL2Cache 키가 설정됨")
    void mdcKey_shouldBeSet_whenWithSkip() throws Exception {
      // given: 초기 상태 - MDC 비어있음
      assertThat(MDC.get("skipL2Cache")).isNull();

      // when
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        // then: MDC에 값 설정됨
        assertThat(MDC.get("skipL2Cache")).isEqualTo("true");
      }

      // then: 블록 종료 후 MDC 정리됨
      assertThat(MDC.get("skipL2Cache")).isNull();
    }

    @Test
    @DisplayName("String based restore - 새로운 API 사용")
    void stringRestore_shouldWorkWithNewAPI() {
      // given
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();

      // when: 새로운 String 기반 API 사용 ("true" = enabled)
      SkipEquipmentL2CacheContext.restore("true");

      // then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isTrue();

      // when: null로 복원 (disable)
      SkipEquipmentL2CacheContext.restore((String) null);

      // then
      assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
    }

    @Test
    @DisplayName("snapshot/restore 패턴 - async 전파 지원")
    @SuppressWarnings("deprecation")
    void snapshotRestore_shouldSupportAsyncPropagation() throws Exception {
      // given: 현재 상태 캡처
      String before = SkipEquipmentL2CacheContext.snapshot();
      assertThat(before).isNull();

      // when: 스킵 활성화 후 스냅샷
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        String duringSkip = SkipEquipmentL2CacheContext.snapshot();
        assertThat(duringSkip).isEqualTo("true");

        // then: 복원
        SkipEquipmentL2CacheContext.restore(before);
        assertThat(SkipEquipmentL2CacheContext.enabled()).isFalse();
      }
    }

    @Test
    @DisplayName("로그에서 MDC 값 확인 가능 - Observability")
    void mdcValue_shouldBeVisibleInLogs() throws Exception {
      // when
      try (var ignored = SkipEquipmentL2CacheContext.withSkip()) {
        // then: MDC.getCopyOfContextMap()에서 확인 가능
        var contextMap = MDC.getCopyOfContextMap();
        assertThat(contextMap).containsEntry("skipL2Cache", "true");
      }
    }
  }
}
