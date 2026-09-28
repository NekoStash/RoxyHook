package hk.uwu.roxyhook

/** Reusable business hooks without any dependency on the platform entry class. */
abstract class RoxyHooker {
    abstract fun PackageScope.onHook()

    /** Non-destructive validation before a hot reload is accepted. */
    open fun onHotReloadPreflight(): Boolean = true

    /** Stop generation-owned work after saved state has been accepted. */
    open fun onHotReloadQuiesce() = Unit

    internal fun install(scope: PackageScope) {
        scope.runtime.registerHooker(this)
        with(scope) { onHook() }
    }
}
