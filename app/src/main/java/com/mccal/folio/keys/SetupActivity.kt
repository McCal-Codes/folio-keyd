package com.mccal.folio.keys

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What you see after installing: the two steps Android makes you take, and what its warning on the second one
 * actually means. It is also the keyboard's settings screen, named in `method.xml`.
 */
class SetupActivity : Activity() {

    /** Refreshed in [onResume], because both steps happen in someone else's screen. */
    private lateinit var status: TextView
    private lateinit var forget: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#000000"))
        }

        fun title(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(0, (8 * dp).toInt(), 0, (10 * dp).toInt())
        })

        fun body(text: String) = column.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#B9B9C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, (14 * dp).toInt())
        })

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * dp).toInt() }
        }

        fun action(text: String, onClick: () -> Unit) = column.addView(Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (10 * dp).toInt() }
        })

        title(getString(R.string.setup_title))
        body(getString(R.string.setup_intro))
        action(getString(R.string.setup_step_one)) {
            // A new task, or Android's settings join ours: the screen stays on our back stack, and every later
            // launch of Folio Keys resumes into Settings instead of this screen.
            startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        action(getString(R.string.setup_step_two)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#0A84FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, (2 * dp).toInt(), 0, (16 * dp).toInt())
        }
        column.addView(status)
        body(getString(R.string.setup_warning))

        column.addView(TextView(this).apply {
            text = getString(R.string.setup_try_it)
            setTextColor(Color.parseColor("#8E8E93"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.START
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        })
        column.addView(android.widget.EditText(this).apply {
            hint = getString(R.string.setup_try_hint)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E6E73"))
        })

        action(getString(R.string.settings_open)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        body(getString(R.string.setup_learning))
        forget = button(getString(R.string.setup_forget_none)) {
            getSharedPreferences("keys", MODE_PRIVATE).edit().remove(LEARNED).apply()
            forget.text = getString(R.string.setup_forgot)
            forget.isEnabled = false
        }
        column.addView(forget)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        val words = Learned.decode(getSharedPreferences("keys", MODE_PRIVATE).getString(LEARNED, null)).size
        forget.text =
            if (words == 0) getString(R.string.setup_forget_none) else getString(R.string.setup_forget, words)
        forget.isEnabled = words > 0
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // Asked of the input-method service rather than read out of Settings: an app is allowed this one.
        val added = runCatching {
            manager.enabledInputMethodList.any { it.packageName == packageName }
        }.getOrDefault(false)
        val current = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        status.setText(
            when (setupState(added, current, packageName)) {
                SetupState.NOT_ADDED -> R.string.setup_state_not_added
                SetupState.ADDED -> R.string.setup_state_added
                SetupState.IN_USE -> R.string.setup_state_in_use
            },
        )
    }

    private companion object {
        /** The same place the service keeps them. */
        const val LEARNED = "learnedWords"
    }
}
