package app.kepler.feature.library

import app.kepler.core.model.NarrationState
import app.kepler.core.model.PaidSwitchStatus
import app.kepler.core.model.PauseReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedCopyTest {
    @Test fun everyEngineReasonHasItsOwnKindAndCopy() {
        assertEquals(PausedKind.PaidConfirm, pausedKind(PauseReason.PAID_CONFIRM.code))
        assertEquals(PausedKind.BookCap, pausedKind(PauseReason.BOOK_CAP.code))
        assertEquals(PausedKind.MonthlyCap, pausedKind(PauseReason.MONTHLY_CAP.code))
        assertEquals(PausedKind.Offline, pausedKind(PauseReason.OFFLINE.code))
        assertEquals(PausedKind.System, pausedKind(PauseReason.SYSTEM_STOP.code))
        assertEquals(PausedKind.System, pausedKind("something_new"))
        val copies = PauseReason.entries.map { pausedReasonRes(it.code) }
        assertEquals(copies.size, copies.toSet().size)
    }

    @Test fun capTextBecomesCents() {
        assertEquals(500, parseCents("5"))
        assertEquals(750, parseCents("$7.50"))
        assertEquals(750, parseCents("7,5"))
        assertEquals(0, parseCents("  "))
        assertNull(parseCents("abc"))
        assertNull(parseCents("-3"))
        assertEquals("$5.00", moneyText(500))
    }

    @Test fun noticeShowsOnlyNearAndAfterTheSwitch() {
        assertFalse(PaidUi(PaidSwitchStatus.FREE_ACTIVE).showsNotice)
        assertTrue(PaidUi(PaidSwitchStatus.SWITCHING_SOON(3)).showsNotice)
        assertTrue(PaidUi(PaidSwitchStatus.PAID, confirmed = false).showsNotice)
        assertFalse(PaidUi(PaidSwitchStatus.PAID, confirmed = true).showsNotice)
    }
}

class NarrationSafetyViewModelTest : VmTest() {
    private fun deps(): FakeDeps = FakeDeps(library = FakeLibrary(listOf(book(1)))).apply {
        settings.fish = "k"
        narration.voicesByProvider = mapOf("fish" to listOf(voice("fish", "ava")), "system" to listOf(voice("system", "en-us")))
    }

    @Test fun paidConfirmationRecordsTheOkAndRestartsTheJob() = runTest {
        val d = deps().apply { fakeControls.status = PaidSwitchStatus.PAID; fakeControls.monthSpent = 120.0; fakeControls.bookSpent = 40.0 }
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        d.narration.states.value = NarrationState.Paused(1, PauseReason.PAID_CONFIRM.code)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(PausedKind.PaidConfirm, pausedKind((s.narration as NarrationState.Paused).reason))
        assertEquals(120, s.paid.spentMonthCents)
        assertEquals(40, s.paid.spentBookCents)
        assertEquals(500, s.paid.monthlyCapCents)
        assertTrue(s.paid.showsNotice)
        assertTrue(d.narration.started.isEmpty())
        vm.confirmPaid()
        advanceUntilIdle()
        assertTrue(d.fakeControls.confirmed)
        assertEquals(1, d.narration.started.size)
        assertEquals("ava", d.narration.started.single().voice.id)
        assertFalse(vm.state.value.paid.showsNotice)
    }

    @Test fun capPausesExposeSpendAndTheEffectiveCap() = runTest {
        val d = deps().apply { fakeControls.monthSpent = 499.0; fakeControls.monthlyCap = 500 }
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        d.narration.states.value = NarrationState.Paused(1, PauseReason.MONTHLY_CAP.code)
        advanceUntilIdle()
        assertEquals(499, vm.state.value.paid.spentMonthCents)
        assertEquals(500, vm.state.value.paid.monthlyCapCents)
        d.narration.states.value = NarrationState.Paused(1, PauseReason.BOOK_CAP.code)
        advanceUntilIdle()
        assertEquals(300, vm.state.value.paid.bookCapCents)
    }

    @Test fun offlineAndSystemPausesResumeWithTheChosenVoice() = runTest {
        val d = deps()
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        for (r in listOf(PauseReason.OFFLINE, PauseReason.SYSTEM_STOP)) {
            d.narration.states.value = NarrationState.Paused(1, r.code)
            advanceUntilIdle()
            vm.resume()
            advanceUntilIdle()
        }
        assertEquals(2, d.narration.started.size)
    }

    @Test fun switchingSoonNoticeCarriesDaysAndPrice() = runTest {
        val d = deps().apply { fakeControls.status = PaidSwitchStatus.SWITCHING_SOON(4) }
        val vm = NarrationSetupViewModel(d, 1, "hi")
        advanceUntilIdle()
        val paid = vm.state.value.paid
        assertTrue(paid.showsNotice)
        assertEquals(PaidSwitchStatus.SWITCHING_SOON(4), paid.switch)
        assertEquals("$0.84", hourlyText(paid.centsPerHour))
    }

    @Test fun hubDeleteStopsPlaybackThenCancelsThenDeletes() = runTest {
        val d = deps().apply { fakePlayback = FakePlayback(current = 1) }
        val events = mutableListOf<String>()
        d.fakePlayback!!.onPause = { events += "pause" }
        d.fakeControls.onCancel = { events += "cancel" }
        d.library.onDelete = { events += "delete" }
        val vm = HubViewModel(d, 1)
        var done = false
        vm.remove { done = true }
        advanceUntilIdle()
        assertEquals(listOf("pause", "cancel", "delete"), events)
        assertTrue(done)
    }

    @Test fun deletingAnotherBookLeavesPlaybackAlone() = runTest {
        val d = deps().apply { fakePlayback = FakePlayback(current = 2) }
        d.removeBook(1)
        assertTrue(d.fakePlayback!!.pauses.isEmpty())
        assertEquals(listOf(1L), d.fakeControls.cancelled)
        assertEquals(listOf(1L), d.library.deleted)
    }

    @Test fun shelfDeleteCancelsBeforeDeletingAndSurvivesACancelFailure() = runTest {
        val d = FakeDeps(library = FakeLibrary(listOf(book(1), book(2))))
        val events = mutableListOf<String>()
        d.fakeControls.onCancel = { events += "cancel" }
        d.library.onDelete = { events += "delete" }
        d.fakeControls.cancelThrows = IllegalStateException("job mid-flight")
        val vm = LibraryViewModel(d)
        vm.requestDelete(book(1)); vm.confirmDelete()
        assertEquals(listOf("cancel", "delete"), events)
        assertEquals(listOf(1L), d.library.deleted)
        assertEquals(listOf(2L), d.library.books.first().map { it.id })
    }

    @Test fun settingsMonthlyCapTextSavesCentsAndZeroMeansDefault() = runTest {
        val d = deps()
        val vm = SettingsViewModel(d)
        advanceUntilIdle()
        assertTrue(vm.setMonthlyCapText("7.50"))
        advanceUntilIdle()
        assertEquals(750, d.settings.current.monthlyCapCents)
        assertFalse(vm.setMonthlyCapText("lots"))
        assertEquals(750, d.settings.current.monthlyCapCents)
        vm.setMonthlyCapCents(0)
        advanceUntilIdle()
        assertEquals(0, d.settings.current.monthlyCapCents)
        assertEquals(500, vm.state.value.paid.monthlyCapCents)
    }
}
