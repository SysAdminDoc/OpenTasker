package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import kotlinx.serialization.encodeToString
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.DEFAULT_PROJECT_ID

@Entity("profiles", indices = [Index("projectId")])
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val enterTaskId: Long,
    val exitTaskId: Long?,
    val cooldownSec: Int,
    val contextsJson: String,
    val automationMode: String = AutomationMode.SINGLE.name,
    val profileGroup: String? = null,
    val requiresRiskAcknowledgement: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "1") val projectId: Long = DEFAULT_PROJECT_ID,
    @androidx.room.ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val gracePeriodSec: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "'NEVER'") val lifetime: String = ProfileLifetime.NEVER.name,
    val expiresAtMs: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val lifetimeConsumed: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val maxActiveExecutions: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val burstLimit: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "'LOG'") val overflowPolicy: String = ProfileOverflowPolicy.LOG.name,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val fallbackTaskId: Long? = null,
) {
    fun toDomain(): Profile = toDomainDecodeResult().requireDecoded()

    fun toDomainDecodeResult(): StorageDecodeResult<Profile> {
        val mode = runCatching { AutomationMode.valueOf(automationMode) }.getOrDefault(AutomationMode.SINGLE)
        val decodedContexts = runCatching {
            StoredProfileContexts(
                contexts = StorageJson.decodeFromString<List<ContextSpec>>(contextsJson),
                expression = null,
            )
        }.recoverCatching {
            val payload = StorageJson.decodeFromString<StoredProfileContexts>(contextsJson)
            StoredProfileContexts(payload.contexts, payload.expression)
        }.getOrElse { error ->
                return StorageDecodeResult(
                    value = Profile(
                        id,
                        name,
                        enabled,
                        emptyList(),
                        enterTaskId,
                        exitTaskId,
                        cooldownSec,
                        mode,
                        profileGroup,
                        requiresRiskAcknowledgement,
                        projectId,
                    ),
                    issue = StorageDecodeIssue(
                        recordType = StorageRecordType.PROFILE,
                        recordId = id,
                        recordName = name,
                        fieldName = "contextsJson",
                        message = error.storageDecodeMessage(),
                    ),
                )
            }
        val contexts = decodedContexts.contexts

        val profileLifetime = runCatching { ProfileLifetime.valueOf(lifetime) }
            .getOrDefault(ProfileLifetime.NEVER)
        val profileOverflowPolicy = runCatching { ProfileOverflowPolicy.valueOf(overflowPolicy) }
            .getOrDefault(ProfileOverflowPolicy.LOG)
        return StorageDecodeResult(
            value = Profile(
                id = id,
                name = name,
                enabled = enabled,
                contexts = contexts,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec,
                automationMode = mode,
                group = profileGroup,
                requiresRiskAcknowledgement = requiresRiskAcknowledgement,
                projectId = projectId,
                contextExpression = decodedContexts.expression,
                priority = priority,
                gracePeriodSec = gracePeriodSec,
                lifetime = profileLifetime,
                expiresAtMs = expiresAtMs,
                lifetimeConsumed = lifetimeConsumed,
                maxActiveExecutions = maxActiveExecutions,
                burstLimit = burstLimit,
                overflowPolicy = profileOverflowPolicy,
                fallbackTaskId = fallbackTaskId,
            ),
        )
    }
}

fun Profile.toEntity() = ProfileEntity(
    id,
    name,
    enabled,
    enterTaskId,
    exitTaskId,
    cooldownSec,
    if (contextExpression == null) {
        StorageJson.encodeToString(contexts)
    } else {
        StorageJson.encodeToString(StoredProfileContexts(contexts, contextExpression))
    },
    automationMode.name,
    group,
    requiresRiskAcknowledgement,
    projectId,
    priority,
    gracePeriodSec,
    lifetime.name,
    expiresAtMs,
    lifetimeConsumed,
    maxActiveExecutions,
    burstLimit,
    overflowPolicy.name,
    fallbackTaskId,
)

@kotlinx.serialization.Serializable
private data class StoredProfileContexts(
    val contexts: List<ContextSpec>,
    val expression: ContextExpressionNode? = null,
)

@Dao
interface ProfileDao {
    @Insert suspend fun insert(p: ProfileEntity): Long
    @Upsert suspend fun upsert(p: ProfileEntity)
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun getById(id: Long): ProfileEntity?
    @Query("SELECT * FROM profiles") suspend fun getAll(): List<ProfileEntity>

    /**
     * Case-insensitive name lookup for callers that must not load the whole table — notably the
     * exported broadcast target, which answers inside a bounded `goAsync()` window.
     *
     * `COLLATE NOCASE` folds ASCII only, which is narrower than Kotlin's
     * `String.equals(ignoreCase = true)`. That is the deliberate trade: two profile names that
     * differ only by the case of a non-ASCII letter are treated as distinct here.
     */
    @Query("SELECT * FROM profiles WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByNameIgnoreCase(name: String): ProfileEntity?

    /**
     * Turns one profile on or off without rewriting the rest of its row, so an edit saved in the
     * meantime isn't lost. It never turns on a profile still waiting for its review,
     * and returns how many rows it changed, so 0 means the profile is gone or still needs review.
     */
    @Query("UPDATE profiles SET enabled = :enabled WHERE id = :id AND (:enabled = 0 OR requiresRiskAcknowledgement = 0)")
    suspend fun setEnabled(id: Long, enabled: Boolean): Int

    @Query("SELECT id FROM profiles") suspend fun getAllIds(): List<Long>
    @Query("SELECT COUNT(*) FROM profiles") suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM profiles WHERE enabled = 1 AND requiresRiskAcknowledgement = 0")
    suspend fun countEnabled(): Int
    @Query("SELECT * FROM profiles WHERE enabled = 1 AND requiresRiskAcknowledgement = 0")
    suspend fun getAllEnabled(): List<ProfileEntity>
    @Query("SELECT * FROM profiles") fun getAllAsFlow(): kotlinx.coroutines.flow.Flow<List<ProfileEntity>>
    @Query("UPDATE profiles SET projectId = :targetProjectId WHERE projectId = :sourceProjectId")
    suspend fun reassignProject(sourceProjectId: Long, targetProjectId: Long)
}
