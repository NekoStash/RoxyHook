package hk.uwu.roxyhook.platform

/**
 * Platform SPI: a current-generation wrapper for an already-installed KEEP registration.
 * [options] must be decoded from trusted native metadata, not the new callback declaration.
 * Creating this wrapper and adopting it must not register or replace a native callback.
 */
data class RetainedHook(val hook: PlatformHook, val options: HookOptions)
