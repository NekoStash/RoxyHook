package hk.uwu.roxyhook

class Argument internal constructor(private val param: HookParam, private val index: Int) {
    init { require(index in param.args.indices) { "Argument index $index is out of bounds" } }
    var value: Any?
        get() = param.args[index]
        set(value) { param.args[index] = value }
    fun set(value: Any?) { this.value = value }
    fun string(): String = value as String
    fun int(): Int = value as Int
    fun long(): Long = value as Long
    fun boolean(): Boolean = value as Boolean
    inline fun <reified T> cast(): T = value as T
}
