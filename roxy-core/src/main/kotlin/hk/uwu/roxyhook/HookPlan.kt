package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.reflect.validateArguments
import hk.uwu.roxyhook.reflect.validateResult
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method


internal data class HookPlan(
    val options: HookOptions,
    val policy: CallbackErrorPolicy,
    val before: (HookParam.() -> Unit)?,
    val after: (HookParam.() -> Unit)?,
    val replacement: (HookParam.() -> Any?)?,
    val failureHandler: ((HookFailure) -> Unit)? = null
) {
    fun checkMember(member: Executable) {
        require(member !is Constructor<*> || replacement == null) { "Constructor replacement is not supported" }
    }
    fun interceptor(platform: HookPlatform): HookInterceptor = HookInterceptor { raw ->
        val call = ScopedCall(raw)
        val param = HookParam(call, platform)
        try {
            param.phase = if (replacement == null) Phase.BEFORE else Phase.REPLACE
            before?.let { invokeProtected(param, platform, it) }
            replacement?.let { callback -> invokeProtected(param, platform) { result = callback() } }
            if (param.outcome === Outcome.Pending) {
                param.outcome = try { Outcome.Returned(call.proceed(param.args)) }
                catch (error: Throwable) { rethrowFatal(error); Outcome.Thrown(error) }
            }
            param.phase = Phase.AFTER
            after?.let { invokeProtected(param, platform, it) }
            when (val outcome = param.outcome) {
                is Outcome.Returned -> if (call.member is Constructor<*> ||
                    (call.member as? Method)?.returnType == Void.TYPE) null else outcome.value
                is Outcome.Thrown -> throw outcome.error
                Outcome.Pending -> error("Hook outcome was not completed")
            }
        } finally { call.finish() }
    }

    internal fun invokeProtected(param: HookParam, platform: HookPlatform, callback: HookParam.() -> Unit) {
        val savedOutcome = param.outcome
        val savedArguments = param.args.copyOf()
        val callsBefore = param.originalCalls
        try {
            callback(param)
            if (param.phase != Phase.AFTER) validateArguments(param.member, param.args)
            (param.outcome as? Outcome.Returned)?.let { validateResult(param.member, it.value) }
        } catch (error: Throwable) {
            rethrowFatal(error)
            try { failureHandler?.invoke(HookFailure(param.member, param.phase.name, error)) }
            catch (observerError: Throwable) {
                rethrowFatal(observerError)
                if (observerError !== error) error.addSuppressed(observerError)
            }
            if (policy == CallbackErrorPolicy.PROPAGATE) throw error
            param.restoreArguments(savedArguments)
            // Never replay a side-effecting original call after a failed replacement/before callback.
            param.outcome = if (savedOutcome === Outcome.Pending && param.originalCalls > callsBefore)
                param.lastOriginalOutcome else savedOutcome
            try { platform.logger.log(LogLevel.ERROR, "Hook callback failed: ${param.member}", error) }
            catch (loggingError: Throwable) { rethrowFatal(loggingError) }
        }
    }
}
