package app.daysemble.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import app.daysemble.ui.calendar.MorphState
import app.daysemble.ui.components.checkScale
import app.daysemble.ui.theme.DsMotion
import app.daysemble.ui.today.DaySwipeState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The owners of animated values converge to the latest input, however it is interrupted: the tab
 * cross-fade, the Calendar's Month/Week morph, the Today day swipe and the check circle's pop.
 * They run on a virtual frame clock, so they prove the logic, not how it feels.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MotionStateTest {
    private val m = DsMotion()

    // ---- Tab cross-fade ---------------------------------------------------------------------

    @Test fun tabFade_endsOnTheLastTab_afterAnySequenceOfTaps() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val fade = TabFade(Tab.TODAY)
        var job = launch(clock) { fade.show(Tab.CALENDAR, m) }
        advanceTimeBy(50); runCurrent()
        job.cancel(); job = launch(clock) { fade.show(Tab.TASKS, m) }
        advanceTimeBy(30); runCurrent()
        job.cancel(); job = launch(clock) { fade.show(Tab.TODAY, m) }
        advanceTimeBy(1_000); runCurrent()
        assertThat(job.isCompleted).isTrue()
        assertThat(fade.top).isEqualTo(Tab.TODAY)
        assertThat(fade.base).isEqualTo(Tab.TODAY)
        assertThat(fade.alphaOf(Tab.TODAY, Tab.TODAY)).isEqualTo(1f)
    }

    @Test fun tabFade_goingBackToTheTabUnderneath_keepsThePictureContinuous() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val fade = TabFade(Tab.TODAY)
        val job = launch(clock) { fade.show(Tab.TASKS, m) }
        advanceTimeBy(60); runCurrent()
        // The picture is Tasks at `a` over Today.
        val a = fade.alphaOf(Tab.TASKS, Tab.TASKS)
        assertThat(a).isGreaterThan(0f)
        assertThat(a).isLessThan(1f)
        job.cancel()
        val back = launch(clock) { fade.show(Tab.TODAY, m) }
        runCurrent()
        // The same picture: Today at 1 - a over Tasks, which is now opaque underneath. No jump.
        assertThat(fade.base).isEqualTo(Tab.TASKS)
        assertThat(fade.alphaOf(Tab.TODAY, Tab.TODAY)).isWithin(0.05f).of(1f - a)
        assertThat(fade.alphaOf(Tab.TASKS, Tab.TODAY)).isEqualTo(1f)
        advanceTimeBy(1_000); runCurrent()
        assertThat(back.isCompleted).isTrue()
        assertThat(fade.alphaOf(Tab.TODAY, Tab.TODAY)).isEqualTo(1f)
    }

    @Test fun tabFade_withMotionOff_isAtOnce() = runTest {
        val fade = TabFade(Tab.TODAY)
        launch(TestMonotonicFrameClock(this)) { fade.show(Tab.TASKS, DsMotion(reduced = true)) }
        runCurrent()
        assertThat(fade.top).isEqualTo(Tab.TASKS)
        assertThat(fade.alphaOf(Tab.TASKS, Tab.TASKS)).isEqualTo(1f)
    }

    // ---- Month / Week morph -----------------------------------------------------------------

    @Test fun morph_aDragStopsASettle_andTheSettleStartsFromTheDraggedValue() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val morph = MorphState(0f).apply { travel = 100f }
        val settle = launch(clock) { morph.settle(1f, 0f, tween(1_000)) }
        advanceTimeBy(300); runCurrent()
        assertThat(morph.value).isGreaterThan(0f)
        // The finger takes the grid: the settle ends at once and the value stays where the finger puts it.
        launch(clock) { morph.drag(MutePriorityUserInput) { dragBy(-10f) } }
        advanceTimeBy(100); runCurrent()
        assertThat(settle.isCompleted).isTrue()
        val held = morph.value
        advanceTimeBy(2_000); runCurrent()
        assertThat(morph.value).isEqualTo(held)
        // Released: it settles from there with no jump.
        launch(clock) { morph.settle(0f, 0f, tween(500)) }
        runCurrent()
        assertThat(morph.value).isWithin(0.02f).of(held)
        advanceTimeBy(1_000); runCurrent()
        assertThat(morph.value).isEqualTo(0f)
    }

    @Test fun morph_deltasAreAppliedInOrder_andClamped() = runTest {
        val morph = MorphState(0f).apply { travel = 100f }
        // Dragging up (negative) grows the value; ten events of 20 px are two full travels.
        repeat(10) { morph.dispatchRawDelta(-20f) }
        assertThat(morph.value).isEqualTo(1f)
        repeat(3) { morph.dispatchRawDelta(10f) }
        assertThat(morph.value).isWithin(1e-4f).of(0.7f)
    }

    @Test fun morph_aRepeatedRequestForTheSameTarget_doesNotRestartTheSettle() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val morph = MorphState(0f)
        val first = launch(clock) { morph.settle(1f, 0f, tween(1_000)) }
        advanceTimeBy(200); runCurrent()
        val second = launch(clock) { morph.settle(1f, 0f, tween(1_000)) }
        runCurrent()
        assertThat(second.isCompleted).isTrue()
        assertThat(first.isActive).isTrue()
        advanceTimeBy(1_000); runCurrent()
        assertThat(morph.value).isEqualTo(1f)
    }

    @Test fun morph_snapTo_stopsASettleAndJumpsToTheTarget() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val morph = MorphState(1f)
        val settle = launch(clock) { morph.settle(0f, 0f, tween(1_000)) }
        advanceTimeBy(200); runCurrent()
        launch(clock) { morph.snapTo(0f) }
        advanceTimeBy(50); runCurrent()
        assertThat(settle.isCompleted).isTrue()
        // TestMonotonicFrameClock still runs the frame callback of an interrupted animation (the
        // Android clock removes it), which can land after the jump; jump again once it has.
        launch(clock) { morph.snapTo(0f) }
        advanceTimeBy(50); runCurrent()
        assertThat(morph.value).isEqualTo(0f)
        advanceTimeBy(1_000); runCurrent()
        assertThat(morph.value).isEqualTo(0f)
        // And a later settle to the other end is not mistaken for the one that was interrupted.
        launch(clock) { morph.settle(1f, 0f, tween(100)) }
        advanceTimeBy(500); runCurrent()
        assertThat(morph.value).isEqualTo(1f)
    }

    // ---- Today day swipe --------------------------------------------------------------------

    @Test fun daySwipe_aCommittedSwipe_entersFromTheFarSideAndSettlesAtRest() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val swipe = DaySwipeState()
        swipe.dispatchRawDelta(-300f / DaySwipeState.RESISTANCE) // dragged 300 px toward the start edge
        assertThat(swipe.offset).isWithin(0.01f).of(-300f)
        val seen = mutableListOf<Float>()
        val job = launch(clock) { swipe.glide(m.snappy(), enterFrom = 40f, arrived = { true }) }
        repeat(60) { advanceTimeBy(16); runCurrent(); seen += swipe.offset }
        assertThat(job.isCompleted).isTrue()
        // It never goes back across the old position: from the far side (+) to rest, with the spring's small overshoot.
        assertThat(seen.min()).isAtLeast(-2f)
        assertThat(seen.max()).isAtMost(40.5f)
        assertThat(swipe.offset).isEqualTo(0f)
    }

    @Test fun daySwipe_ifTheNewDayNeverArrives_theOldOneSpringsBackFromWhereItIs() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val swipe = DaySwipeState()
        swipe.dispatchRawDelta(-200f / DaySwipeState.RESISTANCE)
        launch(clock) { swipe.glide(m.spring(), enterFrom = 40f, arrived = { false }) }
        runCurrent()
        assertThat(swipe.offset).isWithin(1f).of(-200f)
        advanceTimeBy(2_000); runCurrent()
        assertThat(swipe.offset).isEqualTo(0f)
    }

    @Test fun daySwipe_grabbingTheContentMidGlide_stopsTheGlide() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val swipe = DaySwipeState()
        swipe.dispatchRawDelta(100f)
        val glide = launch(clock) { swipe.glide(tween(1_000)) }
        advanceTimeBy(300); runCurrent()
        launch(clock) { swipe.drag(MutePriorityUserInput) { dragBy(10f) } }
        advanceTimeBy(100); runCurrent()
        assertThat(glide.isCompleted).isTrue()
        val held = swipe.offset
        advanceTimeBy(2_000); runCurrent()
        assertThat(swipe.offset).isEqualTo(held)
    }

    // ---- Check circle -----------------------------------------------------------------------

    @Test fun checkScale_isOneWhereTheFillRests_soNoToggleSequenceCanLeaveItScaled() {
        assertThat(checkScale(0f, motion = true)).isEqualTo(1f)
        assertThat(checkScale(1f, motion = true)).isWithin(1e-6f).of(1f)
        assertThat(checkScale(0.5f, motion = true)).isGreaterThan(1f)
        assertThat(checkScale(0.5f, motion = false)).isEqualTo(1f)
    }

    private companion object {
        val MutePriorityUserInput = MutatePriority.UserInput
    }
}
