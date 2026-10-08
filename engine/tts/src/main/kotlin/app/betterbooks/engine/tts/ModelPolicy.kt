package app.betterbooks.engine.tts

import java.time.Clock
import java.time.Instant

/**
 * Chooses the Fish model header. Fish silently bills the paid model when the header is missing,
 * so every request carries the value returned here (see [FishProvider]).
 */
class ModelPolicy(private val clock: Clock = Clock.systemUTC()) {
    fun model(): String = if (isFree()) FREE_MODEL else PAID_MODEL

    fun isFree(): Boolean = !clock.instant().isAfter(FREE_UNTIL)

    /** US cents per UTF-8 byte: $15 per 1M bytes on the paid model. */
    fun centsPerByte(): Double = if (isFree()) 0.0 else PAID_CENTS_PER_BYTE

    companion object {
        const val FREE_MODEL = "s2.1-pro-free"
        const val PAID_MODEL = "s2.1-pro"
        val FREE_UNTIL: Instant = Instant.parse("2026-11-30T23:59:59Z")
        const val PAID_CENTS_PER_BYTE = 1500.0 / 1_000_000.0
    }
}
