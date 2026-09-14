package hk.uwu.roxyhook.platform

import java.lang.reflect.Executable

class UnsupportedCapabilityException(platform: String, capability: Capability) :
    UnsupportedOperationException("$platform does not support $capability")
