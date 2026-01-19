package `in`.chinmoydas.signal.utils

import java.nio.ByteBuffer
import java.security.MessageDigest
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

    // Derive a 32-byte (256-bit) AES Key from any password string
    fun deriveKey(input: String?): SecretKeySpec {
        // Fallback to a default key if channel has no password (Open Channel)
        val safeInput = if (input.isNullOrBlank()) "PublicOpenChannelKey2026" else input
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(safeInput.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(data: ByteArray, seqNum: Int, key: SecretKeySpec): ByteArray? {
        try {
            var cipher = encryptCipher.get()
            if (cipher == null) {
                cipher = Cipher.getInstance(ALGORITHM)
                encryptCipher.set(cipher)
            }
            // Use Sequence Number as IV (Unique per packet)
            val iv = generateIv(seqNum)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            return cipher.doFinal(data)
        } catch (e: Exception) {
            e.printStackTrace()
            return null // Return null if encryption fails
        }
    }

    fun decrypt(data: ByteArray, seqNum: Int, key: SecretKeySpec): ByteArray? {
        try {
            var cipher = decryptCipher.get()
            if (cipher == null) {
                cipher = Cipher.getInstance(ALGORITHM)
                decryptCipher.set(cipher)
            }
            // Regenerate the exact same IV
            val iv = generateIv(seqNum)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            return cipher.doFinal(data)
        } catch (e: Exception) {
            // Decryption failed (Wrong Key or Packet Tampered)
            return null
        }
    }

    private fun generateIv(seqNum: Int): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTE)
        val buffer = ByteBuffer.wrap(iv)
        buffer.putInt(seqNum)
        buffer.putInt(seqNum.inv())
        buffer.putInt(seqNum * 31)
        return iv
    }
}