package com.example.tasks.shared.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionSpecTest {
    private val tol = 1e-3f

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected but was $actual")

    private fun check(
        t: Long,
        exit: Long?,
        card: Float,
        disc: Float,
        check: Float,
        wordAlpha: Float,
        wordOffset: Float,
        contentAlpha: Float,
        contentScale: Float,
        reveal: Float,
        finished: Boolean,
    ) {
        val f = MotionSpec.Splash.frameAt(t, exit, reduceMotion = false)
        val at = "t=$t"
        near(card, f.cardTrim, "$at cardTrim")
        near(disc, f.discScale, "$at discScale")
        near(check, f.checkTrim, "$at checkTrim")
        near(wordAlpha, f.wordmarkAlpha, "$at wordmarkAlpha")
        near(wordOffset, f.wordmarkOffsetY, "$at wordmarkOffsetY")
        near(contentAlpha, f.contentAlpha, "$at contentAlpha")
        near(contentScale, f.contentScale, "$at contentScale")
        near(reveal, f.revealFraction, "$at revealFraction")
        near(1f, f.overlayAlpha, "$at overlayAlpha")
        assertEquals(finished, f.finished, "$at finished")
    }

    @Test fun table_t0() = check(0, null, 0f, 0f, 0f, 0f, 12f, 1f, 1f, 0f, false)

    @Test fun table_t400() = check(400, null, 0.9535f, 0.2942f, 0f, 0f, 12f, 1f, 1f, 0f, false)

    @Test fun table_t820() = check(820, null, 1f, 0.9915f, 0.9535f, 0.3506f, 7.793f, 1f, 1f, 0f, false)

    @Test fun table_t1300() = check(1300, null, 1f, 0.9999f, 1f, 1f, 0f, 1f, 1f, 0f, false)

    @Test fun table_t1820_exiting() = check(1820, 1500, 1f, 1f, 1f, 1f, 0f, 0f, 1.06f, 0.8522f, false)

    @Test fun table_t2100_finished() = check(2100, 1500, 1f, 1f, 1f, 1f, 0f, 0f, 1.06f, 1f, true)

    @Test fun discPeaksNear590ms() {
        var bestT = 0L
        var best = 0f
        for (t in 340L..1000L) {
            val v = MotionSpec.Splash.frameAt(t, null, false).discScale
            if (v > best) {
                best = v
                bestT = t
            }
        }
        near(1.0948f, best, "peak")
        assertTrue(bestT in 585L..595L, "peak at $bestT")
    }

    @Test fun springStiffness() = near(246.74f, MotionSpec.Splash.discSpring.stiffness, "stiffness").also {
        assertTrue(abs(MotionSpec.Splash.discSpring.stiffness - 246.74f) < 0.01f)
    }

    @Test fun springIsZeroBeforeStart() {
        assertEquals(0f, MotionSpec.Splash.discSpring.valueAt(0f))
        assertEquals(0f, MotionSpec.Splash.discSpring.valueAt(-1f))
    }

    @Test fun emphasizedSanity() {
        val v = MotionSpec.emphasized.transform(0.5f)
        assertTrue(v > 0.85f && v < 0.92f, "emphasized(0.5)=$v")
        assertEquals(0f, MotionSpec.emphasized.transform(-1f))
        assertEquals(1f, MotionSpec.emphasized.transform(2f))
    }

    @Test fun discCenterInMark() {
        val (x, y) = MotionSpec.Splash.discCenterInMark()
        // Handoff text says (80.92, 51.10), but its own formula (604-176)*132/698 gives 80.9398;
        // we follow the formula and keep the handoff's rounded figure within 0.05.
        near(428f * 132f / 698f, x, "x")
        near(270f * 120f / 634f, y, "y")
        assertTrue(abs(x - 80.92f) < 0.05f && abs(y - 51.10f) < 0.05f, "($x, $y)")
    }

    @Test fun exitStart_cases() {
        assertEquals(1500L, MotionSpec.Splash.exitStartMs(300, 300))
        assertEquals(1800L, MotionSpec.Splash.exitStartMs(1800, 1800))
        assertEquals(2500L, MotionSpec.Splash.exitStartMs(4000, 4000))
        assertNull(MotionSpec.Splash.exitStartMs(null, 1000))
        assertEquals(2500L, MotionSpec.Splash.exitStartMs(null, 2500))
    }

    @Test fun reducedMotion_entryStaticAndOverlayFullBeforeExit() {
        val f = MotionSpec.Splash.frameAt(1300, 1500, reduceMotion = true)
        assertEquals(1f, f.cardTrim)
        assertEquals(1f, f.discScale)
        assertEquals(1f, f.checkTrim)
        assertEquals(1f, f.wordmarkAlpha)
        assertEquals(0f, f.wordmarkOffsetY)
        assertEquals(1f, f.captionAlpha)
        assertEquals(0f, f.captionOffsetY)
        assertEquals(1f, f.overlayAlpha)
        assertFalse(f.finished)
        val first = MotionSpec.Splash.frameAt(0, null, reduceMotion = true)
        assertEquals(1f, first.cardTrim)
        assertEquals(1f, first.discScale)
    }

    @Test fun reducedMotion_fadesLinearlyAndFinishes() {
        val mid = MotionSpec.Splash.frameAt(1600, 1500, reduceMotion = true)
        near(0.5f, mid.overlayAlpha, "overlayAlpha")
        assertEquals(0f, mid.revealFraction)
        assertFalse(mid.finished)
        val end = MotionSpec.Splash.frameAt(1700, 1500, reduceMotion = true)
        assertEquals(0f, end.overlayAlpha)
        assertTrue(end.finished)
    }
}
