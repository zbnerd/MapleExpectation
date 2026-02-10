package maple.expectation.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipUtils {

  private GzipUtils() {
  }

  /**
   * 문자열을 GZIP 압축합니다.
   *
   * @param str 압축할 문자열
   * @return 압축된 바이트 배열
   * @throws IOException 압축 중 I/O 오류 발생 시
   */
  public static byte[] compress(String str) throws IOException {
    if (str == null || str.isBlank()) {
      return new byte[0];
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GZIPOutputStream gzip = new GZIPOutputStream(out);
    gzip.write(str.getBytes(StandardCharsets.UTF_8));
    gzip.finish();
    return out.toByteArray();
  }

  /**
   * GZIP 압축된 바이트 배열을 압축 해제합니다.
   *
   * @param compressed 압축된 바이트 배열
   * @return 압축 해제된 문자열
   * @throws IOException 압축 해제 중 I/O 오류 발생 시
   */
  public static String decompress(byte[] compressed) throws IOException {
    if (compressed == null || compressed.length == 0) {
      return "";
    }

    if (!isGzipped(compressed)) {
      return new String(compressed, StandardCharsets.UTF_8);
    }

    GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
    return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static boolean isGzipped(byte[] data) {
    return data.length >= 2
        && data[0] == (byte) (GZIPInputStream.GZIP_MAGIC)
        && data[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8);
  }
}
