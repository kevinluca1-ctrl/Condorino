package com.condorino.weekend.scoring

/** Small numeric helpers shared by the scoring code. Kept separate so they are easy to unit-test. */
object ScoringMath {

    fun clamp(value: Double, min: Double = 0.0, max: Double = 1.0): Double =
        when {
            value.isNaN() -> min
            value < min -> min
            value > max -> max
            else -> value
        }

    fun clamp100(value: Double): Double = clamp(value, 0.0, 100.0)

    /** Linear interpolation through a sorted list of (x, y) control points, clamped at both ends. */
    fun piecewise(x: Double, points: List<Pair<Double, Double>>): Double {
        require(points.isNotEmpty()) { "points must not be empty" }
        val sorted = points.sortedBy { it.first }
        if (x <= sorted.first().first) return sorted.first().second
        if (x >= sorted.last().first) return sorted.last().second
        for (i in 0 until sorted.size - 1) {
            val (x0, y0) = sorted[i]
            val (x1, y1) = sorted[i + 1]
            if (x in x0..x1) {
                if (x1 == x0) return y1
                val t = (x - x0) / (x1 - x0)
                return y0 + t * (y1 - y0)
            }
        }
        return sorted.last().second
    }
}
