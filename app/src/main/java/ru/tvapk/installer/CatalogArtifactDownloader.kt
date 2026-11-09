package ru.tvapk.installer

import android.content.Context
import android.system.Os
import ru.tvapk.installer.CleanYourTvTrust.CatalogVersion
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Bounded, resumable downloader for APK artifacts described by a verified signed catalog.
 * A partial file is resumed only with its saved ETag; every redirect is revalidated.
 */
internal object CatalogArtifactDownloader {
    private val allowedHosts = setOf(
        "github.com", "api.github.com",
        "objects.githubusercontent.com", "release-assets.githubusercontent.com"
    )
    private const val MAX_REDIRECTS = 5
    private const val MAX_ATTEMPTS = 3

    fun download(
        context: Context,
        version: CatalogVersion,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        require(version.size in 1..(200L * 1024L * 1024L)) { "Недопустимый размер APK" }
        require(version.sha256.matches(Regex("^[0-9a-f]{64}$"))) { "Некорректный SHA-256 APK" }
        require(isAllowedUrl(version.apkUrl)) { "Источник APK не входит в список доверенных HTTPS-хостов" }

        val directory = File(context.cacheDir, "catalog-artifacts")
        require(directory.isDirectory || directory.mkdirs()) { "Не удалось создать папку загрузок" }
        val partial = File(directory, "${version.sha256}.part")
        val etagFile = File(directory, "${version.sha256}.etag")
        val complete = File(directory, "${version.sha256}.apk")

        if (complete.isFile && complete.length() == version.size && sha256(complete) == version.sha256) {
            onProgress(version.size, version.size)
            return complete
        }
        if (complete.exists()) complete.delete()

        repeat(MAX_ATTEMPTS) { attempt ->
            var offset = partial.length()
            var etag = etagFile.takeIf { it.isFile }?.readText()?.take(512)?.takeIf { it.isNotBlank() }
            if (offset > version.size || (offset > 0L && etag == null)) {
                partial.delete()
                etagFile.delete()
                offset = 0L
            }
            if (offset == version.size) {
                if (sha256(partial) == version.sha256) {
                    Os.rename(partial.absolutePath, complete.absolutePath)
                    etagFile.delete()
                    return complete
                }
                partial.delete()
                etagFile.delete()
                offset = 0L
            }

            val response = openFollowingRedirects(version.apkUrl, offset.takeIf { it > 0L }, etag)
            try {
                val code = response.responseCode
                if (code == 416 && offset > 0L) {
                    partial.delete()
                    etagFile.delete()
                    return@repeat
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL && offset > 0L
                if (code != HttpURLConnection.HTTP_OK && !append) {
                    throw IOException("Сервер вернул HTTP $code при загрузке APK")
                }
                if (append) {
                    val range = response.getHeaderField("Content-Range") ?: ""
                    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(range)
                        ?: throw IOException("Сервер вернул некорректный Content-Range")
                    require(match.groupValues[1].toLong() == offset &&
                        match.groupValues[3].toLong() == version.size) {
                        "Сервер продолжил загрузку не с того места или изменил размер файла"
                    }
                } else if (offset > 0L) {
                    // Server ignored Range or If-Range no longer matches: start from byte zero.
                    partial.delete()
                    etagFile.delete()
                    offset = 0L
                }

                val expectedBody = version.size - offset
                val contentLength = response.getHeaderFieldLong("Content-Length", -1L)
                require(contentLength < 0L || contentLength == expectedBody) {
                    "Размер ответа сервера не совпадает с каталогом"
                }
                val responseEtag = response.getHeaderField("ETag")?.take(512)?.takeIf { it.isNotBlank() }
                if (offset == 0L) {
                    if (responseEtag != null) etagFile.writeText(responseEtag)
                    else etagFile.delete()
                } else {
                    require(responseEtag == null || responseEtag == etag) {
                        "ETag изменился во время возобновления загрузки"
                    }
                }

                val mode = if (append) true else false
                val start = offset
                response.inputStream.use { input ->
                    FileOutputStream(partial, mode).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = start
                        onProgress(total, version.size)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(total + count <= version.size) { "APK превышает размер, указанный в каталоге" }
                            output.write(buffer, 0, count)
                            total += count
                            onProgress(total, version.size)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                require(partial.length() == version.size) {
                    "Загрузка оборвалась: получено ${partial.length()} из ${version.size} байт"
                }
                require(sha256(partial) == version.sha256) {
                    partial.delete()
                    etagFile.delete()
                    "SHA-256 загруженного APK не совпадает с подписанным каталогом"
                }
                Os.rename(partial.absolutePath, complete.absolutePath)
                etagFile.delete()
                return complete
            } catch (e: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw e
                // Keep a bounded partial only if it can be resumed safely with a validator.
                if (partial.length() > version.size || !etagFile.isFile) {
                    partial.delete()
                    etagFile.delete()
                }
            } finally {
                response.disconnect()
            }
        }
        throw IOException("Не удалось завершить загрузку APK")
    }

    private fun openFollowingRedirects(url: String, offset: Long?, etag: String?): HttpURLConnection {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            require(isAllowedUrl(current)) { "Перенаправление ведёт на недоверенный хост" }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 45_000
                useCaches = false
                setRequestProperty("User-Agent", "TV-APK-Installer")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset != null) {
                    setRequestProperty("Range", "bytes=$offset-")
                    if (etag != null) setRequestProperty("If-Range", etag)
                }
            }
            val code = connection.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Перенаправление без адреса" }
                current = URL(URL(current), location).toString()
                continue
            }
            return connection
        }
        throw IOException("Слишком много перенаправлений при загрузке APK")
    }

    private fun isAllowedUrl(value: String): Boolean {
        if (value.length > 2048) return false
        val uri = try { URI(value) } catch (_: Exception) { return false }
        return uri.isAbsolute && !uri.isOpaque &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443) &&
            uri.host?.lowercase(Locale.ROOT) in allowedHosts
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
