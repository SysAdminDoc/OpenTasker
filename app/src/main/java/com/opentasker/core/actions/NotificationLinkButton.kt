package com.opentasker.core.actions

/**
 * A notification button that opens a web address instead of running a task. It answers to the same
 * scheme allowlist as url.open, because a template can fill the address from outside text. Parsing
 * stays on plain strings so the check runs the same way off a device.
 */
sealed interface NotificationLinkButton {
    data class Link(val url: String, val label: String) : NotificationLinkButton

    data class Blocked(val scheme: String) : NotificationLinkButton

    companion object {
        fun urlKey(buttonIndex: Int): String = "button${buttonIndex}_url"

        fun parse(args: Map<String, String>, buttonIndex: Int): NotificationLinkButton? {
            val url = args[urlKey(buttonIndex)]?.trim().orEmpty()
            if (url.isEmpty()) return null
            val scheme = url.substringBefore(':', "").lowercase()
            if (scheme !in OpenUrlAction.allowedSchemes()) return Blocked(scheme)
            val label = args["button${buttonIndex}_label"]?.trim().orEmpty().ifEmpty {
                url.substringAfter("//").substringBefore('/').substringBefore('?').ifEmpty { scheme }
            }
            return Link(url, label)
        }
    }
}
