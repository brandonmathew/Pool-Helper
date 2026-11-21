package com.poolhelper.core

import kotlin.math.abs

/**
 * Calibration state and passive fitting.
 *
 * Locked requirement: if the app detects it is uncalibrated it calibrates
 * itself with NO human interaction. This file holds the profile model and
 * the pure fitting math; the app layer persists the profile and feeds it
 * observed trajectories from real games.
 */
data class CalibrationProfile(
    val physics: PhysicsConstants = PhysicsConstants(),
    /** Screen-proportion length of the aiming guideline for the equipped cue. */
    val guidelineLength: Double? = null,
    /** Aim drag gain: table-angle radians per screen-proportion dragged. */
    val aimDragGain: Double? = null,
    /** Power model: cue ball launch speed per unit of power-bar pull (0..1). */
    val powerGain: Double? = null,
    /** Confidence 0..1 per fitted part. */
    val physicsConfidence: Double = 0.0,
    val aimConfidence: Double = 0.0,
    val powerConfidence: Double = 0.0,
) {
    /** Overall readiness: the bot plays conservatively below this. */
    fun isCalibrated(): Boolean =
        physicsConfidence >= 0.5 && aimConfidence >= 0.5 && powerConfidence >= 0.5

    /**
     * A materially different guideline length means the equipped cue changed
     * (Aim stat), so aim and power calibration for the old cue is stale.
     */
    fun withObservedGuideline(observed: Double): CalibrationProfile {
        val known = guidelineLength
        return if (known != null && abs(observed - known) > 0.02) {
            copy(guidelineLength = observed, aimConfidence = 0.0, powerConfidence = 0.0, aimDragGain = null, powerGain = null)
        } else {
            copy(guidelineLength = observed)
        }
    }
}

object TrajectoryFitter {

    /**
     * Fit rolling deceleration from a recorded free-rolling ball trajectory
     * (positions sampled at a fixed rate, ball rolling in a straight line
     * with no collisions). Under constant deceleration the path length
     * follows s(t) = v0 t - a t^2 / 2; a least-squares fit of cumulative
     * distance against t and t^2 recovers a.
     *
     * Returns null when the samples cannot support a fit.
     */
    fun fitRollingDeceleration(samples: List<Vec2>, samplePeriodSec: Double): Double? {
        if (samples.size < 8 || samplePeriodSec <= 0.0) return null
        // Cumulative path length.
        val s = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            s[i] = s[i - 1] + samples[i].distanceTo(samples[i - 1])
        }
        val totalDistance = s.last()
        if (totalDistance < 0.05) return null
        // Least squares for s = v0 * t - (a/2) * t^2 (no intercept term).
        var stt = 0.0; var st4 = 0.0; var st3 = 0.0; var sT = 0.0; var sT2 = 0.0
        for (i in samples.indices) {
            val t = i * samplePeriodSec
            val t2 = t * t
            stt += t2
            st4 += t2 * t2
            st3 += t2 * t
            sT += s[i] * t
            sT2 += s[i] * t2
        }
        // Normal equations for s = c1 * t + c2 * t^2 with c1 = v0, c2 = -a/2:
        // [stt st3; st3 st4] [c1; c2] = [sT; sT2]
        val det = stt * st4 - st3 * st3
        if (abs(det) < 1e-12) return null
        val c2 = (stt * sT2 - st3 * sT) / det
        val a = -2.0 * c2
        return if (a > 0.01 && a < 2.0) a else null
    }
}
