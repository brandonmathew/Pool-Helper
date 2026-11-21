package com.poolhelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PhysicsTest {

    private fun ball(id: Int, x: Double, y: Double) = BallState(id, Vec2(x, y))

    @Test
    fun `rolling ball stops from friction`() {
        val sim = Simulator()
        val result = sim.simulate(listOf(ball(0, 0.0, 0.0)), Vec2(0.5, 0.0))
        val cue = result.finalBalls.first { it.id == 0 }
        // v^2 / (2a) = 0.25 / 0.28 = 0.89 units of travel.
        assertTrue(cue.pos.x in 0.75..0.97, "expected stop near 0.89, got ${cue.pos.x}")
        assertEquals(0.0, cue.pos.y, 1e-9)
        assertTrue(result.potted.isEmpty())
    }

    @Test
    fun `straight shot pots object ball in side pocket`() {
        val sim = Simulator()
        val balls = listOf(ball(0, 0.0, -0.3), ball(1, 0.0, 0.1))
        val result = sim.simulate(balls, Vec2(0.0, 1.6))
        assertEquals(1, result.firstContact)
        assertTrue(1 in result.potted, "ball 1 should be potted, potted=${result.potted}")
        assertTrue(0 !in result.potted)
    }

    @Test
    fun `cue ball aimed into pocket scratches`() {
        val sim = Simulator()
        val balls = listOf(ball(0, 0.7, 0.35))
        val toPocket = (TableSpec.POCKETS[5] - balls[0].pos).normalized() * 1.2
        val result = sim.simulate(balls, toPocket)
        assertTrue(0 in result.potted, "cue ball should be potted")
    }

    @Test
    fun `cushion rebound reverses travel`() {
        val sim = Simulator()
        val result = sim.simulate(listOf(ball(0, 0.0, 0.0)), Vec2(1.4, 0.0), recordTrajectory = true)
        val maxX = result.cueTrajectory.maxOf { it.x }
        val finalX = result.finalBalls.first { it.id == 0 }.pos.x
        assertTrue(maxX > 0.96, "ball should reach the right cushion, maxX=$maxX")
        assertTrue(finalX < maxX - 0.1, "ball should rebound off the cushion, final=$finalX max=$maxX")
    }

    @Test
    fun `head-on collision transfers momentum to object ball`() {
        val sim = Simulator()
        // Slow enough that the object ball stops before reaching the cushion.
        val balls = listOf(ball(0, -0.25, 0.0), ball(1, 0.0, 0.0), ball(9, -0.9, -0.4))
        val result = sim.simulate(balls, Vec2(0.5, 0.0))
        assertEquals(1, result.firstContact)
        val obj = result.finalBalls.firstOrNull { it.id == 1 }
        assertTrue(obj != null && obj.pos.x > 0.4, "object ball should travel +x, got ${obj?.pos}")
    }

    @Test
    fun `simulation is deterministic`() {
        val sim = Simulator()
        val balls = listOf(ball(0, -0.5, 0.1), ball(1, 0.2, -0.05), ball(9, 0.6, 0.3))
        val a = sim.simulate(balls, Vec2(1.3, 0.4))
        val b = sim.simulate(balls, Vec2(1.3, 0.4))
        assertEquals(a.potted, b.potted)
        assertEquals(a.finalBalls.map { it.pos }, b.finalBalls.map { it.pos })
    }

    @Test
    fun `resting ball near pocket is not potted at shot start`() {
        val sim = Simulator()
        // Ball hanging 0.05 from the corner pocket centre, untouched by the shot.
        val nearPocket = Vec2(0.965, 0.465)
        assertTrue(abs(nearPocket.distanceTo(TableSpec.POCKETS[5]) - 0.05) < 0.01)
        val balls = listOf(ball(0, -0.8, -0.3), ball(3, nearPocket.x, nearPocket.y))
        val result = sim.simulate(balls, Vec2(0.3, 0.0))
        assertTrue(3 !in result.potted, "untouched ball near a pocket must stay on the table")
    }
}
