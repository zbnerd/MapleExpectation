package maple.expectation.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 문자열을 GZIP 압축합니다.
 *
 * @param str 압축할 문자열
 * @return 압축된 바이트 배열
 * @throws IOException 압축 중 I/O 오류 발생 시
 */
@Throws(IOException::class)
fun compress(str: String?): ByteArray {
    return when {
        str.isNullOrBlank() -> ByteArray(0)
        else -> {
            ByteArrayOutputStream().use { out ->
                GZIPOutputStream(out).use { gzip ->
                    gzip.write(str.toByteArray(StandardCharsets.UTF_8))
                    gzip.finish()
                }
                out.toByteArray()
            }
        }
    }
}

/**
 * GZIP 압축된 바이트 배열을 압축 해제합니다.
 *
 * @param compressed 압축된 바이트 배열
 * @return 압축 해제된 문자열
 * @throws IOException 압축 해제 중 I/O 오류 발생 시
 */
@Throws(IOException::class)
fun decompress(compressed: ByteArray?): String {
    return when {
        compressed == null || compressed.isEmpty() -> ""
        !isGzipped(compressed) -> String(compressed, StandardCharsets.UTF_8)
        else -> {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                String(gzip.readAllBytes(), StandardCharsets.UTF_8)
            }
        }
    }
}

/**
 * 주어진 바이트 배열이 GZIP으로 압축되었는지 확인합니다.
 */
private fun isGzipped(data: ByteArray): Boolean {
    return data.size >= 2
            && data[0] == (GZIPInputStream.GZIP_MAGIC).toByte()
            && data[1] == (GZIPInputStream.GZIP_MAGIC shr 8).toByte()
}