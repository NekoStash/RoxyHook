package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.reflect.validateArguments
import hk.uwu.roxyhook.reflect.validateResult
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

@RoxyDsl
class HookBuilder internal constructor(config: RoxyConfig, options: HookOptions = HookOptions()) {
    var priority: Int = options.priority
    var id: String? = options.id
    var errorPolicy: CallbackErrorPolicy = config.callbackErrorPolicy
    private var beforeCallback: (HookParam.() -> Unit)? = null
    private var afterCallback: (HookParam.() -> Unit)? = null
    private var failureHandler: ((HookFailure) -> Unit)? = null
    private var replacement: (HookParam.() -> Any?)? = null

    fun before(block: HookParam.() -> Unit) {
        check(beforeCallback == null) { "Only one before callback per hook" }; beforeCallback = block
    }
    fun after(block: HookParam.() -> Unit) {
        check(afterCallback == null) { "Only one after callback per hook" }; afterCallback = block
    }
    fun replaceAny(block: HookParam.() -> Any?) {
        check(replacement == null) { "Only one replacement per hook" }; replacement = block
    }
    /** Called for callback errors, before the configured propagate/fallback policy is applied. */
    fun onFailure(block: (HookFailure) -> Unit) { failureHandler = block }
    fun replace(block: HookParam.() -> Any?) = replaceAny(block)
    fun replaceUnit(block: HookParam.() -> Unit) = replaceAny { block(); null }
    fun replaceTo(value: Any?) = replaceAny { value }
    fun replaceToTrue() = replaceTo(true)
    fun replaceToFalse() = replaceTo(false)

    internal fun build(): HookPlan {
        check(beforeCallback != null || afterCallback != null || replacement != null) { "Hook has no callbacks" }
        check(replacement == null || (beforeCallback == null && afterCallback == null)) {
            "replaceAny cannot be combined with before/after in the same hook"
        }
        return HookPlan(HookOptions(priority, id), errorPolicy, beforeCallback, afterCallback, replacement, failureHandler)
    }
}
