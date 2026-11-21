package com.fanyi.overlay.overlay

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fanyi.overlay.FanyiApp
import com.fanyi.overlay.MainActivity
import com.fanyi.overlay.data.SessionBus
import com.fanyi.overlay.model.RunPhase
import com.fanyi.overlay.service.CaptureService
import com.fanyi.overlay.ui.theme.Amber
import com.fanyi.overlay.ui.theme.Celadon
import com.fanyi.overlay.ui.theme.Danger
import com.fanyi.overlay.ui.theme.Ink
import com.fanyi.overlay.ui.theme.Mist

@Composable
fun OverlayPanel(
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as FanyiApp
    val settings by app.settings.settings.collectAsState()
    val phase by SessionBus.phase.collectAsState()
    val partial by SessionBus.partial.collectAsState()
    val lines by SessionBus.lines.collectAsState()
    val level by SessionBus.level.collectAsState()
    val expanded by SessionBus.expanded.collectAsState()
    val error by SessionBus.error.collectAsState()
    val textSize = settings.overlayTextSp.sp
    val running = phase == RunPhase.LISTENING || phase == RunPhase.STARTING
    val paused = phase == RunPhase.PAUSED
    val pairs = overlayPairs(lines, partial)
    val visible = if (expanded) {
        pairs
    } else {
        val folded = collapsedPairs(pairs)
        if ((running || paused) && pairs.isEmpty()) {
            folded.dropLast(1) + folded.last().copy(original = "正在听…")
        } else {
            folded
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(expanded, visible, pairs.lastOrNull()?.original, pairs.lastOrNull()?.translation) {
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ink.copy(alpha = if (paused) settings.overlayAlpha * 0.72f else settings.overlayAlpha))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "按住拖动",
                color = Amber,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.x, amount.y)
                        },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Mist.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(level.coerceIn(0.04f, 1f))
                        .height(3.dp)
                        .background(if (running) Amber else Mist.copy(alpha = 0.4f)),
                )
            }
            TextButton(onClick = {
                when {
                    paused -> CaptureService.instanceAction { it.resumeFromShade() }
                    running -> CaptureService.requestStop()
                    else -> {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(CaptureService.EXTRA_AUTO_START, true)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            SessionBus.detailValue = "系统拦住了后台打开。请点通知回到浮译，或在 ColorOS 里允许后台弹出界面。"
                        }
                    }
                }
            }) {
                Text(if (paused) "继续" else if (running) "停止" else "开始")
            }
        }
        if (paused) {
            Text("已暂停", color = Amber, fontSize = 12.sp)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (expanded) 480.dp else 320.dp)
                .verticalScroll(scroll)
                .clickable { SessionBus.toggleExpanded() },
        ) {
            if (visible.isEmpty() && error != null) {
                CaptionLine(error.orEmpty(), textSize, original = false, failed = true)
            }
            visible.forEach { pair ->
                CaptionLine(pair.original, textSize, original = true, failed = false)
                CaptionLine(pair.translation, textSize, original = false, failed = false)
            }
        }
    }
}

@Composable
private fun CaptionLine(
    text: String,
    textSize: TextUnit,
    original: Boolean,
    failed: Boolean,
) {
    Text(
        text = text.ifEmpty { " " },
        color = when {
            failed -> Danger
            original -> Mist.copy(alpha = 0.72f)
            else -> Celadon
        },
        fontSize = if (original) (textSize.value - 1f).coerceAtLeast(12f).sp else textSize,
        fontWeight = if (original) FontWeight.Normal else FontWeight.Medium,
        lineHeight = textSize * 1.35f,
        softWrap = true,
        overflow = TextOverflow.Visible,
        modifier = Modifier.fillMaxWidth(),
    )
}
