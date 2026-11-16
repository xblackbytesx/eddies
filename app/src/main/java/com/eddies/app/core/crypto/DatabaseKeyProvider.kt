package com.eddies.app.core.crypto

import android.content.Context
import android.util.Base64
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
    @param:ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {

    /** Thrown instead of replacing a key that could not be read. Nothing has been changed. */
    class KeyUnavailableException(cause: Throwable?) :
        IllegalStateException("The database key could not be unsealed. Nothing was changed.", cause)

    /**
     * The passphrase bytes SQLCipher wants. Generated on first call, stable after.
     *
     * [databaseExists] says whether there is a database file this key has to
     * open. It decides what a failed unseal means.
     *
     * A stored key is never replaced while a database depends on it. The sealed
     * key is the only way into the ledger, and the Keystore does fail
     * transiently: under memory pressure, straight after boot, during an OS
     * update. This used to answer any such failure by writing a fresh key over
     * the old one, which turned a hiccup that the next launch would have
     * survived into a ledger nobody can ever open again. So it retries, and
     * then fails loudly with everything left in place. A crash here is
     * recoverable. An overwritten key is not.
     *
     * Only when there is no database at all is a fresh key the right answer,
     * because then there is nothing for the old one to open.
     */
    fun passphrase(databaseExists: Boolean): ByteArray {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val sealed = prefs.getString(KEY_SEALED, null)
        if (sealed != null) {
            var failure: Throwable? = null
            repeat(ATTEMPTS) { attempt ->
                try {
                    return secrets.decryptBytes(Base64.decode(sealed, Base64.NO_WRAP))
                } catch (e: Exception) {
                    failure = e
                    if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
            if (databaseExists) throw KeyUnavailableException(failure)
        }
        val fresh = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // commit, not apply: the database is created with this key a moment
        // later, and a key still waiting in a write queue when the process dies
        // would leave a database with no key at all.
        val stored = prefs.edit()
            .putString(KEY_SEALED, Base64.encodeToString(secrets.encrypt(fresh), Base64.NO_WRAP))
            .commit()
        check(stored) { "The database key could not be stored." }
        return fresh
    }

    private companion object {
        const val PREF_FILE = "eddies_db"
        const val KEY_SEALED = "db_key_sealed"
        const val KEY_BYTES = 32
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 150L
    }
}
