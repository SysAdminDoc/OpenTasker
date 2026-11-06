package com.opentasker.core.scripting

import android.content.Context

internal data class ApprovedScript(
    val executable: String,
    val sha256: String,
)

internal enum class ScriptAllowlistSaveResult {
    SAVED,
    INVALID_PATH,
    INVALID_HASH,
    FULL,
}

/**
 * The approval decision, separated from SharedPreferences so it can be tested.
 *
 * This is the boundary that decides whether a script may run at all, so its rules are worth
 * asserting directly rather than only through a store that needs a Context. Termux and Shizuku
 * scripts share these rules and differ only in which paths they accept.
 */
internal class ScriptAllowlistPolicy(private val pathNormalizer: (String) -> String?) {
    fun normalizePath(raw: String): String? = pathNormalizer(raw)

    fun encode(script: ApprovedScript): String = script.sha256 + SEPARATOR + script.executable

    /** Null for anything this version did not write, so a corrupt row reads as absent, not trusted. */
    fun decode(value: String?): ApprovedScript? {
        val parts = value?.split(SEPARATOR, limit = 2) ?: return null
        if (parts.size != 2) return null
        val hash = TermuxScriptPolicy.normalizeHash(parts[0]) ?: return null
        val executable = normalizePath(parts[1]) ?: return null
        return ApprovedScript(executable, hash)
    }

    fun admit(
        executable: String,
        sha256: String,
        alreadyApproved: Boolean,
        approvedCount: Int,
        maxApproved: Int = ScriptAllowlistStore.MAX_APPROVED_SCRIPTS,
    ): ScriptAllowlistSaveResult {
        normalizePath(executable) ?: return ScriptAllowlistSaveResult.INVALID_PATH
        TermuxScriptPolicy.normalizeHash(sha256) ?: return ScriptAllowlistSaveResult.INVALID_HASH
        if (!alreadyApproved && approvedCount >= maxApproved) return ScriptAllowlistSaveResult.FULL
        return ScriptAllowlistSaveResult.SAVED
    }

    companion object {
        private const val SEPARATOR = '\n'

        val termux = ScriptAllowlistPolicy(TermuxScriptPolicy::normalizeExecutable)
        val shizuku = ScriptAllowlistPolicy(ShizukuScriptPolicy::normalizePath)
    }
}

internal class ScriptAllowlistStore private constructor(
    context: Context,
    preferencesName: String,
    val policy: ScriptAllowlistPolicy,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun entries(): List<ApprovedScript> = synchronized(this) {
        preferences.all.values
            .mapNotNull { value -> policy.decode(value as? String) }
            .distinctBy(ApprovedScript::executable)
            .sortedBy(ApprovedScript::executable)
    }

    fun expectedHash(executable: String): String? {
        val normalized = policy.normalizePath(executable) ?: return null
        return preferences.getString(keyFor(normalized), null)?.let(policy::decode)?.sha256
    }

    fun approve(executable: String, sha256: String): ScriptAllowlistSaveResult = synchronized(this) {
        val normalizedPath = policy.normalizePath(executable)
        val normalizedHash = TermuxScriptPolicy.normalizeHash(sha256)
        val key = normalizedPath?.let(::keyFor)
        val decision = policy.admit(
            executable = executable,
            sha256 = sha256,
            alreadyApproved = key != null && preferences.contains(key),
            approvedCount = entries().size,
        )
        if (decision != ScriptAllowlistSaveResult.SAVED) return decision
        val approved = ApprovedScript(requireNotNull(normalizedPath), requireNotNull(normalizedHash))
        preferences.edit().putString(requireNotNull(key), policy.encode(approved)).apply()
        ScriptAllowlistSaveResult.SAVED
    }

    fun revoke(executable: String) {
        policy.normalizePath(executable)?.let { normalized ->
            preferences.edit().remove(keyFor(normalized)).apply()
        }
    }

    private fun keyFor(executable: String): String =
        "script_${TermuxScriptPolicy.hash(executable.toByteArray())}"

    companion object {
        internal const val MAX_APPROVED_SCRIPTS = 64

        fun termux(context: Context): ScriptAllowlistStore =
            ScriptAllowlistStore(context, "termux_script_allowlist", ScriptAllowlistPolicy.termux)

        fun shizuku(context: Context): ScriptAllowlistStore =
            ScriptAllowlistStore(context, "shizuku_script_allowlist", ScriptAllowlistPolicy.shizuku)
    }
}
