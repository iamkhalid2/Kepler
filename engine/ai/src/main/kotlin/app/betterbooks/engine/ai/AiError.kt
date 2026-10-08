package app.betterbooks.engine.ai

/**
 * Every failure the AI layer surfaces. [OpenRouterAiService] only ever throws subclasses of this,
 * so the UI can show a typed, human-readable state instead of a raw exception.
 */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No OpenRouter key stored in settings. */
    class MissingKey : AiError("No OpenRouter API key is set")
    class Unauthorized : AiError("OpenRouter rejected the API key")
    class InsufficientCredits : AiError("The selected model needs OpenRouter credits")
    /** Daily request budget spent (for background work: the reserved share was reached). */
    class BudgetExhausted(val dailyLimit: Int, val backgroundOnly: Boolean = false) :
        AiError(if (backgroundOnly) "Background AI work paused to keep requests for you" else "Daily AI request limit reached ($dailyLimit)")
    class RateLimited(val retryAfterSeconds: Int?) : AiError("AI requests are rate limited, try again shortly")
    /** The model or its provider is down, gone, or refused our request. */
    class ModelUnavailable(val detail: String) : AiError("The AI model is unavailable: $detail")
    /** The model answered but not with usable output, even after one repair retry. */
    class InvalidResponse(val detail: String) : AiError("The AI model returned an unusable answer: $detail")
    class Network(cause: Throwable?) : AiError("Could not reach OpenRouter", cause)
    /** Nothing has been read yet, so there is no text the fence allows. */
    class NothingRead : AiError("Nothing has been read yet")
    /** Unknown section, or one that is excluded from the book. */
    class SectionUnavailable : AiError("This section is not available")
    class Unexpected(cause: Throwable) : AiError("Unexpected AI error: ${cause.message}", cause)
}
