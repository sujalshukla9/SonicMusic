package com.sonicmusic.app.features.player.service

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests verifying Apple Music–Style Audio Crossfade algorithms,
 * equal-power curves, linear fallback, and short-track duration adaptation.
 */
class CrossfadeEngineTest {

    @Test
    fun testEqualPowerCurves_boundaryAndMidpointValues() {
        // At t = 0.0 (start of transition): Outgoing = 1.0, Incoming = 0.0
        val outStart = CrossfadeEngine.calculateOutgoingGain(0.0f, CrossfadeCurve.EQUAL_POWER)
        val inStart = CrossfadeEngine.calculateIncomingGain(0.0f, CrossfadeCurve.EQUAL_POWER)
        assertEquals(1.0f, outStart, 0.001f)
        assertEquals(0.0f, inStart, 0.001f)

        // At t = 0.5 (midpoint): Both should be cos(pi/4) = sin(pi/4) ~ 0.7071
        val outMid = CrossfadeEngine.calculateOutgoingGain(0.5f, CrossfadeCurve.EQUAL_POWER)
        val inMid = CrossfadeEngine.calculateIncomingGain(0.5f, CrossfadeCurve.EQUAL_POWER)
        val expectedMidpoint = 0.7071068f
        assertEquals(expectedMidpoint, outMid, 0.001f)
        assertEquals(expectedMidpoint, inMid, 0.001f)

        // At t = 1.0 (end of transition): Outgoing = 0.0, Incoming = 1.0
        val outEnd = CrossfadeEngine.calculateOutgoingGain(1.0f, CrossfadeCurve.EQUAL_POWER)
        val inEnd = CrossfadeEngine.calculateIncomingGain(1.0f, CrossfadeCurve.EQUAL_POWER)
        assertEquals(0.0f, outEnd, 0.001f)
        assertEquals(1.0f, inEnd, 0.001f)
    }

    @Test
    fun testEqualPowerCurves_preservesConstantAcousticPower() {
        // Equal power requires: V_out(t)^2 + V_in(t)^2 == 1.0 across all steps
        val steps = 100
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val out = CrossfadeEngine.calculateOutgoingGain(t, CrossfadeCurve.EQUAL_POWER)
            val incoming = CrossfadeEngine.calculateIncomingGain(t, CrossfadeCurve.EQUAL_POWER)

            val totalPower = (out * out) + (incoming * incoming)
            assertEquals("Acoustic power must sum to 1.0 at progress $t", 1.0f, totalPower, 0.005f)
        }
    }

    @Test
    fun testLinearCurves_boundaryAndMidpointValues() {
        val outStart = CrossfadeEngine.calculateOutgoingGain(0.0f, CrossfadeCurve.LINEAR)
        val inStart = CrossfadeEngine.calculateIncomingGain(0.0f, CrossfadeCurve.LINEAR)
        assertEquals(1.0f, outStart, 0.001f)
        assertEquals(0.0f, inStart, 0.001f)

        val outMid = CrossfadeEngine.calculateOutgoingGain(0.5f, CrossfadeCurve.LINEAR)
        val inMid = CrossfadeEngine.calculateIncomingGain(0.5f, CrossfadeCurve.LINEAR)
        assertEquals(0.5f, outMid, 0.001f)
        assertEquals(0.5f, inMid, 0.001f)

        val outEnd = CrossfadeEngine.calculateOutgoingGain(1.0f, CrossfadeCurve.LINEAR)
        val inEnd = CrossfadeEngine.calculateIncomingGain(1.0f, CrossfadeCurve.LINEAR)
        assertEquals(0.0f, outEnd, 0.001f)
        assertEquals(1.0f, inEnd, 0.001f)
    }

    @Test
    fun testInputCoercion_handlesOutOfBoundsProgress() {
        // Progress < 0.0 should be clamped to 0.0
        val outNegative = CrossfadeEngine.calculateOutgoingGain(-0.5f, CrossfadeCurve.EQUAL_POWER)
        val inNegative = CrossfadeEngine.calculateIncomingGain(-0.5f, CrossfadeCurve.EQUAL_POWER)
        assertEquals(1.0f, outNegative, 0.001f)
        assertEquals(0.0f, inNegative, 0.001f)

        // Progress > 1.0 should be clamped to 1.0
        val outOver = CrossfadeEngine.calculateOutgoingGain(1.8f, CrossfadeCurve.EQUAL_POWER)
        val inOver = CrossfadeEngine.calculateIncomingGain(1.8f, CrossfadeCurve.EQUAL_POWER)
        assertEquals(0.0f, outOver, 0.001f)
        assertEquals(1.0f, inOver, 0.001f)
    }

    @Test
    fun testEffectiveDuration_adaptsForNormalSong() {
        // Standard 210-second song with 10s crossfade configured
        val songDuration = 210_000L
        val configured = 10_000L
        val effective = CrossfadeEngine.calculateEffectiveDurationMs(songDuration, configured)
        assertEquals(10_000L, effective)
    }

    @Test
    fun testEffectiveDuration_capsAtOneThirdForShortSong() {
        // 12-second song with 8-second crossfade configured
        // Max allowed is 12 / 3 = 4 seconds
        val songDuration = 12_000L
        val configured = 8_000L
        val effective = CrossfadeEngine.calculateEffectiveDurationMs(songDuration, configured)
        assertEquals(4_000L, effective)
    }

    @Test
    fun testEffectiveDuration_disablesForExtremelyShortSong() {
        // 2.4-second soundbite
        // 2400 / 3 = 800ms < 1000ms threshold -> Disables crossfade (returns 0ms)
        val songDuration = 2_400L
        val configured = 5_000L
        val effective = CrossfadeEngine.calculateEffectiveDurationMs(songDuration, configured)
        assertEquals(0L, effective)
    }

    @Test
    fun testEffectiveDuration_handlesUnsetOrZeroDurations() {
        assertEquals(0L, CrossfadeEngine.calculateEffectiveDurationMs(C.TIME_UNSET, 5_000L))
        assertEquals(0L, CrossfadeEngine.calculateEffectiveDurationMs(0L, 5_000L))
        assertEquals(0L, CrossfadeEngine.calculateEffectiveDurationMs(180_000L, 0L))
        assertEquals(0L, CrossfadeEngine.calculateEffectiveDurationMs(-100L, 5_000L))
    }
}
