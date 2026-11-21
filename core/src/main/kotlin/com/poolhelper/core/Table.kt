package com.poolhelper.core

/**
 * Canonical table geometry. Units are chosen so the playing surface is
 * 2.0 (length, x) by 1.0 (width, y) with the origin at the centre; one unit
 * equals 1.27 m, so all axes share one physical scale.
 */
object TableSpec {
    const val LENGTH = 2.0
    const val WIDTH = 1.0
    const val HALF_LENGTH = LENGTH / 2.0
    const val HALF_WIDTH = WIDTH / 2.0

    /** Ball radius: 57.2 mm diameter balls -> 0.0286 m / 1.27 m per unit. */
    const val BALL_RADIUS = 0.0225
    const val BALL_DIAMETER = BALL_RADIUS * 2.0

    /** Pocket capture radii (centre distance at which a ball is potted). */
    const val CORNER_POCKET_RADIUS = 0.040
    const val SIDE_POCKET_RADIUS = 0.036

    /** Pocket centres, slightly outside the playing corners for corners. */
    val POCKETS: List<Vec2> = listOf(
        Vec2(-HALF_LENGTH, -HALF_WIDTH),
        Vec2(0.0, -HALF_WIDTH),
        Vec2(HALF_LENGTH, -HALF_WIDTH),
        Vec2(-HALF_LENGTH, HALF_WIDTH),
        Vec2(0.0, HALF_WIDTH),
        Vec2(HALF_LENGTH, HALF_WIDTH),
    )

    fun pocketRadius(pocketIndex: Int): Double =
        if (pocketIndex == 1 || pocketIndex == 4) SIDE_POCKET_RADIUS else CORNER_POCKET_RADIUS

    /** Head string x position; the break is taken from behind it. */
    const val HEAD_STRING_X = -HALF_LENGTH / 2.0
    val BREAK_CUE_POS = Vec2(HEAD_STRING_X - 0.25, 0.0)

    /** Standard rack apex position (foot spot). */
    val RACK_APEX = Vec2(HALF_LENGTH / 2.0, 0.0)
}

/** Ball identities: 0 = cue, 1-7 solids, 8 = eight ball, 9-15 stripes. */
enum class BallGroup { CUE, SOLID, EIGHT, STRIPE }

fun groupOf(ballId: Int): BallGroup = when (ballId) {
    0 -> BallGroup.CUE
    8 -> BallGroup.EIGHT
    in 1..7 -> BallGroup.SOLID
    in 9..15 -> BallGroup.STRIPE
    else -> throw IllegalArgumentException("ball id out of range: $ballId")
}
