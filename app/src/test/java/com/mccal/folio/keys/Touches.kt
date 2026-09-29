package com.mccal.folio.keys

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A keyboard to put fingers on, for the tests of what 0.3.1 adds to the keys: one finger or two, held or slid, the
 * way Android delivers them. [heard] is everything the keyboard told its listener, in order.
 */
internal class Touches(widthDp: Int = 411) {
    val view = KeyboardView(ApplicationProvider.getApplicationContext<Context>())
    val typed = StringBuilder()
    val heard = mutableListOf<String>()

    /** What the listener hands back when the globe is held. */
    var languages: List<Language> = listOf(Language.ENGLISH, Language.SPANISH, Language.FRENCH)

    val density get() = view.resources.displayMetrics.density

    init {
        view.listener = object : KeyboardView.Listener {
            override fun onText(text: String) { typed.append(text) }
            override fun onBackspace() { heard += "backspace" }
            override fun onBackspaceRepeat() { heard += "repeat" }
            override fun onBackspaceStart(shiftHeld: Boolean) { heard += "start:$shiftHeld" }
            override fun onDeleteWord() { heard += "deleteWord" }
            override fun onShift() { heard += "shift" }
            override fun onLayer(layer: Layer) { heard += "layer" }
            override fun onAction() { heard += "action" }
            override fun onSwitchKeyboard() { heard += "switch" }
            override fun onCursor(steps: Int) { heard += "cursor" }
            override fun onSelectAll() { heard += "selectAll" }
            override fun onCopy() { heard += "copy" }
            override fun onPaste() { heard += "paste" }
            override fun onEmojiPanel() { heard += "emoji" }
            override fun onClipboardPanel() { heard += "clipboard" }
            override fun onSuggestion(word: String) { heard += "suggestion" }
            override fun onHide() { heard += "hide" }
            override fun onUndo() { heard += "undo" }
            override fun onRedo() { heard += "redo" }
            override fun onSelectMove(steps: Int) { heard += "select" }
            override fun languageChoices(): List<Language> = languages
            override fun onLanguage(language: Language) { heard += "language:${language.name}" }
            override fun onOtherKeyboards() { heard += "otherKeyboards" }
            override fun onLanguageSettings() { heard += "languageSettings" }
        }
        show(widthDp = widthDp)
    }

    fun show(
        rules: FieldRules = FieldRules(),
        layer: Layer = Layer.LETTERS,
        settings: Settings = view.settings,
        widthDp: Int = 411,
    ) {
        view.settings = settings
        view.rules = rules
        view.rows = Layouts.rows(layer, false, rules)
        val width = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    fun key(label: String): Placement = view.placements.first { it.key.label == label }
    fun kind(kind: KeyKind): Placement = view.placements.first { it.key.kind == kind }

    fun centre(box: Box) = (box.left + box.right) / 2 to (box.top + box.bottom) / 2

    fun send(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    fun tap(box: Box) {
        val (x, y) = centre(box)
        send(MotionEvent.ACTION_DOWN, x, y)
        send(MotionEvent.ACTION_UP, x, y)
    }

    fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Down on [box] and held for [ms], still down afterwards. Where the finger is, for the moves that follow. */
    fun hold(box: Box, ms: Long = 800): Pair<Float, Float> {
        val (x, y) = centre(box)
        send(MotionEvent.ACTION_DOWN, x, y)
        idle(ms)
        return x to y
    }

    // ---- two fingers ---------------------------------------------------------------------------------------------

    private var fingers = arrayOf<Pair<Float, Float>>()

    private fun pointer(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun at(point: Pair<Float, Float>) = MotionEvent.PointerCoords().apply {
        x = point.first
        y = point.second
        pressure = 1f
        size = 1f
    }

    private fun sendAll(action: Int, count: Int) {
        val properties = Array(count) { pointer(it) }
        val coords = Array(count) { at(fingers[it]) }
        MotionEvent.obtain(0, 0, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            .let { view.onTouchEvent(it); it.recycle() }
    }

    /** Two fingers down, the first on [a] and then the second on [b]. */
    fun twoDown(a: Box, b: Box) {
        fingers = arrayOf(centre(a), centre(b))
        sendAll(MotionEvent.ACTION_DOWN, 1)
        sendAll(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
    }

    /** Both fingers moved by the same amount, in [steps] even moves, the way a real swipe arrives. */
    fun twoMove(dxDp: Float, dyDp: Float = 0f, steps: Int = 5, second: Pair<Float, Float>? = null) {
        val start = fingers.copyOf()
        val (sx, sy) = second ?: (dxDp to dyDp)
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            fingers[0] = start[0].first + dxDp * density * t to start[0].second + dyDp * density * t
            fingers[1] = start[1].first + sx * density * t to start[1].second + sy * density * t
            sendAll(MotionEvent.ACTION_MOVE, 2)
        }
    }

    /** The second finger lifts, then the first. */
    fun twoUp() {
        sendAll(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        sendAll(MotionEvent.ACTION_UP, 1)
    }
}
