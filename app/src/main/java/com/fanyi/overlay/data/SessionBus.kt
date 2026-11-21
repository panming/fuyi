package com.fanyi.overlay.data

import com.fanyi.overlay.model.CaptionLine
import com.fanyi.overlay.model.PartialCaption
import com.fanyi.overlay.model.RunPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionBus {
    private val lock = Any()
    private val _phase = MutableStateFlow(RunPhase.IDLE)
    private val _detail = MutableStateFlow("未开始")
    private val _error = MutableStateFlow<String?>(null)
    private val _lines = MutableStateFlow<List<CaptionLine>>(emptyList())
    private val _partial = MutableStateFlow<PartialCaption?>(null)
    private val _level = MutableStateFlow(0f)
    private val _expanded = MutableStateFlow(false)

    val phase: StateFlow<RunPhase> = _phase.asStateFlow()
    val detail: StateFlow<String> = _detail.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val lines: StateFlow<List<CaptionLine>> = _lines.asStateFlow()
    val partial: StateFlow<PartialCaption?> = _partial.asStateFlow()
    val level: StateFlow<Float> = _level.asStateFlow()
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    var phaseValue: RunPhase
        get() = _phase.value
        set(value) { _phase.value = value }

    var detailValue: String
        get() = _detail.value
        set(value) { _detail.value = value }

    var errorValue: String?
        get() = _error.value
        set(value) { _error.value = value }

    var partialValue: PartialCaption?
        get() = _partial.value
        set(value) { _partial.value = value }

    var levelValue: Float
        get() = _level.value
        set(value) { _level.value = value }

    fun toggleExpanded() {
        _expanded.value = !_expanded.value
    }

    fun resetForStart() {
        synchronized(lock) {
            _lines.value = emptyList()
        }
        _partial.value = null
        _error.value = null
        _level.value = 0f
        _phase.value = RunPhase.STARTING
        _detail.value = "正在准备内录"
    }

    fun appendLine(line: CaptionLine) {
        synchronized(lock) {
            _lines.value = (_lines.value + line).takeLast(40)
        }
    }

    fun updateLine(id: Long, transform: (CaptionLine) -> CaptionLine) {
        synchronized(lock) {
            _lines.value = _lines.value.map { if (it.id == id) transform(it) else it }
        }
    }
}
