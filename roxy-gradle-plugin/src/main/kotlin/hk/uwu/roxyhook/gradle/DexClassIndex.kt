package hk.uwu.roxyhook.gradle

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads class definitions, not a naive search for class-name strings. Not an ART verifier. */
object DexClassIndex {
    fun descriptors(bytes: ByteArray): Set<String> {
        require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) && bytes[7] == 0.toByte()) {
            "Invalid standard DEX header"
        }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun unsigned(offset: Int): Long {
            require(offset >= 0 && offset.toLong() + 4 <= bytes.size) { "DEX offset out of bounds" }
            return data.getInt(offset).toLong() and 0xffffffffL
        }
        fun integer(offset: Int): Int = unsigned(offset).also { require(it <= Int.MAX_VALUE) { "Unsupported DEX offset" } }.toInt()
        require(integer(40) == 0x12345678) { "Unsupported DEX byte order" }
        require(integer(32) == bytes.size && integer(36) >= 112) { "Invalid DEX size" }
        fun table(sizeOffset: Int, offsetOffset: Int, width: Int): Pair<Int, Int> {
            val count = integer(sizeOffset); val start = integer(offsetOffset)
            require(start.toLong() + count.toLong() * width <= bytes.size) { "DEX table out of bounds" }
            return count to start
        }
        val (stringCount, strings) = table(56, 60, 4)
        val (typeCount, types) = table(64, 68, 4)
        val (classCount, classes) = table(96, 100, 32)
        fun stringAt(index: Int): String {
            require(index in 0 until stringCount)
            var position = integer(strings + index * 4)
            var prefixBytes = 0
            while (true) {
                require(position < bytes.size && prefixBytes++ < 5) { "Invalid DEX string length prefix" }
                if ((bytes[position++].toInt() and 128) == 0) break
            }
            val start = position
            while (position < bytes.size && bytes[position] != 0.toByte()) position++
            require(position < bytes.size && position - start <= 65535) { "Unterminated DEX string" }
            // Generated entry names are portable ASCII; other descriptors are irrelevant to the lookup.
            return String(bytes, start, position - start, Charsets.UTF_8)
        }
        return buildSet {
            repeat(classCount) { index ->
                val type = integer(classes + index * 32)
                require(type in 0 until typeCount) { "Invalid DEX class type" }
                add(stringAt(integer(types + type * 4)))
            }
        }
    }
}
