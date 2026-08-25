package com.eddies.app.core.crypto

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher passphrase for the Room database.
 *
 * A 32-byte random key is generated once on first launch and stored sealed by
 * [SecretStore], so the key at rest is protected by the Android Keystore while
 * the database itself is protected by the key. The user never sees or types it.
 *
 * Deliberately not derived from a user passphrase: the app has no account and no
 * login, so there is nothing to derive from, and prompting for one on every cold
 * start would be a worse trade than the app lock already offers.
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {

    /** The passphrase bytes SQLCipher wants. Generated on first call, stable after. */
    fun passphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        prefs.getString(KEY_SEALED, null)?.let { sealed ->
            runCatching {
                return secrets.decryptBytes(Base64.decode(sealed, Base64.NO_WRAP))
            }
            // Falling through means the Keystore key is gone (an OS restore onto
            // new hardware does this). There is no way back to the old database,
            // so a fresh key is the only forward path; the caller handles the
            // unreadable file.
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString(KEY_SEALED, Base64.encodeToString(secrets.encrypt(fresh), Base64.NO_WRAP))
        }
        return fresh
    }

    private companion object {
        const val PREF_FILE = "eddies_db"
        const val KEY_SEALED = "db_key_sealed"
        const val KEY_BYTES = 32
    }
}
