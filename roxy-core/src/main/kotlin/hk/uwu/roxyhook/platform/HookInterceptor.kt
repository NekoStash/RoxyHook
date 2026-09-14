package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

fun interface HookInterceptor { fun intercept(call: HookCall): Any? }
