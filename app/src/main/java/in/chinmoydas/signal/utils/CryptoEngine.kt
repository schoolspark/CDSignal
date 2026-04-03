package `in`.chinmoydas.signal.utils

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128 // 16 bytes auth tag
    private const val IV_LENGTH_BYTE = 12

    // ThreadLocal ensures we don't create new Cipher objects 50 times a second
    private val encryptCipher = ThreadLocal<Cipher>()
    private val decryptCipher = ThreadLocal<Cipher>()

    // Cryptographically secure RNG for unique IVs
    private val secureRandom = SecureRandom()

    // Derive a 32-byte (256-bit) AES Key from any password string
    fun deriveKey(input: String?): SecretKeySpec {
        // Fallback to a default key if channel has no password (Open Channel)
        val safeInput = if (input.isNullOrBlank()) "PublicOpenChannelKey2026" else input
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(safeInput.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * [UPGRADE] Generates a secure IV internally and prepends it to the payload.
     * Upper layers no longer need to manage sequence numbers for cryptography.
     */
    fun encrypt(data: ByteArray, key: SecretKeySpec): ByteArray? {
        try {
            var cipher = encryptCipher.get()
            if (cipher == null) {
                cipher = Cipher.getInstance(ALGORITHM)
                encryptCipher.set(cipher)
            }

            // 1. Generate a universally unique 12-byte IV
            val iv = ByteArray(IV_LENGTH_BYTE)
            secureRandom.nextBytes(iv)

            // 2. Encrypt the data
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            val cipherText = cipher.doFinal(data)

            // 3. Package as: [12-byte IV] + [CipherText]
            return ByteBuffer.allocate(IV_LENGTH_BYTE + cipherText.size)
                .put(iv)
                .put(cipherText)
                .array()

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * [UPGRADE] Automatically extracts the IV from the front of the packet and decrypts.
     */
    fun decrypt(data: ByteArray, key: SecretKeySpec): ByteArray? {
        // Ensure packet is at least the size of an IV
        if (data.size < IV_LENGTH_BYTE) return null

        try {
            var cipher = decryptCipher.get()
            if (cipher == null) {
                cipher = Cipher.getInstance(ALGORITHM)
                decryptCipher.set(cipher)
            }

            // 1. Extract the 12-byte IV and the remaining CipherText
            val iv = data.copyOfRange(0, IV_LENGTH_BYTE)
            val cipherText = data.copyOfRange(IV_LENGTH_BYTE, data.size)

            // 2. Initialize cipher and decrypt
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            return cipher.doFinal(cipherText)

        } catch (e: Exception) {
            // Decryption failed (Wrong Key, Tampered Packet, or Packet Loss)
            return null
        }
    }
}