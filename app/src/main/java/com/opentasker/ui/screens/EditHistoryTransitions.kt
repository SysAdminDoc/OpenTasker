package com.opentasker.ui.screens

import android.content.Context
import androidx.room.withTransaction
import com.opentasker.core.diff.AutomationSemanticDiff
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.diff.SemanticDiffEntry
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.references.AutomationReferenceRewriter
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistorySnapshotDecoder
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.ProjectDeletionSnapshot
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity

/**
 * Undo/redo restore for profiles, tasks, scenes, variables, and projects.
 *
 * Lives outside [ActiveAutomationViewModel] so the history transaction, which is the largest
 * single responsibility the view model had, stops crowding the file against its line ceiling.
 */
internal class EditHistoryTransitions(
    private val db: AppDatabase,
    private val appContext: Context,
    private val fallbackTaskSettings: FallbackTaskSettings,
    private val locationDwellStateStore: LocationDwellStateStore,
    private val writeSettingsGuard: WriteSettingsGuard,
    private val variableRepository: VariableRepository,
) {
    suspend fun transition(entityType: String, entityId: Long, redo: Boolean): SemanticDiffDocument? =
        if (entityType == EditHistoryDao.TYPE_VARIABLE || entityType == EditHistoryDao.TYPE_PROJECT) {
            variableRepository.withMutationLock {
                db.withTransaction {
                    transitionStorageDeletion(entityType, entityId, redo, this@withMutationLock)
                }
            }
        } else {
            transitionAutomation(entityType, entityId, redo)
        }

    private suspend fun transitionAutomation(
        entityType: String,
        entityId: Long,
        redo: Boolean,
    ): SemanticDiffDocument? = db.withTransaction {
        val history = db.editHistoryDao()
        val snapshot = if (redo) {
            history.getRedoCandidate(entityType, entityId)
        } else {
            history.getUndoCandidate(entityType, entityId)
        } ?: return@withTransaction null

        if (snapshot.nextJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        LocaleGrantStore(appContext).revokeAllForTask(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        LocaleConditionGrantStore(appContext).apply {
                            revokeAllForBinding(LocaleConditionGrantStore.profileKey(entityId))
                            currentProfile.contexts.indices.forEach { index ->
                                revokeAllForBinding(LocaleConditionGrantStore.contextKey(entityId, index))
                            }
                        }
                        locationDwellStateStore.clearProfile(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.previousJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.previousJson, entityId)
                        writeSettingsGuard.requireWriteSettingsIfEnabled(restored)
                        db.profileDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.previousJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        if (snapshot.previousJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.nextJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.nextJson, entityId)
                        writeSettingsGuard.requireWriteSettingsIfEnabled(restored)
                        db.profileDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.nextJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        locationDwellStateStore.clearProfile(entityId)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        val targetJson = if (redo) snapshot.nextJson else snapshot.previousJson
        if (targetJson.isBlank()) return@withTransaction null

        when (entityType) {
            EditHistoryDao.TYPE_TASK -> {
                val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.actionsJson
                }
                val target = EditHistorySnapshotDecoder.task(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareTask(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.taskDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_PROFILE -> {
                val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.contextsJson
                }
                val target = EditHistorySnapshotDecoder.profile(targetJson, entityId)
                writeSettingsGuard.requireWriteSettingsIfEnabled(target)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareProfile(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.profileDao().upsert(target.toEntity())
                locationDwellStateStore.clearProfile(entityId)
                // Undoing a removed context moves the ones after it back, so a grant on a position
                // ends here as it does on a save. Unreadable contexts give no positions to compare.
                LocaleConditionGrantStore(appContext).run {
                    if (currentDecoded.issue == null) {
                        revokeChangedContexts(entityId, currentDecoded.value.contexts, target.contexts)
                    } else {
                        revokeAllForBinding(LocaleConditionGrantStore.profileContextsKey(entityId))
                    }
                }
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_SCENE -> {
                val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.elementsJson
                }
                val target = EditHistorySnapshotDecoder.scene(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareScene(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.sceneDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            else -> return@withTransaction null
        }
    }

    private suspend fun transitionStorageDeletion(
        entityType: String,
        entityId: Long,
        redo: Boolean,
        variables: VariableRepository.LockedMutations,
    ): SemanticDiffDocument? {
        val history = db.editHistoryDao()
        val historyEntry = if (redo) {
            history.getRedoCandidate(entityType, entityId)
        } else {
            history.getUndoCandidate(entityType, entityId)
        } ?: return null
        if (historyEntry.previousJson.isBlank() || historyEntry.nextJson.isNotBlank()) return null

        val changed = when (entityType) {
            EditHistoryDao.TYPE_VARIABLE -> transitionVariableDeletion(
                historyEntry.previousJson,
                entityId,
                redo,
                variables,
            )
            EditHistoryDao.TYPE_PROJECT -> transitionProjectDeletion(
                historyEntry.previousJson,
                entityId,
                redo,
                variables,
            )
            else -> false
        }
        if (!changed) return null
        if (redo) history.markRedone(historyEntry.id) else history.markUndone(historyEntry.id, "")
        return SemanticDiffDocument()
    }

    private suspend fun transitionVariableDeletion(
        json: String,
        entityId: Long,
        redo: Boolean,
        variables: VariableRepository.LockedMutations,
    ): Boolean {
        val snapshot = EditHistorySnapshotDecoder.variable(json, entityId)
        val current = variables.getStored(snapshot.name, snapshot.projectId)
        if (!redo) {
            if (current != null) return false
            variables.restoreStored(snapshot)
            return true
        }
        if (current != snapshot) return false
        val variable = variables.get(snapshot.name, snapshot.projectId) ?: return false
        if (!variableCanBeDeleted(variable)) return false
        variables.delete(snapshot.name, snapshot.projectId)
        LocaleConditionGrantStore(appContext).revokeAllForBinding(
            LocaleConditionGrantStore.variableKey(snapshot.projectId, snapshot.name),
        )
        return true
    }

    private suspend fun variableCanBeDeleted(variable: com.opentasker.core.model.Variable): Boolean {
        fun <T> requireDecoded(result: com.opentasker.core.storage.StorageDecodeResult<T>): T {
            result.issue?.let { throw CorruptRecordOverwriteException(it) }
            return result.value
        }
        val profiles = db.profileDao().getAll().map { requireDecoded(it.toDomainDecodeResult()) }
        val tasks = db.taskDao().getAll().map { requireDecoded(it.toDomainDecodeResult()) }
        val scenes = db.sceneDao().getAll().map { requireDecoded(it.toDomainDecodeResult()) }
        return AutomationReferenceRewriter.guardVariableDeletion(variable, profiles, tasks, scenes).canCommit
    }

    private suspend fun transitionProjectDeletion(
        json: String,
        entityId: Long,
        redo: Boolean,
        variables: VariableRepository.LockedMutations,
    ): Boolean {
        val snapshot = EditHistorySnapshotDecoder.projectDeletion(json, entityId)
        return if (redo) {
            redoProjectDeletion(snapshot, variables)
        } else {
            restoreProjectDeletion(snapshot, variables)
        }
    }

    private suspend fun restoreProjectDeletion(
        snapshot: ProjectDeletionSnapshot,
        variables: VariableRepository.LockedMutations,
    ): Boolean {
        if (db.projectDao().getById(snapshot.project.id) != null) return false
        if (db.projectDao().getById(snapshot.targetProjectId) == null) return false
        if (db.projectDao().getAll().any { it.name.equals(snapshot.project.name, ignoreCase = true) }) return false
        if (!projectMembership(snapshot.project.id).isEmpty()) return false
        if (!projectMembership(snapshot.targetProjectId).contains(snapshot.membership())) return false

        db.projectDao().insert(snapshot.project.toEntity())
        moveProjectMembership(snapshot, snapshot.targetProjectId, snapshot.project.id, variables)
        return true
    }

    private suspend fun redoProjectDeletion(
        snapshot: ProjectDeletionSnapshot,
        variables: VariableRepository.LockedMutations,
    ): Boolean {
        val currentProject = db.projectDao().getById(snapshot.project.id) ?: return false
        if (currentProject.toDomain() != snapshot.project) return false
        if (db.projectDao().getById(snapshot.targetProjectId) == null) return false
        if (projectMembership(snapshot.project.id) != snapshot.membership()) return false

        moveProjectMembership(snapshot, snapshot.project.id, snapshot.targetProjectId, variables)
        check(db.projectDao().deleteIfNotDefault(snapshot.project.id) == 1) {
            "Project no longer exists."
        }
        return true
    }

    private suspend fun moveProjectMembership(
        snapshot: ProjectDeletionSnapshot,
        fromProjectId: Long,
        toProjectId: Long,
        variables: VariableRepository.LockedMutations,
    ) {
        snapshot.taskIds.forEach { id ->
            val entity = checkNotNull(db.taskDao().getById(id)) { "Project task #$id is missing." }
            check(entity.projectId == fromProjectId) { "Project task #$id moved elsewhere." }
            db.taskDao().update(entity.copy(projectId = toProjectId))
        }
        snapshot.profileIds.forEach { id ->
            val entity = checkNotNull(db.profileDao().getById(id)) { "Project profile #$id is missing." }
            check(entity.projectId == fromProjectId) { "Project profile #$id moved elsewhere." }
            db.profileDao().update(entity.copy(projectId = toProjectId))
        }
        snapshot.sceneIds.forEach { id ->
            val entity = checkNotNull(db.sceneDao().getById(id)) { "Project scene #$id is missing." }
            check(entity.projectId == fromProjectId) { "Project scene #$id moved elsewhere." }
            db.sceneDao().update(entity.copy(projectId = toProjectId))
        }
        variables.reassignProject(snapshot.variableNames.toSet(), fromProjectId, toProjectId)
    }

    private suspend fun projectMembership(projectId: Long): ProjectMembership = ProjectMembership(
        taskIds = db.taskDao().getAll().filter { it.projectId == projectId }.mapTo(sortedSetOf()) { it.id },
        profileIds = db.profileDao().getAll().filter { it.projectId == projectId }.mapTo(sortedSetOf()) { it.id },
        sceneIds = db.sceneDao().getAll().filter { it.projectId == projectId }.mapTo(sortedSetOf()) { it.id },
        variableNames = db.variableDao().getAllInProject(projectId).mapTo(sortedSetOf()) { it.name },
    )
}

private fun documentOf(entry: SemanticDiffEntry): SemanticDiffDocument = SemanticDiffDocument(listOf(entry))

private data class ProjectMembership(
    val taskIds: Set<Long>,
    val profileIds: Set<Long>,
    val sceneIds: Set<Long>,
    val variableNames: Set<String>,
) {
    fun isEmpty(): Boolean = taskIds.isEmpty() && profileIds.isEmpty() && sceneIds.isEmpty() && variableNames.isEmpty()

    fun contains(other: ProjectMembership): Boolean =
        taskIds.containsAll(other.taskIds) &&
            profileIds.containsAll(other.profileIds) &&
            sceneIds.containsAll(other.sceneIds) &&
            variableNames.containsAll(other.variableNames)
}

private fun ProjectDeletionSnapshot.membership(): ProjectMembership = ProjectMembership(
    taskIds = taskIds.toSet(),
    profileIds = profileIds.toSet(),
    sceneIds = sceneIds.toSet(),
    variableNames = variableNames.toSet(),
)
