package hk.uwu.roxyhook

/** Per-key initialization lock; failed construction is retryable, recursive construction is rejected. */
internal class RuntimeServiceSlot {
    @Volatile var value: Any? = null
        private set
    private var creating = false
    @Synchronized fun obtain(factory: () -> Any): Any {
        value?.let { return it }
        check(!creating) { "Cyclic runtime service factory" }
        creating = true
        try { return factory().also { value = it } }
        finally { creating = false }
    }
}
