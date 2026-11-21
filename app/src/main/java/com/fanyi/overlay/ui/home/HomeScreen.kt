package com.fanyi.overlay.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fanyi.overlay.data.SessionBus
import com.fanyi.overlay.model.LinePhase
import com.fanyi.overlay.model.RunPhase
import com.fanyi.overlay.model.translationConfigured
import com.fanyi.overlay.ui.theme.Amber
import com.fanyi.overlay.ui.theme.Celadon
import com.fanyi.overlay.ui.theme.Danger
import com.fanyi.overlay.ui.theme.InkRaised
import com.fanyi.overlay.ui.theme.Mist

@Composable
fun HomeScreen(
    settings: com.fanyi.overlay.model.AppSettings,
    storageError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSettings: () -> Unit,
) {
    val phase by SessionBus.phase.collectAsState()
    val detail by SessionBus.detail.collectAsState()
    val error by SessionBus.error.collectAsState()
    val lines by SessionBus.lines.collectAsState()
    val partial by SessionBus.partial.collectAsState()
    val level by SessionBus.level.collectAsState()
    val running = phase == RunPhase.LISTENING || phase == RunPhase.STARTING
    val paused = phase == RunPhase.PAUSED
    val provider = "${com.fanyi.overlay.model.vendorLabel(settings.asrProvider)} 识别 · ${com.fanyi.overlay.model.vendorLabel(settings.mtProvider)} 翻译"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("浮译", style = MaterialTheme.typography.headlineMedium, color = Amber)
        Text(
            "听这部手机正在播放的声音，在别的应用上显示原文和简体中文。",
            style = MaterialTheme.typography.bodyLarge,
            color = Mist.copy(alpha = 0.86f),
        )
        if (storageError != null) {
            Banner(storageError, Danger)
        }
        if (!translationConfigured(settings)) {
            Banner("还没填写可用的翻译密钥。火山引擎填豆包语音 API Key 即可；阿里云仍要翻译 AccessKey。可以先听写。", Amber)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(InkRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(detail, style = MaterialTheme.typography.titleMedium)
            Text(
                when (phase) {
                    RunPhase.IDLE -> "空闲 · $provider"
                    RunPhase.STARTING -> "正在连接 · $provider"
                    RunPhase.LISTENING -> "内录中 · $provider"
                    RunPhase.PAUSED -> "已暂停 · 授权还在 · $provider"
                    RunPhase.STOPPING -> "正在停止"
                    RunPhase.ERROR -> "出错了 · $provider"
                },
                color = if (phase == RunPhase.ERROR) Danger else Celadon,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Mist.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (running) level.coerceIn(0.05f, 1f) else 0.05f)
                        .height(6.dp)
                        .background(Amber),
                )
            }
            if (error != null && phase == RunPhase.ERROR) {
                Text(error.orEmpty(), color = Danger, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = {
                when {
                    running -> onPause()
                    paused -> onResume()
                    else -> onStart()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    running -> "暂停翻译"
                    paused -> "继续翻译"
                    else -> "开始内录"
                },
            )
        }
        if (running || paused) {
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("结束本次内录")
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("设置密钥和悬浮层")
        }
        Text("最近字幕", style = MaterialTheme.typography.titleMedium)
        partial?.let {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(InkRaised)
                    .padding(14.dp),
            ) {
                Text("实时", color = Amber, fontWeight = FontWeight.Medium)
                Text(it.text, color = Mist)
                if (!it.translation.isNullOrBlank()) {
                    Text(it.translation, color = Celadon, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (lines.isEmpty() && partial == null) {
            Text(
                "还没有字幕。开始之后切到视频或游戏，字幕会出现在悬浮层和这里。",
                color = Mist.copy(alpha = 0.7f),
            )
        }
        lines.takeLast(6).asReversed().forEach { line ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(InkRaised)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(line.original, color = Mist.copy(alpha = 0.78f))
                val translated = when {
                    !line.translation.isNullOrBlank() -> line.translation.orEmpty()
                    line.phase == LinePhase.SKIPPED -> "中文，未再翻译"
                    line.phase == LinePhase.FAILED -> line.error ?: "翻译失败"
                    else -> ""
                }
                Text(
                    translated,
                    color = if (line.phase == LinePhase.FAILED) Danger else Celadon,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Text(
            "只翻译你自己正在播放、并且有权使用的内容。不要用来偷录别人的通话或聊天。",
            style = MaterialTheme.typography.bodyMedium,
            color = Mist.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(12.dp),
    ) {
        Text(text, color = color)
    }
}
