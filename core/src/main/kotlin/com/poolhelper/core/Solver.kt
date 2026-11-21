package com.poolhelper.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ShotKind { DIRECT, BANK, COMBO, SAFETY }

data class ShotCandidate(
    val kind: ShotKind,
    val targetBallId: Int,
    /** Intended pocket, null for safeties. */
    val pocketIndex: Int?,
    val cueVelocity: Vec2,
    val spin: Spin,
    /** Expected-value score, higher is better. */
    val score: Double,
    /** Estimated probability the intended ball is potted (jitter-robust). */
    val potProbability: Double,
    /** Estimated probability of a scratch under jitter. */
    val scratchRisk: Double,
    val result: ShotResult,
)

/**
 * Shot solver implementing the locked policy: win-first with an attack
 * threshold. Every candidate kind (direct, bank, combo, safety) is scored by
 * expected value — pot probability under aim/power jitter, scratch risk, and
 * the cue-ball position left for the next shot. When no pot candidate clears
 * the attack threshold, the best safety is ranked first.
 */
class Solver(
    private val simulator: Simulator = Simulator(),
    private val attackThreshold: Double = 0.35,
) {

    fun solve(balls: List<BallState>, state: GameState, player: Player): List<ShotCandidate> {
        val cue = balls.firstOrNull { it.id == 0 } ?: return emptyList()
        val targets = Rules.legalTargets(state, player).filter { t -> balls.any { it.id == t } }
        if (targets.isEmpty()) return emptyList()
        val byId = balls.associateBy { it.id }

        val raw = mutableListOf<ShotCandidate>()

        for (targetId in targets) {
            val target = byId[targetId] ?: continue
            for (pi in TableSpec.POCKETS.indices) {
                val pocket = TableSpec.POCKETS[pi]
                directCandidate(balls, cue, target, pi, pocket)?.let { raw.add(it) }
                bankCandidates(balls, cue, target, pi, pocket).forEach { raw.add(it) }
            }
            // Combos: this legal ball drives another ball into a pocket.
            for (other in balls) {
                if (other.id == 0 || other.id == targetId) continue
                if (other.id == 8 && 8 !in targets) continue
                for (pi in TableSpec.POCKETS.indices) {
                    comboCandidate(balls, cue, target, other, pi, TableSpec.POCKETS[pi])?.let { raw.add(it) }
                }
            }
        }

        // Robustness pass on the most promising pot candidates only.
        val prelim = raw.sortedByDescending { preliminaryScore(it) }.take(8)
        val scored = prelim.map { jitterScore(balls, it, targets) }

        val safeties = safetyCandidates(balls, cue, targets, state, player)
        val bestPotProb = scored.maxOfOrNull { it.potProbability } ?: 0.0
        val all = scored + safeties
        return if (bestPotProb < attackThreshold && safeties.isNotEmpty()) {
            val bestPotScore = scored.maxOfOrNull { it.score } ?: 0.0
            all.sortedByDescending { if (it.kind == ShotKind.SAFETY) max(it.score, bestPotScore + 0.05) else it.score }
        } else {
            all.sortedByDescending { it.score }
        }
    }

    // -- Candidate generation ----------------------------------------------

    private fun ghostFor(targetPos: Vec2, toward: Vec2): Vec2 =
        targetPos - (toward - targetPos).normalized() * TableSpec.BALL_DIAMETER

    private fun directCandidate(
        balls: List<BallState>, cue: BallState, target: BallState, pocketIndex: Int, pocket: Vec2,
    ): ShotCandidate? {
        val ghost = ghostFor(target.pos, pocket)
        val aimDir = (ghost - cue.pos)
        if (aimDir.length() < 1e-6) return null
        val dir = aimDir.normalized()
        // Cut angle: angle between cue travel and target travel directions.
        val targetDir = (pocket - target.pos).normalized()
        if (dir.dot(targetDir) < 0.15) return null // > ~81 degrees: not makeable directly
        if (!pathClear(balls, cue.pos, ghost, setOf(0, target.id))) return null
        if (!pathClear(balls, target.pos, pocket, setOf(target.id))) return null
        val travel = aimDir.length() + target.pos.distanceTo(pocket)
        val speed = (0.55 + travel * 0.8).coerceIn(0.6, 2.8)
        val vel = dir * speed
        val result = simulator.simulate(balls, vel)
        if (result.firstContact != target.id) return null
        if (target.id !in result.potted) return null
        return ShotCandidate(ShotKind.DIRECT, target.id, pocketIndex, vel, Spin(), 0.0, 0.0, 0.0, result)
    }

    private fun bankCandidates(
        balls: List<BallState>, cue: BallState, target: BallState, pocketIndex: Int, pocket: Vec2,
    ): List<ShotCandidate> {
        val out = mutableListOf<ShotCandidate>()
        val cx = TableSpec.HALF_LENGTH - TableSpec.BALL_RADIUS
        val cy = TableSpec.HALF_WIDTH - TableSpec.BALL_RADIUS
        // Mirror the pocket across the ball-centre cushion lines
        // (x = +/-cx, y = +/-cy): reflection across line L maps p to 2L - p.
        val mirrors = mutableListOf<Vec2>()
        // One-cushion mirrors.
        mirrors.add(Vec2(2 * cx - pocket.x, pocket.y))
        mirrors.add(Vec2(-2 * cx - pocket.x, pocket.y))
        mirrors.add(Vec2(pocket.x, 2 * cy - pocket.y))
        mirrors.add(Vec2(pocket.x, -2 * cy - pocket.y))
        // Two-cushion mirrors (reflect across both axes' cushion lines).
        mirrors.add(Vec2(2 * cx - pocket.x, 2 * cy - pocket.y))
        mirrors.add(Vec2(-2 * cx - pocket.x, -2 * cy - pocket.y))

        for (mirror in mirrors) {
            val ghost = ghostFor(target.pos, mirror)
            val aim = ghost - cue.pos
            if (aim.length() < 1e-6) continue
            val dir = aim.normalized()
            if (!pathClear(balls, cue.pos, ghost, setOf(0, target.id))) continue
            val travel = aim.length() + target.pos.distanceTo(mirror)
            val speed = (0.8 + travel * 0.85).coerceIn(0.9, 3.4)
            val vel = dir * speed
            val result = simulator.simulate(balls, vel)
            if (result.firstContact != target.id) continue
            if (target.id !in result.potted) continue
            if (result.pottedPockets.getOrNull(result.potted.indexOf(target.id)) != pocketIndex) continue
            out.add(ShotCandidate(ShotKind.BANK, target.id, pocketIndex, vel, Spin(), 0.0, 0.0, 0.0, result))
        }
        return out
    }

    private fun comboCandidate(
        balls: List<BallState>, cue: BallState, first: BallState, second: BallState,
        pocketIndex: Int, pocket: Vec2,
    ): ShotCandidate? {
        val ghostSecond = ghostFor(second.pos, pocket)
        val firstTravelDir = (ghostSecond - first.pos)
        if (firstTravelDir.length() < 1e-6) return null
        val ghostFirst = first.pos - firstTravelDir.normalized() * TableSpec.BALL_DIAMETER
        val aim = ghostFirst - cue.pos
        if (aim.length() < 1e-6) return null
        val dir = aim.normalized()
        if (!pathClear(balls, cue.pos, ghostFirst, setOf(0, first.id))) return null
        val travel = aim.length() + firstTravelDir.length() + second.pos.distanceTo(pocket)
        val speed = (0.7 + travel * 0.85).coerceIn(0.8, 3.2)
        val vel = dir * speed
        val result = simulator.simulate(balls, vel)
        if (result.firstContact != first.id) return null
        if (second.id !in result.potted) return null
        return ShotCandidate(ShotKind.COMBO, first.id, pocketIndex, vel, Spin(), 0.0, 0.0, 0.0, result)
    }

    private fun safetyCandidates(
        balls: List<BallState>, cue: BallState, targets: List<Int>,
        state: GameState, player: Player,
    ): List<ShotCandidate> {
        val byId = balls.associateBy { it.id }
        val opponentTargets = Rules.legalTargets(state, player.other())
            .mapNotNull { byId[it] }
        val out = mutableListOf<ShotCandidate>()
        for (targetId in targets) {
            val target = byId[targetId] ?: continue
            // Thin hit: aim at a ghost point offset perpendicular to the
            // cue->target line so the cue ball deflects away.
            val line = (target.pos - cue.pos)
            if (line.length() < 1e-6) continue
            val dir = line.normalized()
            val perp = Vec2(-dir.y, dir.x)
            for (side in listOf(1.0, -1.0)) {
                val aimPoint = target.pos - dir * TableSpec.BALL_DIAMETER * 0.4 + perp * (side * TableSpec.BALL_DIAMETER * 0.75)
                val aimDir = (aimPoint - cue.pos).normalized()
                for (speed in listOf(0.75, 1.05)) {
                    val vel = aimDir * speed
                    val result = simulator.simulate(balls, vel)
                    if (result.firstContact != targetId) continue
                    if (0 in result.potted) continue // scratch
                    if (!result.railOrPotAfterContact) continue // would be a foul
                    if (targetId in result.potted) continue // a pot, not a safety
                    val finalCue = result.finalBalls.firstOrNull { it.id == 0 }?.pos ?: continue
                    val oppDist = opponentTargets.minOfOrNull { it.pos.distanceTo(finalCue) } ?: 1.0
                    val score = 0.22 + 0.12 * min(1.0, oppDist)
                    out.add(ShotCandidate(ShotKind.SAFETY, targetId, null, vel, Spin(), score, 0.0, 0.0, result))
                }
            }
        }
        return out.sortedByDescending { it.score }.take(3)
    }

    // -- Scoring -------------------------------------------------------------

    private fun preliminaryScore(c: ShotCandidate): Double {
        var s = 1.0
        if (c.pocketIndex != null) {
            val idx = c.result.potted.indexOf(c.targetBallId)
            if (idx >= 0 && c.result.pottedPockets.getOrNull(idx) == c.pocketIndex) s += 0.3
        }
        if (0 in c.result.potted) s -= 1.5
        return s
    }

    private fun jitterScore(balls: List<BallState>, c: ShotCandidate, targets: List<Int>): ShotCandidate {
        val perturbations = listOf(
            Pair(0.0, 1.0),
            Pair(0.007, 1.05),
            Pair(-0.007, 0.95),
            Pair(0.016, 1.0),
            Pair(-0.016, 1.0),
        )
        var pots = 0
        var scratches = 0
        var lastResult = c.result
        for ((angle, powerScale) in perturbations) {
            val vel = c.cueVelocity.rotated(angle) * powerScale
            val r = simulator.simulate(balls, vel, c.spin)
            lastResult = r
            val pottedTarget = when (c.kind) {
                ShotKind.COMBO -> r.potted.any { it != 0 && it != 8 }
                else -> c.targetBallId in r.potted
            }
            if (pottedTarget) pots++
            if (0 in r.potted) scratches++
        }
        val potProb = pots.toDouble() / perturbations.size
        val scratchRisk = scratches.toDouble() / perturbations.size
        val finalCue = lastResult.finalBalls.firstOrNull { it.id == 0 }?.pos
        val remaining = targets.filter { it != c.targetBallId }
        val posBonus = if (finalCue != null && remaining.isNotEmpty()) {
            val byId = balls.associateBy { it.id }
            val nearest = remaining.mapNotNull { byId[it] }.minOfOrNull { it.pos.distanceTo(finalCue) } ?: 1.0
            0.15 * (1.0 - min(1.0, nearest))
        } else 0.0
        val score = potProb - scratchRisk * 0.8 + posBonus - abs(c.cueVelocity.length()) * 0.01
        return c.copy(score = score, potProbability = potProb, scratchRisk = scratchRisk)
    }

    // -- Helpers ---------------------------------------------------------------

    /** True if no ball (other than [ignore]) blocks the segment a->b. */
    private fun pathClear(balls: List<BallState>, a: Vec2, b: Vec2, ignore: Set<Int>): Boolean {
        val ab = b - a
        val lenSq = ab.lengthSq()
        if (lenSq < 1e-12) return true
        for (ball in balls) {
            if (ball.id in ignore) continue
            val t = ((ball.pos - a).dot(ab) / lenSq).coerceIn(0.0, 1.0)
            val closest = a + ab * t
            if (closest.distanceTo(ball.pos) < TableSpec.BALL_DIAMETER * 0.98) return false
        }
        return true
    }
}
