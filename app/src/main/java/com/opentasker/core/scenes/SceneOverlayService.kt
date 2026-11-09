package com.opentasker.core.scenes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.opentasker.app.R
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.InternalTaskRunSource
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Foreground service that displays a [Scene] as a draggable floating overlay
 * using [WindowManager] with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 */
class SceneOverlayService : Service() {

    private var overlayView: View? = null
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            AppLogger.warn(TAG, message = "Received null intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            AppLogger.error(TAG, message = "Overlay permission not granted, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val sceneJson = intent.getStringExtra(EXTRA_SCENE_JSON)
        if (sceneJson == null) {
            AppLogger.error(TAG, message = "Missing scene JSON extra, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val scene = try {
            Json.decodeFromString<Scene>(sceneJson)
        } catch (e: Exception) {
            AppLogger.error(TAG, message = "Failed to deserialize scene", throwable = e)
            stopSelf()
            return START_NOT_STICKY
        }

        AppLogger.info(TAG, message = "Showing scene overlay: ${scene.name} (id=${scene.id})")

        // Remove any existing overlay before showing a new one
        removeOverlay()
        showOverlay(scene)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        imageScope.cancel()
        super.onDestroy()
    }

    private fun showOverlay(scene: Scene) {
        val density = resources.displayMetrics.density
        val headerHeightPx = (HEADER_HEIGHT_DP * density).toInt().coerceAtLeast(1)
        val closeButtonSizePx = (CLOSE_BUTTON_SIZE_DP * density).toInt().coerceAtLeast(1)
        val displayMetrics = resources.displayMetrics
        val layoutPlan = SceneOverlayLayoutPlanner.plan(
            scene = scene,
            density = density,
            maxContentWidthPx = displayMetrics.widthPixels,
            maxContentHeightPx = (displayMetrics.heightPixels - headerHeightPx).coerceAtLeast(1),
        )

        // Root container: header + scene content
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OVERLAY_BACKGROUND)
        }

        // Header bar (draggable area + close button)
        val header = FrameLayout(this).apply {
            setBackgroundColor(HEADER_BACKGROUND)
            contentDescription = getString(R.string.scene_overlay_drag_handle_content_description)
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx,
            )
        }

        val closeButton = TextView(this).apply {
            text = CLOSE_LABEL
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.scene_overlay_close_content_description)
            layoutParams = FrameLayout.LayoutParams(closeButtonSizePx, closeButtonSizePx).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { stopSelf() }
        }
        header.addView(closeButton)
        root.addView(header)

        // Scene content area
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                layoutPlan.contentHeightPx,
            )
            clipChildren = true
        }

        for (layout in layoutPlan.elements) {
            val view = buildElementView(layout.element, layout.widthPx, layout.heightPx)
            content.addView(
                view,
                FrameLayout.LayoutParams(layout.widthPx, layout.heightPx).apply {
                    leftMargin = layout.xPx
                    topMargin = layout.yPx
                },
            )
        }
        root.addView(content)

        // WindowManager layout params
        val params = WindowManager.LayoutParams(
            layoutPlan.contentWidthPx,
            headerHeightPx + layoutPlan.contentHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        // Dragging via the header
        setupDrag(header, params)

        windowManager.addView(root, params)
        overlayView = root
    }

    private fun buildElementView(element: SceneElement, widthPx: Int, heightPx: Int): View {
        if (
            element.type == SceneElementType.SLIDER ||
            element.type == SceneElementType.IMAGE
        ) {
            if (!SceneElementConfigValidator.isValid(element)) return unsupportedElementView(element)
        }
        return when (element.type) {
            SceneElementType.BUTTON -> Button(this).apply {
                text = element.config["label"] ?: getString(R.string.scene_overlay_default_button)
                // Tapjacking guard: drop taps delivered while a malicious window obscures this
                // task-firing control (the overlay floats over other apps).
                filterTouchesWhenObscured = true
                setOnClickListener {
                    element.tapTaskId?.let { taskId -> fireRunTask(taskId) }
                }
                element.longPressTaskId?.let { longTaskId ->
                    setOnLongClickListener {
                        fireRunTask(longTaskId)
                        true
                    }
                }
            }

            SceneElementType.TEXT -> TextView(this).apply {
                // `label` is read as a fallback so an element migrated from a removed type keeps
                // the caption it was authored with instead of rendering as an invisible box.
                text = element.config["text"] ?: element.config["label"].orEmpty()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }

            SceneElementType.SLIDER -> {
                val config = SceneElementConfigResolver.slider(element)
                val boundTaskId = element.tapTaskId
                val seekBar = SeekBar(this).apply {
                    min = config.min
                    max = config.max
                    progress = config.value
                    // A slider bound to a task fires it on release with the value as a variable;
                    // an unbound slider is display-only.
                    if (boundTaskId != null) {
                        // Tapjacking guard for the task-firing slider.
                        filterTouchesWhenObscured = true
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
                            override fun onStartTrackingTouch(bar: SeekBar) = Unit
                            override fun onStopTrackingTouch(bar: SeekBar) {
                                fireRunTask(boundTaskId, SceneSliderBinding.taskVariables(config, bar.progress))
                            }
                        })
                    }
                }
                if (config.label.isBlank()) {
                    seekBar
                } else {
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(this@SceneOverlayService).apply {
                                text = config.label
                                setTextColor(Color.WHITE)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            },
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        addView(
                            seekBar,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f,
                            ),
                        )
                    }
                }
            }

            // The source is another app's content provider, which can take as long as it likes,
            // and an overlay that waits for it here freezes this process's main thread.
            SceneElementType.IMAGE -> FrameLayout(this).also { slot ->
                imageScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        SceneImageLoader.load(
                            context = this@SceneOverlayService,
                            source = element.config["source"].orEmpty(),
                            targetWidthPx = widthPx,
                            targetHeightPx = heightPx,
                        )
                    }
                    val image = if (bitmap != null) {
                        ImageView(this@SceneOverlayService).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = element.config["content_description"]?.trim()?.takeIf { it.isNotEmpty() }
                            importantForAccessibility = if (
                                element.config["decorative"].equals("true", ignoreCase = true)
                            ) {
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            } else {
                                View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            }
                        }
                    } else {
                        unsupportedElementView(element)
                    }
                    slot.addView(
                        image,
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                    )
                }
            }
        }
    }

    private fun unsupportedElementView(element: SceneElement): TextView =
        TextView(this).apply {
            val typeLabel = getString(
                when (element.type) {
                    SceneElementType.BUTTON -> R.string.scene_element_type_button
                    SceneElementType.TEXT -> R.string.scene_element_type_text
                    SceneElementType.SLIDER -> R.string.scene_element_type_slider
                    SceneElementType.IMAGE -> R.string.scene_element_type_image
                },
            )
            text = getString(R.string.scene_overlay_unsupported_element, typeLabel)
            setTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val padPx = (2 * resources.displayMetrics.density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

    private fun setupDrag(dragHandle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val accessibilityStepPx = (ACCESSIBILITY_MOVE_STEP_DP * resources.displayMetrics.density)
            .toInt()
            .coerceAtLeast(1)

        // Keep the window (including its drag handle and close button) on screen, so a stray
        // drag or repeated accessibility move can't park it entirely offscreen with no recovery.
        fun clampToDisplay() {
            val view = overlayView ?: return
            val metrics = resources.displayMetrics
            val viewWidth = view.width.takeIf { it > 0 } ?: params.width.coerceAtLeast(0)
            val viewHeight = view.height.takeIf { it > 0 } ?: params.height.coerceAtLeast(0)
            // TOP|START gravity: x/y are offsets from the top-left. Keep at least a sliver
            // (the header) reachable on every edge.
            val margin = (48 * metrics.density).toInt()
            val maxX = (metrics.widthPixels - margin).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - margin).coerceAtLeast(0)
            val minX = -(viewWidth - margin).coerceAtLeast(0)
            val minY = 0
            params.x = params.x.coerceIn(minX, maxX)
            params.y = params.y.coerceIn(minY, maxY)
        }

        fun moveBy(deltaX: Int, deltaY: Int): Boolean {
            params.x += deltaX
            params.y += deltaY
            clampToDisplay()
            overlayView?.let { windowManager.updateViewLayout(it, params) }
            return true
        }

        ViewCompat.addAccessibilityAction(
            dragHandle,
            getString(R.string.scene_overlay_move_left_action),
        ) { _, _ -> moveBy(-accessibilityStepPx, 0) }
        ViewCompat.addAccessibilityAction(
            dragHandle,
            getString(R.string.scene_overlay_move_up_action),
        ) { _, _ -> moveBy(0, -accessibilityStepPx) }
        ViewCompat.addAccessibilityAction(
            dragHandle,
            getString(R.string.scene_overlay_move_down_action),
        ) { _, _ -> moveBy(0, accessibilityStepPx) }
        ViewCompat.addAccessibilityAction(
            dragHandle,
            getString(R.string.scene_overlay_move_right_action),
        ) { _, _ -> moveBy(accessibilityStepPx, 0) }

        dragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    moved = moved || kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop
                    params.x = initialX + deltaX.toInt()
                    params.y = initialY + deltaY.toInt()
                    clampToDisplay()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun fireRunTask(taskId: Long, variables: Map<String, String> = emptyMap()) {
        AppLogger.info(TAG, message = "Scene element firing task $taskId")
        val intent = AutomationTargetContract.internalRunTaskIntent(
            context = this,
            taskId = taskId,
            source = InternalTaskRunSource.SCENE_OVERLAY,
            variables = variables,
        )
        sendBroadcast(intent, AutomationTargetContract.PERMISSION)
    }

    private fun removeOverlay() {
        imageScope.coroutineContext.cancelChildren()
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> AppLogger.warn(TAG, message = "Failed to remove overlay view", throwable = e) }
            overlayView = null
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.scene_overlay_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.scene_overlay_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SceneOverlayService"

        const val EXTRA_SCENE_ID = "com.opentasker.extra.SCENE_ID"
        const val EXTRA_SCENE_JSON = "com.opentasker.extra.SCENE_JSON"
        const val CHANNEL_ID = "opentasker.scenes"
        const val NOTIFICATION_ID = 1002

        private const val HEADER_HEIGHT_DP = 48
        private const val CLOSE_BUTTON_SIZE_DP = 48
        private const val ACCESSIBILITY_MOVE_STEP_DP = 24
        private const val CLOSE_LABEL = "✕"
        private const val OVERLAY_BACKGROUND = 0xE0_1E_1E_2E.toInt()  // Catppuccin Mocha base ~88% alpha
        private const val HEADER_BACKGROUND = 0xFF_31_32_44.toInt()   // Catppuccin Mocha surface0

        /** Start the overlay service, displaying the given [scene]. */
        fun show(context: Context, scene: Scene) {
            if (!Settings.canDrawOverlays(context)) {
                AppLogger.warn(TAG, message = "Cannot show scene overlay: overlay permission not granted")
                return
            }
            val intent = Intent(context, SceneOverlayService::class.java).apply {
                putExtra(EXTRA_SCENE_ID, scene.id)
                putExtra(EXTRA_SCENE_JSON, Json.encodeToString(Scene.serializer(), scene))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Dismiss the current scene overlay. */
        fun dismiss(context: Context) {
            context.stopService(Intent(context, SceneOverlayService::class.java))
        }
    }
}
