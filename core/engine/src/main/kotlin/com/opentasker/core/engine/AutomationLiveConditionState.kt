package com.opentasker.core.engine

/**
 * Process-local match state exposed to the Locale condition target.
 *
 * A missing entry is deliberately distinct from false: a query made before the engine has
 * subscribed its matchers cannot safely claim that a profile or context is unsatisfied.
 */
object AutomationLiveConditionState {
    private val activeProfiles = mutableSetOf<Long>()
    private val profileStates = mutableMapOf<Long, Boolean>()
    private val contextStates = mutableMapOf<ContextKey, Boolean>()

    @Synchronized
    fun updateProfile(profileId: Long, active: Boolean) {
        profileStates[profileId] = active
        if (active) activeProfiles += profileId else activeProfiles -= profileId
    }

    /** A null [matched] means the context's value can't be told, so a query hears unknown again. */
    @Synchronized
    fun updateContext(profileId: Long, contextIndex: Int, matched: Boolean?) {
        val key = ContextKey(profileId, contextIndex)
        if (matched == null) contextStates.remove(key) else contextStates[key] = matched
    }

    @Synchronized
    fun profileState(profileId: Long): Boolean? = profileStates[profileId]

    /** The profiles that are on, in the order they went on. */
    @Synchronized
    fun activeProfileIds(): List<Long> = activeProfiles.toList()

    @Synchronized
    fun contextState(profileId: Long, contextIndex: Int): Boolean? =
        contextStates[ContextKey(profileId, contextIndex)]

    @Synchronized
    fun retainProfiles(profileIds: Set<Long>) {
        activeProfiles.retainAll(profileIds)
        profileStates.keys.retainAll(profileIds)
        contextStates.keys.removeAll { it.profileId !in profileIds }
    }

    @Synchronized
    fun clearProfile(profileId: Long) {
        activeProfiles -= profileId
        profileStates.remove(profileId)
        contextStates.keys.removeAll { it.profileId == profileId }
    }

    @Synchronized
    fun clear() {
        activeProfiles.clear()
        profileStates.clear()
        contextStates.clear()
    }

    private data class ContextKey(
        val profileId: Long,
        val contextIndex: Int,
    )
}
