package com.shilapi.xcertplay

import android.content.Context
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

enum class WheelAction { PREVIOUS, NEXT, ANSWER, HANG_UP, SIRI, HOME }

data class WheelKey(val keyCode: Int, val scanCode: Int) {
    fun matches(other: WheelKey): Boolean = keyCode == other.keyCode &&
        (scanCode == 0 || other.scanCode == 0 || scanCode == other.scanCode)
}

/** A draft stays separate from saved mappings until the user presses Save. */
internal class WheelMappingState(initial: Map<WheelAction, WheelKey> = emptyMap()) {
    val mappings = initial.toMutableMap()
    var editing: WheelAction? = null
        private set
    var draft: WheelKey? = null
        private set
    private val presses = LinkedHashSet<Triple<Int, Int, Long>>()

    fun begin(action: WheelAction) { editing = action; draft = null }
    fun cancel() { editing = null; draft = null }
    fun capture(key: WheelKey) { if (editing != null && draft == null) draft = key }
    fun conflict(): WheelAction? = draft?.let { key ->
        mappings.entries.firstOrNull { it.key != editing && it.value.matches(key) }?.key
    }
    fun save(): Boolean {
        val action = editing ?: return false
        val key = draft ?: return false
        if (conflict() != null) return false
        mappings[action] = key
        cancel()
        return true
    }
    fun clear(action: WheelAction) { mappings.remove(action) }
    fun hasMapping(key: WheelKey): Boolean = mappings.values.any { it.matches(key) }
    fun action(key: WheelKey): WheelAction? = mappings.entries.filter { it.value.matches(key) }.singleOrNull()?.key

    // downTime identifies one physical press even when Android delivers it via two input paths.
    fun firstDown(key: WheelKey, downTime: Long, repeat: Int): Boolean {
        if (repeat != 0) return false
        val stamp = Triple(key.keyCode, key.scanCode, downTime)
        if (presses.any { it.first == key.keyCode && (it.second == 0 || key.scanCode == 0 || it.second == key.scanCode) && it.third == downTime }) return false
        presses += stamp
        if (presses.size > 32) presses.remove(presses.first())
        return true
    }
}

/** Kept separate so a fresh instance reads the same settings after an application restart. */
internal class WheelMappingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("carplay_wheel_keys", Context.MODE_PRIVATE)
    fun load(): Map<WheelAction, WheelKey> = WheelAction.entries.mapNotNull { action ->
        val value = preferences.getString(action.name, null)?.split(':') ?: return@mapNotNull null
        val key = value.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val scan = value.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        if (key <= 0 || scan < 0) null else action to WheelKey(key, scan)
    }.toMap()
    fun save(mappings: Map<WheelAction, WheelKey>) {
        preferences.edit().clear().also { editor ->
            mappings.forEach { (action, key) -> editor.putString(action.name, "${key.keyCode}:${key.scanCode}") }
        }.apply()
    }
}

/** One input gate shared by the focused window, mapping dialog and active media session. */
internal object SteeringWheelMappings {
    private var state: WheelMappingState? = null
    private var store: WheelMappingStore? = null
    var onCaptured: ((WheelKey) -> Unit)? = null
        private set

    private fun load(context: Context): WheelMappingState {
        state?.let { return it }
        val nextStore = WheelMappingStore(context)
        store = nextStore
        return WheelMappingState(nextStore.load()).also { state = it }
    }
    fun mapping(context: Context, action: WheelAction) = load(context).mappings[action]
    fun begin(context: Context, action: WheelAction, captured: (WheelKey) -> Unit) {
        load(context).begin(action)
        onCaptured = captured
        CarPlayMediaKeys.beginWheelCapture(context)
    }
    fun cancel() { state?.cancel(); onCaptured = null; CarPlayMediaKeys.endWheelCapture() }
    fun conflict(): WheelAction? = state?.conflict()
    fun save(): Boolean {
        val current = state ?: return false
        if (!current.save()) return false
        persist(current)
        onCaptured = null
        CarPlayMediaKeys.endWheelCapture()
        return true
    }
    fun clear(context: Context, action: WheelAction) { val current = load(context); current.clear(action); persist(current) }
    private fun persist(current: WheelMappingState) { store?.save(current.mappings) }
    fun capturing() = state?.editing != null
    fun consume(context: Context, event: KeyEvent, defaults: Boolean = false): Boolean {
        val current = load(context)
        // Back cancels the dialog; system Home and volume retain their Android behavior.
        if (event.keyCode in excludedKeys) return false
        val key = WheelKey(event.keyCode, event.scanCode)
        if (current.editing != null) {
            if (event.action == KeyEvent.ACTION_DOWN && current.firstDown(key, event.downTime, event.repeatCount) && current.draft == null) {
                current.capture(key)
                onCaptured?.invoke(key)
            }
            return true
        }
        val mapped = current.hasMapping(key)
        val action = current.action(key)
        val media = if (defaults && !mapped) CarPlayMediaButton.forKeyCode(event.keyCode) else null
        val siri = defaults && !mapped && CarPlayMediaButton.opensSiri(event.keyCode)
        if (!mapped && media == null && !siri) return false
        if (event.action == KeyEvent.ACTION_DOWN && current.firstDown(key, event.downTime, event.repeatCount)) {
            when {
                action != null -> CarPlayMediaKeys.sendWheelAction(action)
                media != null -> CarPlayMediaKeys.sendHardwareMedia(media)
                siri -> CarPlayMediaKeys.sendWheelAction(WheelAction.SIRI)
            }
        }
        return true
    }
    private val excludedKeys = setOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
}
