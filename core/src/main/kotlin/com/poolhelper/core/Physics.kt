package com.poolhelper.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Physical constants for the simulator. These are DEFAULTS only: Miniclip's
 * physics is proprietary, so the auto-calibration system fits the real values
 * from observed trajectories and replaces them.
 *
 * Units: canonical table units (1 unit = 1.27 m) and seconds.
 */
data class PhysicsConstants(
    /** Constant deceleration of a rolling ball (units/s^2). */
    val rollingDeceleration: Double = 0.14,
    /** Ball-ball coefficient of restitution (1 = perfectly elastic). */
    val ballRestitution: Double = 0.95,
    /** Fraction of tangential velocity transferred as throw on ball-ball contact. */
    val throwFactor: Double = 0.04,
    /** Cushion coefficient of restitution. */
    val cushionRestitution: Double = 0.75,
    /** Tangential velocity kept after a cushion bounce (1 - friction loss). */
    val cushionTangentialKeep: Double = 0.92,
    /** Speed below which a ball is treated as stopped (units/s). */
    val stopSpeed: Double = 0.005,
    /** Extra pocket capture margin when a ball is moving slowly over a pocket. */
    val pocketDropSpeed: Double = 0.6,
)

/** Cue ball spin: x = side spin (english), y = follow (+) / draw (-). Range -1..1. */
data class Spin(val x: Double = 0.0, val y: Double = 0.0)

data class BallState(
    val id: Int,
    var pos: Vec2,
    var vel: Vec2 = Vec2.ZERO,
)

data class ShotResult(
    /** Final positions of balls still on the table (potted balls removed). */
    val finalBalls: List<BallState>,
    /** Potted ball ids in the order they dropped. */
    val potted: List<Int>,
    /** Pocket index each potted ball fell into, parallel to [potted]. */
    val pottedPockets: List<Int>,
    /** First ball the cue ball contacted, or null if it hit nothing. */
    val firstContact: Int?,
    /** True if the cue ball touched a cushion after first contact. */
    val cueCushionAfterContact: Boolean,
    /** True if any ball touched a cushion after first contact. */
    val anyCushionAfterContact: Boolean,
    /** True if anything was potted or any cushion was touched after contact. */
    val railOrPotAfterContact: Boolean,
    /** Cue ball positions sampled during the shot (for calibration fitting). */
    val cueTrajectory: List<Vec2>,
    /** Total simulated seconds. */
    val durationSec: Double,
)

/**
 * Deterministic shot simulator. Fixed-substep integration with impulse
 * collision resolution; the substep is small enough that the fastest ball
 * moves less than a quarter radius per substep, so collisions are not missed.
 * No randomness anywhere: identical inputs give identical results.
 */
class Simulator(val constants: PhysicsConstants = PhysicsConstants()) {

    fun simulate(
        initialBalls: List<BallState>,
        cueVelocity: Vec2,
        spin: Spin = Spin(),
        recordTrajectory: Boolean = false,
        trajectoryPeriodSec: Double = 1.0 / 120.0,
    ): ShotResult {
        val balls = initialBalls.map { BallState(it.id, it.pos, it.vel) }.toMutableList()
        val cue = balls.firstOrNull { it.id == 0 }
            ?: throw IllegalArgumentException("ball list must contain the cue ball (id 0)")
        cue.vel = cueVelocity

        val potted = mutableListOf<Int>()
        val pottedPockets = mutableListOf<Int>()
        var firstContact: Int? = null
        var cueCushionAfterContact = false
        var anyCushionAfterContact = false
        var contactMade = false
        var spinApplied = false
        val trajectory = mutableListOf<Vec2>()
        var nextSampleAt = 0.0
        var elapsed = 0.0
        val maxTime = 40.0

        while (elapsed < maxTime) {
            val maxSpeed = balls.maxOfOrNull { it.vel.length() } ?: 0.0
            if (maxSpeed < constants.stopSpeed) break
            // Substep so the fastest ball moves at most a quarter radius.
            val dt = min(1.0 / 240.0, (TableSpec.BALL_RADIUS * 0.25) / max(maxSpeed, 1e-6))
            elapsed += dt

            // Rolling friction: constant deceleration opposing motion.
            for (b in balls) {
                val speed = b.vel.length()
                if (speed > 0.0) {
                    val newSpeed = max(0.0, speed - constants.rollingDeceleration * dt)
                    b.vel = b.vel * (newSpeed / speed)
                }
                b.pos = b.pos + b.vel * dt
            }

            // Pocket capture.
            val iter = balls.listIterator()
            while (iter.hasNext()) {
                val b = iter.next()
                for (pi in TableSpec.POCKETS.indices) {
                    val pocket = TableSpec.POCKETS[pi]
                    val r = TableSpec.pocketRadius(pi)
                    val d = b.pos.distanceTo(pocket)
                    // A slow ball drops only when it is over the mouth and
                    // still moving toward the pocket centre; a ball merely
                    // resting near a pocket must not vanish at shot start.
                    val slowDrop = b.vel.length() < constants.pocketDropSpeed && d < r * 1.15 &&
                        b.vel.dot(pocket - b.pos) > 0.0
                    if (d < r || slowDrop) {
                        potted.add(b.id)
                        pottedPockets.add(pi)
                        iter.remove()
                        break
                    }
                }
            }
            if (balls.none { it.id == 0 }) {
                // Cue ball potted: shot is over for our purposes.
                return finish(balls, potted, pottedPockets, firstContact,
                    cueCushionAfterContact, anyCushionAfterContact, trajectory, elapsed)
            }

            // Cushion collisions.
            for (b in balls) {
                var bounced = false
                if (b.pos.x < -TableSpec.HALF_LENGTH + TableSpec.BALL_RADIUS && b.vel.x < 0) {
                    b.pos = Vec2(-TableSpec.HALF_LENGTH + TableSpec.BALL_RADIUS, b.pos.y)
                    b.vel = Vec2(-b.vel.x * constants.cushionRestitution, b.vel.y * constants.cushionTangentialKeep)
                    bounced = true
                } else if (b.pos.x > TableSpec.HALF_LENGTH - TableSpec.BALL_RADIUS && b.vel.x > 0) {
                    b.pos = Vec2(TableSpec.HALF_LENGTH - TableSpec.BALL_RADIUS, b.pos.y)
                    b.vel = Vec2(-b.vel.x * constants.cushionRestitution, b.vel.y * constants.cushionTangentialKeep)
                    bounced = true
                }
                if (b.pos.y < -TableSpec.HALF_WIDTH + TableSpec.BALL_RADIUS && b.vel.y < 0) {
                    b.pos = Vec2(b.pos.x, -TableSpec.HALF_WIDTH + TableSpec.BALL_RADIUS)
                    b.vel = Vec2(b.vel.x * constants.cushionTangentialKeep, -b.vel.y * constants.cushionRestitution)
                    bounced = true
                } else if (b.pos.y > TableSpec.HALF_WIDTH - TableSpec.BALL_RADIUS && b.vel.y > 0) {
                    b.pos = Vec2(b.pos.x, TableSpec.HALF_WIDTH - TableSpec.BALL_RADIUS)
                    b.vel = Vec2(b.vel.x * constants.cushionTangentialKeep, -b.vel.y * constants.cushionRestitution)
                    bounced = true
                }
                if (bounced) {
                    // Side spin on the cue ball kicks it tangentially off the cushion.
                    if (b.id == 0 && abs(spin.x) > 0.01) {
                        b.vel = b.vel + Vec2(-b.vel.y, b.vel.x).normalized() * (spin.x * 0.05)
                    }
                    if (contactMade) {
                        anyCushionAfterContact = true
                        if (b.id == 0) cueCushionAfterContact = true
                    }
                }
            }

            // Ball-ball collisions (equal mass, impulse along the line of centres).
            for (i in balls.indices) {
                for (j in i + 1 until balls.size) {
                    val a = balls[i]
                    val b = balls[j]
                    val delta = b.pos - a.pos
                    val dist = delta.length()
                    val minDist = TableSpec.BALL_DIAMETER
                    if (dist > 0.0 && dist < minDist) {
                        val n = delta / dist
                        // Positional separation, split evenly.
                        val overlap = (minDist - dist) / 2.0
                        a.pos = a.pos - n * overlap
                        b.pos = b.pos + n * overlap
                        val relVel = (b.vel - a.vel).dot(n)
                        if (relVel < 0.0) {
                            val impulse = -(1.0 + constants.ballRestitution) * relVel / 2.0
                            a.vel = a.vel - n * impulse
                            b.vel = b.vel + n * impulse
                            // Throw: small tangential drag on the object ball.
                            val tangent = Vec2(-n.y, n.x)
                            val throwV = tangent * ((a.vel - b.vel).dot(tangent) * constants.throwFactor)
                            b.vel = b.vel + throwV

                            if (a.id == 0 || b.id == 0) {
                                val other = if (a.id == 0) b.id else a.id
                                if (firstContact == null) {
                                    firstContact = other
                                    contactMade = true
                                    // Follow/draw: first-order cue ball deflection at contact.
                                    if (!spinApplied && abs(spin.y) > 0.01) {
                                        val cueBall = if (a.id == 0) a else b
                                        cueBall.vel = cueBall.vel + n * (spin.y * cueVelocity.length() * 0.18)
                                        spinApplied = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (recordTrajectory && balls.any { it.id == 0 }) {
                while (elapsed >= nextSampleAt) {
                    trajectory.add(balls.first { it.id == 0 }.pos)
                    nextSampleAt += trajectoryPeriodSec
                }
            }
        }

        return finish(balls, potted, pottedPockets, firstContact,
            cueCushionAfterContact, anyCushionAfterContact, trajectory, elapsed)
    }

    private fun finish(
        balls: List<BallState>,
        potted: List<Int>,
        pottedPockets: List<Int>,
        firstContact: Int?,
        cueCushionAfterContact: Boolean,
        anyCushionAfterContact: Boolean,
        trajectory: List<Vec2>,
        elapsed: Double,
    ): ShotResult {
        val railOrPot = anyCushionAfterContact || potted.isNotEmpty()
        return ShotResult(
            finalBalls = balls.map { BallState(it.id, it.pos, Vec2.ZERO) },
            potted = potted.toList(),
            pottedPockets = pottedPockets.toList(),
            firstContact = firstContact,
            cueCushionAfterContact = cueCushionAfterContact,
            anyCushionAfterContact = anyCushionAfterContact,
            railOrPotAfterContact = railOrPot,
            cueTrajectory = trajectory.toList(),
            durationSec = elapsed,
        )
    }
}
