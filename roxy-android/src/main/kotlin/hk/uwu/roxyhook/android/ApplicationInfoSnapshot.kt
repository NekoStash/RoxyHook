package hk.uwu.roxyhook.android

import android.content.pm.ApplicationInfo
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.PlatformSnapshot

/**
 * Immutable [ApplicationInfo] copy captured from one LibXposed package event and carried by
 * `PackageContext.platformSnapshot`. The adapter defensively copies the framework's instance so
 * later framework mutations cannot alter this event's view. It is event data, not a cache.
 * Constructed by the platform adapter; module code reads [PackageScope.appInfo] instead.
 */
class ApplicationInfoSnapshot(info: ApplicationInfo) : PlatformSnapshot() {
    /** Defensive copy of the event's ApplicationInfo; safe to read but module code must not mutate. */
    val applicationInfo: ApplicationInfo = ApplicationInfo(info)
}

/**
 * Host application's [ApplicationInfo] as delivered by the current package event, or null when the
 * platform did not supply one (for example a system_server event or a non-LibXposed platform).
 * The value describes this event's package only; it is never looked up from the PackageManager.
 */
val PackageScope.appInfo: ApplicationInfo?
    get() = (context.platformSnapshot as? ApplicationInfoSnapshot)?.applicationInfo
