package com.opentasker.core.apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.annotation.RequiresApi

/** The signing-certificate pins for outbound targets, one entry per package, in their own prefs file. */
class TargetSignerPinStore(context: Context) : TargetSignerPins {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(packageName: String): TargetSignerPin? =
        TargetSignerPinCodec.decode(packageName, prefs.getString(KEY_PREFIX + packageName, null))

    override fun put(pin: TargetSignerPin) {
        prefs.edit().putString(KEY_PREFIX + pin.packageName, TargetSignerPinCodec.encode(pin)).apply()
    }

    override fun remove(packageName: String) {
        prefs.edit().remove(KEY_PREFIX + packageName).apply()
    }

    override fun all(): List<TargetSignerPin> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                TargetSignerPinCodec.decode(key.removePrefix(KEY_PREFIX), value)
            } else {
                null
            }
        }.sortedBy { it.packageName }

    companion object {
        private const val PREFS = "target_signer_pins"
        private const val KEY_PREFIX = "pin:"
    }
}

/**
 * Reads signing certificates from the package manager. API 28+ uses SigningInfo: the rotation
 * history for a single signer, or every APK signer when there are several. API 26-27 only have
 * GET_SIGNATURES.
 */
class AndroidPackageSignatureSource(private val packageManager: PackageManager) : PackageSignatureSource {
    override fun signers(packageName: String): InstalledSigners? {
        val info = try {
            packageInfo(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fromSigningInfo(info) else fromSignatures(info)
    }

    // Every signer is read and compared as a set, which is what the lint warning asks for.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        else -> packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun fromSigningInfo(info: PackageInfo): InstalledSigners {
        val signing = info.signingInfo ?: return InstalledSigners(current = emptyList())
        if (signing.hasMultipleSigners()) {
            return InstalledSigners(current = digests(signing.apkContentsSigners))
        }
        // Oldest first, current last. With no rotation it holds just the current certificate.
        val history = (signing.signingCertificateHistory ?: emptyArray()).map { sha256Hex(it.toByteArray()) }
        return InstalledSigners(current = listOfNotNull(history.lastOrNull()), lineage = history)
    }

    @Suppress("DEPRECATION")
    private fun fromSignatures(info: PackageInfo): InstalledSigners =
        InstalledSigners(current = digests(info.signatures))

    private fun digests(signatures: Array<Signature>?): List<String> =
        (signatures ?: emptyArray()).map { sha256Hex(it.toByteArray()) }.distinct().sorted()
}
