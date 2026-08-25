package com.eddies.app.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for the portable backup file. PBKDF2-HMAC-SHA256
 * derives an AES-256 key from the user's passphrase, then AES-GCM authenticates
 * and encrypts the payload.
 *
 * This is deliberately independent of the device Android Keystore, which cannot
 * move between phones: the user's passphrase is the only thing needed to restore
 * on a new device. Pure javax.crypto, so it is unit-testable on the JVM.
 *
 * File layout: [MAGIC(4)][version(1)][salt(16)][iv(12)][ciphertext+tag].
 *
 * The layout is a compatibility contract. Changing any constant here strands
 * every backup a user has already written, so a change means a new VERSION and a
 * decrypt path that still reads the old one.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'D'.code.toByte(), 'Y'.code.toByte())
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    class BadPassphraseException(cause: Throwable? = null) :
        Exception("Incorrect passphrase or corrupted backup.", cause)

    class InvalidBackupException(message: String) : Exception(message)

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val iv = ByteArray(IV_LEN).also(rng::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(VERSION.toByte()) + salt + iv + ct
    }

    fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        val headerLen = MAGIC.size + 1 + SALT_LEN + IV_LEN
        if (data.size < headerLen) throw InvalidBackupException("Not a valid Eddies backup file.")
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw InvalidBackupException("Not a valid Eddies backup file.")
        }
        var off = MAGIC.size
        val version = data[off].toInt(); off += 1
        if (version != VERSION) throw InvalidBackupException("Unsupported backup version ($version).")
        val salt = data.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val iv = data.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val ct = data.copyOfRange(off, data.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            // AEADBadTagException (wrong passphrase) or any other crypto failure.
            throw BadPassphraseException(e)
        }
    }

    /** True when [data] looks like an Eddies backup, for picking a file in the UI. */
    fun looksLikeBackup(data: ByteArray): Boolean =
        data.size >= MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
