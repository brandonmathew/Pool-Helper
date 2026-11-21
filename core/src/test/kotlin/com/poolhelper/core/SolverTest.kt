package com.poolhelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SolverTest {

    private fun ball(id: Int, x: Double, y: Double) = BallState(id, Vec2(x, y))

    @Test
    fun `open table straight pot is the best shot`() {
        val balls = listOf(
            ball(0, 0.0, -0.3),
            ball(1, 0.0, 0.1),
            ball(9, -0.8, 0.4),
        )
        val solver = Solver()
        val candidates = solver.solve(balls, GameState(), Player.ONE)
        assertTrue(candidates.isNotEmpty())
        val best = candidates.first()
        assertEquals(ShotKind.DIRECT, best.kind)
        assertEquals(1, best.targetBallId)
        assertTrue(best.potProbability > 0.5, "pot probability ${best.potProbability}")
    }

    @Test
    fun `blocked direct path returns a bank or a safety, not the blocked direct`() {
        // Ball 1 near the top-right corner pocket; ball 9 blocks the
        // target-to-pocket line. Player ONE is on solids; only ball 1 remains.
        val balls = listOf(
            ball(0, -0.5, 0.0),
            ball(1, 0.6, 0.0),
            ball(9, 0.8, 0.25),
        )
        val state = GameState(
            groups = mapOf(Player.ONE to BallGroup.SOLID, Player.TWO to BallGroup.STRIPE),
            potted = setOf(2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15),
            breakShotPending = false,
        )
        val solver = Solver()
        val candidates = solver.solve(balls, state, Player.ONE)
        assertTrue(candidates.isNotEmpty(), "expected at least one viable candidate")
        val best = candidates.first()
        assertEquals(1, best.targetBallId)
        assertNotEquals(ShotKind.DIRECT, best.kind)
        assertTrue(best.kind == ShotKind.BANK || best.kind == ShotKind.SAFETY, "got ${best.kind}")
    }
}
