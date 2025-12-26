package maple.expectation.service.v2.alert;

import maple.expectation.service.v2.alert.dto.DiscordMessage;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DiscordMessageFactory {

    private static final int ERROR_COLOR = 16711680; // 빨간색

    public DiscordMessage createCriticalEmbed(String title, String description, Exception e) {
        return new DiscordMessage(List.of(
            new DiscordMessage.Embed(
                "🚨 " + title,
                description,
                ERROR_COLOR,
                createFields(e),
                new DiscordMessage.Footer("MapleExpectation Alert System"),
                ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT)
            )
        ));
    }

    private List<DiscordMessage.Field> createFields(Exception e) {
        return List.of(
            new DiscordMessage.Field("📄 Exception Type", e.getClass().getSimpleName(), true),
            new DiscordMessage.Field("💻 Server IP", getServerIp(), true),
            new DiscordMessage.Field("💬 Root Cause", getShortMessage(e), false),
            new DiscordMessage.Field("Stack Trace (Top 5)", "```java\n" + getStackTrace(e) + "\n```", false)
        );
    }

    private String getServerIp() {
        return System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "Unknown";
    }

    private String getShortMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "No message provided";
    }

    private String getStackTrace(Exception e) {
        return Arrays.stream(e.getStackTrace())
                .limit(5)
                .map(StackTraceElement::toString)
                .collect(Collectors.joining("\n"));
    }
}