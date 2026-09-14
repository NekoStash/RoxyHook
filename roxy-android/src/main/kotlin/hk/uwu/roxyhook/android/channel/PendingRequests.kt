package hk.uwu.roxyhook.android.channel

import android.os.Handler
import hk.uwu.roxyhook.channel.ChannelPacket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

internal class PendingRequests(private val handler: Handler) : AutoCloseable {
    private data class Pending(val target: String, val topic: String, val future: CompletableFuture<ChannelPacket>, val timeout: Runnable)
    private val requests = ConcurrentHashMap<String, Pending>()
    private var closed = false
    @Synchronized fun register(packet: ChannelPacket, timeoutMillis: Long): CompletableFuture<ChannelPacket> {
        check(!closed)
        require(timeoutMillis in 1..300_000)
        check(requests.size < 256) { "Too many pending DataChannel requests" }
        val future = CompletableFuture<ChannelPacket>()
        val timeout = Runnable { requests.remove(packet.id)?.future?.completeExceptionally(TimeoutException("DataChannel ${packet.topic}")) }
        requests[packet.id] = Pending(packet.target, packet.topic, future, timeout)
        future.whenComplete { _, _ -> requests.remove(packet.id)?.let { handler.removeCallbacks(it.timeout) } }
        if (!handler.postDelayed(timeout, timeoutMillis)) {
            requests.remove(packet.id)
            future.completeExceptionally(IllegalStateException("Channel handler is shutting down"))
        }
        return future
    }
    fun complete(packet: ChannelPacket): Boolean {
        val entry = requests[packet.replyTo] ?: return false
        if (packet.sender != entry.target || packet.topic != entry.topic) return false
        if (!requests.remove(packet.replyTo, entry)) return false
        handler.removeCallbacks(entry.timeout)
        return entry.future.complete(packet)
    }
    fun fail(id: String, error: Throwable) {
        requests.remove(id)?.let { handler.removeCallbacks(it.timeout); it.future.completeExceptionally(error) }
    }
    @Synchronized override fun close() {
        closed = true
        requests.keys.toList().forEach { fail(it, IllegalStateException("DataChannel closed")) }
    }
}
