package com.mccal.folio.keys

import android.app.Activity
import android.content.Context
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

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(column)
            },
        )
        redraw()
    }

    private fun add() {
        if (!shortcuts.add(trigger.text.toString(), phrase.text.toString())) {
            // Said out loud rather than swallowed: an Add button that sometimes does nothing is worse than one
            // that says why.
            empty.text = getString(R.string.shortcuts_rejected)
            empty.visibility = TextView.VISIBLE
            return
        }
        trigger.setText("")
        phrase.setText("")
        save()
        redraw()
    }

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

    private companion object {
        /** The same place the service reads them from. */
        const val SHORTCUTS = "shortcuts"
    }
}
