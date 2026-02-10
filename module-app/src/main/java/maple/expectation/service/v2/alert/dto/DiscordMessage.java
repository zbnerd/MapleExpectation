package maple.expectation.service.v2.alert.dto;

import java.util.List;

public record DiscordMessage(List<Embed> embeds) {
  public record Embed(
      String title,
      String description,
      Integer color,
      List<Field> fields,
      Footer footer,
      String timestamp) {}

  public record Field(String name, String value, boolean inline) {}

  public record Footer(String text) {}
}
