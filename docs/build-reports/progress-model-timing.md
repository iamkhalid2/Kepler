PROGRESS MODEL + CHAPTER TIMING + PLAYBACK STATE - report

DONE. Passing: :core:database, :engine:playback, :engine:tts unit tests; :app:compileDebugKotlin.

Position (#111)
- Book.positionFraction (default 0f, last field): most recent saved position (reading or listening, latest updatedAt wins) / sentenceCount, clamped 0..1. Written in the same transaction as every savePosition. readFraction/listenFraction unchanged (consumed, fence/stats only).
- Room DB version 2, KeplerMigrations.V1_TO_V2 (ALTER ADD COLUMN DEFAULT 0, then backfill from position rows). Additive, no destructive fallback. schemas/2.json exported. MigrationTest builds a real v1 file from schemas/1.json, migrates it, and lets Room validate the schema.

PlaybackState (media ms at 1x; wall time = /speed)
- currentSectionId (ChapterMark.sectionId, null if none), sectionElapsedMs, sectionDurationMs (whole chapter, not just loaded audio), sectionDurationEstimated, bookRemainingMs, bookRemainingEstimated. positionMs/durationMs now equal the section values. Updates ~4 Hz while playing (250 ms quantum), immediately on seek, section change and when audio/text arrives.
- Extensions in core/model PlaybackTiming.kt: sectionFraction, sectionElapsedWallMs, sectionRemainingWallMs, bookRemainingWallMs.
- PlaybackController.seekWithinSection(fraction) (interface default via seekByMs; engine and PlaybackClient override exactly) and seekWithinBook(fraction). PlaybackInfo.bookElapsedMs/bookTotalMs now come from the same model.
- AppSettings.scrubberScope (ScrubberScope.CHAPTER default | BOOK, persisted). The notification item uses chapter or whole-book duration and position per scope.
- Logic: ChapterTiming.kt (ChapterTimeline, BookTiming), JVM-tested. Real segment audio where it exists, otherwise words x pace (calibrated from the chapter's own audio, else 385 ms/word), else the book average per sentence. Pauses counted at 450 ms per segment join (close, not identical, to the playlist).

Counts
- CardRepository.reviewedTodayFlow(nowMs) (default method, Room-backed in impl): re-emits on review. UI should collect it instead of calling reviewedToday once. dueCount was already a live Flow.

DESIGN ASSUMPTIONS: none (no UI). Unverified on device.
