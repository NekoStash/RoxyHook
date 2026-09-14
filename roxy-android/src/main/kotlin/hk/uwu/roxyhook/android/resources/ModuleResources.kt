package hk.uwu.roxyhook.android.resources

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import hk.uwu.roxyhook.PackageScope

/** Read module assets/resources through an isolated package Context; never mutate host Resources. */
class ModuleResources(hostContext: Context, val packageName: String) {
    val context: Context = hostContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    val resources: Resources get() = context.resources
    fun id(name: String, type: String): Int = resources.getIdentifier(name, type, packageName).also {
        require(it != 0) { "Module resource not found: $packageName:$type/$name" }
    }
    fun string(name: String, vararg args: Any): String = resources.getString(id(name, "string"), *args)
    fun drawable(name: String): Drawable = requireNotNull(resources.getDrawable(id(name, "drawable"), context.theme))
    fun raw(name: String): java.io.InputStream = resources.openRawResource(id(name, "raw"))
}
fun PackageScope.moduleResources(hostContext: Context): ModuleResources =
    ModuleResources(hostContext, checkNotNull(modulePackageName) { "Module package is unavailable" })
