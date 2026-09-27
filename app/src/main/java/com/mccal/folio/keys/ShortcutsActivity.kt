package com.mccal.folio.keys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Where shortcuts are made and unmade.
 *
 * A list and two boxes. Samsung's equivalent is buried three screens down under "Smart typing"; this one is a tap
 * from the settings screen, and it shows what each shortcut will actually produce rather than truncating it.
 */
class ShortcutsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("keys", Context.MODE_PRIVATE) }
    private lateinit var shortcuts: Shortcuts
    private lateinit var list: LinearLayout
    private lateinit var trigger: EditText
    private lateinit var phrase: EditText
    private lateinit var empty: TextView
    private lateinit var fromClip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcuts = Shortcuts.decode(prefs.getString(SHORTCUTS, null))
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = getString(R.string.shortcuts_note)
            setTextColor(Color.parseColor("#A0A0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, (16 * dp).toInt())
        })

        trigger = EditText(this).apply {
            hint = getString(R.string.shortcuts_trigger_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E6E73"))
        }
        phrase = EditText(this).apply {
            hint = getString(R.string.shortcuts_phrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E6E73"))
        }
        fromClip = TextView(this).apply {
            text = getString(R.string.shortcuts_from_clip)
            setTextColor(Color.parseColor("#A0A0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = TextView.GONE
        }
        column.addView(fromClip)
        column.addView(trigger)
        column.addView(phrase)
        column.addView(
            Button(this).apply {
                text = getString(R.string.shortcuts_add)
                setOnClickListener { add() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * dp).toInt() }
            },
        )

        empty = TextView(this).apply {
            text = getString(R.string.shortcuts_empty)
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (22 * dp).toInt(), 0, 0)
        }
        column.addView(empty)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        column.addView(list)

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        }
        setContentView(scroll)
        redraw()
        prefill(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prefill(intent)
    }

    /**
     * Opened from a pinned clip, the phrase is already chosen: it goes in the second box, and the cursor waits in the
     * first for the few letters that will stand for it. Nothing is added until Add is tapped.
     */
    private fun prefill(intent: Intent?) {
        val expansion = intent?.getStringExtra(EXTRA_EXPANSION)?.let(::oneLine)?.takeIf { it.isNotBlank() } ?: return
        phrase.setText(expansion)
        trigger.setText("")
        trigger.requestFocus()
        fromClip.visibility = TextView.VISIBLE
        openedFromClip = true
        // Focusing the box scrolls it to the top, which pushed the note saying where the phrase came from out of
        // sight on a cover screen. The page is short, so starting at the top keeps both in view.
        scroll.post { scroll.scrollTo(0, 0) }
        // Once used, the extra is spent: turning the phone shouldn't put a clip back that was already dealt with.
        intent.removeExtra(EXTRA_EXPANSION)
    }

    private fun add() {
        if (!shortcuts.add(trigger.text.toString(), oneLine(phrase.text.toString()))) {
            // Said out loud rather than swallowed: an Add button that sometimes does nothing is worse than one
            // that says why.
            empty.text = getString(R.string.shortcuts_rejected)
            empty.visibility = TextView.VISIBLE
            return
        }
        trigger.setText("")
        phrase.setText("")
        fromClip.visibility = TextView.GONE
        save()
        redraw()
        // From a clip, making the shortcut was the whole errand: go back to whatever was being typed in.
        if (openedFromClip) finish()
    }

    /** True when the keyboard opened this page for one clip, rather than someone browsing their shortcuts. */
    private var openedFromClip = false
    private lateinit var scroll: ScrollView

    private fun save() = prefs.edit().putString(SHORTCUTS, shortcuts.encode()).apply()

    private fun redraw() {
        val dp = resources.displayMetrics.density
        list.removeAllViews()
        val all = shortcuts.all()
        empty.visibility = if (all.isEmpty()) TextView.VISIBLE else TextView.GONE
        if (all.isEmpty()) empty.text = getString(R.string.shortcuts_empty)
        for ((key, value) in all) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            row.addView(
                TextView(this).apply {
                    text = getString(R.string.shortcuts_row, key, value)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                Button(this).apply {
                    text = getString(R.string.shortcuts_remove)
                    isAllCaps = false
                    setOnClickListener {
                        shortcuts.remove(key)
                        save()
                        redraw()
                    }
                },
            )
            list.addView(row)
        }
    }

    companion object {
        /**
         * The phrase to start a new shortcut with, as a String. The clipboard panel sends a pinned clip here, so its
         * "Make a text shortcut" opens this screen with the long half already filled in.
         */
        const val EXTRA_EXPANSION = "com.mccal.folio.keys.EXPANSION"

        /** The same place the service reads them from. */
        private const val SHORTCUTS = "shortcuts"

        /**
         * Shortcuts are stored a line each with a tab between the halves, so a phrase can hold neither. A clip often
         * has line breaks (an address, say); they become spaces rather than breaking every shortcut after it.
         */
        internal fun oneLine(text: String): String = text.replace(Regex("[\\t\\r\\n]+"), " ").trim()
    }
}
