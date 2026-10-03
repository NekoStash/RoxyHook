package hk.uwu.roxyhook.platform.libxposed

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Versioned native identity. Never stored in business savedInstanceState. */
internal object KeepHookId {
    const val RESERVED_PREFIX = "roxy.keep."
    const val MAX_ID_BYTES = 1024
    private const val PREFIX = "roxy.keep.v1:"
    private const val MAX_NATIVE_LENGTH = 1400

    data class Identity(val priority: Int, val userId: String?)

    fun encode(priority: Int, userId: String?): String {
        if (userId == null) return "${PREFIX}a:$priority"
        require(userId.isNotBlank() && !userId.startsWith(RESERVED_PREFIX)) { "Invalid KEEP user id" }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(userId)).let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
        require(bytes.size <= MAX_ID_BYTES) { "KEEP user id exceeds $MAX_ID_BYTES UTF-8 bytes" }
        return "${PREFIX}n:$priority:${
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }"
    }

    /** Ordinary ids return null; malformed/reserved future versions fail closed. */
    fun decode(nativeId: String?): Identity? {
        if (nativeId == null || !nativeId.startsWith(RESERVED_PREFIX)) return null
        require(nativeId.length <= MAX_NATIVE_LENGTH && nativeId.startsWith(PREFIX)) {
            "Unsupported KEEP native id; restart the target process"
        }
        val fields = nativeId.removePrefix(PREFIX).split(':')
        require(fields.size in 2..3) { "Malformed KEEP native id" }
        val priority = fields[1].toIntOrNull()
        require(priority != null && priority.toString() == fields[1]) { "Malformed KEEP priority" }
        val userId = when (fields[0]) {
            "a" -> {
                require(fields.size == 2) { "Malformed AUTO KEEP id" }; null
            }

            "n" -> {
                require(fields.size == 3 && fields[2].matches(Regex("[A-Za-z0-9_-]+"))) { "Malformed NAMED KEEP id" }
                val bytes = Base64.getUrlDecoder().decode(fields[2])
                require(bytes.size <= MAX_ID_BYTES) { "KEEP user id is too long" }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                    .toString()
            }

            else -> error("Unknown KEEP slot; restart the target process")
        }
        require(encode(priority, userId) == nativeId) { "Noncanonical KEEP native id" }
        return Identity(priority, userId)
    }
}
