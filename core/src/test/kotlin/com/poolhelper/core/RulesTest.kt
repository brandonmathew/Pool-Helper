package com.poolhelper.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RulesTest {

    private fun outcome(
        potted: List<Int> = emptyList(),
        firstContact: Int? = null,
        railOrPot: Boolean = true,
        cuePotted: Boolean = false,
        breakShot: Boolean = false,
    ) = ShotOutcome(potted, firstContact, railOrPot, cuePotted, breakShot)

    @Test
    fun `first legal pot after break assigns groups and keeps turn`() {
        val r = Rules.applyShot(GameState(), outcome(potted = listOf(3), firstContact = 3))
        assertFalse(r.foul)
        assertTrue(r.groupsAssigned)
        assertEquals(BallGroup.SOLID, r.state.groups[Player.ONE])
        assertEquals(BallGroup.STRIPE, r.state.groups[Player.TWO])
        assertEquals(Player.ONE, r.state.currentPlayer)
    }

    @Test
    fun `pot on the break does not assign groups`() {
        val r = Rules.applyShot(GameState(), outcome(potted = listOf(3), firstContact = 3, breakShot = true))
        assertEquals(null, r.state.groups[Player.ONE])
        assertFalse(r.groupsAssigned)
    }

    @Test
    fun `no contact is a foul with ball in hand`() {
        val r = Rules.applyShot(GameState(), outcome(firstContact = null))
        assertTrue(r.foul)
        assertTrue(r.state.ballInHand)
        assertEquals(Player.TWO, r.state.currentPlayer)
    }

    @Test
    fun `scratch is a foul with ball in hand`() {
        val r = Rules.applyShot(GameState(), outcome(firstContact = 4, cuePotted = true))
        assertTrue(r.foul)
        assertTrue(r.state.ballInHand)
    }

    @Test
    fun `no rail after contact is a foul`() {
        val r = Rules.applyShot(GameState(), outcome(firstContact = 4, railOrPot = false))
        assertTrue(r.foul)
    }

    @Test
    fun `hitting opponent group first is a foul`() {
        val state = GameState(
            groups = mapOf(Player.ONE to BallGroup.SOLID, Player.TWO to BallGroup.STRIPE),
            breakShotPending = false,
        )
        val r = Rules.applyShot(state, outcome(firstContact = 10))
        assertTrue(r.foul)
    }

    @Test
    fun `early eight ball loses the game`() {
        val state = GameState(
            groups = mapOf(Player.ONE to BallGroup.SOLID, Player.TWO to BallGroup.STRIPE),
            potted = setOf(1, 2),
            breakShotPending = false,
        )
        val r = Rules.applyShot(state, outcome(potted = listOf(8), firstContact = 1))
        assertTrue(r.state.gameOver)
        assertEquals(Player.TWO, r.state.winner)
    }

    @Test
    fun `eight ball after clearing group wins`() {
        val state = GameState(
            groups = mapOf(Player.ONE to BallGroup.SOLID, Player.TWO to BallGroup.STRIPE),
            potted = setOf(1, 2, 3, 4, 5, 6, 7),
            breakShotPending = false,
        )
        val r = Rules.applyShot(state, outcome(potted = listOf(8), firstContact = 8))
        assertTrue(r.state.gameOver)
        assertEquals(Player.ONE, r.state.winner)
    }

    @Test
    fun `legal targets follow group and open table`() {
        val open = GameState()
        assertTrue(8 !in Rules.legalTargets(open, Player.ONE))
        assertTrue(3 in Rules.legalTargets(open, Player.ONE))
        val assigned = GameState(
            groups = mapOf(Player.ONE to BallGroup.SOLID, Player.TWO to BallGroup.STRIPE),
            potted = setOf(1, 2, 3, 4, 5, 6, 7),
            breakShotPending = false,
        )
        assertEquals(listOf(8), Rules.legalTargets(assigned, Player.ONE))
        assertTrue(Rules.legalTargets(assigned, Player.TWO).all { it in 9..15 })
    }
}
