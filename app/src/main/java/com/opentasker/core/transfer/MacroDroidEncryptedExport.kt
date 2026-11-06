package com.opentasker.core.transfer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * MacroDroid's password-protected export (`.emdr`): the same JSON as a `.mdr`, encrypted with the
 * password the user typed when exporting. The cipher and key derivation below are the ones
 * MacroDroid itself uses, so they have to stay exactly as they are, weak as AES/ECB is.
 */
internal object MacroDroidEncryptedExport {
    private const val CIPHER = "AES/ECB/PKCS5Padding"
    private const val KEY_DERIVATION = "PBKDF2WithHmacSHA1"
    private const val SALT = "37o3gcd77a77URp"
    private const val ITERATIONS = 1000
    private const val KEY_BITS = 128
    private const val BLOCK_BYTES = 16

    /** A whole number of AES blocks, the only shape this cipher can produce. */
    fun hasCipherShape(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.size % BLOCK_BYTES == 0

    /** The export's JSON, or null when [password] doesn't open it. */
    fun decrypt(bytes: ByteArray, password: CharArray): String? {
        val keySpec = PBEKeySpec(password, SALT.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance(KEY_DERIVATION).generateSecret(keySpec).encoded
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            // Padding happens to validate about once in 256 wrong passwords, so the plaintext has
            // to read as a JSON object as well before it counts as opened.
            decodeStrictUtf8(cipher.doFinal(bytes))?.takeIf { text ->
                text.trimStart { it == BYTE_ORDER_MARK_CHAR || it.isWhitespace() }.startsWith("{")
            }
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            keySpec.clearPassword()
        }
    }
}

/** What a picked Tasker or MacroDroid file turned out to hold. */
internal sealed interface TaskerOrMacroDroidDocument {
    data class Text(val text: String) : TaskerOrMacroDroidDocument
    data object MacroDroidEncrypted : TaskerOrMacroDroidDocument
    data object Unrecognized : TaskerOrMacroDroidDocument

    companion object {
        /**
         * Ciphertext can start with any byte, so the first character says nothing. Valid UTF-8 does:
         * random bytes stop being valid within a few characters. Text in some other encoding still
         * reaches the parsers when it opens like XML or JSON and can't be AES blocks.
         */
        fun classify(bytes: ByteArray): TaskerOrMacroDroidDocument {
            decodeStrictUtf8(bytes)?.let { return Text(it) }
            if (MacroDroidEncryptedExport.hasCipherShape(bytes)) return MacroDroidEncrypted
            val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
            return if (first == '<' || first == '{') Text(bytes.toString(Charsets.UTF_8)) else Unrecognized
        }
    }
}

private val BYTE_ORDER_MARK_CHAR = Char(0xFEFF)

private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}
