package hk.uwu.roxyhook.android

import android.content.SharedPreferences
import hk.uwu.roxyhook.prefs.*
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only public surface, even if the underlying SharedPreferences happens to be writable. */
open class AndroidPreferences(protected val native: SharedPreferences) : Preferences {
    override fun contains(name: String): Boolean = native.contains(name)
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: PreferenceKey<T>): T = when (key) {
        is PreferenceKey.BooleanKey -> native.getBoolean(key.name, key.default)
        is PreferenceKey.IntKey -> native.getInt(key.name, key.default)
        is PreferenceKey.LongKey -> native.getLong(key.name, key.default)
        is PreferenceKey.FloatKey -> native.getFloat(key.name, key.default)
        is PreferenceKey.StringKey -> native.getString(key.name, key.default) ?: key.default
        is PreferenceKey.StringSetKey -> (native.getStringSet(key.name, key.default) ?: key.default).toSet()
    } as T

    override fun observe(listener: (String?) -> Unit): Subscription {
        val active = AtomicBoolean(true)
        val bridge = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (active.get()) listener(key)
        }
        native.registerOnSharedPreferenceChangeListener(bridge)
        return Subscription {
            if (active.compareAndSet(true, false)) native.unregisterOnSharedPreferenceChangeListener(bridge)
        }
    }
}
class AndroidMutablePreferences(native: SharedPreferences) : AndroidPreferences(native), MutablePreferences {
    override fun edit(block: PreferenceEditor.() -> Unit) {
        val editor = native.edit()
        AndroidEditor(editor).apply(block)
        editor.apply()
    }
    override fun editAndCommit(block: PreferenceEditor.() -> Unit): Boolean {
        val editor = native.edit()
        AndroidEditor(editor).apply(block)
        return editor.commit()
    }
    private class AndroidEditor(private val editor: SharedPreferences.Editor) : PreferenceEditor {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> set(key: PreferenceKey<T>, value: T) {
            when (key) {
                is PreferenceKey.BooleanKey -> editor.putBoolean(key.name, value as Boolean)
                is PreferenceKey.IntKey -> editor.putInt(key.name, value as Int)
                is PreferenceKey.LongKey -> editor.putLong(key.name, value as Long)
                is PreferenceKey.FloatKey -> editor.putFloat(key.name, value as Float)
                is PreferenceKey.StringKey -> editor.putString(key.name, value as String)
                is PreferenceKey.StringSetKey -> editor.putStringSet(key.name, (value as Set<String>).toSet())
            }
        }
        override fun remove(name: String) { editor.remove(name) }
        override fun clear() { editor.clear() }
    }
}
