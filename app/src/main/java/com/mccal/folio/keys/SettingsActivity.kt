package com.mccal.folio.keys

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The settings screen.
 *
 * Deliberately short. Every keyboard has one of these and most are a wall of switches whose names only make sense
 * to whoever wrote them - "auto punctuate", "auto spacing", "alternative characters" - leaving people to toggle
 * things and see what happens. Each switch here says in a sentence what it changes, and where the answer is not
 * entirely up to the setting, it says that too: the phone's own sound and vibration settings still apply, and a
 * password field ignores several of these whatever anyone chooses.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: Settings
    private val prefs by lazy { getSharedPreferences("keys", Context.MODE_PRIVATE) }
    private lateinit var forget: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(prefs)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun heading(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, (22 * dp).toInt(), 0, (6 * dp).toInt())
        })

        fun note(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, (14 * dp).toInt())
        })

        fun option(title: String, explanation: String, on: Boolean, change: (Boolean) -> Settings) {
            column.addView(Switch(this).apply {
                text = title
                isChecked = on
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, (10 * dp).toInt(), 0, 0)
                setOnCheckedChangeListener { _, checked ->
                    settings = change(checked)
                    settings.save(prefs)
                }
            })
            column.addView(TextView(this).apply {
                text = explanation
                setTextColor(Color.parseColor("#A0A0A6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, (2 * dp).toInt(), (48 * dp).toInt(), (6 * dp).toInt())
            })
        }

        heading(getString(R.string.settings_typing))
        option(
            getString(R.string.settings_suggestions), getString(R.string.settings_suggestions_note),
            settings.suggestions,
        ) { settings.copy(suggestions = it) }
        option(
            getString(R.string.settings_autocorrect), getString(R.string.settings_autocorrect_note),
            settings.autocorrect,
        ) { settings.copy(autocorrect = it) }
        option(
            getString(R.string.settings_learn), getString(R.string.settings_learn_note), settings.learn,
        ) { settings.copy(learn = it) }
        option(
            getString(R.string.settings_capitals), getString(R.string.settings_capitals_note),
            settings.autoCapitalise,
        ) { settings.copy(autoCapitalise = it) }
        option(
            getString(R.string.settings_double_space), getString(R.string.settings_double_space_note),
            settings.doubleSpaceFullStop,
        ) { settings.copy(doubleSpaceFullStop = it) }

        forget = Button(this).apply {
            setOnClickListener {
                prefs.edit().remove(LEARNED).apply()
                text = getString(R.string.setup_forgot)
                isEnabled = false
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * dp).toInt() }
        }
        column.addView(forget)

        heading(getString(R.string.settings_keys))
        option(
            getString(R.string.settings_number_row), getString(R.string.settings_number_row_note),
            settings.numberRow,
        ) { settings.copy(numberRow = it) }
        option(
            getString(R.string.settings_accents), getString(R.string.settings_accents_note), settings.accents,
        ) { settings.copy(accents = it) }
        option(
            getString(R.string.settings_preview), getString(R.string.settings_preview_note), settings.keyPreview,
        ) { settings.copy(keyPreview = it) }

        heading(getString(R.string.settings_feel))
        option(
            getString(R.string.settings_sound), getString(R.string.settings_sound_note), settings.sound,
        ) { settings.copy(sound = it) }
        option(
            getString(R.string.settings_vibrate), getString(R.string.settings_vibrate_note), settings.vibrate,
        ) { settings.copy(vibrate = it) }
        note(getString(R.string.settings_system_note))

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(column)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val words = Learned.decode(prefs.getString(LEARNED, null)).size
        forget.text =
            if (words == 0) getString(R.string.setup_forget_none) else getString(R.string.setup_forget, words)
        forget.isEnabled = words > 0
    }

    private companion object {
        const val LEARNED = "learnedWords"
    }
}
