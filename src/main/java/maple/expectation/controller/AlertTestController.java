package maple.expectation.controller;

import lombok.RequiredArgsConstructor;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Profile("!prod")
public class AlertTestController {

    private final DiscordAlertService alertService;

    @PostMapping("/api/admin/test/alert")
    public String triggerTestAlert() {
        // 실제 에러 상황과 똑같은 메시지로 알림 발송 모듈만 호출
        try {
            // 강제로 테스트용 예외 생성
            throw new RuntimeException("배포 후 알림 시스템 점검용 테스트 에러입니다.");
        } catch (Exception e) {
            // 위에서 작성하신 알림 메서드 호출
            alertService.sendCriticalAlert(
                    "[TEST] 배포 점검 알림",
                    "이 알림은 실제 에러가 아닙니다. 알림 시스템 작동 여부를 확인 중입니다.",
                    e
            );
        }
        return "알림 발송 요청 완료 (Discord 및 서버 로그를 확인하세요)";
    }
}
