package app.betterbooks.feature.library

import app.betterbooks.core.designsystem.BbSeed
import app.betterbooks.core.model.AiBudget
import app.betterbooks.core.model.ImportResult
import app.betterbooks.core.model.NarrationState
import app.betterbooks.core.model.VoiceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
abstract class VmTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }
}

class LibraryViewModelTest : VmTest() {
    private fun deps(vararg books: app.betterbooks.core.model.Book) = FakeDeps(library = FakeLibrary(books.toList()))

    @Test fun searchMatchesEveryWordInTitleOrAuthor() = runTest {
        val vm = LibraryViewModel(deps(book(1, "Meditations", "Marcus Aurelius"), book(2, "Walden", "Thoreau")))
        vm.setQuery("marcus med")
        assertEquals(listOf(1L), vm.state.value.books.map { it.id })
        vm.setQuery("zzz")
        assertTrue(vm.state.value.noMatches)
        vm.toggleSearch(); vm.toggleSearch()
        assertEquals("", vm.state.value.query)
    }

    @Test fun closingSearchClearsTheQuery() = runTest {
        val vm = LibraryViewModel(deps(book(1)))
        vm.toggleSearch(); vm.setQuery("x")
        vm.toggleSearch()
        assertFalse(vm.state.value.searching)
        assertEquals("", vm.state.value.query)
    }

    @Test fun sortsRecentTitleAuthor() = runTest {
        val vm = LibraryViewModel(
            deps(book(1, "B", "Zed", addedAt = 10), book(2, "A", "Yan", addedAt = 5, lastOpenedAt = 99), book(3, "C", "Xi", addedAt = 7)),
        )
        assertEquals(listOf(2L, 1L, 3L), vm.state.value.books.map { it.id })
        vm.setSort(LibrarySort.Title)
        assertEquals(listOf(2L, 1L, 3L), vm.state.value.books.map { it.id })
        vm.setSort(LibrarySort.Author)
        assertEquals(listOf(3L, 2L, 1L), vm.state.value.books.map { it.id })
    }

    @Test fun emptyLibraryIsFlagged() = runTest {
        assertTrue(LibraryViewModel(deps()).state.value.isEmpty)
    }

    @Test fun importResultsBecomeMessages() = runTest {
        val d = deps()
        val vm = LibraryViewModel(d)
        d.library.nextImport = ImportResult.Imported(book(9, "New"))
        vm.import("content://x")
        assertEquals(LibraryMessage.Imported("New"), vm.state.value.message)
        assertFalse(vm.state.value.importing)
        d.library.nextImport = ImportResult.Duplicate(book(9, "New"))
        vm.import("content://x")
        assertEquals(LibraryMessage.Duplicate("New"), vm.state.value.message)
        d.library.nextImport = ImportResult.Failed("zip: bad header")
        vm.import("content://x")
        assertEquals(LibraryMessage.ImportFailed, vm.state.value.message)
        d.library.importThrows = IllegalStateException("boom")
        vm.import("content://x")
        assertEquals(LibraryMessage.ImportFailed, vm.state.value.message)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test fun deleteNeedsConfirmation() = runTest {
        val d = deps(book(1), book(2))
        val vm = LibraryViewModel(d)
        vm.requestDelete(book(1))
        assertEquals(1L, vm.state.value.pendingDelete?.id)
        vm.cancelDelete()
        assertTrue(d.library.deleted.isEmpty())
        vm.requestDelete(book(1)); vm.confirmDelete()
        assertEquals(listOf(1L), d.library.deleted)
        assertEquals(listOf(2L), vm.state.value.books.map { it.id })
        assertNull(vm.state.value.pendingDelete)
    }

    @Test fun layoutToggles() = runTest {
        val vm = LibraryViewModel(deps())
        assertEquals(LibraryLayout.Shelf, vm.state.value.layout)
        vm.toggleLayout()
        assertEquals(LibraryLayout.List, vm.state.value.layout)
    }
}

class ShelfMathTest {
    @Test fun rowsPackByWidth() {
        val rows = packShelfRows(List(10) { 10f }, available = 52f, gap = 2f)
        // 4 spines = 40 + 3*2 = 46; a fifth needs 58 > 52
        assertEquals(listOf(4, 4, 2), rows.map { it.size })
        assertEquals((0 until 10).toList(), rows.flatten())
    }

    @Test fun oversizedSpineGetsItsOwnRow() {
        assertEquals(listOf(listOf(0), listOf(1)), packShelfRows(listOf(80f, 10f), available = 50f))
    }

    @Test fun emptyShelfHasNoRows() = assertTrue(packShelfRows(emptyList(), 100f).isEmpty())

    @Test fun materialIndexIsStableAndSpread() {
        assertEquals(materialIndexFor(42, 6), materialIndexFor(42, 6))
        val seen = (1L..60L).map { materialIndexFor(it, 6) }.toSet()
        assertEquals(6, seen.size)
        assertTrue((1L..200L).all { materialIndexFor(it, 6) in 0..5 })
    }

    @Test fun spineBucketsFollowSize() {
        assertEquals(SpineBucket.Slim, spineBucketFor(500))
        assertEquals(SpineBucket.Regular, spineBucketFor(8_000))
        assertEquals(SpineBucket.Thick, spineBucketFor(40_000))
        assertEquals(9f, spineThicknessUnits(500), 0.001f)
    }

    @Test fun oppoFamilyDetection() {
        assertTrue(isOppoFamily("OPPO", "OPPO"))
        assertTrue(isOppoFamily("OnePlus", null))
        assertTrue(isOppoFamily("realme", "realme"))
        assertFalse(isOppoFamily("samsung", "samsung"))
        assertFalse(isOppoFamily(null, null))
    }
}

class OnboardingViewModelTest : VmTest() {
    @Test fun stepsIncludeBatteryOnlyWhenAsked() = runTest {
        assertEquals(4, OnboardingViewModel(FakeDeps()).state.value.steps.size)
        val d = FakeDeps().apply { battery = true }
        val vm = OnboardingViewModel(d)
        assertEquals(OnboardingStep.Battery, vm.state.value.steps.last())
        repeat(10) { vm.next() }
        assertTrue(vm.state.value.isLast)
        vm.back()
        assertEquals(OnboardingStep.Privacy, vm.state.value.step)
    }

    @Test fun acceptedKeyIsSavedAndRejectedKeyIsNot() = runTest {
        val d = FakeDeps()
        val vm = OnboardingViewModel(d)
        vm.verify(KeyKind.Fish, "  good-key ")
        assertEquals(KeyCheck.Connected, vm.state.value.fish)
        assertEquals("good-key", d.settings.fish)
        d.orOk = false
        vm.verify(KeyKind.OpenRouter, "bad")
        assertEquals(KeyCheck.Rejected, vm.state.value.openRouter)
        assertNull(d.settings.openRouter)
    }

    @Test fun unreachableCheckOffersSavingUnverified() = runTest {
        val d = FakeDeps().apply { verifyThrows = java.io.IOException("offline") }
        val vm = OnboardingViewModel(d)
        vm.verify(KeyKind.OpenRouter, "k")
        assertEquals(KeyCheck.Unreachable, vm.state.value.openRouter)
        assertNull(d.settings.openRouter)
        vm.saveUnverified(KeyKind.OpenRouter, "k")
        assertEquals("k", d.settings.openRouter)
        assertEquals(KeyCheck.Connected, vm.state.value.openRouter)
    }

    @Test fun blankKeyIsIgnoredAndSkipNeedsNothing() = runTest {
        val d = FakeDeps()
        val vm = OnboardingViewModel(d)
        vm.verify(KeyKind.Fish, "   ")
        assertEquals(KeyCheck.Idle, vm.state.value.fish)
        vm.finish()
        assertTrue(d.settings.current.onboarded)
        assertTrue(vm.state.value.finished)
    }

    @Test fun existingKeysShowAsConnected() = runTest {
        val d = FakeDeps().apply { settings.fish = "k" }
        assertEquals(KeyCheck.Connected, OnboardingViewModel(d).state.value.fish)
    }
}

class HubViewModelTest : VmTest() {
    @Test fun primaryFollowsTheMostUsedMode() {
        assertEquals(HubMode.Listen, primaryMode(book(1, read = 0.2f, listen = 0.4f)))
        assertEquals(HubMode.Read, primaryMode(book(1, read = 0.4f, listen = 0.4f)))
        assertEquals(HubMode.Read, primaryMode(book(1)))
    }

    @Test fun narrationStatusMapping() {
        assertEquals(NarrationStatus.None, narrationStatus(NarrationState.Idle, 0f))
        assertEquals(NarrationStatus.Bound, narrationStatus(NarrationState.Idle, 1f))
        assertEquals(NarrationStatus.Binding(0.62f, false, null), narrationStatus(NarrationState.Idle, 0.62f))
        assertEquals(
            NarrationStatus.Binding(0.5f, true, 90),
            narrationStatus(NarrationState.Running(1, 50, 100, 90), 0f),
        )
        assertEquals(NarrationStatus.Bound, narrationStatus(NarrationState.Done(1), 0.3f))
        assertEquals(NarrationStatus.Paused("net"), narrationStatus(NarrationState.Paused(1, "net"), 0f))
        assertEquals(NarrationStatus.Failed("x"), narrationStatus(NarrationState.Failed(1, "x"), 0f))
    }

    @Test fun hubCombinesBookNarrationAndCards() = runTest {
        val d = FakeDeps(library = FakeLibrary(listOf(book(5, read = 0.1f, listen = 0.3f))))
        d.audio.coverage.value = 0.62f
        d.settings.update { it.copy(voice = VoiceRef("fish", "ava", "Ava")) }
        val vm = HubViewModel(d, 5)
        val ui = vm.state.value
        assertEquals(HubMode.Listen, ui.primary)
        assertTrue(ui.started)
        assertEquals("Ava", ui.voiceName)
        assertEquals(NarrationStatus.Binding(0.62f, false, null), ui.narration)
        assertEquals(listOf(5L), d.library.opened)
    }

    @Test fun missingBookStillLoads() = runTest {
        val vm = HubViewModel(FakeDeps(), 77)
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.book)
    }
}

class NarrationSetupViewModelTest : VmTest() {
    private fun deps(fishKey: Boolean): FakeDeps = FakeDeps(library = FakeLibrary(listOf(book(1)))).apply {
        if (fishKey) settings.fish = "k"
        narration.voicesByProvider = mapOf(
            "fish" to listOf(voice("fish", "ava"), voice("fish", "ben")),
            "system" to listOf(voice("system", "en-us")),
        )
    }

    @Test fun defaultsToFishWithAKeyAndSelectsTheFirstVoice() = runTest {
        val vm = NarrationSetupViewModel(deps(true), 1, "hi")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(PROVIDER_FISH, s.provider)
        assertEquals("ava", s.selected?.id)
        assertTrue(s.voices is VoicesUi.Ready)
    }

    @Test fun withoutAKeyFishIsUnavailableAndSystemWorks() = runTest {
        val vm = NarrationSetupViewModel(deps(false), 1, "hi")
        advanceUntilIdle()
        assertEquals(PROVIDER_SYSTEM, vm.state.value.provider)
        assertEquals("en-us", vm.state.value.selected?.id)
        vm.setProvider(PROVIDER_FISH)
        advanceUntilIdle()
        assertEquals(VoicesUi.Unavailable(VoiceProblem.NoFishKey), vm.state.value.voices)
    }

    @Test fun estimateFollowsVoiceAndSpeedAfterADebounce() = runTest {
        val d = deps(true)
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        assertTrue((vm.state.value.estimate as EstimateUi.Ready).estimate.freeTier)
        vm.setSpeed(1.5f)
        vm.setSpeed(1.2f)
        advanceUntilIdle()
        assertEquals(1.2f, d.narration.estimateCalls.last().first.speed)
        vm.select(VoiceRef("fish", "ben", "Ben"))
        advanceUntilIdle()
        assertEquals("ben", d.narration.estimateCalls.last().first.voice.id)
    }

    @Test fun startPersistsTheChoiceAndRunsNarration() = runTest {
        val d = deps(true)
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        vm.setSpeed(1.2f)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, d.narration.started.size)
        assertEquals("ava", d.narration.started.single().voice.id)
        assertEquals(1.2f, d.settings.current.speed)
        assertEquals("ava", d.settings.current.voice?.id)
        d.narration.states.value = NarrationState.Running(1, 10, 100, 60)
        assertFalse(vm.state.value.canStart)
        vm.stop()
        assertEquals(listOf(1L), d.narration.stopped)
    }

    @Test fun networkFailureLeavesAMessageNotACrash() = runTest {
        val d = deps(true).apply { narration.voicesThrow = java.io.IOException("x") }
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        assertEquals(VoicesUi.Unavailable(VoiceProblem.Network), vm.state.value.voices)
        assertFalse(vm.state.value.canStart)
    }

    @Test fun previewEmitsAFilePath() = runTest {
        val d = deps(true)
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        val got = mutableListOf<String>()
        val job = launch { vm.previews.collect { got += it } }
        advanceUntilIdle()
        vm.preview(voice("fish", "ava"))
        advanceUntilIdle()
        assertEquals(listOf("/tmp/preview.mp3"), got)
        assertNull(vm.state.value.previewing)
        d.narration.previewThrows = java.io.IOException("x")
        vm.preview(voice("fish", "ava"))
        advanceUntilIdle()
        assertTrue(vm.state.value.previewFailed)
        job.cancel()
    }

    @Test fun durationSplit() {
        assertEquals(6 to 12, splitHoursMinutes(6 * 3600L + 12 * 60))
        assertEquals(0 to 1, splitHoursMinutes(45))
    }
}

class SettingsViewModelTest : VmTest() {
    @Test fun themeAndTogglesPersistThroughTheStore() = runTest {
        val d = FakeDeps()
        val vm = SettingsViewModel(d)
        vm.setSeed(BbSeed.Saffron); vm.setDark(false); vm.setHaptics(false); vm.setMonthlyCapCents(500)
        vm.setOpenRouterModel("a/b:free")
        advanceUntilIdle()
        val s = d.settings.current
        assertEquals("Saffron", s.seed); assertFalse(s.dark); assertFalse(s.haptics)
        assertEquals(500, s.monthlyCapCents); assertEquals("a/b:free", s.openRouterModel)
        assertEquals(BbSeed.Saffron, vm.state.value.seed)
        assertFalse(vm.state.value.app.dark)
    }

    @Test fun unknownStoredSeedFallsBackToCobalt() = runTest {
        val d = FakeDeps()
        d.settings.update { it.copy(seed = "Mystery") }
        assertEquals(BbSeed.Cobalt, SettingsViewModel(d).state.value.seed)
    }

    @Test fun keysAreVerifiedBeforeTheyAreSaved() = runTest {
        val d = FakeDeps().apply { fishOk = false }
        val vm = SettingsViewModel(d)
        vm.verifyAndSave(KeyKind.Fish, "nope")
        assertEquals(KeyCheck.Rejected, vm.state.value.fishCheck)
        assertNull(d.settings.fish)
        d.fishOk = true
        vm.verifyAndSave(KeyKind.Fish, "yes")
        assertEquals("yes", d.settings.fish)
        assertTrue(vm.state.value.fishSet)
        vm.removeKey(KeyKind.Fish)
        assertNull(d.settings.fish)
        assertFalse(vm.state.value.fishSet)
    }

    @Test fun budgetAndModelsLoadOnlyWithAnOpenRouterKey() = runTest {
        val d = FakeDeps().apply {
            budget = AiBudget(18, 50, true, "x/y:free"); models = listOf("x/y:free"); spent = 120
        }
        val vm = SettingsViewModel(d)
        advanceUntilIdle()
        assertNull(vm.state.value.budget)
        assertEquals(120, vm.state.value.spentCents)
        vm.verifyAndSave(KeyKind.OpenRouter, "k")
        advanceUntilIdle()
        assertEquals(18, vm.state.value.budget?.requestsLeftToday)
        assertEquals(listOf("x/y:free"), vm.state.value.models)
    }
}
