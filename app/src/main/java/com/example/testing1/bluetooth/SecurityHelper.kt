// In file: app/src/main/java/com/example/testing1/bluetooth/SecurityHelper.kt

package com.example.testing1.bluetooth

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityHelper {

    // --- UPDATED: Full transformation string with mode and padding ---
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    // -------------------------------------------------------------
    private const val ALGORITHM = "AES"
    private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
    private const val KEY_FACTORY_ALGORITHM = "EC"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 16 // IV size for AES is 16 bytes

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

    // --- UPDATED: encrypt function now handles the Initialization Vector (IV) ---
    fun encrypt(message: String, sharedSecret: ByteArray): String {
        // Use the specified transformation
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKeySpec = SecretKeySpec(sharedSecret, ALGORITHM)

        // 1. Generate a random Initialization Vector (IV)
        val iv = ByteArray(IV_SIZE)
        val secureRandom = SecureRandom()
        secureRandom.nextBytes(iv)
        val ivParameterSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
        val encryptedBytes = cipher.doFinal(message.toByteArray())

        // 2. Combine the IV and the encrypted message into one byte array
        val combined = iv + encryptedBytes

        // 3. Encode the combined result to a string for sending
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }
    // -------------------------------------------------------------------------

    // --- UPDATED: decrypt function now extracts the IV before decrypting ---
    fun decrypt(encryptedMessage: String, sharedSecret: ByteArray): String {
        // Use the specified transformation
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKeySpec = SecretKeySpec(sharedSecret, ALGORITHM)

        // 1. Decode the received string to get the combined IV and message
        val combined = Base64.decode(encryptedMessage, Base64.DEFAULT)

        // 2. Extract the IV from the first 16 bytes
        val iv = combined.copyOfRange(0, IV_SIZE)
        val ivParameterSpec = IvParameterSpec(iv)

        // 3. Extract the actual encrypted message (the rest of the array)
        val encryptedBytes = combined.copyOfRange(IV_SIZE, combined.size)

        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }
    // ---------------------------------------------------------------------

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