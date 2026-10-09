# AI speed (free models only)

Done: streaming, latency-ranked free models, AiStreaming/AiPrefetch API, Ask without packs, short streamed Recap, background PackPrefetcher, smaller packs. `:engine:ai:test` passes (64 tests, MockEngine); `:app:compileDebugKotlin` passes. AppContainer: one change, `probeModels = true`. No UI code touched.

## API for the UI (core/model/AiExtras.kt, implemented by OpenRouterAiService)
- `AiStreaming.askStream(bookId, question): Flow<AskEvent>`, `explainStream(bookId, ordinal, selection)`, `defineStream(bookId, ordinal, term)` (also `Flow<AskEvent>`, never Citations), `recapStream(bookId): Flow<String>` (text deltas; errors are thrown, use `.catch`).
- `AskEvent` = `Delta(text)` | `Citations(citations)` (cumulative) | `Done(answer)` (canonical text, replace streamed text) | `Failed(error: Throwable)`. `error` is typed Throwable because core:model cannot see engine:ai; it is always an `AiError`, `toUiError()` works.
- `AiPrefetch.prefetch(bookId, ordinal)`: call on section change or book open. Debounced, BACKGROUND priority, also keeps the latency probe fresh.
- Old suspend `AiService` calls still work, built on the streams.

## Measured live (real OpenRouter, free models)
Probe (6 models, 8 s): ling-3.1-flash 1373 ms p50 TTFT, nemotron-3-super-120b 2467 ms. Dropped: gemma-4 x2 (429), nemotron-3-ultra (unavailable), nemotron-3.5-lightning (>8 s). Streamed Ask: first token 1370 ms, total 1723 ms (ling-3.1-flash). One background pack build: 6.3 s. Recap with cached packs: no model call. Latency is noisy (super measured 0.7 s earlier today, 2.5 s now).

## Notes / skipped
- Requests: second run spent 8 (6 probes, 1 Ask, 1 pack). A first run went over budget on pack retries before probes existed; I did not record its count (about 12-17).
- Probe costs ~6 of the 50 daily free requests, at most once per 24 h.
- Fence blocks the "next" section in FRONTIER mode, so prefetch builds previous (complete), current (partial, rebuilt every 40 sentences) and next only when the fence allows.
- Packs read at most 40k characters of a section (head); prompts v2 (packs, ask, explain, define); old cached packs are rebuilt.
- Explain/define no longer strip passage numbers mid-stream beyond dropping `[n]`; Done text is fully stripped.
- Unmeasured model order is a bundled prior until the first probe answers (first Ask waits up to 6 s, normally ~1 s).
