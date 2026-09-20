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

        /**
         * A row of choices where only one can be true at a time.
         *
         * A switch can only ever say yes or no, and "split the keyboard" has three honest answers: when it makes
         * sense, always, and never. Forcing that into a switch is how settings screens end up lying.
         */
        fun <T> choice(title: String, explanation: String, options: List<Pair<String, T>>, current: T, change: (T) -> Settings) {
            column.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, (12 * dp).toInt(), 0, (2 * dp).toInt())
            })
            column.addView(TextView(this).apply {
                text = explanation
                setTextColor(Color.parseColor("#A0A0A6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, (48 * dp).toInt(), (8 * dp).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = mutableListOf<Button>()
            for ((label, value) in options) {
                val button = Button(this).apply {
                    text = label
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = (6 * dp).toInt() }
                    setOnClickListener {
                        settings = change(value)
                        settings.save(prefs)
                        buttons.forEach { other -> other.alpha = if (other === this) 1f else 0.45f }
                    }
                }
                buttons += button
                row.addView(button)
            }
            buttons.forEachIndexed { index, button ->
                button.alpha = if (options[index].second == current) 1f else 0.45f
            }
            column.addView(row)
        }

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
            getString(R.string.settings_spell_check), getString(R.string.settings_spell_check_note),
            settings.spellCheck,
        ) { settings.copy(spellCheck = it) }
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

        column.addView(
            Button(this).apply {
                text = getString(R.string.shortcuts_open)
                setOnClickListener { startActivity(android.content.Intent(context, ShortcutsActivity::class.java)) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * dp).toInt() }
            },
        )

        heading(getString(R.string.settings_look))
        choice(
            getString(R.string.settings_size), getString(R.string.settings_size_note),
            listOf(
                getString(R.string.settings_size_small) to Size.SMALL,
                getString(R.string.settings_size_medium) to Size.MEDIUM,
                getString(R.string.settings_size_large) to Size.LARGE,
            ),
            settings.size,
        ) { settings.copy(size = it) }
        choice(
            getString(R.string.settings_appearance), getString(R.string.settings_appearance_note),
            listOf(
                getString(R.string.settings_appearance_system) to Appearance.SYSTEM,
                getString(R.string.settings_appearance_light) to Appearance.LIGHT,
                getString(R.string.settings_appearance_dark) to Appearance.DARK,
            ),
            settings.appearance,
        ) { settings.copy(appearance = it) }
        option(
            getString(R.string.settings_high_contrast), getString(R.string.settings_high_contrast_note),
            settings.highContrast,
        ) { settings.copy(highContrast = it) }
        choice(
            getString(R.string.settings_split), getString(R.string.settings_split_note),
            listOf(
                getString(R.string.settings_split_auto) to Split.AUTO,
                getString(R.string.settings_split_always) to Split.ALWAYS,
                getString(R.string.settings_split_never) to Split.NEVER,
            ),
            settings.split,
        ) { settings.copy(split = it) }

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

        heading(getString(R.string.settings_gestures))
        option(
            getString(R.string.settings_flick_down), getString(R.string.settings_flick_down_note),
            settings.flickForAlternate,
        ) { settings.copy(flickForAlternate = it) }
        option(
            getString(R.string.settings_flick_up), getString(R.string.settings_flick_up_note),
            settings.flickForCapital,
        ) { settings.copy(flickForCapital = it) }
        option(
            getString(R.string.settings_cursor_swipe), getString(R.string.settings_cursor_swipe_note),
            settings.cursorSwipe,
        ) { settings.copy(cursorSwipe = it) }
        option(
            getString(R.string.settings_delete_word), getString(R.string.settings_delete_word_note),
            settings.deleteWordSwipe,
        ) { settings.copy(deleteWordSwipe = it) }
        option(
            getString(R.string.settings_swipe_hide), getString(R.string.settings_swipe_hide_note),
            settings.swipeDownToHide,
        ) { settings.copy(swipeDownToHide = it) }
        note(getString(R.string.settings_gestures_note))

        heading(getString(R.string.settings_feel))
        option(
            getString(R.string.settings_sound), getString(R.string.settings_sound_note), settings.sound,
        ) { settings.copy(sound = it) }
        option(
            getString(R.string.settings_vibrate), getString(R.string.settings_vibrate_note), settings.vibrate,
        ) { settings.copy(vibrate = it) }
        note(getString(R.string.settings_system_note))

        column.addView(
            Button(this).apply {
                text = getString(R.string.settings_reset)
                setOnClickListener {
                    Settings.reset(prefs)
                    recreate()   // the quickest honest way to show every switch back where it started
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (20 * dp).toInt() }
            },
        )

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
