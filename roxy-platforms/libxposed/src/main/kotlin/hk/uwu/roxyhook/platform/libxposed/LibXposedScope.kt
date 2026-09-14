package hk.uwu.roxyhook.platform.libxposed

import hk.uwu.roxyhook.PackageScope
import java.io.File

/**
 * The module's own APK file, reported by LibXposed as `moduleApplicationInfo.sourceDir` at load
 * time. Fails fast when the platform supplied no module APK path instead of guessing locations.
 * The file is the installed module package; treat it read-only.
 */
val PackageScope.moduleAppFile: File
    get() = File(checkNotNull(moduleApkPath) { "Platform did not supply the module APK path" })
