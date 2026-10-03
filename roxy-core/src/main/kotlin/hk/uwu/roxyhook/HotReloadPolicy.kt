package hk.uwu.roxyhook

/** Controls ownership when a module generation is hot-reloaded. */
enum class HotReloadPolicy {
    /** Remove the old hook; the module must explicitly reinstall it (e.g. via package replay). */
    REINSTALL,

    /**
     * Experimental: retain the original callback and its classloader. A null id identifies one
     * AUTO slot per actual executable; explicit ids distinguish independent NAMED slots.
     * Requires the execution framework to enumerate surviving hooks across every generation.
     */
    KEEP,
}
