package es.iesvirgendelacaridad.etcp.audio

import org.junit.Assert.*
import org.junit.Test

class BalancedSegmentPlanTest {
    private fun plan(totalUs: Long, packetUs: Long = 20_000L, maxMinutes: Int = 45): List<Long> {
        val samples = ((totalUs + packetUs - 1) / packetUs).toInt()
        val boundaries = LongArray(samples + 1) { minOf(totalUs, it * packetUs) }
        val cuts = BalancedSegmentPlan.create(boundaries, maxMinutes)
        val durations = cuts.asList().zipWithNext { a, b -> b - a }
        assertEquals(totalUs, durations.sum())
        assertTrue(durations.all { it in 1..maxMinutes * 60_000_000L })
        assertTrue(cuts.all { boundaries.binarySearch(it) >= 0 })
        assertTrue(durations.max() - durations.min() <= packetUs * 2)
        return durations
    }

    @Test fun shortAudioProducesOnePart() {
        assertEquals(listOf(10_000_000L), plan(10_000_000L))
    }
    @Test fun exactlyFortyFiveMinutesProducesNoEmptyTail() {
        assertEquals(listOf(2_700_000_000L), plan(2_700_000_000L))
    }
    @Test fun justOverLimitIsBalancedInsteadOfLeavingTinyTail() {
        assertEquals(2, plan(2_700_000_001L).size)
    }
    @Test fun oneHourProducesTwoThirtyMinuteParts() {
        assertEquals(listOf(1_800_000_000L, 1_800_000_000L), plan(3_600_000_000L))
    }
    @Test fun twoHoursProducesThreeFortyMinuteParts() {
        assertEquals(listOf(2_400_000_000L, 2_400_000_000L, 2_400_000_000L), plan(7_200_000_000L))
    }
    @Test fun aacFramesRemainWholeAndBalanced() {
        assertEquals(3, plan(7_200_000_000L, 23_220L).size)
    }
    @Test fun exactMultipleWithUnalignedPacketsStillHonorsCap() {
        assertEquals(3, plan(5_400_000_000L, 64_000L).size)
    }
    @Test fun acceptsSmallerLimit() {
        assertEquals(listOf(1_800_000_000L, 1_800_000_000L), plan(3_600_000_000L, maxMinutes = 30))
    }
    @Test fun handlesNonzeroInitialTimestamp() {
        assertArrayEquals(longArrayOf(2_000L, 60_002_000L, 120_002_000L),
            BalancedSegmentPlan.create(longArrayOf(2_000L, 60_002_000L, 120_002_000L), 1))
    }
    @Test fun rejectsInvalidLimits() {
        for (limit in listOf(-1, 0, 46, Int.MAX_VALUE)) {
            try { BalancedSegmentPlan.create(longArrayOf(0L, 1L), limit); fail("Accepted $limit") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun rejectsEmptyAndInvalidTimelines() {
        for (boundaries in listOf(longArrayOf(), longArrayOf(0L), longArrayOf(-1L, 1L), longArrayOf(0L, 0L), longArrayOf(1L, 0L))) {
            try { BalancedSegmentPlan.create(boundaries); fail("Accepted invalid boundaries") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
