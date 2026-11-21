package com.fanyi.overlay.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fanyi.overlay.FanyiApp
import com.fanyi.overlay.ui.ignoringBattery
import com.fanyi.overlay.ui.openAppDetails
import com.fanyi.overlay.ui.openAutoStart
import com.fanyi.overlay.ui.openBatterySettings
import com.fanyi.overlay.ui.openOverlaySettings
import com.fanyi.overlay.ui.theme.Amber
import com.fanyi.overlay.ui.theme.Celadon
import com.fanyi.overlay.ui.theme.Mist

@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var overlayOn by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var audioOn by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var noticeOn by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var batteryOn by remember { mutableStateOf(ignoringBattery(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOn = Settings.canDrawOverlays(context)
                audioOn = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                noticeOn = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                batteryOn = ignoringBattery(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        audioOn = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        noticeOn = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("第 ${step + 1} / 6 步", color = Amber, style = MaterialTheme.typography.labelLarge)
        when (step) {
            0 -> {
                Text("把正在播放的声音译成中文", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "浮译听的是这部手机自己放出的媒体声：视频、音乐、游戏。识别出的原文和简体中文会浮在其他应用上面。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Mist.copy(alpha = 0.9f),
                )
                Text(
                    "它不录电话，也不读聊天记录。只翻译你自己正在播放、并且有权使用的内容。",
                    color = Celadon,
                )
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("继续") }
            }
            1 -> {
                Text("允许悬浮窗", style = MaterialTheme.typography.headlineMedium)
                Text("字幕要画在别的应用上面，系统要求单独打开悬浮窗。OnePlus / ColorOS 里这项有时叫「悬浮窗」。")
                Text(if (overlayOn) "已允许" else "还没有允许", color = if (overlayOn) Celadon else Amber)
                OutlinedButton(onClick = { openOverlaySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("去开启悬浮窗")
                }
                Button(
                    onClick = { step = 2 },
                    enabled = overlayOn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("悬浮窗已开，下一步") }
            }
            2 -> {
                Text("录音和通知", style = MaterialTheme.typography.headlineMedium)
                Text("Android 把内录算进「麦克风」权限。本应用只用 AudioPlaybackCapture 采集正在播放的媒体，不会把通话送去识别。")
                Text("前台服务需要一条常驻通知。状态栏里的屏幕捕获提示是系统自己加的，应用不会把它藏起来，也不会保存画面。")
                Text(
                    "录音 ${if (audioOn) "已允许" else "未允许"} · 通知 ${if (noticeOn) "已允许" else "未允许"}",
                    color = if (audioOn && noticeOn) Celadon else Amber,
                )
                OutlinedButton(
                    onClick = {
                        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
                        permissionLauncher.launch(needed.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("授予这两项") }
                Button(
                    onClick = { step = 3 },
                    enabled = audioOn && noticeOn,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("下一步") }
            }
            3 -> {
                Text("ColorOS 上再确认三处", style = MaterialTheme.typography.headlineMedium)
                Text("OnePlus PKR110 这类系统，悬浮窗权限之外还要自己打开：")
                Text("1. 应用信息 → 权限 → 后台弹出界面。否则悬浮层上的「开始」可能拉不起授权页。")
                Text("2. 电池里把浮译设为允许后台，或忽略电池优化。")
                Text("3. 自启动建议打开。不打开也能用，但切走之后更容易被清掉。")
                Text(if (batteryOn) "电池优化已忽略" else "电池优化仍可能限制后台", color = if (batteryOn) Celadon else Amber)
                OutlinedButton(onClick = { openAppDetails(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("打开应用详情")
                }
                OutlinedButton(onClick = { openBatterySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("忽略电池优化")
                }
                OutlinedButton(
                    onClick = {
                        if (!openAutoStart(context)) openAppDetails(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("尝试打开自启动列表") }
                Button(onClick = { step = 4 }, modifier = Modifier.fillMaxWidth()) { Text("我已看过，下一步") }
            }
            4 -> KeyStep(onContinue = { step = 5 })
            else -> {
                Text("准备开始", style = MaterialTheme.typography.headlineMedium)
                Text("回到主页后点「开始内录」。系统会弹出屏幕捕获授权，请选整个屏幕。浮译只取播放声音，不上传、不保存画面。")
                Text("密钥在上一页或设置里填写。开通「豆包流式语音识别模型 2.0」和「豆包机器翻译模型」，贴一把 API Key 就够。不用 IAM，也不用旧的「机器翻译 · 文本翻译」。不要开通按分钟计费的「实时语音翻译」。")
                Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("进入浮译") }
            }
        }
    }
}

@Composable
private fun KeyStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FanyiApp
    val current = app.settings.settings.value
    var apiKey by remember { mutableStateOf(current.volcApiKey) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("贴上豆包语音 API Key", style = MaterialTheme.typography.headlineMedium)
        Text("在豆包语音控制台开通「流式语音识别 2.0」和「豆包机器翻译模型」。同一把 API Key 会同时用于听写和翻译，不用再填访问控制的 Access Key。阿里云、腾讯云，以及只做文本翻译的 OpenAI 兼容接口，可以稍后在设置里分开选择。已经保存的密钥会留着。")
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("豆包语音 API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                app.settings.update { it.copy(volcApiKey = apiKey.trim()) }
                onContinue()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存并继续") }
        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("稍后在设置里填写") }
    }
}
