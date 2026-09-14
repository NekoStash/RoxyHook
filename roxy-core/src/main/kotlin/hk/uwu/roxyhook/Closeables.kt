package hk.uwu.roxyhook

internal fun closeEvery(handles: List<AutoCloseable>) {
    var failure: Throwable? = null
    handles.forEach { handle ->
        try { handle.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
