package rdpmobile.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.security.keystore.KeyPermanentlyInvalidatedException
import org.json.JSONException
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Resolution(val label: String) {
    PHONE("Match phone"),
    HD("1280 × 720"),
    FHD("1920 × 1080"),
}

data class LaptopConfig(
    val name: String = "My laptop",
    val host: String = "",
    val rdpPort: Int = DEFAULT_RDP_PORT,
    val username: String = "",
    val password: String = "",
    val agentPort: Int = DEFAULT_AGENT_PORT,
    val agentToken: String = "",
    val resolution: Resolution = Resolution.FHD,
) {
    val isComplete: Boolean get() = Tailnet.isTailnetLiteral(host) && username.isNotBlank()
    val hasAgent: Boolean get() = agentToken.isNotBlank()
    val pinKey: String get() = "${host.lowercase()}:$rdpPort"

    companion object {
        const val DEFAULT_RDP_PORT = 3389
        const val DEFAULT_AGENT_PORT = 8765
    }

    /** Changes when the agent target or its token changes. */
    val agentKey: String get() = "${host.trim()}:$agentPort:${agentToken.hashCode()}"
}

/**
 * Stores the laptop config as one JSON blob encrypted with an AES-256-GCM key
 * held in the Android Keystore. The key never leaves the keystore, so the
 * prefs file alone does not reveal the RDP password or agent token.
 */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val pins = context.getSharedPreferences("cert_pins", Context.MODE_PRIVATE)

    fun load(): LaptopConfig = loadOrNull() ?: LaptopConfig()

    /**
     * The saved config, a default config when nothing is saved (or the saved
     * data can never be read again and was reset), or null when it cannot be
     * read right now (a passing keystore error) and the caller should retry.
     */
    fun loadOrNull(): LaptopConfig? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return LaptopConfig()
        val json = try {
            JSONObject(decrypt(blob))
        } catch (e: Exception) {
            if (isPermanent(e)) {
                // The keystore key is gone or changed (for example after
                // clearing credentials), so the blob can never be read again.
                // Drop both so the next save starts with a fresh key.
                Log.w(TAG, "saved settings unreadable, resetting: ${e.javaClass.simpleName}")
                reset()
                return LaptopConfig()
            }
            // A passing keystore error must not erase the settings.
            Log.w(TAG, "could not read settings this time: ${e.javaClass.simpleName}")
            return null
        }
        return LaptopConfig(
            name = json.optString("name", "My laptop"),
            host = json.optString("host"),
            rdpPort = json.optInt("rdpPort", LaptopConfig.DEFAULT_RDP_PORT),
            username = json.optString("username"),
            password = json.optString("password"),
            agentPort = json.optInt("agentPort", LaptopConfig.DEFAULT_AGENT_PORT),
            agentToken = json.optString("agentToken"),
            resolution = runCatching { Resolution.valueOf(json.optString("resolution")) }
                .getOrDefault(Resolution.FHD),
        )
    }

    fun save(c: LaptopConfig) {
        val json = JSONObject()
            .put("name", c.name)
            .put("host", c.host.trim())
            .put("rdpPort", c.rdpPort)
            .put("username", c.username)
            .put("password", c.password)
            .put("agentPort", c.agentPort)
            .put("agentToken", c.agentToken.trim())
            .put("resolution", c.resolution.name)
        prefs.edit()
            .putString(KEY_BLOB, encrypt(json.toString()))
            .putInt(KEY_VERSION, version() + 1)
            .apply()
    }

    /** Changes on every save; lets callers spot new settings without decrypting. */
    fun version(): Int = prefs.getInt(KEY_VERSION, 0)

    /** True for errors after which the saved blob can never be decrypted again. */
    private fun isPermanent(e: Exception): Boolean =
        e is AEADBadTagException || e is KeyPermanentlyInvalidatedException ||
            e is UnrecoverableKeyException || e is JSONException ||
            e is CorruptBlobException || e is IllegalArgumentException // bad Base64

    private fun reset() {
        prefs.edit().remove(KEY_BLOB).apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    /** Pinned certificate fingerprint for host:port, set on first accepted connect. */
    fun pin(key: String): String? = pins.getString(key, null)

    fun setPin(key: String, fingerprint: String) {
        pins.edit().putString(key, fingerprint).apply()
    }

    fun clearPin(key: String) {
        pins.edit().remove(key).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** The saved blob is too short to hold an IV and a GCM tag. */
    private class CorruptBlobException : Exception()

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size < IV_LEN + GCM_TAG_BYTES) throw CorruptBlobException()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN))
    }

    private companion object {
        const val TAG = "ConfigStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rdpmobile.config"
        const val KEY_BLOB = "blob"
        const val KEY_VERSION = "version"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val GCM_TAG_BYTES = 16
    }
}

/**
 * FreeRDP passes either a colon-separated hash or a full PEM certificate
 * (VERIFY_CERT_FLAG_FP_IS_PEM). Pins always store a SHA-256 hex string.
 */
fun normalizeFingerprint(fingerprint: String): String {
    if (!fingerprint.startsWith("-----BEGIN")) return fingerprint.lowercase()
    val der = Base64.decode(
        fingerprint.lines().filterNot { it.startsWith("-----") }.joinToString(""),
        Base64.DEFAULT,
    )
    return MessageDigest.getInstance("SHA-256").digest(der)
        .joinToString(":") { "%02x".format(it) }
}
