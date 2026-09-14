package hk.uwu.roxyhook.channel

import java.util.UUID

/** Classloader-neutral transport: serialize your own schema into bytes/UTF-8, never Java objects. */
class ChannelPacket(val module: String, val sender: String, val target: String, val topic: String,
                    val id: String = UUID.randomUUID().toString(), val replyTo: String = "",
                    val timestampMillis: Long = System.currentTimeMillis(), payload: ByteArray) {
    private val content = payload.copyOf()
    val payload: ByteArray get() = content.copyOf()
    fun text(): String = content.toString(Charsets.UTF_8)
    init {
        listOf(module, sender, target).forEach { require(PACKAGE.matches(it)) { "Invalid channel package: $it" } }
        require(TOPIC.matches(topic)) { "Topic must be 1..128 ASCII letters, digits, dot, underscore or hyphen" }
        require(UUID_PATTERN.matches(id) && (replyTo.isEmpty() || UUID_PATTERN.matches(replyTo))) { "Invalid message id" }
        require(timestampMillis > 0)
        require(content.size <= MAX_PAYLOAD_BYTES) { "DataChannel payload exceeds $MAX_PAYLOAD_BYTES bytes" }
    }
    companion object {
        const val MAX_PAYLOAD_BYTES = 48 * 1024
        private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
        private val TOPIC = Regex("[A-Za-z0-9_.-]{1,128}")
        private val UUID_PATTERN = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }
}
