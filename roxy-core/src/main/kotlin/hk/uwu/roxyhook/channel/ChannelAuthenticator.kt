package hk.uwu.roxyhook.channel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC authenticates possession of the shared key, NOT an individual app's identity; no encryption. */
class ChannelAuthenticator(secret: ByteArray) : AutoCloseable {
    private val key = secret.copyOf()
    private var closed = false
    init { require(key.size == 32) { "A 256-bit random channel secret is required" } }
    @Synchronized fun encode(packet: ChannelPacket): ByteArray {
        check(!closed)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            listOf(packet.module, packet.sender, packet.target, packet.topic, packet.id, packet.replyTo).forEach {
                require(it.length <= 255) { "Channel field too long" }; data.writeUTF(it)
            }
            data.writeLong(packet.timestampMillis)
            val bytes = packet.payload
            data.writeInt(bytes.size); data.write(bytes)
        }
        val body = output.toByteArray()
        return body + sign(body)
    }
    /** Malformed/forged packets throw before application handlers see them. */
    @Synchronized fun decode(wire: ByteArray): ChannelPacket {
        check(!closed)
        require(wire.size in 64..MAX_WIRE_BYTES) { "Invalid packet length" }
        val body = wire.copyOfRange(0, wire.size - TAG_BYTES)
        val supplied = wire.copyOfRange(wire.size - TAG_BYTES, wire.size)
        require(MessageDigest.isEqual(supplied, sign(body))) { "Unauthenticated channel packet" }
        return DataInputStream(ByteArrayInputStream(body)).use { data ->
            require(data.readInt() == MAGIC) { "Unsupported channel protocol" }
            val fields = List(6) { data.readUTF().also { require(it.length <= 255) } }
            val time = data.readLong()
            val length = data.readInt()
            require(length in 0..ChannelPacket.MAX_PAYLOAD_BYTES && length == data.available()) { "Invalid payload length" }
            val payload = ByteArray(length).also { data.readFully(it) }
            ChannelPacket(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], time, payload)
        }
    }
    private fun sign(body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(body)
    }
    @Synchronized override fun close() { closed = true; key.fill(0) }
    companion object {
        private const val MAGIC = 0x52585901
        private const val TAG_BYTES = 32
        const val MAX_WIRE_BYTES = 52 * 1024
        fun newSecret(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
        fun secretToHex(secret: ByteArray): String {
            require(secret.size == 32)
            return secret.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun secretFromHex(value: String): ByteArray {
            require(Regex("[0-9a-f]{64}").matches(value)) { "Missing or invalid DataChannel key; initialize it in the module app first" }
            return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
