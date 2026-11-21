package com.poolhelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CalibrationTest {

    @Test
    fun `fitter recovers rolling deceleration from a simulated trajectory`() {
        val trueDecel = 0.20
        val sim = Simulator(PhysicsConstants(rollingDeceleration = trueDecel))
        val period = 1.0 / 120.0
        val result = sim.simulate(
            listOf(BallState(0, Vec2(-0.95, 0.0))),
            Vec2(0.55, 0.0),
            recordTrajectory = true,
            trajectoryPeriodSec = period,
        )
        assertTrue(result.cueTrajectory.size > 100, "samples: ${result.cueTrajectory.size}")
        val fitted = TrajectoryFitter.fitRollingDeceleration(result.cueTrajectory, period)
        assertNotNull(fitted)
        assertTrue(abs(fitted!! - trueDecel) < 0.04, "fitted $fitted vs true $trueDecel")
    }

    @Test
    fun `cue change marks aim and power calibration stale`() {
        val profile = CalibrationProfile(
            guidelineLength = 0.30,
            aimDragGain = 2.0,
            powerGain = 1.5,
            aimConfidence = 0.9,
            powerConfidence = 0.9,
            physicsConfidence = 0.9,
        )
        val changed = profile.withObservedGuideline(0.45)
        assertEquals(0.0, changed.aimConfidence)
        assertEquals(0.0, changed.powerConfidence)
        assertEquals(0.9, changed.physicsConfidence)

        val same = profile.withObservedGuideline(0.305)
        assertEquals(0.9, same.aimConfidence)
        assertEquals(0.9, same.powerConfidence)
    }

    @Test
    fun `uncalibrated profile reports not calibrated`() {
        assertTrue(!CalibrationProfile().isCalibrated())
        val ready = CalibrationProfile(physicsConfidence = 0.8, aimConfidence = 0.8, powerConfidence = 0.8)
        assertTrue(ready.isCalibrated())
    }
}
