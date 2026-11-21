package com.poolhelper.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 2D vector in canonical table units. The table is 2.0 x 1.0, origin at centre. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)

    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
    fun lengthSq() = x * x + y * y
    fun distanceTo(o: Vec2) = (this - o).length()

    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-12) Vec2(0.0, 0.0) else Vec2(x / len, y / len)
    }

    /** Rotate by [angleRad] counter-clockwise. */
    fun rotated(angleRad: Double): Vec2 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun angle() = atan2(y, x)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromAngle(angleRad: Double, magnitude: Double = 1.0) =
            Vec2(cos(angleRad) * magnitude, sin(angleRad) * magnitude)
    }
}
