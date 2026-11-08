package com.opentasker.core.storage

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts `.otbackup` files using AES-256-GCM with PBKDF2-derived keys.
 *
 * Format v2 is a bounded-memory sequence of independently authenticated 64 KiB frames. The frame
 * index and plaintext length are authenticated and each frame has a unique nonce, preventing
 * reorder, omission, length manipulation, and unauthenticated plaintext publication. A final
 * authenticated zero-length frame makes truncation detectable. Legacy whole-file AES-GCM v1 files
 * remain readable.
 */
object BackupEncryption {
    private val MAGIC = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    internal const val CURRENT_FORMAT_VERSION = 2
    internal const val STREAM_CHUNK_BYTES = 64 * 1024

    private const val LEGACY_FORMAT_VERSION = 1
    private const val SALT_LENGTH = 32
    private const val NONCE_PREFIX_LENGTH = 8
    private const val LEGACY_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    internal const val MAX_EXPLICIT_WORKING_BUFFER_BYTES = (STREAM_CHUNK_BYTES * 2) + GCM_TAG_BYTES + 64
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_FACTORY = "PBKDF2WithHmacSHA256"

    fun encrypt(
        plainInput: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        maxPlaintextBytes: Long = DatabaseBackupManager.MAX_BACKUP_BYTES,
        cancellationCheck: () -> Unit = {},
    ) {
        require(maxPlaintextBytes >= 0) { "maxPlaintextBytes must not be negative" }
        val salt = randomBytes(SALT_LENGTH)
        val noncePrefix = randomBytes(NONCE_PREFIX_LENGTH)
        val chunkSizeBytes = intToBytes(STREAM_CHUNK_BYTES)
        val header = MAGIC + intToBytes(CURRENT_FORMAT_VERSION) + salt + noncePrefix + chunkSizeBytes
        val key = deriveKey(passphrase, salt)

        output.write(header)
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        var totalPlaintext = 0L
        var frameIndex = 0
        while (true) {
            cancellationCheck()
            val count = plainInput.readChunk(buffer, cancellationCheck)
            if (count == 0) break
            totalPlaintext += count
            if (totalPlaintext > maxPlaintextBytes) {
                throw backupTooLarge(maxPlaintextBytes)
            }

            writeFrame(
                output = output,
                key = key,
                header = header,
                noncePrefix = noncePrefix,
                frameIndex = frameIndex,
                plaintextLength = count,
                plaintext = buffer,
            )
            frameIndex += 1
        }

        cancellationCheck()
        writeFrame(
            output = output,
            key = key,
            header = header,
            noncePrefix = noncePrefix,
            frameIndex = frameIndex,
            plaintextLength = 0,
            plaintext = EMPTY_BYTES,
        )
        output.flush()
    }

    fun decrypt(
        encryptedInput: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        maxPlaintextBytes: Long = DatabaseBackupManager.MAX_BACKUP_BYTES,
        cancellationCheck: () -> Unit = {},
    ) {
        require(maxPlaintextBytes >= 0) { "maxPlaintextBytes must not be negative" }
        cancellationCheck()
        val magic = encryptedInput.readExact(MAGIC.size, cancellationCheck)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("Not a valid .otbackup file (bad magic)")
        }
        val versionBytes = encryptedInput.readExact(Int.SIZE_BYTES, cancellationCheck)
        when (val version = bytesToInt(versionBytes)) {
            CURRENT_FORMAT_VERSION -> decryptV2(
                encryptedInput,
                output,
                passphrase,
                magic + versionBytes,
                maxPlaintextBytes,
                cancellationCheck,
            )
            LEGACY_FORMAT_VERSION -> decryptLegacyV1(
                encryptedInput,
                output,
                passphrase,
                maxPlaintextBytes,
                cancellationCheck,
            )
            else -> throw IOException("Unsupported .otbackup format version: $version")
        }
    }

    private fun decryptV2(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        headerPrefix: ByteArray,
        maxPlaintextBytes: Long,
        cancellationCheck: () -> Unit,
    ) {
        val salt = input.readExact(SALT_LENGTH, cancellationCheck)
        val noncePrefix = input.readExact(NONCE_PREFIX_LENGTH, cancellationCheck)
        val chunkSizeBytes = input.readExact(Int.SIZE_BYTES, cancellationCheck)
        val chunkSize = bytesToInt(chunkSizeBytes)
        if (chunkSize != STREAM_CHUNK_BYTES) {
            throw IOException("Unsupported .otbackup frame size: $chunkSize")
        }
        val header = headerPrefix + salt + noncePrefix + chunkSizeBytes
        val key = deriveKey(passphrase, salt)
        var totalPlaintext = 0L
        var frameIndex = 0
        var sawShortDataFrame = false

        while (true) {
            cancellationCheck()
            val plaintextLength = bytesToInt(input.readExact(Int.SIZE_BYTES, cancellationCheck))
            if (plaintextLength !in 0..chunkSize) {
                throw IOException("Invalid .otbackup frame length: $plaintextLength")
            }
            if (sawShortDataFrame && plaintextLength != 0) {
                throw IOException("Invalid .otbackup frame sequence after final data frame")
            }
            if (plaintextLength > 0) {
                totalPlaintext += plaintextLength
                if (totalPlaintext > maxPlaintextBytes) throw backupTooLarge(maxPlaintextBytes)
                if (plaintextLength < chunkSize) sawShortDataFrame = true
            }

            val encryptedFrame = input.readExact(plaintextLength + GCM_TAG_BYTES, cancellationCheck)
            val plaintext = authenticateFrame(
                key = key,
                header = header,
                noncePrefix = noncePrefix,
                frameIndex = frameIndex,
                plaintextLength = plaintextLength,
                encryptedFrame = encryptedFrame,
            )
            if (plaintextLength == 0) {
                if (plaintext.isNotEmpty()) throw IOException("Invalid .otbackup terminal frame")
                if (input.read() != -1) throw IOException("Unexpected trailing data after .otbackup terminal frame")
                cancellationCheck()
                output.flush()
                return
            }
            if (plaintext.size != plaintextLength) throw IOException("Invalid decrypted .otbackup frame length")
            output.write(plaintext)
            frameIndex += 1
        }
    }

    /** Stream the legacy format without recreating its former explicit whole-file byte arrays. */
    private fun decryptLegacyV1(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        maxPlaintextBytes: Long,
        cancellationCheck: () -> Unit,
    ) {
        val salt = input.readExact(SALT_LENGTH, cancellationCheck)
        val iv = input.readExact(LEGACY_IV_LENGTH, cancellationCheck)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val buffer = ByteArray(STREAM_CHUNK_BYTES)
        var encryptedBytes = 0L
        var plaintextBytes = 0L

        try {
            while (true) {
                cancellationCheck()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                encryptedBytes += count
                if (encryptedBytes > maxPlaintextBytes + GCM_TAG_BYTES) throw backupTooLarge(maxPlaintextBytes)
                cipher.update(buffer, 0, count)?.takeIf { it.isNotEmpty() }?.let { plain ->
                    plaintextBytes += plain.size
                    if (plaintextBytes > maxPlaintextBytes) throw backupTooLarge(maxPlaintextBytes)
                    output.write(plain)
                }
            }
            cipher.doFinal().takeIf { it.isNotEmpty() }?.let { plain ->
                plaintextBytes += plain.size
                if (plaintextBytes > maxPlaintextBytes) throw backupTooLarge(maxPlaintextBytes)
                output.write(plain)
            }
        } catch (error: GeneralSecurityException) {
            throw decryptionFailure(error)
        }
        cancellationCheck()
        output.flush()
    }

    private fun writeFrame(
        output: OutputStream,
        key: SecretKeySpec,
        header: ByteArray,
        noncePrefix: ByteArray,
        frameIndex: Int,
        plaintextLength: Int,
        plaintext: ByteArray,
    ) {
        val lengthBytes = intToBytes(plaintextLength)
        val cipher = frameCipher(Cipher.ENCRYPT_MODE, key, header, noncePrefix, frameIndex, lengthBytes)
        val encrypted = cipher.doFinal(plaintext, 0, plaintextLength)
        output.write(lengthBytes)
        output.write(encrypted)
    }

    private fun authenticateFrame(
        key: SecretKeySpec,
        header: ByteArray,
        noncePrefix: ByteArray,
        frameIndex: Int,
        plaintextLength: Int,
        encryptedFrame: ByteArray,
    ): ByteArray = try {
        val lengthBytes = intToBytes(plaintextLength)
        frameCipher(Cipher.DECRYPT_MODE, key, header, noncePrefix, frameIndex, lengthBytes)
            .doFinal(encryptedFrame)
    } catch (error: GeneralSecurityException) {
        throw decryptionFailure(error)
    }

    private fun frameCipher(
        mode: Int,
        key: SecretKeySpec,
        header: ByteArray,
        noncePrefix: ByteArray,
        frameIndex: Int,
        lengthBytes: ByteArray,
    ): Cipher = Cipher.getInstance(ALGORITHM).apply {
        init(mode, key, GCMParameterSpec(GCM_TAG_BITS, noncePrefix + intToBytes(frameIndex)))
        updateAAD(header)
        updateAAD(intToBytes(frameIndex))
        updateAAD(lengthBytes)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val keyBytes = SecretKeyFactory.getInstance(KEY_FACTORY).generateSecret(spec).encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun InputStream.readChunk(buffer: ByteArray, cancellationCheck: () -> Unit): Int {
        var filled = 0
        while (filled < buffer.size) {
            cancellationCheck()
            val count = read(buffer, filled, buffer.size - filled)
            if (count < 0) break
            if (count == 0) {
                val single = read()
                if (single < 0) break
                buffer[filled] = single.toByte()
                filled += 1
            } else {
                filled += count
            }
        }
        return filled
    }

    private fun InputStream.readExact(length: Int, cancellationCheck: () -> Unit): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            cancellationCheck()
            val count = read(bytes, offset, length - offset)
            if (count < 0) throw IOException("Unexpected end of .otbackup file")
            if (count == 0) continue
            offset += count
        }
        return bytes
    }

    private fun randomBytes(length: Int): ByteArray = ByteArray(length).also(SecureRandom()::nextBytes)

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun backupTooLarge(maxBytes: Long): IOException =
        IOException("Backup file exceeds ${maxBytes / 1024 / 1024} MB limit")

    private fun decryptionFailure(cause: Exception): IOException =
        IOException("Decryption failed. The passphrase is wrong or the file is damaged.", cause)

    private val EMPTY_BYTES = ByteArray(0)
}
