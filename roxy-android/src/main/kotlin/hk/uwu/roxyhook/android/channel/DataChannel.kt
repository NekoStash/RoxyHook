package hk.uwu.roxyhook.android.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import hk.uwu.roxyhook.channel.ChannelPacket
import hk.uwu.roxyhook.platform.LogLevel
import hk.uwu.roxyhook.platform.RoxyLogger
import hk.uwu.roxyhook.prefs.Subscription
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Live, same-user cross-process broadcasts. No persistence, wakeup or exactly-once delivery guarantee.
 * Broadcast routing is package-scoped but unauthenticated; do not send confidential data or authorization tokens.
 * Receive handlers run on the Android main thread; send off large parsing/work to your executor. */
class DataChannel(
    context: Context, val modulePackage: String,
                  private val logger: RoxyLogger? = null) : AutoCloseable {
    private val context = context.applicationContext ?: context
    private val localPackage = context.packageName
    private val action = "$modulePackage.ROXY_DATA_V1"
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val pending = PendingRequests(Handler(Looper.getMainLooper()))
    private class Listener(val topic: String, val callback: (ChannelMessage) -> Unit) { val active = AtomicBoolean(true) }
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed.get() || intent.action != action) return
            val packet = decode(intent) ?: return
            if (packet.module != modulePackage || packet.target != localPackage) return
            if (packet.replyTo.isNotEmpty()) { pending.complete(packet); return }
            val message = ChannelMessage(packet, this@DataChannel)
            listeners.forEach { listener ->
                if (!closed.get() && listener.active.get() && listener.topic == packet.topic) {
                    // A business callback must never crash the BroadcastReceiver or the host process.
                    // Even a Throwable raised by the callback is contained and reported, never rethrown.
                    try { listener.callback(message) } catch (error: Throwable) {
                        reportHandlerError(packet.topic, error)
                    }
                }
            }
        }
    }
    init {
        // Validate routing even if no message is ever sent.
        ChannelPacket(modulePackage, localPackage, localPackage, "validation", payload = byteArrayOf())
        try {
            // minSdk 26 already exposes the flags overload. The channel accepts broadcasts from
            // other packages that target this module package, so it must be exported.
            this.context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        } catch (error: Throwable) {
            pending.close(); throw error
        }
    }

    fun receive(topic: String, callback: (ChannelMessage) -> Unit): Subscription =
        synchronized(lock) {
        check(!closed.get())
        ChannelPacket(modulePackage, localPackage, localPackage, topic, payload = byteArrayOf())
        val listener = Listener(topic, callback)
        listeners += listener
        Subscription.once { listener.active.set(false); listeners -= listener }
    }

    fun put(targetPackage: String, topic: String, payload: ByteArray = byteArrayOf()): String {
        val packet = ChannelPacket(modulePackage, localPackage, targetPackage, topic, payload = payload)
        transmit(packet)
        return packet.id
    }

    fun put(targetPackage: String, topic: String, text: String): String =
        put(targetPackage, topic, text.toByteArray(Charsets.UTF_8))

    fun waitFor(
        targetPackage: String, topic: String, payload: ByteArray = byteArrayOf(),
                timeoutMillis: Long = 5_000): CompletableFuture<ChannelPacket> {
        val packet = ChannelPacket(modulePackage, localPackage, targetPackage, topic, payload = payload)
        val future = pending.register(packet, timeoutMillis)
        try { transmit(packet) } catch (error: Throwable) { pending.fail(packet.id, error) }
        return future
    }
    internal fun reply(request: ChannelPacket, payload: ByteArray): String {
        require(request.replyTo.isEmpty()) { "Cannot reply to a reply" }
        val packet = ChannelPacket(modulePackage, localPackage, request.sender, request.topic, replyTo = request.id, payload = payload)
        transmit(packet)
        return packet.id
    }
    private fun transmit(packet: ChannelPacket) = synchronized(lock) {
        check(!closed.get())
        val intent = Intent(action).apply {
            setPackage(packet.target)
            putExtra(EXTRA_MODULE, packet.module)
            putExtra(EXTRA_SENDER, packet.sender)
            putExtra(EXTRA_TARGET, packet.target)
            putExtra(EXTRA_TOPIC, packet.topic)
            putExtra(EXTRA_ID, packet.id)
            putExtra(EXTRA_REPLY_TO, packet.replyTo)
            putExtra(EXTRA_TIMESTAMP, packet.timestampMillis)
            putExtra(EXTRA_PAYLOAD, packet.payload)
        }
        context.sendBroadcast(intent)
    }
    override fun close() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            listeners.forEach { it.active.set(false) }; listeners.clear()
        }
        try { context.unregisterReceiver(receiver) } finally {
            pending.close()
        }
    }

    private fun decode(intent: Intent): ChannelPacket? {
        return try {
            val module = intent.getStringExtra(EXTRA_MODULE) ?: return null
            val sender = intent.getStringExtra(EXTRA_SENDER) ?: return null
            val target = intent.getStringExtra(EXTRA_TARGET) ?: return null
            val topic = intent.getStringExtra(EXTRA_TOPIC) ?: return null
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            ChannelPacket(
                module = module,
                sender = sender,
                target = target,
                topic = topic,
                id = id,
                replyTo = intent.getStringExtra(EXTRA_REPLY_TO) ?: "",
                timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP, 0L),
                payload = intent.getByteArrayExtra(EXTRA_PAYLOAD) ?: byteArrayOf()
            )
        } catch (error: RuntimeException) {
            reportDecodeError(error)
            null
        }
    }

    private fun reportDecodeError(error: Throwable) {
        try {
            logger?.log(LogLevel.DEBUG, "DataChannel dropped malformed packet", error)
                ?: Log.d(TAG, "DataChannel dropped malformed packet", error)
        } catch (logging: Throwable) {
            Log.e(TAG, "DataChannel packet logging failed", logging)
        }
    }
    /** Route a handler failure to the configured logger; if the logger itself throws, fall back to Log.e
     * so the original callback error stays observable instead of being masked or silently swallowed. */
    private fun reportHandlerError(topic: String, error: Throwable) {
        try {
            logger?.log(LogLevel.ERROR, "DataChannel handler failed: $topic", error)
                ?: Log.e(TAG, "DataChannel handler failed: $topic", error)
        } catch (loggerFailure: Throwable) {
            Log.e(TAG, "DataChannel handler failed: $topic", error)
            Log.e(TAG, "DataChannel logger failed while reporting handler error", loggerFailure)
        }
    }
    companion object {
        private const val EXTRA_MODULE = "hk.uwu.roxyhook.DATA_MODULE_V1"
        private const val EXTRA_SENDER = "hk.uwu.roxyhook.DATA_SENDER_V1"
        private const val EXTRA_TARGET = "hk.uwu.roxyhook.DATA_TARGET_V1"
        private const val EXTRA_TOPIC = "hk.uwu.roxyhook.DATA_TOPIC_V1"
        private const val EXTRA_ID = "hk.uwu.roxyhook.DATA_ID_V1"
        private const val EXTRA_REPLY_TO = "hk.uwu.roxyhook.DATA_REPLY_TO_V1"
        private const val EXTRA_TIMESTAMP = "hk.uwu.roxyhook.DATA_TIMESTAMP_V1"
        private const val EXTRA_PAYLOAD = "hk.uwu.roxyhook.DATA_PAYLOAD_V1"
        private const val TAG = "RoxyDataChannel"
    }
}
