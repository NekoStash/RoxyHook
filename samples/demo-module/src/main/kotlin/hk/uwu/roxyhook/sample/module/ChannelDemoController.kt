package hk.uwu.roxyhook.sample.module

import android.content.Context
import hk.uwu.roxyhook.android.channel.DataChannel
import hk.uwu.roxyhook.platform.libxposed.channel.dataChannel
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService

/** Owns the module-app receiver for the sample's key-free channel. */
class ChannelDemoController(context: Context, private val output: (String) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private var channel: DataChannel? = null
    private var closed = false
    fun initialize(service: LibXposedService) {
        check(!closed)
        channel?.close(); channel = null
        runCatching { service.dataChannel(context) }
            .onSuccess { channel = it; output("Channel initialized. Press Ping target.") }
            .onFailure { output(it.toString()) }
    }
    fun ping() {
        check(!closed)
        val active = channel ?: return output("Initialize the channel first.")
        active.put("hk.uwu.roxyhook.sample.target", "ping")
        output("Ping sent to target.")
    }

    fun disconnect() {
        channel?.close(); channel = null
    }
    override fun close() {
        if (closed) return
        closed = true
        disconnect()
    }
}
