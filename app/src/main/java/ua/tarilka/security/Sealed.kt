package ua.tarilka.security

import android.content.Context
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Personal settings (profile with pregnancy, cycle length, the "not found" list) kept encrypted while app
 * protection is on. The key is derived from the diary's own key, so these open only together with the diary
 * (after fingerprint or PIN) and are closed again when it locks. AES-256-GCM, a fresh IV on every write.
 */
class Sealed(context: Context) {
    private val sp = context.getSharedPreferences("tarilka_sealed", Context.MODE_PRIVATE)
    @Volatile private var key: SecretKeySpec? = null
    @Volatile private var data: JSONObject? = null

    val isOpen: Boolean get() = data != null

    /** Opens with the diary key; a blob that does not open (key reset) starts empty. */
    @Synchronized
    fun open(rawKey: ByteArray) {
        val k = derive(rawKey)
        key = k
        data = read(k) ?: JSONObject()
    }

    /** The stored values read with [rawKey], whatever is open now (used while protection is being turned off). */
    fun readWith(rawKey: ByteArray): JSONObject? = read(derive(rawKey))

    private fun derive(rawKey: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rawKey, "HmacSHA256")) }
        return SecretKeySpec(mac.doFinal("tarilka-sealed-prefs-v1".toByteArray()), "AES")
    }

    private fun read(k: SecretKeySpec): JSONObject? =
        sp.getString(BLOB, null)?.let { runCatching { decrypt(k, it) }.getOrNull() }

    @Synchronized
    fun close() {
        key = null
        data = null
    }

    /** A copy of the values, or null while locked. */
    @Synchronized
    fun snapshot(): JSONObject? = data?.let { JSONObject(it.toString()) }

    @Synchronized
    fun write(change: (JSONObject) -> Unit) {
        val d = data ?: return
        val k = key ?: return
        change(d)
        sp.edit().putString(BLOB, encrypt(k, d)).commit()
    }

    /** Forgets everything stored here (protection turned off: the values went back to the plain settings). */
    @Synchronized
    fun wipe() {
        sp.edit().remove(BLOB).commit()
        data = if (key != null) JSONObject() else null
    }

    private fun encrypt(k: SecretKeySpec, d: JSONObject): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val out = c.doFinal(d.toString().toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder()
        return b64.encodeToString(c.iv) + ":" + b64.encodeToString(out)
    }

    private fun decrypt(k: SecretKeySpec, s: String): JSONObject {
        val (iv, out) = s.split(':').map { java.util.Base64.getDecoder().decode(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        return JSONObject(String(c.doFinal(out), Charsets.UTF_8))
    }

    private companion object {
        const val BLOB = "blob"
    }
}
