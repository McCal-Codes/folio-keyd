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
 * The state, from the two system settings that hold it.
 *
 * [enabled] is Android's colon-separated list of allowed input methods and [current] the one in use, both of which
 * name a component like `com.example/.SomeService`; a component belongs to us when its package half matches.
 */
internal fun setupState(enabled: String?, current: String?, packageName: String): SetupState {
    fun mine(component: String) = component.substringBefore('/').trim() == packageName
    if (current != null && mine(current)) return SetupState.IN_USE
    val added = enabled.orEmpty().split(':').any { it.isNotBlank() && mine(it) }
    return if (added) SetupState.ADDED else SetupState.NOT_ADDED
}
