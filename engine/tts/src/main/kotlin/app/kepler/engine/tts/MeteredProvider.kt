package app.kepler.engine.tts

/**
 * Puts every request of [inner] behind the [SpendLedger]: the price is reserved before the request is sent
 * (so the caps hold at every dispatch, JIT and previews included), becomes spend when the provider reports the
 * request accepted, and is dropped when the request fails before that.
 */
internal class MeteredProvider(
    private val inner: TtsProvider,
    private val ledger: SpendLedger,
) : TtsProvider by inner {

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        val price = request.text.toByteArray(Charsets.UTF_8).size * inner.centsPerByte()
        val reservation = ledger.reserve(request.bookId, price)
        try {
            val result = inner.synthesize(
                request.copy(onAccepted = {
                    reservation.commit()
                    request.onAccepted()
                }),
            )
            // A provider that never reported acceptance still delivered audio, so the request counts.
            reservation.commit()
            return result
        } catch (e: Throwable) {
            reservation.release()
            throw e
        }
    }
}
