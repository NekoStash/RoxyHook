package hk.uwu.roxyhook.android.channel

import hk.uwu.roxyhook.channel.ChannelPacket

class ChannelMessage internal constructor(val packet: ChannelPacket, private val channel: DataChannel) {
    val sender get() = packet.sender
    val topic get() = packet.topic
    val payload get() = packet.payload
    fun text(): String = packet.text()
    fun reply(payload: ByteArray) = channel.reply(packet, payload)
    fun reply(text: String) = reply(text.toByteArray(Charsets.UTF_8))
}
