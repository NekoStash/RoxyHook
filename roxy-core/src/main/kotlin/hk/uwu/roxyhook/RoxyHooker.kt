package hk.uwu.roxyhook

/** Reusable business hooks without any dependency on the platform entry class. */
abstract class RoxyHooker {
    abstract fun PackageScope.onHook()
    internal fun install(scope: PackageScope) { with(scope) { onHook() } }
}
