package app.familycloud.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressPumpTest {
    @Test
    fun intermediateBytesAreThrottledButTheEndOfAFileIsKept() {
        var clock = 0L
        val seen = mutableListOf<Long>()
        val pump = ProgressPump(now = { clock }) { seen += it.sent }
        pump.offer(TransferProgress(0, 2, sent = 100, size = 1000))
        clock += 10_000_000L
        pump.offer(TransferProgress(0, 2, sent = 200, size = 1000))
        pump.offer(TransferProgress(0, 2, sent = 1000, size = 1000))
        assertEquals(listOf(100L, 1000L), seen)
    }
}
