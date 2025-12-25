package maple.expectation.util;

import maple.expectation.global.error.exception.CompressionException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipUtils {

    // 문자열 -> 압축된 byte[]
    public static byte[] compress(String str) {
        if (str == null || str.isEmpty()) return new byte[0];
        
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new CompressionException(e.toString());
        }
    }

    // 압축된 byte[] -> 문자열
    public static String decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return "";

        try (ByteArrayInputStream in = new ByteArrayInputStream(compressed);
             GZIPInputStream gzip = new GZIPInputStream(in)) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompressionException(e.toString());
        }
    }
}