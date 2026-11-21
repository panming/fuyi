package com.fanyi.overlay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fanyi.overlay.model.asrMissingMessage
import com.fanyi.overlay.service.CaptureService
import com.fanyi.overlay.ui.home.HomeScreen
import com.fanyi.overlay.ui.openOverlaySettings
import com.fanyi.overlay.ui.settings.SettingsScreen
import com.fanyi.overlay.ui.setup.SetupScreen
import com.fanyi.overlay.ui.theme.FanyiTheme
import com.fanyi.overlay.ui.theme.Ink
import androidx.compose.material3.Surface
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val autoStart = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (consumeAutoStart()) autoStart.intValue += 1
        setContent {
            FanyiTheme {
                Surface(color = Ink) {
                    val signal = autoStart.intValue
                    FanyiRoot(signal)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeAutoStart()) autoStart.intValue += 1
    }

    private fun consumeAutoStart(): Boolean {
        val yes = intent?.getBooleanExtra(CaptureService.EXTRA_AUTO_START, false) == true
        if (yes) intent?.removeExtra(CaptureService.EXTRA_AUTO_START)
        return yes
    }
}

@Composable
private fun FanyiRoot(autoStartSignal: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as FanyiApp
    val settings by app.settings.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(context, result.resultCode, data)
        } else {
            scope.launch { snackbar.showSnackbar("没有授予屏幕捕获，内录没有开始。") }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] != true) {
            scope.launch { snackbar.showSnackbar("需要录音权限。系统用它开放内录，本应用不录通话。") }
            return@rememberLauncherForActivityResult
        }
        launchProjection(context, projectionLauncher)
    }

    fun begin() {
        val missing = asrMissingMessage(settings)
        if (missing != null) {
            scope.launch { snackbar.showSnackbar(missing) }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbar.showSnackbar("请先允许悬浮窗。") }
            openOverlaySettings(context)
            return
        }
        val needed = missingPermissions(context)
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed)
            return
        }
        launchProjection(context, projectionLauncher)
    }

    LaunchedEffect(autoStartSignal) {
        if (autoStartSignal > 0) begin()
    }

    if (!settings.setupDone) {
        SetupScreen(onFinished = { app.settings.update { it.copy(setupDone = true) } })
        return
    }

    val nav = rememberNavController()
    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    settings = settings,
                    storageError = app.storageError,
                    onStart = { begin() },
                    onStop = { CaptureService.requestStop() },
                    onPause = { CaptureService.instanceAction { it.pauseFromShade() } },
                    onResume = { CaptureService.instanceAction { it.resumeFromShade() } },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun missingPermissions(context: Context): Array<String> {
    val needed = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        needed += Manifest.permission.RECORD_AUDIO
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        needed += Manifest.permission.POST_NOTIFICATIONS
    }
    return needed.toTypedArray()
}

private fun launchProjection(context: Context, launcher: ActivityResultLauncher<android.content.Intent>) {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        manager.createScreenCaptureIntent()
    }
    launcher.launch(intent)
}
