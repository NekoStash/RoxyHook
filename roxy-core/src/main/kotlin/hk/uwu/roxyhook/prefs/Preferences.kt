package hk.uwu.roxyhook.prefs

import java.util.concurrent.atomic.AtomicBoolean

/** Keeping the subscription alive also keeps the registered listener alive. */
fun interface Subscription : AutoCloseable {
    override fun close()
    companion object {
        fun once(close: () -> Unit): Subscription {
            val closed = AtomicBoolean(false)
            return Subscription { if (closed.compareAndSet(false, true)) close() }
        }
    }
}

sealed class PreferenceKey<T : Any>(val name: String, val default: T) {
    init { require(name.isNotBlank()) { "Preference key must not be blank" } }
    class BooleanKey(name: String, default: Boolean) : PreferenceKey<Boolean>(name, default)
    class IntKey(name: String, default: Int) : PreferenceKey<Int>(name, default)
    class LongKey(name: String, default: Long) : PreferenceKey<Long>(name, default)
    class FloatKey(name: String, default: Float) : PreferenceKey<Float>(name, default)
    class StringKey(name: String, default: String) : PreferenceKey<String>(name, default)
    class StringSetKey(name: String, default: Set<String>) : PreferenceKey<Set<String>>(name, default.toSet())
}
fun booleanPreference(name: String, default: Boolean = false) = PreferenceKey.BooleanKey(name, default)
fun intPreference(name: String, default: Int = 0) = PreferenceKey.IntKey(name, default)
fun longPreference(name: String, default: Long = 0L) = PreferenceKey.LongKey(name, default)
fun floatPreference(name: String, default: Float = 0f) = PreferenceKey.FloatKey(name, default)
fun stringPreference(name: String, default: String = "") = PreferenceKey.StringKey(name, default)
fun stringSetPreference(name: String, default: Set<String> = emptySet()) = PreferenceKey.StringSetKey(name, default)

interface Preferences {
    fun contains(name: String): Boolean
    operator fun <T : Any> get(key: PreferenceKey<T>): T
    /** Listener thread is platform-defined. Null means all keys may have changed. */
    fun observe(listener: (String?) -> Unit): Subscription
}
interface MutablePreferences : Preferences {
    /** Apply asynchronously. Do not equate this with synchronous cross-process delivery. */
    fun edit(block: PreferenceEditor.() -> Unit)
    /** Potentially blocking. Call off the UI thread. */
    fun editAndCommit(block: PreferenceEditor.() -> Unit): Boolean
}
interface PreferenceEditor {
    operator fun <T : Any> set(key: PreferenceKey<T>, value: T)
    fun remove(name: String)
    fun clear()
}
