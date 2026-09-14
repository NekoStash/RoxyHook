package hk.uwu.roxyhook

import hk.uwu.roxyhook.platform.*
import hk.uwu.roxyhook.reflect.validateArguments
import hk.uwu.roxyhook.reflect.validateResult
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method


internal class ScopedCall(delegate: HookCall) : HookCall {
    private var delegate: HookCall? = delegate
    private val owner = Thread.currentThread()
    private var active = true
    private var proceeded = false
    fun checkActive() {
        check(active) { "Hook call has already finished" }
        check(Thread.currentThread() === owner) { "Hook calls must stay on their original thread" }
    }
    fun finish() { active = false; delegate = null }
    override val member: Executable get() { checkActive(); return checkNotNull(delegate).member }
    override val receiver: Any? get() { checkActive(); return checkNotNull(delegate).receiver }
    override val arguments: List<Any?> get() { checkActive(); return checkNotNull(delegate).arguments }
    override fun proceed(arguments: Array<Any?>): Any? {
        checkActive()
        check(!proceeded) { "proceed() may only be called once; use invokeOriginal for an intentional extra call" }
        validateArguments(member, arguments)
        proceeded = true
        return checkNotNull(delegate).proceed(arguments)
    }
}
