package com.example.tasks.shared.presentation

import com.example.tasks.shared.domain.VoiceWaveform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceWaveformTest {
    @Test
    fun downsamplesToMaxPerBucketNormalizedToThePeak() {
        // 80 levels → 40 buckets of 2; bucket i holds (2i, 2i+1): max is the second value.
        val levels = List(80) { if (it % 2 == 1) (it / 2 + 1) / 40f * 0.5f else 0f }
        val result = VoiceWaveform.downsample(levels)
        assertEquals(40, result.size)
        assertEquals(1f, result.last()) // the clip's peak normalizes to 1
        assertEquals(0.5f, result[19], 0.001f)
        assertTrue(result.all { it in 0.08f..1f })
    }

    @Test
    fun floorKeepsQuietBucketsVisible() {
        val levels = List(40) { if (it == 10) 1f else 0f }
        val result = VoiceWaveform.downsample(levels)
        assertEquals(1f, result[10])
        assertEquals(0.08f, result[0])
        assertEquals(0.08f, result[39])
    }

    @Test
    fun silenceIsAllFloor() {
        assertTrue(VoiceWaveform.downsample(List(500) { 0f }).all { it == 0.08f })
        assertTrue(VoiceWaveform.downsample(emptyList()).all { it == 0.08f })
    }

    @Test
    fun fewerLevelsThanBarsStillFillsAllBars() {
        val result = VoiceWaveform.downsample(listOf(0.2f, 0.4f, 0.8f))
        assertEquals(40, result.size)
        assertEquals(1f, result.max())
        assertEquals(0.25f, result.first()) // 0.2 / 0.8
    }

    @Test
    fun valuesAreRoundedToThreeDecimals() {
        val result = VoiceWaveform.downsample(listOf(0.123456f, 0.3333333f) + List(78) { 0f })
        assertTrue(result.all { (it * 1000).let { v -> v == kotlin.math.round(v) } })
    }
}
