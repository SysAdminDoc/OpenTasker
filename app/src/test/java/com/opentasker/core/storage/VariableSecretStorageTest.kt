package com.opentasker.core.storage

import com.opentasker.core.model.Variable
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableSecretStorageTest {
    @Test
    fun aesGcmEnvelopeAuthenticatesValueAndVariableName() {
        val codec = codec(newKey())
        val envelope = codec.encrypt(DEFAULT_PROJECT_ID, "API_TOKEN", "super-secret-value")

        assertTrue(envelope.startsWith("otsec:v2:"))
        assertFalse(envelope.contains("super-secret-value"))
        assertEquals(
            "super-secret-value",
            codec.decrypt(DEFAULT_PROJECT_ID, "API_TOKEN", envelope).getOrThrow(),
        )
        assertTrue(codec.decrypt(DEFAULT_PROJECT_ID, "OTHER_TOKEN", envelope).isFailure)
        // The project is part of the variable's identity, so the same name in another project
        // must not be able to borrow this envelope.
        assertTrue(codec.decrypt(DEFAULT_PROJECT_ID + 1, "API_TOKEN", envelope).isFailure)

        val tamperIndex = envelope.lastIndex - 2
        val tampered = envelope.replaceRange(
            tamperIndex,
            tamperIndex + 1,
            if (envelope[tamperIndex] == 'A') "B" else "A",
        )
        assertTrue(codec.decrypt(DEFAULT_PROJECT_ID, "API_TOKEN", tampered).isFailure)
    }

    @Test
    fun secretRowsStoreOnlyCiphertextAndDecodeForUi() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(Variable("API_TOKEN", "token-123", isGlobal = true, isSecret = true))

        val stored = dao.get("API_TOKEN")!!
        assertTrue(stored.isSecret)
        assertNotEquals("token-123", stored.value)
        assertFalse(stored.value.contains("token-123"))
        assertEquals("token-123", repository.observeGlobals().first().single().value)
    }

    @Test
    fun globalWritesUseCanonicalScopePolicy() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(Variable("%counter", "7", isGlobal = true))
        repository.importVariable(Variable("myValue", "8", isGlobal = true))

        assertEquals("7", dao.get("Counter")?.value)
        assertEquals("8", dao.get("myValue")?.value)
        assertTrue(runCatching {
            repository.upsert(Variable("localName", "invalid", isGlobal = false))
        }.isFailure)
    }

    @Test
    fun projectScopedRenameKeepsProjectAndRekeysSecretCiphertext() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))

        repository.upsert(
            Variable(
                "API_TOKEN",
                "token-123",
                isGlobal = true,
                isSecret = true,
                projectId = 42,
            ),
        )
        assertEquals(42L, dao.getInProject("API_TOKEN", 42)?.projectId)
        assertEquals("token-123", repository.get("API_TOKEN", 42)?.value)

        repository.rename(
            previousName = "API_TOKEN",
            variable = Variable(
                "ROTATED_TOKEN",
                "replacement",
                isGlobal = true,
                isSecret = true,
                projectId = 42,
            ),
        )

        assertNull(dao.getInProject("API_TOKEN", 42))
        assertEquals(42L, dao.getInProject("ROTATED_TOKEN", 42)?.projectId)
        assertEquals("replacement", repository.runtimeGlobals(42).values["ROTATED_TOKEN"])
    }

    @Test
    fun concurrentDisjointGlobalCommitsMergeWithoutLoss() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity("ALPHA", "0", isGlobal = true),
            VariableEntity("BETA", "0", isGlobal = true),
        )
        val key = newKey()
        val first = VariableRepository(dao, codec(key))
        val second = VariableRepository(dao, codec(key))
        val baseline = first.runtimeGlobals()

        val results = listOf(
            async {
                first.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("ALPHA", "1", isSecret = false)),
                )
            },
            async {
                second.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("BETA", "2", isSecret = false)),
                )
            },
        ).awaitAll()

        assertTrue(results.all { it.conflictedNames.isEmpty() })
        assertEquals(mapOf("ALPHA" to "1", "BETA" to "2"), first.runtimeGlobals().values)
    }

    @Test
    fun concurrentSameGlobalCommitKeepsFirstCommittedValue() = runBlocking {
        val dao = FakeVariableDao(VariableEntity("COUNT", "0", isGlobal = true))
        val key = newKey()
        val first = VariableRepository(dao, codec(key))
        val second = VariableRepository(dao, codec(key))
        val baseline = first.runtimeGlobals()

        val results = listOf(
            async {
                first.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("COUNT", "1", isSecret = false)),
                )
            },
            async {
                second.persistRuntimeAtomically(
                    baseline,
                    listOf(RuntimeVariableValue("COUNT", "2", isSecret = false)),
                )
            },
        ).awaitAll()

        assertEquals(1, results.count { "COUNT" in it.appliedNames })
        assertEquals(1, results.count { "COUNT" in it.conflictedNames })
        assertTrue(first.runtimeGlobals().values["COUNT"] in setOf("1", "2"))
    }

    @Test
    fun aGlobalTheRunUnsetIsDeletedSoItStaysUnsetNextRun() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity("LOG1", "a", isGlobal = true),
            VariableEntity("LOG2", "b", isGlobal = true),
        )
        val repository = VariableRepository(dao, codec(newKey()))
        val baseline = repository.runtimeGlobals()

        val result = repository.persistRuntimeAtomically(
            baseline,
            listOf(RuntimeVariableValue("LOG1", "b", isSecret = false)),
            removedNames = listOf("LOG2", "NEVER_SET"),
        )

        assertEquals(listOf("LOG1", "LOG2", "NEVER_SET"), result.appliedNames.sorted())
        assertTrue(result.conflictedNames.isEmpty())
        assertEquals(mapOf("LOG1" to "b"), repository.runtimeGlobals().values)
    }

    @Test
    fun anUnsetGlobalAnotherRunChangedMeanwhileIsKept() = runBlocking {
        val dao = FakeVariableDao(VariableEntity("LOG2", "b", isGlobal = true))
        val key = newKey()
        val first = VariableRepository(dao, codec(key))
        val second = VariableRepository(dao, codec(key))
        val baseline = first.runtimeGlobals()
        second.persistRuntimeAtomically(baseline, listOf(RuntimeVariableValue("LOG2", "newer", isSecret = false)))

        val result = first.persistRuntimeAtomically(baseline, emptyList(), removedNames = listOf("LOG2"))

        assertEquals(listOf("LOG2"), result.conflictedNames)
        assertEquals("newer", first.runtimeGlobals().values["LOG2"])
    }

    @Test
    fun missingOrReplacedKeystoreKeyRequiresReentryWithoutReturningCiphertext() = runBlocking {
        val dao = FakeVariableDao()
        VariableRepository(dao, codec(newKey())).upsert(
            Variable("API_TOKEN", "token-123", isGlobal = true, isSecret = true),
        )

        val restoredOnDifferentDevice = VariableRepository(dao, codec(newKey()))
        val variable = restoredOnDifferentDevice.observeGlobals().first().single()
        val runtime = restoredOnDifferentDevice.runtimeGlobals()

        assertTrue(variable.isSecret)
        assertFalse(variable.secretAvailable)
        assertEquals("", variable.value)
        assertFalse("Unavailable secrets must not be expanded", runtime.values.containsKey("API_TOKEN"))
        assertEquals(setOf("API_TOKEN"), runtime.unavailableSecretNames)

        restoredOnDifferentDevice.upsert(variable.copy(value = "replacement", secretAvailable = true))
        assertEquals("replacement", restoredOnDifferentDevice.runtimeGlobals().values["API_TOKEN"])
    }

    @Test
    fun legacyNameMaskedVariablesAreEncryptedBeforeReadOrExport() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity("PASSWORD", "legacy-plaintext", isGlobal = true, isSecret = true),
            VariableEntity("COUNT", "7", isGlobal = true),
        )
        val repository = VariableRepository(dao, codec(newKey()))

        val observed = repository.observeGlobals().first()
        val storedSecret = dao.get("PASSWORD")!!
        val exported = repository.ordinaryExport()

        assertTrue(storedSecret.isSecret)
        assertFalse(storedSecret.value.contains("legacy-plaintext"))
        assertTrue(observed.single { it.name == "PASSWORD" }.isSecret)
        assertEquals(listOf("COUNT"), exported.variables.map { it.name })
        assertEquals(1, exported.omittedSecretCount)
    }

    @Test
    fun legacyValueThatOnlyLooksLikeAnEnvelopeIsStillEncrypted() = runBlocking {
        val legacyValue = "otsec:v1:not-a-valid-envelope"
        val dao = FakeVariableDao(
            VariableEntity("API_TOKEN", legacyValue, isGlobal = true, isSecret = true),
        )
        val repository = VariableRepository(dao, codec(newKey()))

        repository.requireEncryptedSecretRows()

        assertNotEquals(legacyValue, dao.get("API_TOKEN")!!.value)
        assertEquals(legacyValue, repository.runtimeGlobals().values["API_TOKEN"])
    }

    @Test
    fun backupGuardFailsClosedWhenLegacyPlaintextCannotBeEncrypted() = runBlocking {
        val dao = FakeVariableDao(
            VariableEntity("API_TOKEN", "legacy-plaintext", isGlobal = true, isSecret = true),
        )
        val repository = VariableRepository(
            dao,
            object : VariableSecretCodec {
                override fun encrypt(projectId: Long, variableName: String, plaintext: String): String =
                    error("Keystore unavailable")

                override fun decrypt(projectId: Long, variableName: String, envelope: String): Result<String> =
                    Result.failure(IllegalStateException("Keystore unavailable"))
            },
        )

        val failure = runCatching { repository.requireEncryptedSecretRows() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("legacy-plaintext", dao.get("API_TOKEN")!!.value)
    }

    @Test
    fun movingVariablesToAnotherProjectReEncryptsSecretsSoTheyStayReadable() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))
        val source = DEFAULT_PROJECT_ID + 7
        val target = DEFAULT_PROJECT_ID + 9
        repository.upsert(Variable("API_TOKEN", "token-123", isGlobal = true, isSecret = true, projectId = source))
        repository.upsert(Variable("Plain", "kept", isGlobal = true, projectId = source))

        repository.withMutationLock { reassignProject(source, target) }

        // A raw row copy would leave the envelope bound to the old project id and decode to "".
        val moved = repository.get("API_TOKEN", target)!!
        assertTrue(moved.secretAvailable)
        assertEquals("token-123", moved.value)
        assertEquals("kept", repository.get("Plain", target)!!.value)
        assertTrue(dao.getAllInProject(source).isEmpty())
    }

    @Test
    fun selectedProjectMoveLeavesOtherVariablesAndKeepsSecretsReadable() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))
        repository.upsert(Variable("TOKEN", "secret", isGlobal = true, isSecret = true, projectId = 5L))
        repository.upsert(Variable("Keep", "source", isGlobal = true, projectId = 5L))

        repository.withMutationLock {
            reassignProject(setOf("TOKEN"), fromProjectId = 5L, toProjectId = 9L)
        }

        assertEquals("secret", repository.get("TOKEN", 9L)?.value)
        assertEquals("source", repository.get("Keep", 5L)?.value)
        assertNull(repository.get("TOKEN", 5L))
    }

    @Test
    fun storedSecretSnapshotRestoresTheOriginalReadableCiphertext() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))
        repository.upsert(Variable("TOKEN", "secret", isGlobal = true, isSecret = true, projectId = 5L))

        val snapshot = repository.withMutationLock {
            val stored = requireNotNull(getStored("TOKEN", 5L))
            assertFalse(stored.value.contains("secret"))
            delete("TOKEN", 5L)
            stored
        }
        assertNull(repository.get("TOKEN", 5L))

        repository.withMutationLock { restoreStored(snapshot) }

        assertEquals("secret", repository.get("TOKEN", 5L)?.value)
        assertEquals(snapshot, dao.getInProject("TOKEN", 5L))
    }

    @Test
    fun movingAnUndecryptableSecretAbortsInsteadOfRelocatingDeadCiphertext() = runBlocking {
        // An envelope written under a key this install no longer has (rotated/lost Keystore key).
        val orphaned = codec(newKey()).encrypt(5L, "API_TOKEN", "token-123")
        val dao = FakeVariableDao(
            VariableEntity("API_TOKEN", orphaned, isGlobal = true, isSecret = true, projectId = 5L),
        )
        val repository = VariableRepository(dao, codec(newKey()))

        val failure = runCatching { repository.withMutationLock { reassignProject(5L, 9L) } }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, dao.getAllInProject(5L).size)
        assertTrue(dao.getAllInProject(9L).isEmpty())
    }

    @Test
    fun lockedMutationsRunWithoutReacquiringTheMutationLock() = runBlocking {
        val dao = FakeVariableDao()
        val repository = VariableRepository(dao, codec(newKey()))
        repository.upsert(Variable("API_TOKEN", "token-123", isGlobal = true, isSecret = true))

        // Every member must be reachable in one scope: the mutex is not reentrant, so a member
        // that still took the lock itself would hang here rather than fail.
        repository.withMutationLock {
            assertEquals("token-123", get("API_TOKEN")!!.value)
            importVariable(Variable("Imported", "9", isGlobal = true))
            rename("API_TOKEN", Variable("Renamed", "token-456", isGlobal = true, isSecret = true))
            delete("Imported")
        }

        assertNull(dao.get("API_TOKEN"))
        assertNull(dao.get("Imported"))
        assertEquals("token-456", repository.get("Renamed")!!.value)
    }

    private fun codec(key: SecretKey): VariableSecretCodec = AesGcmVariableSecretCodec(keyProvider = { key })

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class FakeVariableDao(vararg initial: VariableEntity) : VariableDao {
        private val state = MutableStateFlow(initial.toList())

        override suspend fun insert(v: VariableEntity) {
            state.value = state.value.filterNot { it.name == v.name && it.projectId == v.projectId } + v
        }

        override suspend fun insertStrict(v: VariableEntity) {
            check(getInProject(v.name, v.projectId) == null)
            state.value += v
        }

        override suspend fun insertAll(values: List<VariableEntity>) {
            values.forEach { insert(it) }
        }

        override suspend fun upsert(v: VariableEntity) {
            insert(v)
        }

        override suspend fun upsertAll(values: List<VariableEntity>) {
            values.forEach { upsert(it) }
        }

        override suspend fun update(v: VariableEntity) {
            insert(v)
        }

        override suspend fun delete(v: VariableEntity) {
            deleteByNameInProject(v.name, v.projectId)
        }

        override suspend fun deleteByName(name: String) {
            deleteByNameInProject(name, DEFAULT_PROJECT_ID)
        }

        override suspend fun deleteByNameInProject(name: String, projectId: Long) {
            state.value = state.value.filterNot { it.name == name && it.projectId == projectId }
        }

        override suspend fun get(name: String): VariableEntity? = state.value.firstOrNull { it.name == name }

        override suspend fun getInProject(name: String, projectId: Long): VariableEntity? =
            state.value.firstOrNull { it.name == name && it.projectId == projectId }

        override suspend fun getAll(): List<VariableEntity> = state.value

        override suspend fun getAllInProject(projectId: Long): List<VariableEntity> =
            state.value.filter { it.projectId == projectId }

        override suspend fun getAllGlobal(): List<VariableEntity> = state.value.filter { it.isGlobal }

        override suspend fun getAllGlobalInProject(projectId: Long): List<VariableEntity> =
            state.value.filter { it.isGlobal && it.projectId == projectId }

        override fun getAllGlobalAsFlow(): Flow<List<VariableEntity>> = state

        override fun getAllGlobalAsFlowInProject(projectId: Long): Flow<List<VariableEntity>> =
            state.map { values -> values.filter { it.isGlobal && it.projectId == projectId } }

        override fun getAllGlobalAsFlowAll(): Flow<List<VariableEntity>> = state.map { values -> values.filter { it.isGlobal } }

        override suspend fun countInProject(projectId: Long, name: String): Int =
            state.value.count { it.projectId == projectId && it.name == name }

        override suspend fun deleteAllInProject(projectId: Long) {
            state.value = state.value.filterNot { it.projectId == projectId }
        }
    }
}
