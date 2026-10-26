package ua.tarilka.ui

import android.app.KeyguardManager
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.tarilka.R
import ua.tarilka.TarilkaApp
import ua.tarilka.data.AppDatabase
import ua.tarilka.security.DbCrypt
import ua.tarilka.security.KeyVault
import ua.tarilka.security.sqlCipherRawKey
import javax.crypto.Cipher

/**
 * Optional app protection: the diary database is encrypted (SQLCipher) with a random key that is
 * itself encrypted by a hardware key usable only right after a fingerprint or the phone PIN.
 * Turning it on or off converts the existing database; nothing is lost on the way.
 */
object Protection {
    /** Converting or opening: the lock screen shows a spinner. */
    val busy = MutableStateFlow(false)
    /** The last problem, shown on the lock screen or in Options. */
    val message = MutableStateFlow<Int?>(null)
    /** The hardware key is gone (screen lock removed): the diary cannot be opened any more. */
    val invalid = MutableStateFlow(false)
    val enabled = MutableStateFlow(false)

    fun init(app: TarilkaApp) {
        enabled.value = app.prefs.appLock
        invalid.value = app.prefs.appLock && KeyVault(app).let { it.isInvalidated() || it.isKeyMissing() }
    }

    fun deviceSecure(activity: FragmentActivity): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Keeps the diary out of the recent-apps preview while protection is on: from Android 13 without
     * blocking screenshots; on Android 12 only by marking the window secure.
     */
    fun applyRecents(activity: FragmentActivity) {
        val on = enabled.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!on)
        } else if (on) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun prompt(activity: FragmentActivity, cipher: Cipher, title: Int, onOk: (Cipher) -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onOk) ?: run { message.value = R.string.lock_failed }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelling is not an error worth a message.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) message.value = R.string.lock_failed
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** The unwrap cipher, or null (and the "key gone" state) when the hardware key is invalidated or missing. */
    private fun unlockCipher(app: TarilkaApp): Cipher? {
        val vault = KeyVault(app)
        return try {
            if (vault.isKeyMissing()) throw KeyPermanentlyInvalidatedException()
            vault.cipherForUnlock()
        } catch (e: KeyPermanentlyInvalidatedException) {
            vault.markInvalidated()
            invalid.value = true
            null
        } catch (e: Exception) {
            message.value = R.string.lock_failed
            null
        }
    }

    fun unlock(activity: FragmentActivity) {
        val app = activity.application as TarilkaApp
        if (busy.value || app.dbState.value != null) return
        val gen = app.lockGen.get()
        val cipher = unlockCipher(app) ?: return
        prompt(activity, cipher, R.string.lock_unlock_title) { ok ->
            busy.value = true
            app.scope.launch {
                runCatching {
                    val secret = KeyVault(app).finishUnlock(ok)
                    val key = sqlCipherRawKey(secret)
                    secret.fill(0)
                    // If the screen went off meanwhile, this does not open it.
                    app.openDb(key, gen)
                }.onFailure { message.value = R.string.lock_failed }
                busy.value = false
            }
        }
    }

    fun enable(activity: FragmentActivity) {
        val app = activity.application as TarilkaApp
        if (!deviceSecure(activity)) { message.value = R.string.protect_no_lock; return }
        val vault = KeyVault(app)
        val cipher = runCatching { vault.cipherForSetup() }.getOrElse { message.value = R.string.lock_failed; return }
        prompt(activity, cipher, R.string.protect_confirm_title) { ok ->
            busy.value = true
            app.converting = true
            app.scope.launch {
                var swapped = false
                runCatching {
                    // Taken before closing: a screen-off during the conversion keeps it locked afterwards.
                    val gen = app.lockGen.get()
                    val secret = vault.finishSetup(ok)
                    val key = sqlCipherRawKey(secret)
                    secret.fill(0)
                    app.closeDb().join()
                    DbCrypt.encrypt(app, key)
                    // From here the file is encrypted: protection is on whatever happens next.
                    swapped = true
                    app.prefs.appLock = true
                    enabled.value = true
                    message.value = R.string.protect_on_done
                    app.openDb(key, gen)
                }.onFailure {
                    if (swapped) {
                        // Encrypted and the key is kept: it opens with fingerprint or PIN.
                        app.prefs.appLock = true
                        enabled.value = true
                    } else {
                        // Back to the plain database, exactly as it was.
                        vault.reset()
                        app.prefs.appLock = false
                        enabled.value = false
                        if (app.dbState.value == null) runCatching { app.openDb(null) }
                        message.value = R.string.protect_failed
                    }
                }
                app.converting = false
                busy.value = false
            }
        }
    }

    fun disable(activity: FragmentActivity) {
        val app = activity.application as TarilkaApp
        val cipher = unlockCipher(app) ?: return
        prompt(activity, cipher, R.string.protect_confirm_title) { ok ->
            busy.value = true
            app.converting = true
            app.scope.launch {
                val vault = KeyVault(app)
                var key: ByteArray? = null
                var swapped = false
                runCatching {
                    val secret = vault.finishUnlock(ok)
                    key = sqlCipherRawKey(secret)
                    secret.fill(0)
                    app.closeDb().join()
                    DbCrypt.decrypt(app, key!!)
                    // From here the file is plain: protection is off whatever happens next.
                    swapped = true
                    app.prefs.appLock = false
                    enabled.value = false
                    vault.reset()
                    app.prefs.clearCache()
                    message.value = R.string.protect_off_done
                    app.openDb(null)
                }.onFailure {
                    if (swapped) {
                        app.prefs.appLock = false
                        enabled.value = false
                        runCatching { vault.reset() }
                        if (app.dbState.value == null) runCatching { app.openDb(null) }
                    } else {
                        // Still encrypted and still protected: it opens again with fingerprint or PIN.
                        message.value = R.string.protect_failed
                    }
                }
                key?.fill(0)
                app.converting = false
                busy.value = false
            }
        }
    }

    /** Only when the key is gone: an empty diary, protection off. A backup can then be restored. */
    fun startFresh(activity: FragmentActivity) {
        val app = activity.application as TarilkaApp
        app.scope.launch {
            app.closeDb().join()
            activity.deleteDatabase(AppDatabase.NAME)
            KeyVault(app).reset()
            app.prefs.appLock = false
            app.prefs.clearCache()
            enabled.value = false
            invalid.value = false
            app.openDb(null)
        }
    }
}

/** Shown while the protected diary is locked, and while it is being converted. */
@Composable
fun LockScreen(onUnlock: () -> Unit, onStartFresh: () -> Unit) {
    val busy by Protection.busy.collectAsState()
    val invalid by Protection.invalid.collectAsState()
    val msg by Protection.message.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔒", style = MaterialTheme.typography.displayMedium)
            when {
                busy -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.lock_wait), textAlign = TextAlign.Center)
                }
                invalid -> {
                    Text(stringResource(R.string.lock_key_gone), textAlign = TextAlign.Center)
                    TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.lock_start_fresh)) }
                }
                else -> {
                    Text(stringResource(R.string.lock_text), textAlign = TextAlign.Center)
                    Button(onClick = onUnlock) { Text(stringResource(R.string.lock_open)) }
                }
            }
            msg?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.lock_start_fresh)) },
            text = { Text(stringResource(R.string.lock_start_fresh_text)) },
            confirmButton = { TextButton(onClick = { confirm = false; onStartFresh() }) { Text(stringResource(R.string.lock_start_fresh_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
