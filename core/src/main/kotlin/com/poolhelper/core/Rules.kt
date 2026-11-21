package com.poolhelper.core

/**
 * 8-ball rules engine.
 *
 * NOTE: London Pub table rules in 8 Ball Pool must be verified on device
 * (public sources conflict). The two rules most likely to differ from WPA
 * tournament rules are flagged in comments: group assignment on the break,
 * and whether the 8-ball must be called.
 */
enum class Player { ONE, TWO }

fun Player.other(): Player = if (this == Player.ONE) Player.TWO else Player.ONE

data class GameState(
    val groups: Map<Player, BallGroup?> = mapOf(Player.ONE to null, Player.TWO to null),
    val potted: Set<Int> = emptySet(),
    val currentPlayer: Player = Player.ONE,
    val ballInHand: Boolean = false,
    val breakShotPending: Boolean = true,
    val gameOver: Boolean = false,
    val winner: Player? = null,
    val loser: Player? = null,
)

/** What the physics layer observed during one shot. */
data class ShotOutcome(
    val potted: List<Int>,
    val firstContact: Int?,
    val railOrPotAfterContact: Boolean,
    val cuePotted: Boolean,
    val isBreakShot: Boolean,
)

data class RuleResult(
    val state: GameState,
    val foul: Boolean,
    val foulReasons: List<String>,
    val groupsAssigned: Boolean,
)

object Rules {

    fun groupOfPlayer(state: GameState, player: Player): BallGroup? = state.groups[player]

    /** Balls of [player]'s group still on the table. Null group = open table. */
    fun remainingFor(state: GameState, player: Player): List<Int> {
        val g = state.groups[player] ?: return (1..15).filter { it !in state.potted }
        return (1..15).filter { it !in state.potted && groupOf(it) == g }
    }

    fun isLegalFirstContact(state: GameState, player: Player, ballId: Int): Boolean {
        val g = state.groups[player]
        return when {
            ballId == 0 -> false
            g == null -> ballId != 8 // open table: anything except the 8 first
            remainingFor(state, player).isEmpty() -> ballId == 8 // group cleared: 8 is legal
            else -> groupOf(ballId) == g
        }
    }

    /** Legal target balls for [player] right now (for the solver). */
    fun legalTargets(state: GameState, player: Player): List<Int> {
        val g = state.groups[player]
        return when {
            g == null -> (1..15).filter { it !in state.potted && it != 8 }
            remainingFor(state, player).isEmpty() -> listOf(8).filter { it !in state.potted }
            else -> remainingFor(state, player)
        }
    }

    fun applyShot(state: GameState, outcome: ShotOutcome): RuleResult {
        if (state.gameOver) return RuleResult(state, foul = false, emptyList(), groupsAssigned = false)
        val player = state.currentPlayer
        val reasons = mutableListOf<String>()

        // --- Foul checks -------------------------------------------------
        if (outcome.cuePotted) reasons.add("cue ball potted (scratch)")
        val fc = outcome.firstContact
        if (fc == null) {
            reasons.add("no ball contacted")
        } else if (!isLegalFirstContact(state, player, fc)) {
            reasons.add("illegal first contact: ball $fc")
        }
        if (fc != null && !outcome.railOrPotAfterContact && outcome.potted.isEmpty()) {
            reasons.add("no rail after contact and nothing potted")
        }
        val foul = reasons.isNotEmpty()

        val newPotted = state.potted + outcome.potted
        var groups = state.groups
        var assigned = false

        // --- 8-ball resolution -------------------------------------------
        if (8 in outcome.potted) {
            val cleared = state.groups[player] != null && remainingFor(state, player).isEmpty()
            val win = cleared && !foul
            val next = state.copy(
                potted = newPotted,
                gameOver = true,
                winner = if (win) player else player.other(),
                loser = if (win) player.other() else player,
                ballInHand = false,
                breakShotPending = false,
            )
            return RuleResult(next, foul, reasons, groupsAssigned = false)
        }

        // --- Group assignment ---------------------------------------------
        // VERIFY ON DEVICE: this implements the WPA rule that balls potted on
        // the break do not assign groups; assignment happens on the first
        // legally potted ball after the break. Miniclip may differ.
        if (groups[player] == null && !outcome.isBreakShot && !foul) {
            val firstPot = outcome.potted.firstOrNull { it != 8 }
            if (firstPot != null) {
                val g = groupOf(firstPot)
                groups = mapOf(
                    player to g,
                    player.other() to if (g == BallGroup.SOLID) BallGroup.STRIPE else BallGroup.SOLID,
                )
                assigned = true
            }
        }

        // --- Turn continuation --------------------------------------------
        val pottedOwn = outcome.potted.any { id ->
            if (id == 8) false
            else {
                val g = groups[player]
                g == null || groupOf(id) == g
            }
        }
        val keepTurn = !foul && pottedOwn
        val next = state.copy(
            groups = groups,
            potted = newPotted,
            currentPlayer = if (keepTurn) player else player.other(),
            ballInHand = foul && !outcome.isBreakShot,
            breakShotPending = false,
        )
        return RuleResult(next, foul, reasons, assigned)
    }
}
