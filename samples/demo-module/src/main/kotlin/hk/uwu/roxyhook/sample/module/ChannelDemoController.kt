package hk.uwu.roxyhook.sample.module

import android.content.Context
import hk.uwu.roxyhook.android.AndroidExecutors
import hk.uwu.roxyhook.android.channel.DataChannel
import hk.uwu.roxyhook.platform.libxposed.channel.dataChannel
import hk.uwu.roxyhook.platform.libxposed.channel.provisionDataChannelKey
import hk.uwu.roxyhook.platform.libxposed.service.LibXposedService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Keeps blocking Binder/key provisioning off the UI thread and owns the module-app receiver. */
class ChannelDemoController(context: Context, private val output: (String) -> Unit) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private var channel: DataChannel? = null
    private var closed = false
    fun initialize(service: LibXposedService) {
        check(!closed)
        val version = generation.incrementAndGet()
        channel?.close(); channel = null
        worker.execute {
            val result = runCatching { service.provisionDataChannelKey() }
            AndroidExecutors.main.execute ui@{
                if (closed || generation.get() != version) return@ui
                result.onSuccess {
                    runCatching { channel = service.dataChannel(context) }
                        .onSuccess { output("Channel initialized. Restart the target once, then press Ping target.") }
                        .onFailure { output(it.toString()) }
                }.onFailure { output(it.toString()) }
            }
        }
    }
    fun ping() {
        check(!closed)
        val active = channel ?: return output("Initialize the channel first.")
        active.request("hk.uwu.roxyhook.sample.target", "ping").whenComplete { packet, error ->
            AndroidExecutors.main.execute {
                if (!closed && channel === active) output(error?.toString() ?: packet.text())
            }
        }
    }
    fun disconnect() { generation.incrementAndGet(); channel?.close(); channel = null }
    override fun close() {
        if (closed) return
        closed = true
        try { disconnect() } finally { worker.shutdownNow() }
    }
}
