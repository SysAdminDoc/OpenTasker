package com.opentasker.core.contexts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short-lived Sharesheet entry point; it queues the sanitized event and returns to the sender. */
class ShareReceiverActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handled = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (handled) return
        handled = true
        if (intent == null) {
            finish()
            return
        }
        scope.launch {
            when (withContext(Dispatchers.IO) {
                ShareContextEvents.publishFromIntent(this@ShareReceiverActivity, intent)
            }) {
                SharePublishResult.ACCEPTED -> {
                    // Nothing above this coroutine catches, so a refused start would end the process.
                    runCatching {
                        ContextCompat.startForegroundService(
                            this@ShareReceiverActivity,
                            Intent(this@ShareReceiverActivity, AutomationService::class.java),
                        )
                    }.onFailure { error ->
                        AppLogger.error("ShareReceiver", "Could not start the engine for a share", error)
                    }
                }
                SharePublishResult.URI_NOT_READABLE -> {
                    showShareError(R.string.share_uri_unreadable)
                }
                SharePublishResult.INVALID_INPUT -> {
                    showShareError(R.string.share_invalid)
                }
            }
            finish()
        }
    }

    private fun showShareError(messageRes: Int) {
        android.widget.Toast.makeText(this, messageRes, android.widget.Toast.LENGTH_LONG).show()
    }
}
