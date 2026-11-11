package com.opentasker.core.updates

import com.opentasker.core.data.JsonNesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure protocol and release-version rules for the optional public update check. */
object UpdateCheckProtocol {
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/SysAdminDoc/OpenTasker/releases/latest"
    const val RELEASES_URL_PREFIX =
        "https://github.com/SysAdminDoc/OpenTasker/releases/tag/"
    const val RESPONSE_VARIABLE = "update_check_body"
    const val MAX_RESPONSE_BYTES = 65_536
    /** GitHub's release object nests four levels (`assets[].uploader`). */
    const val MAX_RESPONSE_DEPTH = 32
    const val REQUEST_TIMEOUT_SECONDS = 15

    private const val ACCEPT_HEADER = "Accept: application/vnd.github+json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Arguments passed to [com.opentasker.core.actions.HttpRequestAction]. */
    fun requestArguments(): Map<String, String> = mapOf(
        "method" to "GET",
        "url" to LATEST_RELEASE_URL,
        "headers" to ACCEPT_HEADER,
        "response_var" to RESPONSE_VARIABLE,
        "max_response_bytes" to MAX_RESPONSE_BYTES.toString(),
        "timeout_sec" to REQUEST_TIMEOUT_SECONDS.toString(),
        "call_timeout_sec" to REQUEST_TIMEOUT_SECONDS.toString(),
        "connect_timeout_sec" to REQUEST_TIMEOUT_SECONDS.toString(),
        "read_timeout_sec" to REQUEST_TIMEOUT_SECONDS.toString(),
        "write_timeout_sec" to REQUEST_TIMEOUT_SECONDS.toString(),
        "redirects" to "none",
    )

    fun parseLatestRelease(payload: String, currentVersion: String): UpdateCheckResult {
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            return UpdateCheckResult.Invalid("release response exceeds the size limit")
        }
        val depth = JsonNesting.depth(payload)
        if (depth == JsonNesting.NOT_JSON) return UpdateCheckResult.Invalid("release response is malformed")
        if (depth > MAX_RESPONSE_DEPTH) return UpdateCheckResult.Invalid("release response nests too deep")
        return runCatching {
            parseLatestReleaseObject(payload, currentVersion)
        }.getOrElse {
            UpdateCheckResult.Invalid("release response is malformed")
        }
    }

    private fun parseLatestReleaseObject(payload: String, currentVersion: String): UpdateCheckResult {
        val release = json.parseToJsonElement(payload) as? JsonObject
            ?: return UpdateCheckResult.Invalid("release response is not an object")
        val tag = release["tag_name"]?.jsonPrimitive?.content?.trim()
            ?: return UpdateCheckResult.Invalid("release tag is missing")
        val latest = SemanticVersion.parse(tag)
            ?: return UpdateCheckResult.Invalid("release tag is not a stable semantic version")
        val current = SemanticVersion.parse(currentVersion)
            ?: return UpdateCheckResult.Invalid("installed version is not a stable semantic version")

        if (release.booleanValue("draft") || release.booleanValue("prerelease")) {
            return UpdateCheckResult.NoUpdate
        }
        if (latest <= current) return UpdateCheckResult.NoUpdate

        val releaseUrl = release["html_url"]?.jsonPrimitive?.content?.trim()
            ?: return UpdateCheckResult.Invalid("release URL is missing")
        if (releaseUrl != RELEASES_URL_PREFIX + tag) {
            return UpdateCheckResult.Invalid("release URL is outside the OpenTasker release page")
        }
        return UpdateCheckResult.Available(
            version = latest.display,
            url = releaseUrl,
        )
    }

    private fun JsonObject.booleanValue(name: String): Boolean =
        this[name]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val display: String,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int = compareValuesBy(
            this,
            other,
            SemanticVersion::major,
            SemanticVersion::minor,
            SemanticVersion::patch,
        )

        companion object {
            private val PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

            fun parse(raw: String): SemanticVersion? {
                val match = PATTERN.matchEntire(raw) ?: return null
                val major = match.groupValues[1].toIntOrNull() ?: return null
                val minor = match.groupValues[2].toIntOrNull() ?: return null
                val patch = match.groupValues[3].toIntOrNull() ?: return null
                return SemanticVersion(major, minor, patch, "$major.$minor.$patch")
            }
        }
    }
}

sealed interface UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult

    data class Available(
        val version: String,
        val url: String,
    ) : UpdateCheckResult

    data class Invalid(val reason: String) : UpdateCheckResult
}
