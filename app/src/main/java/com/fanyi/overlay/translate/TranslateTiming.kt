package com.fanyi.overlay.translate

private val clauseMarks = charArrayOf('。', '！', '？', '.', '!', '?', '；', ';', '，', ',', '、', '…')

internal fun endsWithClause(text: String): Boolean {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty()) return false
    if (trimmed.endsWith("...")) return true
    return trimmed.last() in clauseMarks
}

/** First snapshot of a fragment is not worth a request. A comma or period is. */
internal fun worthTranslating(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val cjk = trimmed.count { it.isCjkOrKana() }
    val letters = trimmed.count { it.isLetter() }
    if (endsWithClause(trimmed) && (cjk >= 1 || letters >= 3)) return true
    if (cjk >= 2) return true
    return letters >= 8
}

/**
 * Another request is warranted when the recognizer revised the phrase,
 * added a clause break, or grew the text by more than a short tail.
 */
internal fun grewEnough(previous: String, next: String): Boolean {
    if (previous.isEmpty()) return true
    if (next == previous) return false
    if (!next.startsWith(previous)) return true
    if (endsWithClause(next) && !endsWithClause(previous)) return true
    return next.length - previous.length >= 6
}

/** Stable clause: translate sooner. Open phrase: wait out the next partials. */
internal fun clauseDebounceMs(text: String): Long = if (endsWithClause(text)) 180L else 320L

private fun Char.isCjkOrKana(): Boolean {
    return this in '\u4e00'..'\u9fff' || this in '\u3040'..'\u30ff' || this in '\uac00'..'\ud7af'
}
