@file:JvmName("ResourceLoader")

package maple.expectation.common.resource

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Utility class for loading resources from classpath.
 *
 * <p><strong>SRP:</strong> Single responsibility - resource loading only.
 *
 * <p><strong>Exception Handling:</strong> Converts IOException to RuntimeException
 *
 * <h3>Usage:</h3>
 *
 * ```kotlin
 * val luaScript = ResourceLoader().loadResourceAsString("lua/script.lua")
 * ```
 */
class ResourceLoader {

    /**
     * Load resource from classpath as String.
     *
     * @param path Resource path (e.g., "lua/script.lua")
     * @return Resource content as UTF-8 string
     * @throws IllegalStateException if resource not found or read error occurs
     */
    fun loadResourceAsString(path: String): String {
        getResourceAsStream(path).use { inputStream ->
            return String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    /**
     * Load resource from classpath as InputStream.
     *
     * <p>Caller is responsible for closing the stream.
     *
     * @param path Resource path
     * @return InputStream (caller must close)
     * @throws IllegalStateException if resource not found
     */
    fun loadResourceAsStream(path: String): InputStream {
        return getResourceAsStream(path)
    }

    /**
     * Get resource stream from classpath.
     *
     * @param path Resource path
     * @return InputStream
     * @throws IllegalStateException if resource not found
     */
    private fun getResourceAsStream(path: String): InputStream {
        val inputStream = javaClass.classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Required resource not found: $path")
        return inputStream
    }
}
