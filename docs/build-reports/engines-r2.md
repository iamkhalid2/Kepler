# Engine and data fixes, round 2

Changed
- F4: `NarrationEngine.runJob` now waits for in-flight (JIT) renders overlapping each page, re-checks coverage and renders what is still missing (max 4 rounds, then `Failed(retry)`). JIT renders are registered as in flight before they start; `ensureAhead` starts the chunk holding the requested ordinal first and the rest after it finishes.
- F6: spend booked on HTTP 2xx via `SynthesisRequest.onAccepted`; paid model never re-sends a request after a mid-stream IOException (`TtsException.Network.billed`), free model at most 2 retries. Every provider is wrapped in `MeteredProvider` + `SpendLedger`: price is reserved under one lock with the monthly/per-book cap checks at every dispatch (job, JIT, preview).
- F7: `LibraryRepository.delete` also removes `files/audio/<id>` and the book's `ai_cache` rows (keys now `b<id>:...`, `AiCacheKeys` in `CleanupExtras.kt`). A constraint failure on segment insert gives `JobResult.BookGone` (state Idle, orphan file deleted, no error).
- F3: `core/model/NarrationExtras.kt` (see API below). Paid generation needs `confirmPaidGeneration()` after 2026-11-30T23:59:59Z; earlier confirmations do not count.
- F11a: `LocatorOrdinalMapper.bestOccurrence` allocation-free and thread-safe; signatures unchanged.
- F12: Dokka excluded from fsrskt (engine/srs); NOTICE.md gets the runtime transitive deps; `docs/licenses/OFL-1.1.txt` bundled as app asset.
- F13: `RequestBudgeter` day is UTC. `TextLocatorMapper` (unreferenced) deleted.
- F5: `PlaybackEngine` takes `persistContext` (tests use Unconfined, no `Thread.sleep`). tts and playback unit tests green 5/5 with `--rerun-tasks`.

UI API (`app.kepler.core.model`)
- `PauseReason` (MONTHLY_CAP, BOOK_CAP, PAID_CONFIRM, SYSTEM_STOP, OFFLINE; `code`, `fromCode`), `NarrationState.Paused.pauseReason`.
- `paidSwitchStatus(now: Instant)`: `PaidSwitchStatus.FREE_ACTIVE | SWITCHING_SOON(daysLeft 1..7) | PAID`.
- `NarrationControls` (implemented by `NarrationEngine`): `paidSwitchStatus()`, `paidGenerationConfirmed()`, `confirmPaidGeneration()`, `spentThisMonthCents()`, `spentOnBookCents(id)`, `effectiveMonthlyCapCents()`, `perBookCapCents()`, `cancelBook(id)`.
- `NarrationBlockedException(reason)` from `ensureAhead`/`preview`; playback shows "not ready" plus the reason.
- `NarrationLimits`, `PaidSwitch`.

Integrator: call `narration.cancelBook(id)` before `library.delete(id)`.

Skipped
- Duplicate literata.ttf: core/designsystem already has each family once; the second copy is `feature/reader/src/main/assets/fonts/literata.ttf`, served to the WebView by name (`ReadiumReaderEngine`), so it stays.
