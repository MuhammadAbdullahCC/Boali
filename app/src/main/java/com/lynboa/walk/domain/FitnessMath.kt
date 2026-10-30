package com.lynboa.walk.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

/** Domain rules are independent of the Android UI and testable on the JVM. */
object FitnessMath {
    fun stepDelta(previousRaw: Long?, previousElapsedNanos: Long?,
                  raw: Long, elapsedNanos: Long): Int {
        // A reboot, sensor reset, or first observation must NOT invent steps.
        if (previousRaw == null || previousElapsedNanos == null) return 0
        if (raw < previousRaw || elapsedNanos <= previousElapsedNanos) return 0
        return (raw - previousRaw).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    fun dailyGoal(firstDay: LocalDate, today: LocalDate, startingGoal: Int = 7000,
                  increase: Int = 100, ceiling: Int = 12000): Int {
        val days = ChronoUnit.DAYS.between(firstDay, today).coerceAtLeast(0)
        return (startingGoal.toLong() + days * increase).coerceAtMost(ceiling.toLong()).toInt()
    }

    fun walkedKm(steps: Int, strideCm: Int): Double =
        steps.coerceAtLeast(0).toLong() * strideCm.coerceIn(35, 120).toLong() / 100_000.0

    /** Distance × body mass model: an estimate of ACTIVE walking kcal, not a medical reading. */
    fun activeKcal(steps: Int, strideCm: Int, weightKg: Int): Int =
        (walkedKm(steps, strideCm) * weightKg.coerceIn(30, 250) * 0.65).roundToInt()

    fun haversineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat = Math.toRadians(bLat - aLat)
        val lon = Math.toRadians(bLon - aLon)
        val q = sin(lat / 2).pow(2.0) + cos(Math.toRadians(aLat)) *
            cos(Math.toRadians(bLat)) * sin(lon / 2).pow(2.0)
        return 2 * 6_371_000 * asin(sqrt(q.coerceIn(0.0, 1.0)))
    }

    /** GPS noise doesn't add real walking distance. */
    fun acceptedSegmentMeters(aLat: Double, aLon: Double, aTime: Long,
                              bLat: Double, bLon: Double, bTime: Long,
                              accuracyMeters: Float): Double? {
        if (accuracyMeters > 35f || accuracyMeters <= 0f || bTime <= aTime) return null
        val distance = haversineMeters(aLat, aLon, bLat, bLon)
        val seconds = (bTime - aTime) / 1000.0
        if (distance > 150.0 && seconds > 45.0) return null // likely GPS reacquisition gap
        if (distance / seconds > 7.0) return null // car ride, jump or bad fix
        return if (distance >= 3.0) distance else 0.0
    }
}
