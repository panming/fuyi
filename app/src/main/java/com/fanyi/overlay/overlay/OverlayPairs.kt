package com.fanyi.overlay.overlay

import com.fanyi.overlay.model.CaptionLine
import com.fanyi.overlay.model.LinePhase
import com.fanyi.overlay.model.PartialCaption

internal data class OverlayPair(
    val original: String,
    val translation: String,
)

internal fun overlayPairs(lines: List<CaptionLine>, partial: PartialCaption?): List<OverlayPair> {
    val finalized = lines.map { line ->
        OverlayPair(original = line.original, translation = translationOf(line))
    }
    val live = partial?.text?.trim().orEmpty()
    if (live.isEmpty()) return finalized
    return finalized + OverlayPair(original = live, translation = partial?.translation.orEmpty())
}

/** Collapsed overlay is the previous finalized pair plus the current pair. */
internal fun collapsedPairs(pairs: List<OverlayPair>): List<OverlayPair> {
    val tail = pairs.takeLast(2)
    return List(2 - tail.size) { OverlayPair("", "") } + tail
}

private fun translationOf(line: CaptionLine): String {
    if (!line.translation.isNullOrBlank()) return line.translation
    return when (line.phase) {
        LinePhase.SKIPPED -> line.original
        LinePhase.FAILED -> line.error.orEmpty()
        else -> ""
    }
}
