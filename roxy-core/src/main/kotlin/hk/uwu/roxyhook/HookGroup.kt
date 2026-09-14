package hk.uwu.roxyhook

class HookGroup internal constructor(handles: List<HookHandle>) : AutoCloseable {
    val handles: List<HookHandle> = handles.toList()
    fun unhook() = close()
    override fun close() = closeEvery(handles.asReversed())
}
