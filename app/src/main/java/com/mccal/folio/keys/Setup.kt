package com.mccal.folio.keys

/**
 * How far through Android's two steps the keyboard is.
 *
 * Android makes you add a keyboard and then choose it, in two different screens, and tells you nothing afterwards
 * about whether either stuck. On some phones it doesn't stick: reinstalling drops a keyboard out of the enabled list,
 * and a managed phone can put its own keyboard back the moment you leave the picker. A setup screen that always shows
 * the same two buttons leaves you guessing which of those happened, so this reads the answer back.
 */
enum class SetupState { NOT_ADDED, ADDED, IN_USE }

/**
 * The state, from what the system will actually tell an ordinary app.
 *
 * [added] comes from `InputMethodManager`, which answers reliably. [current] is
 * `Settings.Secure.DEFAULT_INPUT_METHOD`, which does not: on some phones an app reading it gets nothing back, so a
 * null here means "could not tell", never "no". That is why being added is established first and separately -
 * reading one restricted setting must not be able to make an installed keyboard look uninstalled.
 */
internal fun setupState(added: Boolean, current: String?, packageName: String): SetupState {
    if (!added) return SetupState.NOT_ADDED
    val mine = current != null && current.substringBefore('/').trim() == packageName
    return if (mine) SetupState.IN_USE else SetupState.ADDED
}
