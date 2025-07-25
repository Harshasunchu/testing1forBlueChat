// In file: app/src/main/java/com/example/testing1/bluetooth/SecurityHelper.kt

package com.example.testing1.bluetooth

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object SecurityHelper {

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val ALGORITHM = "AES"
    private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
    private const val KEY_FACTORY_ALGORITHM = "EC"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 16

    fun generateKeyPair(): java.security.KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_FACTORY_ALGORITHM)
        keyPairGenerator.initialize(KEY_SIZE)
        return keyPairGenerator.generateKeyPair()
    }

    fun generateSharedSecret(privateKey: java.security.PrivateKey, publicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)
        return keyAgreement.generateSecret().take(16).toByteArray()
    }

    fun generateVerificationCode(sharedSecret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedSecret = digest.digest(sharedSecret)
        val code = (hashedSecret[0].toInt() and 0xFF shl 24) or
                (hashedSecret[1].toInt() and 0xFF shl 16) or
                (hashedSecret[2].toInt() and 0xFF shl 8) or
                (hashedSecret[3].toInt() and 0xFF)
        // --- FIXED: Explicitly use Locale.US for consistent formatting ---
        return String.format(Locale.US, "%05d", abs(code % 100000))
    }

    fun encrypt(message: String, sharedSecret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKeySpec = SecretKeySpec(sharedSecret, ALGORITHM)
        val iv = ByteArray(IV_SIZE)
        val secureRandom = SecureRandom()
        secureRandom.nextBytes(iv)
        val ivParameterSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        val encryptedBytes = cipher.doFinal(message.toByteArray())
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decrypt(encryptedMessage: String, sharedSecret: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKeySpec = SecretKeySpec(sharedSecret, ALGORITHM)
        val combined = Base64.decode(encryptedMessage, Base64.DEFAULT)
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ivParameterSpec = IvParameterSpec(iv)
        val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }

    fun publicKeyToString(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.DEFAULT)
    }

    fun stringToPublicKey(publicKeyString: String): PublicKey {
        val keyBytes = Base64.decode(publicKeyString, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
        return keyFactory.generatePublic(keySpec)
    }
}
