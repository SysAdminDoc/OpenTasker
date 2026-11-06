package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The replayable portion of a rejected trigger. It intentionally excludes the task definition:
 * replay always resolves the current task row, so edits and deletions are handled honestly.
 */
@Serializable
data class HeldExecutionPayload(
    val taskId: Long,
    val taskName: String,
    val source: String,
    val profileId: Long? = null,
    val metadata: List<String> = emptyList(),
    val initialVariables: Map<String, String> = emptyMap(),
)

/** Bounded JSON codec for admission-held trigger data. Values are redacted before persistence. */
object HeldExecutionPayloadCodec {
    const val REDACTED_VALUE = ActionArgumentSensitivity.REDACTED
    const val MAX_PAYLOAD_CHARS = 16 * 1024

    private const val MAX_VARIABLES = 32
    private const val MAX_METADATA_LINES = 24
    private const val MAX_FIELD_CHARS = 512
    private val sensitiveName = Regex(
        "(?i)(password|secret|token|auth|credential|cookie|api[_-]?key|private|pin|code|email|phone|address|message|body)",
    )
    private val sensitiveValue = Regex(
        "(?i)(authorization|bearer|password|secret|token|api[_-]?key|auth|cookie|credential)\\s*[:=]\\s*[^\\s,;]+",
    )

    fun encode(
        task: Task,
        envelope: ExecutionEnvelope,
        metadata: List<String>,
        initialVariables: Map<String, String>,
    ): String = encode(
        HeldExecutionPayload(
            taskId = task.id,
            taskName = task.name,
            source = envelope.source,
            profileId = envelope.profileId,
            metadata = metadata.map(::redactText).take(MAX_METADATA_LINES),
            initialVariables = initialVariables.entries
                .asSequence()
                .mapNotNull { (rawName, value) ->
                    val name = rawName.trim().take(MAX_FIELD_CHARS).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    name to if (isSensitiveName(name)) REDACTED_VALUE else redactText(value)
                }
                .take(MAX_VARIABLES)
                .toMap(),
        ),
    )

    fun encode(payload: HeldExecutionPayload): String {
        val bounded = payload.copy(
            taskName = payload.taskName.trim().take(MAX_FIELD_CHARS),
            source = payload.source.trim().take(MAX_FIELD_CHARS),
            metadata = payload.metadata.map(::redactText).take(MAX_METADATA_LINES),
            initialVariables = payload.initialVariables.entries
                .asSequence()
                .map { (name, value) ->
                    name.trim().take(MAX_FIELD_CHARS) to
                        if (isSensitiveName(name)) REDACTED_VALUE else redactText(value)
                }
                .filter { it.first.isNotBlank() }
                .take(MAX_VARIABLES)
                .toMap(),
        )
        val encoded = StorageJson.encodeToString(bounded)
        if (encoded.length <= MAX_PAYLOAD_CHARS) return encoded

        // Keep the task/source identity and a small safe variable sample if a caller supplied
        // unusually large metadata. The result remains valid JSON and below the database bound.
        val reduced = bounded.copy(
            metadata = emptyList(),
            initialVariables = bounded.initialVariables.entries
                .take(8)
                .associate { (name, value) -> name to value.take(128) },
        )
        val reducedEncoded = StorageJson.encodeToString(reduced)
        if (reducedEncoded.length <= MAX_PAYLOAD_CHARS) return reducedEncoded

        return StorageJson.encodeToString(
            reduced.copy(
                taskName = reduced.taskName.take(128),
                source = reduced.source.take(128),
                initialVariables = emptyMap(),
            ),
        )
    }

    fun decode(encoded: String?): HeldExecutionPayload? {
        if (encoded.isNullOrBlank() || encoded.length > MAX_PAYLOAD_CHARS) return null
        val payload = runCatching { StorageJson.decodeFromString<HeldExecutionPayload>(encoded) }.getOrNull()
            ?: return null
        if (payload.taskId <= 0L || payload.source.isBlank()) return null
        if (payload.taskName.length > MAX_FIELD_CHARS || payload.source.length > MAX_FIELD_CHARS) return null
        if (payload.metadata.size > MAX_METADATA_LINES || payload.initialVariables.size > MAX_VARIABLES) return null
        if (payload.metadata.any { it.length > MAX_FIELD_CHARS }) return null
        if (payload.initialVariables.any { (name, value) ->
                name.isBlank() || name.length > MAX_FIELD_CHARS || value.length > MAX_FIELD_CHARS
            }) {
            return null
        }
        return payload
    }

    private fun isSensitiveName(name: String): Boolean =
        sensitiveName.containsMatchIn(name) || EventTaskVariables.isSensitive(name.trim())

    private fun redactText(value: String): String = value
        .replace(Regex("(?i)\\bbearer\\s+[^\\s,;]+"), "Bearer $REDACTED_VALUE")
        .replace(sensitiveValue, "$1$REDACTED_VALUE")
        .replace(Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"), REDACTED_VALUE)
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(MAX_FIELD_CHARS)
}
