package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.opentasker.app.R
import java.io.ByteArrayOutputStream

/** Raw bytes, because a password-protected MacroDroid export doesn't survive decoding as text. */
internal fun readBoundedTaskerOrMacroDroidBytes(context: Context, uri: Uri): ByteArray {
    return readBoundedDocumentBytes(
        context = context,
        uri = uri,
        maxBytes = TASKER_MACRODROID_IMPORT_MAX_BYTES,
        labelRes = R.string.import_document_tasker_macrodroid,
    )
}

internal fun readBoundedOpenTaskerBundle(context: Context, uri: Uri): String {
    return readBoundedDocumentBytes(
        context = context,
        uri = uri,
        maxBytes = OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES,
        labelRes = R.string.import_document_opentasker_bundle,
    ).toString(Charsets.UTF_8)
}

internal fun readBoundedDocumentBytes(
    context: Context,
    uri: Uri,
    maxBytes: Int,
    @StringRes labelRes: Int,
): ByteArray {
    val label = context.getString(labelRes)
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw UiRejection(R.string.ui_error_document_unreadable, listOf(label))
    ByteArrayOutputStream().use { output ->
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > maxBytes) {
                    throw UiRejection(
                        R.string.ui_error_document_too_large,
                        listOf(label, maxBytes / (1024 * 1024)),
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
