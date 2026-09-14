package hk.uwu.roxyhook.channel

/** Bounded in-memory replay protection. Refuses overflow rather than evicting still-valid nonces. */
class ReplayWindow(private val ttlMillis: Long = 300_000, private val capacity: Int = 2048,
                   private val futureToleranceMillis: Long = 30_000) {
    private val seen = linkedMapOf<String, Long>()
    init { require(ttlMillis in 1..3_600_000 && capacity in 1..65_536 && futureToleranceMillis in 0..60_000) }
    @Synchronized fun accept(packet: ChannelPacket, nowMillis: Long = System.currentTimeMillis()): Boolean {
        require(nowMillis > 0)
        // Compare nonnegative differences to avoid overflow from untrusted timestamps.
        val time = packet.timestampMillis
        if (time > nowMillis && time - nowMillis > futureToleranceMillis) return false
        if (time <= nowMillis && nowMillis - time > ttlMillis) return false
        seen.entries.removeAll { (_, stamp) -> nowMillis >= stamp && nowMillis - stamp > ttlMillis }
        val key = packet.sender + ":" + packet.id
        if (key in seen || seen.size >= capacity) return false
        seen[key] = maxOf(time, nowMillis)
        return true
    }
    @Synchronized fun clear() { seen.clear() }
}
