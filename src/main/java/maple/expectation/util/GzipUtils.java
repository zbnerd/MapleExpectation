package maple.expectation.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import maple.expectation.global.error.exception.CompressionException;

@NoArgsConstructor(access = AccessLevel.PRIVATE) // 유틸 클래스는 생성 방지
public class GzipUtils {

  public static byte[] compress(String str) {
    if (str == null || str.isBlank()) return new byte[0];

    try (ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      gzip.write(str.getBytes(StandardCharsets.UTF_8));
      gzip.finish(); // 명시적으로 스트림 종료
      return out.toByteArray();
    } catch (IOException e) {
      throw new CompressionException("압축 중 오류 발생: " + e.getMessage());
    }
  }

  public static String decompress(byte[] compressed) {
    if (compressed == null || compressed.length == 0) return "";

    // GZIP 포맷인지 확인 (Magic Number 체크)
    if (!isGzipped(compressed)) {
      return new String(compressed, StandardCharsets.UTF_8);
    }

    try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new CompressionException("압축 해제 중 오류 발생: " + e.getMessage());
    }
  }

  private static boolean isGzipped(byte[] data) {
    return data.length >= 2
        && data[0] == (byte) (GZIPInputStream.GZIP_MAGIC)
        && data[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8);
  }
}
