package com.opentasker.core.engine

import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.SmsContextEvents

/**
 * The variables a matched event hands the task it starts. SMS and notification fields are listed
 * by name rather than forwarded wholesale, so a metadata key added for matching doesn't reach
 * tasks by accident.
 */
internal object EventTaskVariables {
    private const val EVENT_SHARE = "share"
    private const val EVENT_NOTIFICATION = "notification"

    /**
     * Other people's words and numbers. An action still receives the real value, but the run log,
     * previews and held-trigger storage show a placeholder, and anything derived from it is
     * tainted the same way.
     */
    private val SENSITIVE = setOf(
        "sms_sender",
        "sms_body",
        "notification_title",
        "notification_body",
    )

    fun isSensitive(name: String): Boolean = name in SENSITIVE

    fun from(event: ContextEvent?): Map<String, String> {
        val metadata = event?.metadata ?: return emptyMap()
        return when (metadata["event"]) {
            BroadcastContextEvents.EVENT_BROADCAST -> buildMap {
                put("broadcast_action", metadata["broadcast_action"].orEmpty())
                put("broadcast_sender", metadata["broadcast_sender"].orEmpty())
                put("broadcast_extra_count", metadata["broadcast_extra_count"].orEmpty())
                put("broadcast_extras_lossy", metadata["broadcast_extras_lossy"] ?: "false")
                // Already bounded and string-only when the event was built, so forwarding them
                // wholesale cannot introduce anything the sanitiser refused.
                metadata.forEach { (key, value) ->
                    if (key.startsWith("broadcast_extra_") && key != "broadcast_extra_count") put(key, value)
                }
            }
            EVENT_SHARE -> mapOf(
                "share_event" to "true",
                "share_text" to metadata["text"].orEmpty(),
                "share_uri" to metadata["uri"].orEmpty(),
                "share_uris" to metadata["uris"].orEmpty(),
                "share_mime" to metadata["mime"].orEmpty(),
                "share_count" to metadata["count"].orEmpty(),
                "share_multiple" to metadata["multiple"].orEmpty(),
            )
            SmsContextEvents.EVENT_SMS_RECEIVED -> mapOf(
                "sms_sender" to metadata["sender"].orEmpty(),
                "sms_body" to metadata["body"].orEmpty(),
                "sms_kind" to metadata["kind"].orEmpty(),
                "sms_message_count" to metadata["messageCount"].orEmpty(),
                "sms_subscription_id" to metadata["subscriptionId"].orEmpty(),
            )
            EVENT_NOTIFICATION -> mapOf(
                "notification_package" to metadata["package"].orEmpty(),
                "notification_title" to metadata["title"].orEmpty(),
                "notification_body" to metadata["body"].orEmpty(),
            )
            else -> emptyMap()
        }
    }
}
