package com.opentasker.core.apps

import android.content.Context
import java.security.MessageDigest

/*
 * Trust on first use for the apps OpenTasker sends commands to: Locale plugins and Termux.
 *
 * When the user approves a target, or the first time OpenTasker sends to one that has no pin yet,
 * the SHA-256 of its signing certificate is stored. Every later dispatch compares the installed
 * signer with that pin and refuses on a mismatch, so an app reinstalled under the same package
 * name by someone else gets nothing until the user re-approves it in Setup. The inbound grant
 * stores are a separate mechanism and are not involved.
 */

/** How a pin came to exist. Diagnostics shows the difference. */
enum class TargetSignerPinSource(internal val key: String) {
    /** The user approved the target: a Termux script approval, or Setup's Re-approve. */
    APPROVED("approved"),

    /** Nothing was pinned when OpenTasker first sent to the target, so the signer found then was trusted. */
    FIRST_USE("first_use"),
    ;

    internal companion object {
        fun fromKey(key: String): TargetSignerPinSource? = entries.firstOrNull { it.key == key }
    }
}

data class TargetSignerPin(
    val packageName: String,
    /** Lowercase hex SHA-256 of each signing certificate, sorted. One entry unless the APK has several signers. */
    val digests: List<String>,
    val source: TargetSignerPinSource,
    val pinnedAtMillis: Long,
)

/** What the package manager reports about an installed package's signers right now. */
data class InstalledSigners(
    /** Lowercase hex SHA-256 of each current signer, sorted. */
    val current: List<String>,
    /**
     * The current signer's rotation lineage on API 28+, original certificate first and current
     * last. Empty for an APK with several signers and on API 26-27, where Android reports none.
     */
    val lineage: List<String> = emptyList(),
)

/** The package manager boundary, so every decision below runs on the JVM against a fake. */
fun interface PackageSignatureSource {
    /** Null when [packageName] is not installed (or not visible to OpenTasker). */
    fun signers(packageName: String): InstalledSigners?
}

/** Where pins live. Production uses [TargetSignerPinStore]; tests use a map. */
interface TargetSignerPins {
    fun get(packageName: String): TargetSignerPin?
    fun put(pin: TargetSignerPin)
    fun remove(packageName: String)
    fun all(): List<TargetSignerPin>
}

sealed interface TargetSignerCheck {
    /** The installed signer is the pinned one, or a rotation of it. */
    data object Match : TargetSignerCheck

    /** No pin existed, so the installed signer was pinned and the dispatch goes ahead. */
    data class PinnedOnFirstUse(val pin: TargetSignerPin) : TargetSignerCheck

    /** Nothing to compare. The dispatch carries on and fails the way it always has. */
    data object NotInstalled : TargetSignerCheck

    /** The installed signer is not the pinned one. Nothing may be sent. */
    data class Mismatch(val packageName: String, val message: String) : TargetSignerCheck
}

enum class TargetSignerState(val label: String) {
    MATCH("match"),
    MISMATCH("mismatch"),
    NOT_INSTALLED("not installed"),
}

data class TargetSignerStatus(val pin: TargetSignerPin, val state: TargetSignerState)

/** A Re-approve, with what it replaced so Setup's Undo can put it back. */
data class TargetSignerApproval(val pin: TargetSignerPin, val previous: TargetSignerPin?)

/**
 * Thrown by [TargetSignerGuard.requireTrusted]. Deliberately not a SecurityException: callers
 * already read that one as "permission denied", which would hide the real reason.
 */
class TargetSignerMismatchException(val packageName: String, message: String) : Exception(message)

/** The package manager listed no signer for an installed package, so there is nothing to pin or compare. */
class TargetSignerUnreadableException(val packageName: String) :
    IllegalStateException("Couldn't read the signing certificate of $packageName.")

class TargetSignerGuard(
    private val signatures: PackageSignatureSource,
    private val pins: TargetSignerPins,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The dispatch-time check. Pins on first use; never sends anything itself. */
    fun check(packageName: String): TargetSignerCheck {
        synchronized(LOCK) {
            val installed = signatures.signers(packageName) ?: return TargetSignerCheck.NotInstalled
            val current = installed.requireCurrent(packageName)
            val pin = pins.get(packageName)
            if (pin == null) {
                val firstUse = TargetSignerPin(packageName, current, TargetSignerPinSource.FIRST_USE, clock())
                pins.put(firstUse)
                return TargetSignerCheck.PinnedOnFirstUse(firstUse)
            }
            return when (signerMatch(pin.digests, installed)) {
                SignerMatch.EXACT -> TargetSignerCheck.Match
                SignerMatch.ROTATED -> {
                    // Follow the rotation, so the retired key alone no longer passes.
                    pins.put(pin.copy(digests = current))
                    TargetSignerCheck.Match
                }
                SignerMatch.NONE -> TargetSignerCheck.Mismatch(packageName, signerMismatchMessage(packageName))
            }
        }
    }

    /** [check] for callers that stop by throwing. */
    fun requireTrusted(packageName: String) {
        val result = check(packageName)
        if (result is TargetSignerCheck.Mismatch) {
            throw TargetSignerMismatchException(packageName, result.message)
        }
    }

    /**
     * Setup's Re-approve: trust whatever is installed now. Null when the package is gone; throws
     * [TargetSignerUnreadableException], leaving the pin as it was, when no signer can be read.
     */
    fun approve(packageName: String): TargetSignerApproval? {
        synchronized(LOCK) {
            val installed = signatures.signers(packageName) ?: return null
            val previous = pins.get(packageName)
            val pin = TargetSignerPin(
                packageName,
                installed.requireCurrent(packageName),
                TargetSignerPinSource.APPROVED,
                clock(),
            )
            pins.put(pin)
            return TargetSignerApproval(pin, previous)
        }
    }

    /**
     * A configuration approval, such as a Termux script added to the allowlist. Pins the installed
     * signer as approved when nothing is pinned or the pin still matches. A pin that no longer
     * matches is left alone: replacing it takes Setup's Re-approve, where the row says what changed.
     */
    fun recordApproval(packageName: String): TargetSignerCheck {
        synchronized(LOCK) {
            val installed = signatures.signers(packageName) ?: return TargetSignerCheck.NotInstalled
            val current = installed.requireCurrent(packageName)
            val pin = pins.get(packageName)
            if (pin != null && signerMatch(pin.digests, installed) == SignerMatch.NONE) {
                return TargetSignerCheck.Mismatch(packageName, signerMismatchMessage(packageName))
            }
            pins.put(TargetSignerPin(packageName, current, TargetSignerPinSource.APPROVED, clock()))
            return TargetSignerCheck.Match
        }
    }

    /** Undo for [approve]: put back the pin it replaced, or remove the one it added. */
    fun restore(packageName: String, previous: TargetSignerPin?) {
        synchronized(LOCK) {
            if (previous == null) pins.remove(packageName) else pins.put(previous)
        }
    }

    /** Every pin and how it compares with what is installed now. Read only: this never pins. */
    fun statuses(): List<TargetSignerStatus> = pins.all().map { pin ->
        val installed = signatures.signers(pin.packageName)
        val state = when {
            installed == null -> TargetSignerState.NOT_INSTALLED
            signerMatch(pin.digests, installed) == SignerMatch.NONE -> TargetSignerState.MISMATCH
            else -> TargetSignerState.MATCH
        }
        TargetSignerStatus(pin, state)
    }

    companion object {
        private val LOCK = Any()

        fun forContext(context: Context): TargetSignerGuard {
            val app = context.applicationContext ?: context
            return TargetSignerGuard(AndroidPackageSignatureSource(app.packageManager), TargetSignerPinStore(app))
        }
    }
}

internal enum class SignerMatch { EXACT, ROTATED, NONE }

/**
 * Compares a pin with the installed signers. Several signers are compared as a set, so neither
 * order nor one extra or missing certificate passes. A single pinned signer also matches a
 * single current signer whose rotation lineage (API 28+) includes it.
 */
internal fun signerMatch(pinned: List<String>, installed: InstalledSigners): SignerMatch {
    val pinnedSet = pinned.toSet()
    val currentSet = installed.current.toSet()
    return when {
        pinnedSet.isEmpty() || currentSet.isEmpty() -> SignerMatch.NONE
        pinnedSet == currentSet -> SignerMatch.EXACT
        pinnedSet.size == 1 && currentSet.size == 1 && pinnedSet.single() in installed.lineage -> SignerMatch.ROTATED
        else -> SignerMatch.NONE
    }
}

/** The refusal every dispatch site reports, so the run log names the package and the fix. */
internal fun signerMismatchMessage(packageName: String): String =
    "Refused to send to $packageName because its signing certificate changed since OpenTasker first trusted it. " +
        "Re-approve $packageName in Setup if you trust the installed version."

private fun InstalledSigners.requireCurrent(packageName: String): List<String> {
    if (current.isEmpty()) throw TargetSignerUnreadableException(packageName)
    return current.distinct().sorted()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** The stored form of a pin: `source|pinnedAtMillis|digest[,digest...]`. */
internal object TargetSignerPinCodec {
    fun encode(pin: TargetSignerPin): String =
        "${pin.source.key}|${pin.pinnedAtMillis}|${pin.digests.joinToString(",")}"

    /** Null for anything malformed, which reads as "no pin" and re-pins on the next dispatch. */
    fun decode(packageName: String, raw: String?): TargetSignerPin? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 3) return null
        val source = TargetSignerPinSource.fromKey(parts[0]) ?: return null
        val pinnedAt = parts[1].toLongOrNull() ?: return null
        val digests = parts[2].split(',').filter { it.isNotEmpty() }
        if (digests.isEmpty() || !digests.all(::isSha256Hex)) return null
        return TargetSignerPin(packageName, digests.distinct().sorted(), source, pinnedAt)
    }
}
