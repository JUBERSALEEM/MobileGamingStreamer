package com.jubersaleem.mobilegamingstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the pure vault-payload framing. Every test drives
 * the real production [packVaultPayload] / [unpackVaultPayload] with
 * synthetic byte patterns — never real credentials, keys or plaintext. The
 * Android Keystore encryption and SharedPreferences integration stay in
 * [StreamKeyVault] and remain explicitly device-gated. No Android
 * dependencies, no sleeps.
 */
class StreamKeyVaultPayloadTest {
    private fun iv(): ByteArray = ByteArray(VAULT_GCM_IV_BYTES) { it.toByte() }

    private fun ciphertext(size: Int = 48): ByteArray = ByteArray(size) { (it * 7 + 1).toByte() }

    @Test
    fun ivLengthMatchesGcmNonceSize() {
        assertEquals(12, VAULT_GCM_IV_BYTES)
    }

    @Test
    fun roundTripPreservesIvAndCiphertext() {
        val iv = iv()
        val encrypted = ciphertext()
        val packed = packVaultPayload(iv, encrypted)
        assertEquals(VAULT_GCM_IV_BYTES + encrypted.size, packed.size)
        val unpacked = unpackVaultPayload(packed)
        assertNotNull(unpacked)
        assertTrue(iv.contentEquals(unpacked!!.iv))
        assertTrue(encrypted.contentEquals(unpacked.encrypted))
    }

    @Test
    fun packedLayoutIsIvPrefixFollowedByCiphertext() {
        val iv = iv()
        val encrypted = ciphertext(16)
        val packed = packVaultPayload(iv, encrypted)
        assertTrue(iv.contentEquals(packed.copyOfRange(0, VAULT_GCM_IV_BYTES)))
        assertTrue(encrypted.contentEquals(packed.copyOfRange(VAULT_GCM_IV_BYTES, packed.size)))
    }

    @Test
    fun unpackCopiesRatherThanAliasing() {
        val packed = packVaultPayload(iv(), ciphertext())
        val unpacked = unpackVaultPayload(packed)!!
        packed[0] = 0x7F
        packed[packed.size - 1] = 0x7F
        assertTrue(iv().contentEquals(unpacked.iv))
        assertTrue(ciphertext().contentEquals(unpacked.encrypted))
    }

    @Test
    fun rejectsEmptyPayload() {
        assertNull(unpackVaultPayload(ByteArray(0)))
    }

    @Test
    fun rejectsUndersizedPayload() {
        assertNull(unpackVaultPayload(ByteArray(1)))
        assertNull(unpackVaultPayload(ByteArray(VAULT_GCM_IV_BYTES - 1)))
    }

    /** Exactly one IV and zero ciphertext bytes is not a valid payload. */
    @Test
    fun rejectsIvOnlyPayload() {
        assertNull(unpackVaultPayload(ByteArray(VAULT_GCM_IV_BYTES)))
    }

    /** The smallest shippable shape: a full IV plus one ciphertext byte. */
    @Test
    fun acceptsMinimalCiphertext() {
        val unpacked = unpackVaultPayload(ByteArray(VAULT_GCM_IV_BYTES + 1))
        assertNotNull(unpacked)
        assertEquals(VAULT_GCM_IV_BYTES, unpacked!!.iv.size)
        assertEquals(1, unpacked.encrypted.size)
    }

    @Test
    fun rejectsTruncatedPayload() {
        val packed = packVaultPayload(iv(), ciphertext())
        assertNull(unpackVaultPayload(packed.copyOfRange(0, 10)))
        assertNull(unpackVaultPayload(packed.copyOfRange(0, VAULT_GCM_IV_BYTES)))
    }

    /** Framing validation is total: every short length rejects without throwing. */
    @Test
    fun unpackNeverThrowsAcrossBoundaryLengths() {
        for (size in 0..VAULT_GCM_IV_BYTES + 4) {
            val result = unpackVaultPayload(ByteArray(size) { size.toByte() })
            if (size <= VAULT_GCM_IV_BYTES) assertTrue("size=$size", result == null)
            else assertTrue("size=$size", result != null)
        }
    }
}
