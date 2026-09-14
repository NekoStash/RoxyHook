package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** Same callback/error/outcome engine as the chain path, without an artificial proceed(). */
internal fun HookPlan.callbacks(platform: HookPlatform): PlatformCallbacks = PlatformCallbacks { member, receiver, arguments ->
    val frame = CallbackFrame(member, receiver, arguments)
    val call = ScopedCall(frame)
    val param = HookParam(call, platform)
    try {
        param.phase = if (replacement == null) Phase.BEFORE else Phase.REPLACE
        before?.let { invokeProtected(param, platform, it) }
        replacement?.let { callback -> invokeProtected(param, platform) { result = callback() } }
        object : CallbackSession {
            override val arguments: Array<Any?> get() = param.args
            override val earlyOutcome: CallbackOutcome? get() {
                call.checkActive()
                return when (val value = param.outcome) {
                    Outcome.Pending -> null
                    is Outcome.Returned -> CallbackOutcome.Returned(value.value)
                    is Outcome.Thrown -> CallbackOutcome.Thrown(value.error)
                }
            }
            override fun complete(receiver: Any?, arguments: Array<Any?>, outcome: CallbackOutcome): CallbackCompletion {
                call.checkActive()
                try {
                    frame.currentReceiver = receiver
                    param.restoreArguments(arguments.copyOf())
                    param.outcome = when (outcome) {
                        is CallbackOutcome.Returned -> Outcome.Returned(outcome.value)
                        is CallbackOutcome.Thrown -> Outcome.Thrown(outcome.error)
                    }
                    param.phase = Phase.AFTER
                    after?.let { invokeProtected(param, platform, it) }
                    val result = when (val value = param.outcome) {
                        is Outcome.Returned -> CallbackOutcome.Returned(
                            if (member is Constructor<*> || (member as? Method)?.returnType == Void.TYPE) null else value.value)
                        is Outcome.Thrown -> CallbackOutcome.Thrown(value.error)
                        Outcome.Pending -> error("Callback outcome was not completed")
                    }
                    return CallbackCompletion(result, param.args.copyOf())
                } finally { call.finish() }
            }
            override fun close() { call.finish() }
        }
    } catch (error: Throwable) { call.finish(); throw error }
}
private class CallbackFrame(
    override val member: Executable,
    var currentReceiver: Any?,
    arguments: Array<Any?>
) : HookCall {
    override val receiver: Any? get() = currentReceiver
    override val arguments: List<Any?> = arguments.toList()
    override fun proceed(arguments: Array<Any?>): Any? = error("A native callback phase has no continuation")
}
