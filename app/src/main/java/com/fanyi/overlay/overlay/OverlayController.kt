package com.fanyi.overlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fanyi.overlay.FanyiApp
import com.fanyi.overlay.ui.theme.FanyiTheme

class OverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        val app = context.applicationContext as FanyiApp
        val metrics = context.resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val width = (metrics.widthPixels - margin * 2).coerceAtMost((420 * metrics.density).toInt())
        val settings = app.settings.settings.value
        val layout = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.overlayX >= 0) settings.overlayX else ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = if (settings.overlayY >= 0) settings.overlayY else (metrics.heightPixels * 0.62f).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        owner.create()
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                FanyiTheme {
                    OverlayPanel(
                        onDrag = { dx, dy -> nudge(dx, dy) },
                        onDragEnd = { persist() },
                    )
                }
            }
        }
        try {
            windowManager.addView(composeView, layout)
        } catch (error: Exception) {
            owner.destroy()
            Toast.makeText(context, "悬浮窗没有加上：${error.message}", Toast.LENGTH_LONG).show()
            throw error
        }
        view = composeView
        params = layout
        owner.start()
    }

    fun hide() {
        val current = view ?: return
        view = null
        params = null
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
        owner.destroy()
    }

    private fun nudge(dx: Float, dy: Float) {
        val layout = params ?: return
        val current = view ?: return
        val metrics = context.resources.displayMetrics
        layout.x = (layout.x + dx.toInt()).coerceIn(-layout.width, metrics.widthPixels)
        layout.y = (layout.y + dy.toInt()).coerceIn(0, metrics.heightPixels - 48)
        try {
            windowManager.updateViewLayout(current, layout)
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val layout = params ?: return
        val app = context.applicationContext as FanyiApp
        app.settings.update { it.copy(overlayX = layout.x, overlayY = layout.y) }
    }
}

private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)
    private var destroyed = false

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        destroyed = false
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() {
        if (destroyed) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }
}
