package com.eddies.app.core.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At-rest encryption for anything secret the app holds: the database key, an
 * optional CoinGecko API key, the app-lock PIN hash.
 *
 * Tink AEAD wrapped by an Android Keystore master key. This is the current
 * recommended replacement for the deprecated EncryptedSharedPreferences.
 *
 * Note what this deliberately does NOT protect: the portable backup file. A
 * Keystore key cannot leave the device, so a backup sealed with it would be
 * undecryptable on a new phone. See core/backup/BackupCrypto.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun encrypt(plaintext: String): ByteArray = aead.encrypt(plaintext.toByteArray(), ASSOCIATED_DATA)

    fun encrypt(plaintext: ByteArray): ByteArray = aead.encrypt(plaintext, ASSOCIATED_DATA)

    fun decrypt(ciphertext: ByteArray): String = String(aead.decrypt(ciphertext, ASSOCIATED_DATA))

    fun decryptBytes(ciphertext: ByteArray): ByteArray = aead.decrypt(ciphertext, ASSOCIATED_DATA)

    private companion object {
        const val KEYSET_NAME = "eddies_keyset"
        const val PREF_FILE = "eddies_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://eddies_master_key"
        val ASSOCIATED_DATA = "eddies".toByteArray()
    }
}
