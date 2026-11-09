package com.opentasker.core.plugins.locale

import android.content.Context
import java.security.MessageDigest

/**
 * Issues and validates revocable read grants for the exported Locale condition receiver.
 *
 * `QUERY_CONDITION` is exported without a permission because the Locale/Tasker contract requires
 * it, and its bundle is entirely attacker-controlled. Without a grant, any app with no permissions
 * at all could name an arbitrary variable and an expected value, read Satisfied/Unsatisfied off the
 * ordered-broadcast result code, and extract the variable's contents a comparison at a time.
 *
 * A grant is minted when the user selects a profile, context, or variable in
 * [LocaleConditionEditActivity] and is bound to that exact condition binding, so a query about
 * anything the user never exposed is refused. Secret variables are refused separately and are
 * never selectable here.
 */
class LocaleConditionGrantStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Create and persist a grant for [bindingKey]; returns the opaque token for the bundle. */
    fun issue(bindingKey: String): String {
        val token = LocaleGrantStore.newToken()
        prefs.edit().putString(KEY_PREFIX + token, bindingKey).apply()
        return token
    }

    /** True only when [token] is currently stored and bound to [bindingKey]. */
    fun isValid(token: String?, bindingKey: String): Boolean {
        val stored = if (token.isNullOrBlank()) null else prefs.getString(KEY_PREFIX + token, null)
        if (isConditionGrantValid(stored, token, bindingKey)) return true
        if (!shouldPinConditionGrant(stored, bindingKey)) return false
        prefs.edit().putString(KEY_PREFIX + token, bindingKey).apply()
        return true
    }

    fun revoke(token: String) {
        prefs.edit().remove(KEY_PREFIX + token).apply()
    }

    /** Revoke every grant bound to [bindingKey] (e.g. when its target is deleted). */
    fun revokeAllForBinding(bindingKey: String) {
        val toRemove = prefs.all
            .filter { (key, value) ->
                key.startsWith(KEY_PREFIX) && (value == bindingKey || (value as? String)?.startsWith("$bindingKey:") == true)
            }
            .keys
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach { remove(it) } }.apply()
    }

    /** Revoke every grant bound to a variable (kept as a named compatibility helper). */
    fun revokeAllForVariable(variableKey: String) = revokeAllForBinding(variableKey)

    /** All currently issued grants, for inspection/revocation UIs. */
    fun grants(): List<LocaleConditionGrant> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_PREFIX) && value is String) {
                LocaleConditionGrant(key.removePrefix(KEY_PREFIX), value)
            } else {
                null
            }
        }.sortedBy { it.bindingKey }

    companion object {
        private const val PREFS = "locale_condition_grants"
        private const val KEY_PREFIX = "grant:"

        /** Stable identity of the profile condition a grant authorizes reading. */
        fun profileKey(profileId: Long): String = "profile:${requirePositiveId(profileId)}"

        /** Stable identity of the context condition a grant authorizes reading. */
        fun contextKey(profileId: Long, contextIndex: Int): String {
            requirePositiveId(profileId)
            require(contextIndex >= 0) { "Context index must not be negative." }
            return "context:$profileId:$contextIndex"
        }

        /** Stable identity of the variable a grant authorizes reading. */
        fun variableKey(projectId: Long, variableName: String): String = "$projectId:$variableName"

        /**
         * Stable identity of one comparison on a variable. The bundle lives in the host, which can
         * rewrite it, so a grant covering the variable alone would let it change the operator and
         * the expected value and read the contents a comparison at a time.
         */
        fun comparisonKey(
            projectId: Long,
            variableName: String,
            operator: LocaleConditionOperator,
            expectedValue: String,
        ): String = "${variableKey(projectId, variableName)}:${operator.wireName}:${sha256Hex(expectedValue)}"

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Stable binding used by the receiver before it touches the database. */
        fun bindingKey(spec: LocaleConditionSpec): String = when (spec.kind) {
            LocaleConditionKind.PROFILE_ACTIVE -> profileKey(requireNotNull(spec.profileId))
            LocaleConditionKind.CONTEXT_SATISFIED -> contextKey(
                requireNotNull(spec.profileId),
                requireNotNull(spec.contextIndex),
            )
            LocaleConditionKind.VARIABLE_COMPARE -> comparisonKey(
                spec.variableProjectId,
                requireNotNull(spec.variableName),
                requireNotNull(spec.operator),
                spec.expectedValue.orEmpty(),
            )
        }

        private fun requirePositiveId(value: Long): Long = value.also {
            require(it > 0) { "Identifier must be positive." }
        }
    }
}

/**
 * Grants issued before comparisons were bound name only the variable. The condition they were
 * issued for keeps working: the first comparison asked is the one the host has stored, and the
 * grant is pinned to it, so none after it can differ.
 */
internal fun shouldPinConditionGrant(stored: String?, bindingKey: String): Boolean =
    stored != null && bindingKey.startsWith("$stored:")

/** A revocable read grant: a high-entropy token bound to one condition binding. */
data class LocaleConditionGrant(val token: String, val bindingKey: String)

/**
 * Pure grant-validation decision. A query is authorized only when a non-blank token was supplied
 * and the stored binding names the same condition. Missing, forged, revoked, and
 * bound-to-another-condition tokens all fail.
 */
internal fun isConditionGrantValid(
    storedBindingKey: String?,
    requestedToken: String?,
    requestedBindingKey: String,
): Boolean = !requestedToken.isNullOrBlank() &&
    requestedBindingKey.isNotBlank() &&
    storedBindingKey == requestedBindingKey
