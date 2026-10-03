package hk.uwu.roxyhook.platform.libxposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeepHookIdTest {
    @Test
    fun autoAndNamedIdsRoundTripWithoutLeakingPublicId() {
        val auto = KeepHookId.encode(50, null)
        assertEquals("roxy.keep.v1:a:50", auto)
        assertEquals(KeepHookId.Identity(50, null), KeepHookId.decode(auto))

        val named = KeepHookId.encode(-7, "用户/keep")
        assertEquals(KeepHookId.Identity(-7, "用户/keep"), KeepHookId.decode(named))
        assertNull(KeepHookId.decode("business-id"))
    }

    @Test
    fun rejectsReservedAndMalformedIds() {
        try {
            KeepHookId.encode(50, "roxy.keep.user"); error("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KeepHookId.decode("roxy.keep.v2:a:50"); error("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KeepHookId.decode("roxy.keep.v1:n:50:not-base64!"); error("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun enforcesUtf8ByteLimit() {
        val value = "é".repeat(KeepHookId.MAX_ID_BYTES / 2 + 1)
        try {
            KeepHookId.encode(50, value); error("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
