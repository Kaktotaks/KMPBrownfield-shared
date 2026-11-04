package com.example.tasks.shared.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** CSS-style cubic-bezier easing; transform() solves x→t by Newton (8 iterations) then returns y. Clamped to [0,1]. */
data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        val x = fraction.toDouble()
        val ax1 = x1.toDouble()
        val ay1 = y1.toDouble()
        val ax2 = x2.toDouble()
        val ay2 = y2.toDouble()
        var u = x
        repeat(NEWTON_ITERATIONS) {
            val v = 1 - u
            val cx = 3 * ax1 * u * v * v + 3 * ax2 * u * u * v + u * u * u - x
            val d = 3 * ax1 * v * v + 6 * (ax2 - ax1) * u * v + 3 * (1 - ax2) * u * u
            if (abs(d) < MIN_DERIVATIVE) return@repeat
            u -= cx / d
        }
        u = u.coerceIn(0.0, 1.0)
        val v = 1 - u
        return (3 * ay1 * u * v * v + 3 * ay2 * u * u * v + u * u * u).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val NEWTON_ITERATIONS = 8
        const val MIN_DERIVATIVE = 1e-6
    }
}

/** Under-damped spring, analytic solution from 0 to 1. SwiftUI .spring(response:dampingFraction:) / Compose spring(dampingRatio, stiffness). */
data class SpringParams(val dampingRatio: Float, val responseSeconds: Float) {
    /** (2π / responseSeconds)² → 246.74 for 0.4 s. */
    val stiffness: Float
        get() {
            val w = 2.0 * PI / responseSeconds
            return (w * w).toFloat()
        }

    /** 0 for seconds <= 0; 1 - e^(-ζωt)(cos ωd t + ζω/ωd sin ωd t). */
    fun valueAt(seconds: Float): Float {
        if (seconds <= 0f) return 0f
        val z = dampingRatio.toDouble()
        val wn = 2.0 * PI / responseSeconds
        val wd = wn * sqrt(1 - z * z)
        val t = seconds.toDouble()
        return (1 - exp(-z * wn * t) * (cos(wd * t) + (z * wn / wd) * sin(wd * t))).toFloat()
    }
}

data class SplashFrame(
    val cardTrim: Float, // 0..1 stroke trim of the card outline
    val discScale: Float, // spring, overshoots to ≈1.095
    val checkTrim: Float, // 0..1
    val wordmarkAlpha: Float,
    val wordmarkOffsetY: Float, // pt/dp, 12 → 0
    val captionAlpha: Float,
    val captionOffsetY: Float, // 8 → 0
    val contentAlpha: Float, // mark + texts, multiplies the alphas above, 1 → 0 on exit
    val contentScale: Float, // 1 → 1.06 on exit, pivot = disc centre
    val revealFraction: Float, // 0..1 of the hole radius (multiply by the distance to the farthest corner)
    val overlayAlpha: Float, // only reduced motion fades the whole overlay
    val finished: Boolean, // remove the overlay
)

object MotionSpec {
    /** The one easing used across the app: cubic-bezier(0.2, 0, 0, 1). */
    val emphasized = CubicBezier(0.2f, 0f, 0f, 1f)

    object Splash {
        const val CARD_START_MS = 120L
        const val CARD_DURATION_MS = 420L
        const val DISC_START_MS = 340L
        val discSpring = SpringParams(dampingRatio = 0.6f, responseSeconds = 0.4f)
        const val CHECK_START_MS = 620L
        const val CHECK_DURATION_MS = 300L
        const val WORDMARK_START_MS = 760L
        const val WORDMARK_DURATION_MS = 400L
        const val CAPTION_START_MS = 860L
        const val CAPTION_DURATION_MS = 400L
        const val MIN_HOLD_MS = 1500L
        const val MAX_HOLD_MS = 2500L
        const val CONTENT_FADE_MS = 200L
        const val REVEAL_DELAY_MS = 80L
        const val REVEAL_DURATION_MS = 520L
        const val REDUCED_FADE_MS = 200L
        const val WORDMARK_OFFSET = 12f
        const val CAPTION_OFFSET = 8f
        const val EXIT_SCALE = 1.06f

        // Geometry (pt on iOS, dp on Android)
        const val MARK_WIDTH = 132f
        const val MARK_HEIGHT = 120f

        /** Mark 120 + 28 + wordmark 38 + 6 + caption 20, block centred on screen. */
        const val BLOCK_HEIGHT = 212f
        const val WORDMARK_TOP = 148f // from the top of the block
        const val CAPTION_TOP = 192f // from the top of the block

        // The mark is the app icon art (1024 space) cropped to this viewBox:
        const val VIEWBOX_X = 176f
        const val VIEWBOX_Y = 160f
        const val VIEWBOX_W = 698f
        const val VIEWBOX_H = 634f

        // Icon art in 1024 space:
        //   card: rounded rect x 196, y 334, 440×440, corner 124, stroke 40, #FFFFFF, no fill
        //   disc: circle centre (604, 430), r 250, #E3F140
        //   check: polyline (486,438) → (574,526) → (736,346), stroke 66, round caps + joins, #0A0A0A
        //   draw order: card, disc, check
        const val DISC_CX = 604f
        const val DISC_CY = 430f

        /** Disc centre inside the 132×120 mark box: (80.94, 51.10). */
        fun discCenterInMark(): Pair<Float, Float> =
            Pair(
                (DISC_CX - VIEWBOX_X) * MARK_WIDTH / VIEWBOX_W,
                (DISC_CY - VIEWBOX_Y) * MARK_HEIGHT / VIEWBOX_H,
            )

        /**
         * When the exit begins, or null while still holding.
         * readyAtMs = elapsed time when the list first left Loading (Loaded or Error), null if not yet.
         * Result = clamp(readyAtMs, MIN_HOLD_MS, MAX_HOLD_MS); if readyAtMs == null and elapsedMs >= MAX_HOLD_MS → MAX_HOLD_MS.
         */
        fun exitStartMs(readyAtMs: Long?, elapsedMs: Long): Long? =
            when {
                readyAtMs != null -> readyAtMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
                elapsedMs >= MAX_HOLD_MS -> MAX_HOLD_MS
                else -> null
            }

        /** Pure function of time — both apps draw exactly this. */
        fun frameAt(elapsedMs: Long, exitStartMs: Long?, reduceMotion: Boolean): SplashFrame {
            val t = elapsedMs
            fun seg(start: Long, duration: Long): Float =
                emphasized.transform(((t - start).toFloat() / duration).coerceIn(0f, 1f))

            var cardTrim = 1f
            var discScale = 1f
            var checkTrim = 1f
            var wordmarkAlpha = 1f
            var wordmarkOffsetY = 0f
            var captionAlpha = 1f
            var captionOffsetY = 0f
            if (!reduceMotion) {
                cardTrim = seg(CARD_START_MS, CARD_DURATION_MS)
                discScale = discSpring.valueAt((t - DISC_START_MS) / 1000f)
                checkTrim = seg(CHECK_START_MS, CHECK_DURATION_MS)
                val w = seg(WORDMARK_START_MS, WORDMARK_DURATION_MS)
                val c = seg(CAPTION_START_MS, CAPTION_DURATION_MS)
                wordmarkAlpha = w
                wordmarkOffsetY = WORDMARK_OFFSET * (1f - w)
                captionAlpha = c
                captionOffsetY = CAPTION_OFFSET * (1f - c)
            }

            var contentAlpha = 1f
            var contentScale = 1f
            var revealFraction = 0f
            var overlayAlpha = 1f
            var finished = false
            if (exitStartMs != null && t >= exitStartMs) {
                if (reduceMotion) {
                    overlayAlpha = 1f - ((t - exitStartMs).toFloat() / REDUCED_FADE_MS).coerceIn(0f, 1f)
                    finished = overlayAlpha <= 0f
                } else {
                    val k = seg(exitStartMs, CONTENT_FADE_MS)
                    contentAlpha = 1f - k
                    contentScale = 1f + (EXIT_SCALE - 1f) * k
                    revealFraction = seg(exitStartMs + REVEAL_DELAY_MS, REVEAL_DURATION_MS)
                    finished = revealFraction >= 1f
                }
            }

            return SplashFrame(
                cardTrim = cardTrim,
                discScale = discScale,
                checkTrim = checkTrim,
                wordmarkAlpha = wordmarkAlpha,
                wordmarkOffsetY = wordmarkOffsetY,
                captionAlpha = captionAlpha,
                captionOffsetY = captionOffsetY,
                contentAlpha = contentAlpha,
                contentScale = contentScale,
                revealFraction = revealFraction,
                overlayAlpha = overlayAlpha,
                finished = finished,
            )
        }
    }
}
