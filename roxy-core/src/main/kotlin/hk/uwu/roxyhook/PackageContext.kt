package hk.uwu.roxyhook

/** Package events do not imply one application per process. System-server is a process property. */
data class PackageContext(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val isFirstPackage: Boolean = true,
    val isSystemServer: Boolean = false,
    val stage: LoadStage = if (isSystemServer) LoadStage.SYSTEM_SERVER_STARTING else LoadStage.PACKAGE_READY,
    val mainProcessName: String = packageName,
    val userId: Int = 0,
    val modulePackageName: String? = null
) {
    val isMainProcess: Boolean get() = processName == mainProcessName
}
enum class LoadStage { PACKAGE_LOADED, PACKAGE_READY, SYSTEM_SERVER_STARTING }
