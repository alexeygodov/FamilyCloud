package app.familycloud.sync

data class TransferProgress(
    val done: Int,
    val total: Int,
    val sent: Long = 0L,
    val size: Long = 0L,
    val name: String = "",
)

object TransferKeys {
    const val DONE = "done"
    const val TOTAL = "total"
    const val SENT = "sent"
    const val SIZE = "size"
    const val NAME = "name"
}

/**
 * Уведомление нельзя обновлять на каждый прочитанный буфер.
 * Границы файла (старт и конец) проходят всегда, промежуточные байты — не чаще чем раз в 400 мс.
 */
class ProgressPump(
    private val now: () -> Long = { System.nanoTime() },
    private val emit: (TransferProgress) -> Unit,
) {
    private var last = 0L
    private var started = false

    fun offer(update: TransferProgress) {
        val time = now()
        val boundary = update.sent == 0L || (update.size > 0L && update.sent >= update.size)
        if (!boundary && started && time - last < INTERVAL) return
        started = true
        last = time
        emit(update)
    }

    private companion object {
        const val INTERVAL = 400_000_000L
    }
}
