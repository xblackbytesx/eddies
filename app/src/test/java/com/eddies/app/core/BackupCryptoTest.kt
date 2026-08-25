package com.eddies.app.core

import com.eddies.app.core.backup.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup format is a compatibility contract with every file a user has
 * already written. These tests exist so a refactor cannot quietly change it.
 */
class BackupCryptoTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val payload = """{"version":1,"transactions":[]}""".toByteArray()

    @Test
    fun `a round trip returns the exact bytes`() {
        val sealed = BackupCrypto.encrypt(payload, passphrase)
        assertArrayEquals(payload, BackupCrypto.decrypt(sealed, passphrase))
    }

    @Test
    fun `the wrong passphrase is rejected, not silently mis-decrypted`() {
        val sealed = BackupCrypto.encrypt(payload, passphrase)
        try {
            BackupCrypto.decrypt(sealed, "wrong".toCharArray())
            error("expected BadPassphraseException")
        } catch (e: BackupCrypto.BadPassphraseException) {
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    @Test
    fun `a tampered ciphertext fails authentication`() {
        // AES-GCM is what makes this detectable. Without the tag a flipped bit
        // would decrypt to garbage that the JSON parser might half-accept.
        val sealed = BackupCrypto.encrypt(payload, passphrase).copyOf()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        try {
            BackupCrypto.decrypt(sealed, passphrase)
            error("expected BadPassphraseException")
        } catch (e: BackupCrypto.BadPassphraseException) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun `a file that is not a backup is refused before any crypto runs`() {
        try {
            BackupCrypto.decrypt("not a backup at all".toByteArray(), passphrase)
            error("expected InvalidBackupException")
        } catch (e: BackupCrypto.InvalidBackupException) {
            assertTrue(e.message!!.contains("valid Eddies backup"))
        }
    }

    @Test
    fun `a truncated file is refused rather than read out of bounds`() {
        val sealed = BackupCrypto.encrypt(payload, passphrase)
        try {
            BackupCrypto.decrypt(sealed.copyOfRange(0, 10), passphrase)
            error("expected InvalidBackupException")
        } catch (e: BackupCrypto.InvalidBackupException) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun `an unknown version is named in the error instead of failing as a bad passphrase`() {
        val sealed = BackupCrypto.encrypt(payload, passphrase).copyOf()
        sealed[4] = 99   // the version byte, right after the 4-byte magic
        try {
            BackupCrypto.decrypt(sealed, passphrase)
            error("expected InvalidBackupException")
        } catch (e: BackupCrypto.InvalidBackupException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test
    fun `the envelope layout is exactly the documented one`() {
        // MAGIC(4) + version(1) + salt(16) + iv(12) = 33 bytes of header. If this
        // changes, every backup already on a user's disk stops opening.
        val sealed = BackupCrypto.encrypt(ByteArray(0), passphrase)
        assertEquals('E'.code.toByte(), sealed[0])
        assertEquals('D'.code.toByte(), sealed[1])
        assertEquals('D'.code.toByte(), sealed[2])
        assertEquals('Y'.code.toByte(), sealed[3])
        assertEquals(1, sealed[4].toInt())
        // An empty payload still carries the 16-byte GCM tag.
        assertEquals(33 + 16, sealed.size)
    }

    @Test
    fun `each encryption uses a fresh salt and iv`() {
        // Reusing a GCM nonce across two files under the same key is a total
        // break of confidentiality, so this is not a stylistic check.
        val a = BackupCrypto.encrypt(payload, passphrase)
        val b = BackupCrypto.encrypt(payload, passphrase)
        assertNotEquals(
            a.copyOfRange(5, 33).toList(),
            b.copyOfRange(5, 33).toList(),
        )
    }

    @Test
    fun `looksLikeBackup recognises our files and rejects others`() {
        assertTrue(BackupCrypto.looksLikeBackup(BackupCrypto.encrypt(payload, passphrase)))
        assertFalse(BackupCrypto.looksLikeBackup("PK zip".toByteArray()))
        assertFalse(BackupCrypto.looksLikeBackup(ByteArray(2)))
    }

    @Test
    fun `an empty passphrase still encrypts and decrypts`() {
        // Discouraged in the UI, but it must not throw from the crypto layer.
        val sealed = BackupCrypto.encrypt(payload, CharArray(0))
        assertArrayEquals(payload, BackupCrypto.decrypt(sealed, CharArray(0)))
    }

    @Test
    fun `a large payload survives intact`() {
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val sealed = BackupCrypto.encrypt(big, passphrase)
        assertArrayEquals(big, BackupCrypto.decrypt(sealed, passphrase))
    }
}
