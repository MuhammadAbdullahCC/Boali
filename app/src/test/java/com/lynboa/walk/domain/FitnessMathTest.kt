package com.lynboa.walk.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FitnessMathTest {
    @Test fun startsWithNoFabricatedSteps() {
        assertEquals(0, FitnessMath.stepDelta(null, null, 180000L, 100L))
        assertEquals(17, FitnessMath.stepDelta(180000L, 100L, 180017L, 200L))
    }
    @Test fun rebootCannotProducePhantomSteps() {
        assertEquals(0, FitnessMath.stepDelta(1000L, 500L, 250L, 40L))
        assertEquals(0, FitnessMath.stepDelta(1000L, 500L, 2000L, 40L))
        assertEquals(0, FitnessMath.stepDelta(1000L, 500L, 900L, 600L))
    }
    @Test fun progressiveGoalCaps() {
        val start = LocalDate.of(2026, 10, 30)
        assertEquals(7000, FitnessMath.dailyGoal(start, start))
        assertEquals(7100, FitnessMath.dailyGoal(start, start.plusDays(1)))
        assertEquals(12000, FitnessMath.dailyGoal(start, start.plusDays(100)))
    }
    @Test fun gpsJumpsIgnored() {
        assertNull(FitnessMath.acceptedSegmentMeters(32.063, 73.377, 1000,
            32.073, 73.377, 6000, 5f))
        assertNull(FitnessMath.acceptedSegmentMeters(32.063, 73.377, 1000,
            32.0631, 73.377, 6000, 80f))
    }
    @Test fun caloriesAreEstimatedNotSensorReadings() {
        assertTrue(FitnessMath.activeKcal(7000, 70, 75) > 0)
    }
}
