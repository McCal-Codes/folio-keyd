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
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        action(getString(R.string.setup_step_two)) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
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

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
    }
}
