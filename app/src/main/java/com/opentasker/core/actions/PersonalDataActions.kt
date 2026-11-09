package com.opentasker.core.actions

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.platform.AppVisibilityTracker
import com.opentasker.core.platform.BackgroundActivityStart
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_CLIPBOARD_CHARS = 64 * 1024
private const val MAX_CONTACT_QUERY_CHARS = 128
private const val MAX_CONTACT_RESULTS = 50
internal const val CONTACT_PICKER_TIMEOUT_MS = 120_000L

class ClipboardGetAction : DeclaredAction(ActionCatalog.require("clipboard.get")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val output = outputVariable(args, "Clipboard") ?: return ActionResult.Failure("invalid output variable")
        val clipboard = ctx.app.getSystemService(ClipboardManager::class.java)
            ?: return ActionResult.Failure("clipboard service unavailable")
        val clip = runCatching { clipboard.primaryClip }
            .getOrElse { return ActionResult.Failure("clipboard read failed: ${it.message}") }

        // Android 10+ returns null rather than throwing when the caller is not the focused window
        // or the default IME. Automations run from the background service, so that is the normal
        // case, and treating null as "" reported Success with an empty value - indistinguishable
        // from a genuinely empty clipboard, and silently feeding blank data to whatever came next.
        if (clip == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !AppVisibilityTracker.isAppVisible) {
            return ActionResult.Failure(
                "Android blocks clipboard reads from the background; run this action while OpenTasker is open",
            )
        }

        val text = clip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?.take(MAX_CLIPBOARD_CHARS)
            .orEmpty()

        ctx.variables.set(output, text, sensitive = true)
        ctx.variables.set("${output}_has_text", text.isNotEmpty().toString())
        ctx.logger("Clipboard read: ${text.length} characters")
        return ActionResult.Success
    }
}

class ClipboardSetAction : DeclaredAction(ActionCatalog.require("clipboard.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"] ?: return ActionResult.Failure("missing text")
        if (text.length > MAX_CLIPBOARD_CHARS) {
            return ActionResult.Failure("clipboard text exceeds $MAX_CLIPBOARD_CHARS characters")
        }
        val clipboard = ctx.app.getSystemService(ClipboardManager::class.java)
            ?: return ActionResult.Failure("clipboard service unavailable")
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenTasker", text))
            ctx.logger("Clipboard write: ${text.length} characters")
            ActionResult.Success
        }.getOrElse { ActionResult.Failure("clipboard write failed: ${it.message}") }
    }
}

class ContactsLookupAction : DeclaredAction(ActionCatalog.require("contacts.lookup")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val query = args["query"]?.trim()?.takeIf(String::isNotBlank)
            ?: return ActionResult.Failure("missing query")
        if (query.length > MAX_CONTACT_QUERY_CHARS) {
            return ActionResult.Failure("contact query exceeds $MAX_CONTACT_QUERY_CHARS characters")
        }
        val output = outputVariable(args, "Contact") ?: return ActionResult.Failure("invalid output variable")
        val mode = args["mode"]?.trim()?.lowercase().ifNullOrBlank {
            if (Build.VERSION.SDK_INT >= 37) "picker" else "permission"
        }
        val contacts = when (mode) {
            "permission" -> {
                if (ctx.app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    return ActionResult.Failure("READ_CONTACTS permission is not granted; grant it in Setup")
                }
                runCatching { ContactLookupRepository(ctx.app.contentResolver).find(query) }
                    .getOrElse { return ActionResult.Failure("contact lookup failed: ${it.message}") }
            }

            "picker" -> runCatching { findWithPicker(ctx, query) }
                .getOrElse { return ActionResult.Failure("contact picker failed: ${it.message}") }
            else -> return ActionResult.Failure("mode must be permission or picker")
        }
        if (contacts.isEmpty()) {
            ctx.variables.set("${output}_count", "0")
            return ActionResult.Failure("no contacts matched query")
        }

        val names = contacts.map(ContactRecord::name).filter(String::isNotBlank)
        val phones = contacts.flatMap(ContactRecord::phones).distinct()
        val emails = contacts.flatMap(ContactRecord::emails).distinct()
        val first = contacts.first()
        ctx.variables.set("${output}_id", first.id.toString(), sensitive = true)
        ctx.variables.set("${output}_name", first.name, sensitive = true)
        ctx.variables.set("${output}_phone", first.phones.firstOrNull().orEmpty(), sensitive = true)
        ctx.variables.set("${output}_email", first.emails.firstOrNull().orEmpty(), sensitive = true)
        ctx.variables.set("${output}_count", contacts.size.toString())
        ctx.variables.setArray("${output}_names", names, sensitive = true)
        ctx.variables.setArray("${output}_phones", phones, sensitive = true)
        ctx.variables.setArray("${output}_emails", emails, sensitive = true)
        ctx.logger("Contacts lookup matched ${contacts.size} contact(s)")
        return ActionResult.Success
    }

    private suspend fun findWithPicker(ctx: ActionContext, query: String): List<ContactRecord> {
        if (Build.VERSION.SDK_INT < 37) {
            throw IllegalStateException("the Android 17 contact picker is unavailable on this device")
        }
        // Android would drop the picker and this would sit out the whole timeout for nothing.
        if (BackgroundActivityStart.likelyBlocked(ctx.app)) {
            throw IllegalStateException(BackgroundActivityStart.failureMessage("the contact picker"))
        }
        val requestId = ContactPickerCoordinator.nextRequestId()
        val result = ContactPickerCoordinator.register(requestId)
        return try {
            ContactPickerCoordinator.launch(ctx.app, requestId, query)
            val sessionUri = withTimeoutOrNull(CONTACT_PICKER_TIMEOUT_MS) { result.await() }
                ?: throw IllegalStateException("contact picker timed out or was cancelled")
            ContactLookupRepository(ctx.app.contentResolver)
                .findPickerSession(sessionUri)
                .filter { it.matches(query) }
        } finally {
            ContactPickerCoordinator.remove(requestId)
        }
    }
}

internal data class ContactRecord(
    val id: Long,
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
)

internal class ContactLookupRepository(private val resolver: ContentResolver) {
    fun find(query: String): List<ContactRecord> {
        val records = linkedMapOf<Long, MutableContact>()
        queryNames(records, query)
        queryPhones(records, query, onlyMatching = true)
        queryEmails(records, query, onlyMatching = true)
        val ids = records.keys.take(MAX_CONTACT_RESULTS)
        if (ids.isNotEmpty()) {
            queryPhonesForIds(records, ids)
            queryEmailsForIds(records, ids)
        }
        return records.values
            .take(MAX_CONTACT_RESULTS)
            .map { it.toRecord() }
    }

    fun findPickerSession(sessionUri: Uri): List<ContactRecord> {
        val records = linkedMapOf<Long, MutableContact>()
        val projection = arrayOf(
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
        )
        resolver.query(sessionUri, projection, null, null, null)?.use { cursor ->
            val lookupIndex = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val mimeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val dataIndex = cursor.getColumnIndex(ContactsContract.Data.DATA1)
            while (cursor.moveToNext() && records.size < MAX_CONTACT_RESULTS) {
                val lookupKey = cursor.stringOrEmpty(lookupIndex).ifBlank { "picker-${records.size}" }
                val id = lookupKey.hashCode().toLong()
                val contact = records.getOrPut(id) { MutableContact(id, cursor.stringOrEmpty(nameIndex)) }
                val value = cursor.stringOrEmpty(dataIndex)
                when (cursor.stringOrEmpty(mimeIndex)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> contact.phones += value
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> contact.emails += value
                }
            }
        }
        return records.values.map { it.toRecord() }
    }

    private fun queryNames(records: MutableMap<Long, MutableContact>, query: String) {
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? ESCAPE '\\'",
            arrayOf(likePattern(query)),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && records.size < MAX_CONTACT_RESULTS) {
                val id = cursor.longOrFallback(idIndex, records.size.toLong())
                records.getOrPut(id) { MutableContact(id, cursor.stringOrEmpty(nameIndex)) }
            }
        }
    }

    private fun queryPhones(records: MutableMap<Long, MutableContact>, query: String, onlyMatching: Boolean) {
        if (!onlyMatching) return
        queryPhones(
            records,
            selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? ESCAPE '\\' OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? ESCAPE '\\'",
            args = arrayOf(likePattern(query), likePattern(query)),
        )
    }

    private fun queryPhonesForIds(records: MutableMap<Long, MutableContact>, ids: Collection<Long>) {
        queryPhones(
            records,
            selection = inSelection(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ids.size),
            args = ids.map(Long::toString).toTypedArray(),
        )
    }

    private fun queryPhones(
        records: MutableMap<Long, MutableContact>,
        selection: String,
        args: Array<String>,
    ) {
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            selection,
            args,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && records.size < MAX_CONTACT_RESULTS) {
                val id = cursor.longOrFallback(idIndex, records.size.toLong())
                val contact = records.getOrPut(id) { MutableContact(id, cursor.stringOrEmpty(nameIndex)) }
                cursor.stringOrEmpty(numberIndex).takeIf(String::isNotBlank)?.let { contact.phones += it }
            }
        }
    }

    private fun queryEmails(records: MutableMap<Long, MutableContact>, query: String, onlyMatching: Boolean) {
        if (!onlyMatching) return
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            "${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ? ESCAPE '\\'",
            arrayOf(likePattern(query)),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addressIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext() && records.size < MAX_CONTACT_RESULTS) {
                val id = cursor.longOrFallback(idIndex, records.size.toLong())
                val contact = records.getOrPut(id) { MutableContact(id, cursor.stringOrEmpty(nameIndex)) }
                cursor.stringOrEmpty(addressIndex).takeIf(String::isNotBlank)?.let { contact.emails += it }
            }
        }
    }

    private fun queryEmailsForIds(records: MutableMap<Long, MutableContact>, ids: Collection<Long>) {
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            ),
            inSelection(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ids.size),
            ids.map(Long::toString).toTypedArray(),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val addressIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext() && records.size < MAX_CONTACT_RESULTS) {
                val id = cursor.longOrFallback(idIndex, records.size.toLong())
                val contact = records.getOrPut(id) { MutableContact(id, cursor.stringOrEmpty(nameIndex)) }
                cursor.stringOrEmpty(addressIndex).takeIf(String::isNotBlank)?.let { contact.emails += it }
            }
        }
    }

    private fun inSelection(column: String, count: Int): String =
        "$column IN (${List(count) { "?" }.joinToString(",")})"

    private class MutableContact(val id: Long, var name: String) {
        val phones = linkedSetOf<String>()
        val emails = linkedSetOf<String>()

        fun toRecord() = ContactRecord(id, name, phones.toList(), emails.toList())
    }
}

private fun Cursor.stringOrEmpty(index: Int): String =
    if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

private fun Cursor.longOrFallback(index: Int, fallback: Long): Long =
    if (index >= 0 && !isNull(index)) getLong(index) else fallback

private fun likePattern(query: String): String =
    "%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"

private fun ContactRecord.matches(query: String): Boolean =
    (listOf(name) + phones + emails).any { it.contains(query, ignoreCase = true) }

private fun String?.ifNullOrBlank(default: () -> String): String =
    if (this.isNullOrBlank()) default() else this

private fun outputVariable(args: Map<String, String>, defaultName: String): String? =
    VariableNamePolicy.normalize(args["var"] ?: args["result"] ?: defaultName)
