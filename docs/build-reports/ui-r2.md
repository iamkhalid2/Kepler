UI round 2: narration safety UI and safe delete

BUILT
- Narration setup: PausedPanel maps every PauseReason to plain copy and a real action. Paid confirm shows the paid cost per hour, this book's estimate, the per-book and monthly caps and the month's spend, with one primary key "Continue with the paid voice" (confirmPaidGeneration() then restarts the job). Book cap and monthly cap show spend against the cap, "Change the limit" (to Settings) and "Use system voice". Offline and Android stop show a "Resume" key that restarts with the chosen voice. The Bind key is hidden while paused.
- Hub status line uses the same copy (pausedReasonRes covers all five reasons).
- Free-to-paid notice (PaidNotice) in Settings and Narration setup from 7 days before 2026-11-30 until the user confirms; price per hour comes from the estimator via LibraryDeps.paidCentsPerHour.
- Settings: monthly limit is a dollar field in a sheet (blank or "Use the default" = 0 = default, shown as "Default $5.00"); per-book limit row.
- Delete (shelf long-press sheet and a new Hub link "Remove from library"): LibraryDeps.removeBook pauses playback if the book is current, calls controls.cancelBook, then library.delete. A failing cancel never blocks the delete.
- Key fields (Onboarding and Settings share KeyField): Password keyboard type, autocorrect off, no capitalisation.

PUBLIC ENTRY POINTS
LibraryDeps gained controls (NarrationControls), playback (PlaybackController?), paidCentsPerHour; LibraryDeps.removeBook(id); HubScreen has optional onRemove. AppContainer wires all three.

TESTS
:feature:library:testDebugUnitTest passes (11 new tests: pause kinds and copy, paid confirm restart, caps, resume, notice, delete ordering for hub and shelf, cancel failure, cap parsing). :app:assembleDebug passes. Not checked on device.

GAPS
- Per-book limit is read-only: AppSettings has no field for it and the contracts could not change. Needs a field in Repositories.kt to become editable.
- The explicit IME_FLAG_NO_PERSONALIZED_LEARNING cannot be set from Compose KeyboardOptions; the Password type is what Compose maps to it/the password variation.
- Predictive delete race: the hub shows "book missing" for a frame before it closes.

DESIGN ASSUMPTIONS
- Paid confirmation is an inline block under the cost slip (not a sheet), with the single primary key.
- Free-to-paid notice is a caption in text2, no accent or icon.
- Hub "Remove from library" is a negative text link at the bottom of the page, reusing the shelf's remove sheet.
- Monthly limit editing uses KpInput plus a secondary "Save" and a text link in a sheet.
- Per-book limit shown as a Settings row with numeral on the right.
