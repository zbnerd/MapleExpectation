package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Profile("!prod")
public class AlertTestController {

  private final DiscordAlertService alertService;
  private final LogicExecutor executor; // ✅ 지능형 실행기 주입

  @PostMapping("/api/admin/test/alert")
  public String triggerTestAlert() {
    TaskContext context = TaskContext.of("Admin", "TestAlert");

    // ✅  테스트용 try-catch조차 허용하지 않는 철저함! 👊🔥
    executor.executeVoid(
        () -> {
          // 1. 강제로 테스트용 예외 생성 (익명 객체나 즉석 생성 활용)
          RuntimeException testEx = new RuntimeException("배포 후 알림 시스템 점검용 테스트 에러입니다.");

          // 2. 알림 서비스 호출
          alertService.sendCriticalAlert(
              "[TEST] 배포 점검 알림", "이 알림은 실제 에러가 아닙니다. 알림 시스템 작동 여부를 확인 중입니다.", testEx);
        },
        context);

    return "알림 발송 요청 완료 (Discord 및 서버 로그를 확인하세요)";
  }
}
