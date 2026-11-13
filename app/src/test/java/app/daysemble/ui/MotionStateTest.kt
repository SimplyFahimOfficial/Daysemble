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

    /** The picture on screen: alphas of the drawn tabs, bottom to top. */
    private fun TabFade.picture() = order.map { it to alphaOf(it) }

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
        assertThat(fade.order).containsExactly(Tab.TODAY)
        assertThat(fade.alphaOf(Tab.TODAY)).isEqualTo(1f)
        assertThat(fade.alphaOf(Tab.CALENDAR)).isEqualTo(0f)
        assertThat(fade.alphaOf(Tab.TASKS)).isEqualTo(0f)
    }

    /** Today to Calendar, then Tasks before the first fade has ended: Calendar stays as far as it had got. */
    @Test fun tabFade_aThirdTab_startsFromTheSamePicture() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val fade = TabFade(Tab.TODAY)
        val first = launch(clock) { fade.show(Tab.CALENDAR, m) }
        advanceTimeBy(60); runCurrent()
        val before = fade.picture()
        assertThat(before.map { it.first }).containsExactly(Tab.TODAY, Tab.CALENDAR).inOrder()
        assertThat(before[1].second).isGreaterThan(0f)
        assertThat(before[1].second).isLessThan(1f)
        first.cancel()
        launch(clock) { fade.show(Tab.TASKS, m) }
        runCurrent()
        // Tasks is on top and transparent; Today and Calendar are exactly as they were.
        assertThat(fade.picture().map { it.first }).containsExactly(Tab.TODAY, Tab.CALENDAR, Tab.TASKS).inOrder()
        assertThat(fade.picture().take(2)).isEqualTo(before)
        assertThat(fade.alphaOf(Tab.TASKS)).isEqualTo(0f)
        advanceTimeBy(1_000); runCurrent()
        assertThat(fade.order).containsExactly(Tab.TASKS)
        assertThat(fade.alphaOf(Tab.TASKS)).isEqualTo(1f)
    }

    /** Going back to a tab that is underneath fades the tabs above it away; nothing is reordered, so nothing jumps. */
    @Test fun tabFade_goingBackToATabUnderneath_fadesTheOnesAboveAway() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val fade = TabFade(Tab.TODAY)
        val first = launch(clock) { fade.show(Tab.CALENDAR, m) }
        advanceTimeBy(60); runCurrent()
        first.cancel()
        val second = launch(clock) { fade.show(Tab.TASKS, m) }
        advanceTimeBy(30); runCurrent()
        second.cancel()
        val before = fade.picture()
        val back = launch(clock) { fade.show(Tab.TODAY, m) }
        runCurrent()
        assertThat(fade.picture().map { it.first }).containsExactly(Tab.TODAY, Tab.CALENDAR, Tab.TASKS).inOrder()
        // At most one frame of progress, no jump in any layer.
        fade.picture().zip(before).forEach { (now, was) -> assertThat(now.second).isWithin(0.15f).of(was.second) }
        advanceTimeBy(1_000); runCurrent()
        assertThat(back.isCompleted).isTrue()
        assertThat(fade.order).containsExactly(Tab.TODAY)
        assertThat(fade.alphaOf(Tab.TODAY)).isEqualTo(1f)
    }

    @Test fun tabFade_withMotionOff_isAtOnce() = runTest {
        val fade = TabFade(Tab.TODAY)
        launch(TestMonotonicFrameClock(this)) { fade.show(Tab.TASKS, DsMotion(reduced = true)) }
        runCurrent()
        assertThat(fade.order).containsExactly(Tab.TASKS)
        assertThat(fade.alphaOf(Tab.TASKS)).isEqualTo(1f)
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

    /** The views that are not on screen are laid out with the value of the last rest, not with every frame of the morph. */
    @Test fun morph_restValue_changesOnlyWhenTheMorphEnds() = runTest {
        val clock = TestMonotonicFrameClock(this)
        val morph = MorphState(0f).apply { travel = 100f }
        assertThat(morph.atRest).isTrue()
        val settle = launch(clock) { morph.settle(1f, 0f, tween(400)) }
        advanceTimeBy(150); runCurrent()
        assertThat(morph.atRest).isFalse()
        assertThat(morph.value).isGreaterThan(0f)
        assertThat(morph.restValue).isEqualTo(0f)
        advanceTimeBy(1_000); runCurrent()
        assertThat(settle.isCompleted).isTrue()
        assertThat(morph.atRest).isTrue()
        assertThat(morph.restValue).isEqualTo(1f)
        // An interrupted settle does not become the rest: a drag takes over half way.
        val back = launch(clock) { morph.settle(0f, 0f, tween(1_000)) }
        advanceTimeBy(300); runCurrent()
        launch(clock) { morph.drag(MutePriorityUserInput) { dragBy(10f) } }
        advanceTimeBy(100); runCurrent()
        assertThat(back.isCompleted).isTrue()
        assertThat(morph.restValue).isEqualTo(1f)
        assertThat(morph.dragging).isFalse() // the drag block has ended; the release settles
    }

    @Test fun morph_jumpTo_isTheNewRest() {
        val morph = MorphState(1f)
        morph.jumpTo(0f)
        assertThat(morph.value).isEqualTo(0f)
        assertThat(morph.restValue).isEqualTo(0f)
        assertThat(morph.atRest).isTrue()
    }

    /** Both views are at half where they trade places, so the picture is the same just before and after. */
    @Test fun morphFade_isOnePathThroughBothHalves_withNoGap() {
        val f = app.daysemble.ui.calendar.MorphFade
        assertThat(f.progress(0f)).isEqualTo(0f)
        assertThat(f.progress(f.START)).isEqualTo(0f)
        assertThat(f.progress(f.MIDDLE)).isWithin(1e-6f).of(0.5f)
        assertThat(f.progress(f.END)).isEqualTo(1f)
        assertThat(f.progress(1f)).isEqualTo(1f)
        var last = 0f
        for (i in 0..100) { val v = f.progress(i / 100f); assertThat(v).isAtLeast(last); last = v }
        // Top view's visibility is progress (week) or 1 - progress (list); the layer under it is whole: the
        // share of the area covered by the two never falls under one half at any step.
        for (i in 0..100) {
            val s = f.progress(i / 100f)
            val top = if (i / 100f >= f.MIDDLE) s else 1f - s
            assertThat(top).isAtLeast(0.5f - 1e-6f)
        }
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
