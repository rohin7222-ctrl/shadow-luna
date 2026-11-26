package com.personal.devicememory.core.common.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCryptoUtilTest {

    @Test
    fun ecdhP256AndAesCtrHmacRoundTripAndTamperFail() {
        val alice = SyncCryptoUtil.generateEphemeralKeyPair()
        val bob = SyncCryptoUtil.generateEphemeralKeyPair()

        val alicePubB64 = SyncCryptoUtil.encodePublicKeyB64(alice.public)
        val bobPubB64 = SyncCryptoUtil.encodePublicKeyB64(bob.public)

        val secretA = SyncCryptoUtil.computeSharedSecret(alice.private, SyncCryptoUtil.decodePublicKeyB64(bobPubB64))
        val secretB = SyncCryptoUtil.computeSharedSecret(bob.private, SyncCryptoUtil.decodePublicKeyB64(alicePubB64))
        assertArrayEquals(secretA, secretB)

        val keysA = SyncCryptoUtil.deriveSessionKeys(secretA, "salt-2026")
        val keysB = SyncCryptoUtil.deriveSessionKeys(secretB, "salt-2026")

        val msg = "வணக்கம் Shadow E2EE Sync Payload 🚀".toByteArray(Charsets.UTF_8)
        val env = SyncCryptoUtil.encrypt(msg, keysA)
        val decrypted = SyncCryptoUtil.decrypt(env, keysB)
        assertEquals("வணக்கம் Shadow E2EE Sync Payload 🚀", String(decrypted, Charsets.UTF_8))

        // Verify HMAC tampering throws
        var failed = false
        try {
            val bad = env.copy(hmacB64 = env.hmacB64.reversed())
            SyncCryptoUtil.decrypt(bad, keysB)
        } catch (_: Throwable) {
            failed = true
        }
        assertEquals(true, failed)
    }
}
