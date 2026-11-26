package com.personal.devicememory.core.common.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DerivedSyncKeys(
    val encKey: ByteArray, // 32 bytes (AES-256-CTR)
    val macKey: ByteArray  // 32 bytes (HMAC-SHA256)
)

data class EncryptedEnvelope(
    val cipherTextB64: String,
    val ivB64: String,
    val hmacB64: String
)

/**
 * 2026 End-to-End Encrypted LAN Sync Crypto Engine (NIST P-256 ECDH + AES-256-CTR + HMAC-SHA256).
 * Compatible with Android minSdk 26+ AND pure Python stdlib LunaServer without third-party dependencies.
 */
object SyncCryptoUtil {

    private const val CURVE = "secp256r1"
    private val random = SecureRandom()

    private fun p256Params(): ECParameterSpec {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec(CURVE))
        return params.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE))
        return kpg.generateKeyPair()
    }

    /** Encodes EC public key into standard uncompressed X9.62 65-byte Base64 (`04 || X(32) || Y(32)`). */
    fun encodePublicKeyB64(pubKey: PublicKey): String {
        val ecPub = pubKey as ECPublicKey
        val w = ecPub.w
        val xBytes = toFixed32(w.affineX)
        val yBytes = toFixed32(w.affineY)
        val raw = ByteArray(65)
        raw[0] = 0x04
        System.arraycopy(xBytes, 0, raw, 1, 32)
        System.arraycopy(yBytes, 0, raw, 33, 32)
        return Base64.getEncoder().encodeToString(raw)
    }

    fun decodePublicKeyB64(b64: String): PublicKey {
        val raw = Base64.getDecoder().decode(b64.trim())
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid uncompressed P-256 public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params())
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun computeSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun deriveSessionKeys(sharedSecret: ByteArray, sessionSalt: String): DerivedSyncKeys {
        val saltBytes = sessionSalt.toByteArray(Charsets.UTF_8)
        val encKey = MessageDigest.getInstance("SHA-256").run {
            update("SHADOW-ENC-V1|".toByteArray(Charsets.UTF_8))
            update(sharedSecret)
            update(saltBytes)
            digest()
        }
        val macKey = MessageDigest.getInstance("SHA-256").run {
            update("SHADOW-MAC-V1|".toByteArray(Charsets.UTF_8))
            update(sharedSecret)
            update(saltBytes)
            digest()
        }
        return DerivedSyncKeys(encKey = encKey, macKey = macKey)
    }

    fun encrypt(plaintext: ByteArray, keys: DerivedSyncKeys): EncryptedEnvelope {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val hmac = computeHmac(keys.macKey, iv, ciphertext)
        return EncryptedEnvelope(
            cipherTextB64 = Base64.getEncoder().encodeToString(ciphertext),
            ivB64 = Base64.getEncoder().encodeToString(iv),
            hmacB64 = Base64.getEncoder().encodeToString(hmac)
        )
    }

    fun decrypt(envelope: EncryptedEnvelope, keys: DerivedSyncKeys): ByteArray {
        val iv = Base64.getDecoder().decode(envelope.ivB64)
        val ciphertext = Base64.getDecoder().decode(envelope.cipherTextB64)
        val providedHmac = Base64.getDecoder().decode(envelope.hmacB64)

        val expectedHmac = computeHmac(keys.macKey, iv, ciphertext)
        require(MessageDigest.isEqual(providedHmac, expectedHmac)) { "E2EE HMAC verification failed" }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.encKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun computeHmac(macKey: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        return mac.doFinal()
    }

    private fun toFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { dst -> System.arraycopy(raw, 0, dst, 32 - raw.size, raw.size) }
        }
    }
}
