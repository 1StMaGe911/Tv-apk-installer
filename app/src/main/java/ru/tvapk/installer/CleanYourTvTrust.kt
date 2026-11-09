package ru.tvapk.installer

import android.content.Context
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.Locale

/**
 * Verifies the Clean Your TV add-on catalog exactly as minisign does.
 * The trust root is compiled into this app; downloaded catalog data is never trusted by itself.
 */
internal object CleanYourTvTrust {
    private const val ROOT_KEY = "RWTBm+H9rjMuaX944TBay7uDMxQEC8Q+E8yF1p7hyGl7Fa+8bzBKE4io"
    private const val BUNDLED_KEYS_SEQUENCE = 1L
    private const val MAX_INDEX_BYTES = 1024 * 1024
    private const val MAX_KEYS_BYTES = 64 * 1024
    private const val MAX_SIGNATURE_BYTES = 4096
    private val allowedHosts = setOf(
        "github.com", "api.github.com",
        "objects.githubusercontent.com", "release-assets.githubusercontent.com"
    )
    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val id16 = Regex("^[0-9A-F]{16}$")

    data class CatalogModule(
        val id: String,
        val name: String,
        val summary: String,
        val description: String,
        val packageName: String,
        val type: String,
        val tier: String,
        val publisher: String,
        val source: String,
        val licence: String,
        val versions: List<CatalogVersion>
    )

    data class CatalogVersion(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val signerSha256: String,
        val minSdk: Int,
        val abis: Set<String>,
        val permissions: List<Pair<String, String>>,
        val networkStatement: String,
        val revoked: Boolean
    )

    data class VerifiedCatalog(
        val sequence: Long,
        val expiresAtMillis: Long,
        val modules: List<CatalogModule>,
        val revoked: Set<String>,
        val stale: Boolean,
        val trustedComment: String
    )

    fun loadBundled(context: Context): VerifiedCatalog {
        val index = readAsset(context, "cleanyourtv-catalog/index.json", MAX_INDEX_BYTES)
        val indexSig = readAsset(context, "cleanyourtv-catalog/index.json.minisig", MAX_SIGNATURE_BYTES)
        val keys = readAsset(context, "cleanyourtv-catalog/keys.json", MAX_KEYS_BYTES)
        val keysSig = readAsset(context, "cleanyourtv-catalog/keys.json.minisig", MAX_SIGNATURE_BYTES)
        val prefs = context.getSharedPreferences("cleanyourtv_catalog_trust", Context.MODE_PRIVATE)
        val lastIndex = prefs.getLong("accepted_index_sequence", 0L).takeIf { it > 0L }
        val lastKeys = prefs.getLong("accepted_keys_sequence", 0L).takeIf { it > 0L }
        val verified = verify(index, indexSig, keys, keysSig, lastIndex, System.currentTimeMillis(), lastKeys)
        // Persist high-water marks only after the complete chain of signatures and documents verifies.
        prefs.edit()
            .putLong("accepted_index_sequence", verified.sequence)
            .putLong("accepted_keys_sequence", JSONObject(String(keys, Charsets.UTF_8)).getLong("sequence"))
            .commit()
            .also { persisted -> require(persisted) { "Не удалось сохранить защиту от отката каталога" } }
        verified
    }

    fun verify(
        indexBytes: ByteArray,
        indexSignature: ByteArray,
        keysBytes: ByteArray,
        keysSignature: ByteArray,
        lastAcceptedSequence: Long?,
        nowMillis: Long,
        lastAcceptedKeysSequence: Long? = null
    ): VerifiedCatalog {
        require(keysBytes.size <= MAX_KEYS_BYTES) { "Файл ключей каталога слишком большой" }
        val root = parsePublicKey(ROOT_KEY)
        verifyMinisign(keysBytes, keysSignature, listOf(root))
        val keysJson = JSONObject(String(keysBytes, Charsets.UTF_8))
        require(keysJson.getLong("schema") == 1L) { "Неподдерживаемая схема keys.json" }
        val keysSequence = keysJson.getLong("sequence")
        require(keysSequence >= BUNDLED_KEYS_SEQUENCE) { "Файл ключей старее встроенного доверенного состояния" }
        require(lastAcceptedKeysSequence == null || keysSequence >= lastAcceptedKeysSequence) {
            "Обнаружен откат файла ключей каталога на более старую версию"
        }
        val active = mutableListOf<MiniKey>()
        val revokedKeys = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val keysArray = keysJson.getJSONArray("keys")
        require(keysArray.length() in 1..32) { "Некорректное количество ключей каталога" }
        for (i in 0 until keysArray.length()) {
            val item = keysArray.getJSONObject(i)
            val id = item.getString("id")
            require(id16.matches(id)) { "Некорректный идентификатор ключа" }
            val key = parsePublicKey(item.getString("public_key"))
            require(keyIdHex(key.id) == id) { "Идентификатор ключа не совпадает с открытым ключом" }
            require(!key.id.contentEquals(root.id)) { "Корневой ключ не может подписывать каталог" }
            require(seen.add(id)) { "Повторяющийся ключ каталога" }
            when (item.getString("status")) {
                "active" -> active += key
                "revoked" -> revokedKeys += id
                else -> throw IllegalArgumentException("Неизвестный статус ключа каталога")
            }
        }
        require(active.isNotEmpty()) { "Нет активных ключей каталога" }

        val parsedSignature = parseSignature(indexSignature)
        val signerId = keyIdHex(parsedSignature.keyId)
        require(signerId !in revokedKeys) { "Подпись каталога отозвана" }
        val signer = active.firstOrNull { it.id.contentEquals(parsedSignature.keyId) }
            ?: throw IllegalArgumentException("Каталог подписан неизвестным ключом")
        val trustedComment = verifyMinisign(indexBytes, indexSignature, listOf(signer))

        require(indexBytes.size <= MAX_INDEX_BYTES) { "Каталог слишком большой" }
        val rootJson = JSONObject(String(indexBytes, Charsets.UTF_8))
        require(rootJson.getLong("schema") == 1L) { "Неподдерживаемая схема каталога" }
        val sequence = rootJson.getLong("sequence")
        require(sequence > 0) { "Некорректный номер версии каталога" }
        require(lastAcceptedSequence == null || sequence >= lastAcceptedSequence) {
            "Обнаружен откат каталога на более старую версию"
        }
        val generated = Instant.parse(rootJson.getString("generated")).toEpochMilli()
        val expires = Instant.parse(rootJson.getString("expires")).toEpochMilli()
        require(expires > generated) { "Некорректный срок действия каталога" }
        require(nowMillis < expires) { "Подписанный каталог истёк; обновление списка обязательно" }

        val revoked = mutableSetOf<String>()
        val revokedArray = rootJson.optJSONArray("revoked") ?: JSONArray()
        require(revokedArray.length() <= 5000) { "Слишком много записей отзыва" }
        for (i in 0 until revokedArray.length()) {
            val entry = revokedArray.getJSONObject(i)
            val digest = entry.getString("sha256")
            require(hex64.matches(digest)) { "Некорректный SHA-256 в списке отзыва" }
            if (entry.has("reason")) require(entry.getString("reason").length <= 200) { "Слишком длинная причина отзыва" }
            revoked += digest
        }

        val modulesArray = rootJson.getJSONArray("modules")
        require(modulesArray.length() <= 500) { "Слишком много модулей в каталоге" }
        val ids = mutableSetOf<String>()
        val packages = mutableSetOf<String>()
        val modules = ArrayList<CatalogModule>(modulesArray.length())
        for (i in 0 until modulesArray.length()) {
            val m = modulesArray.getJSONObject(i)
            val id = m.getString("id")
            require(Regex("^[a-z0-9][a-z0-9-]{0,39}$").matches(id) && ids.add(id)) { "Некорректный или повторный id модуля" }
            val pkg = m.getString("package")
            require(pkg.length <= 255 && Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(pkg) && packages.add(pkg)) {
                "Некорректный или повторный package name"
            }
            val source = m.getString("source")
            require(isAllowedHttps(source)) { "Недопустимый адрес страницы проекта" }
            val versionsJson = m.getJSONArray("versions")
            require(versionsJson.length() in 1..20) { "Некорректное количество версий модуля" }
            val versions = ArrayList<CatalogVersion>(versionsJson.length())
            val versionCodes = mutableSetOf<Long>()
            var signerRevoked = false
            for (j in 0 until versionsJson.length()) {
                val v = versionsJson.getJSONObject(j)
                val code = v.getLong("version_code")
                require(code in 1..Int.MAX_VALUE.toLong() && versionCodes.add(code)) { "Некорректный versionCode" }
                val apk = v.getJSONObject("apk")
                val url = apk.getString("url")
                require(isAllowedHttps(url)) { "Недопустимый адрес APK" }
                val size = apk.getLong("size")
                require(size in 1..(200L * 1024L * 1024L)) { "Недопустимый размер APK" }
                val sha = apk.getString("sha256")
                val signerSha = apk.getString("signer_sha256")
                require(hex64.matches(sha) && hex64.matches(signerSha)) { "Некорректная контрольная сумма APK" }
                if (signerSha in revoked) signerRevoked = true
                val artifactRevoked = sha in revoked
                val abisJson = v.getJSONArray("abis")
                require(abisJson.length() <= 4) { "Слишком много ABI" }
                val abis = linkedSetOf<String>()
                for (k in 0 until abisJson.length()) {
                    val abi = abisJson.getString(k)
                    require(abi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) { "Неизвестная архитектура APK" }
                    abis += abi
                }
                val permsJson = v.getJSONArray("permissions")
                require(permsJson.length() <= 64) { "Слишком много разрешений APK" }
                val perms = ArrayList<Pair<String, String>>(permsJson.length())
                for (k in 0 until permsJson.length()) {
                    val p = permsJson.getJSONObject(k)
                    val permission = p.getString("permission")
                    require(permission.length <= 160 && Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(permission)) {
                        "Некорректное разрешение APK"
                    }
                    perms += permission to p.getString("plain_words").take(200)
                }
                versions += CatalogVersion(
                    code, v.getString("version_name"), url, size, sha, signerSha,
                    v.getInt("min_sdk"), abis, perms, v.getString("network").take(300), artifactRevoked || signerSha in revoked
                )
            }
            val tier = if (signerRevoked) "revoked" else m.getString("tier")
            require(tier in setOf("verified", "community", "quarantined", "revoked")) { "Неизвестный уровень доверия модуля" }
            modules += CatalogModule(
                id, m.getString("name").take(40), m.getString("one_line").take(80),
                m.getString("description").take(2000), pkg, m.getString("type").take(20), tier,
                m.getString("publisher").take(60), source, m.getString("licence").take(40),
                versions.sortedByDescending { it.versionCode }
            )
        }
        return VerifiedCatalog(sequence, expires, modules, revoked, false, trustedComment)
    }

    private data class MiniKey(val id: ByteArray, val publicKey: ByteArray)
    private data class MiniSignature(
        val prehashed: Boolean, val keyId: ByteArray, val signature: ByteArray,
        val trustedComment: String, val globalSignature: ByteArray
    )

    private fun parsePublicKey(text: String): MiniKey {
        require(text.length <= 1024) { "Файл открытого ключа слишком большой" }
        val line = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("untrusted comment:") }
            ?: throw IllegalArgumentException("Открытый ключ не найден")
        val raw = Base64.getDecoder().decode(line)
        require(raw.size == 42 && raw[0] == 69.toByte() && raw[1] == 100.toByte()) { "Неверный формат minisign-ключа" }
        return MiniKey(raw.copyOfRange(2, 10), raw.copyOfRange(10, 42))
    }

    private fun parseSignature(bytes: ByteArray): MiniSignature {
        require(bytes.size <= MAX_SIGNATURE_BYTES) { "Файл подписи слишком большой" }
        val lines = String(bytes, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        require(lines.size == 4 && lines[0].startsWith("untrusted comment:") && lines[2].startsWith("trusted comment: ")) {
            "Некорректный формат minisign-подписи"
        }
        val raw = Base64.getDecoder().decode(lines[1].trim())
        require(raw.size == 74) { "Неверная длина minisign-подписи" }
        val prehashed = when {
            raw[0] == 69.toByte() && raw[1] == 68.toByte() -> true
            raw[0] == 69.toByte() && raw[1] == 100.toByte() -> false
            else -> throw IllegalArgumentException("Неизвестный алгоритм minisign")
        }
        val global = Base64.getDecoder().decode(lines[3].trim())
        require(global.size == 64) { "Неверная длина глобальной подписи" }
        val comment = lines[2].removePrefix("trusted comment: ")
        require(comment.toByteArray(Charsets.UTF_8).size <= 1024) { "Слишком длинный trusted comment" }
        return MiniSignature(prehashed, raw.copyOfRange(2, 10), raw.copyOfRange(10, 74), comment, global)
    }

    private fun verifyMinisign(data: ByteArray, signatureBytes: ByteArray, keys: List<MiniKey>): String {
        val sig = parseSignature(signatureBytes)
        val key = keys.firstOrNull { it.id.contentEquals(sig.keyId) }
            ?: throw IllegalArgumentException("Идентификатор ключа подписи не совпадает")
        val message = if (sig.prehashed) {
            val digest = Blake2bDigest(512)
            digest.update(data, 0, data.size)
            ByteArray(64).also { digest.doFinal(it, 0) }
        } else data
        require(Ed25519.verify(sig.signature, 0, key.publicKey, 0, message, 0, message.size)) {
            "Криптографическая проверка подписи не пройдена"
        }
        val globalMessage = sig.signature + sig.trustedComment.toByteArray(Charsets.UTF_8)
        require(Ed25519.verify(sig.globalSignature, 0, key.publicKey, 0, globalMessage, 0, globalMessage.size)) {
            "Глобальная подпись trusted comment не пройдена"
        }
        return sig.trustedComment
    }

    private fun keyIdHex(id: ByteArray): String =
        id.reversedArray().joinToString("") { "%02X".format(it) }

    private fun isAllowedHttps(value: String): Boolean {
        if (value.length > 2048) return false
        val uri = try { URI(value) } catch (_: Exception) { return false }
        if (!uri.isAbsolute || uri.isOpaque || uri.scheme != "https") return false
        if (uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port != 443)) return false
        return uri.host?.lowercase(Locale.ROOT) in allowedHosts
    }

    private fun readAsset(context: Context, name: String, limit: Int): ByteArray =
        context.assets.open(name).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Встроенный файл каталога слишком большой: $name" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
}
