package io.embodit.spatialoffice.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motion of the Stage's panels (spread, storyline, rehearsal): pure math, no Android, unit-tested (`MotionTest`).
 *
 * Every panel is a damped spring towards its target pose. The spring is solved in closed form for each step, so the
 * path is the same at 10 frames a second and at 90 (the system lowers a container's frame rate when it likes), and a
 * target that changes in mid-flight is picked up with the speed the panel already has: no jump, no restart.
 *
 * A pose is six numbers: x, y, z (metres, Stage space), yaw (radians), scale, tilt (radians).
 */
object Motion {
    const val X = 0; const val Y = 1; const val Z = 2; const val YAW = 3; const val SCALE = 4; const val TILT = 5
    const val SIZE = 6

    /**
     * How a panel moves. [omega] = natural frequency (1/s): the higher, the quicker. [zeta] = damping ratio:
     * 1 = arrives without passing its place, below 1 = passes it a little and comes back.
     */
    data class Feel(val omega: Float, val zeta: Float) {
        /** Seconds until what is left of the way is under 2 % (an estimate from the decay rate). */
        val settle: Float get() = (if (zeta >= 1f) 5.8f else 4f / zeta) / omega
    }

    /** Laying the pieces out, changing the arrangement, bringing the layout back to the front: calm, exact. */
    val GLIDE = Feel(omega = 12.5f, zeta = 1f)
    /** The piece that comes to the front: a touch quicker, and it lands with a small give (about 1.5 % past its place). */
    val FOCUS = Feel(omega = 15f, zeta = 0.8f)
    /** The big moves (the slide growing into the rehearsal screen): slower, so the eye can follow 3 m of travel. */
    val GRAND = Feel(omega = 8.5f, zeta = 1f)
    /** Gathering the pieces back before the Stage closes: has to be done within the 350 ms the close waits. */
    val GATHER = Feel(omega = 20f, zeta = 1f)

    /** A moving pose: where it is, how fast, and how long it still waits before it starts ([delay], seconds). */
    class Body(val pos: FloatArray, val vel: FloatArray = FloatArray(SIZE), var delay: Float = 0f) {
        fun copy(): Body = Body(pos.copyOf(), vel.copyOf(), delay)
    }

    /**
     * Moves [b] towards [target] for [dt] seconds with [feel]. A body that is still waiting uses up its [Body.delay]
     * first (and stays where it is for that part of the step). Yaw takes the short way round.
     */
    fun step(b: Body, target: FloatArray, dt: Float, feel: Feel) {
        var t = dt.coerceIn(0f, MAX_DT)
        if (b.delay > 0f) {
            val wait = minOf(b.delay, t)
            b.delay -= wait; t -= wait
            if (t <= 0f) return
        }
        for (i in 0 until SIZE) {
            val goal = if (i == YAW) b.pos[i] + shortest(target[i] - b.pos[i]) else target[i]
            val (d, v) = spring(b.pos[i] - goal, b.vel[i], t, feel)
            b.pos[i] = goal + d
            b.vel[i] = v
        }
    }

    /**
     * A small push on one axis: the body leaves its place and the spring brings it back (the big screen "takes" a slide
     * change). With a critically damped [feel] the furthest it gets is [speed] / (ω·e).
     */
    fun nudge(b: Body, axis: Int, speed: Float) { b.vel[axis] += speed }

    /** Speed for [nudge] that takes a critically damped body [depth] away from its place at the most. */
    fun nudgeSpeed(depth: Float, feel: Feel): Float = depth * feel.omega * E

    /** True when [b] is at [target] for the eye: under a millimetre, a tenth of a degree and 0.2 % of scale, and nearly still. */
    fun arrived(b: Body, target: FloatArray): Boolean {
        if (b.delay > 0f) return false
        for (i in 0 until SIZE) {
            val d = if (i == YAW) shortest(target[i] - b.pos[i]) else target[i] - b.pos[i]
            val eps = when (i) { YAW, TILT -> 0.0018f; SCALE -> 0.002f; else -> 0.001f }
            if (abs(d) > eps || abs(b.vel[i]) > eps * 8f) return false
        }
        return true
    }

    /**
     * Exact solution of the damped spring x'' + 2ζω x' + ω² x = 0 after [t] seconds, from displacement [d0] and
     * velocity [v0]. Returns displacement and velocity.
     */
    fun spring(d0: Float, v0: Float, t: Float, feel: Feel): Pair<Float, Float> {
        val w = feel.omega.toDouble(); val z = feel.zeta.toDouble().coerceIn(0.05, 1.0)
        val d = d0.toDouble(); val v = v0.toDouble(); val tt = t.toDouble()
        return if (z >= 0.999) {
            val c2 = v + w * d
            val e = exp(-w * tt)
            ((d + c2 * tt) * e).toFloat() to ((c2 - w * (d + c2 * tt)) * e).toFloat()
        } else {
            val wd = w * sqrt(1 - z * z)
            val e = exp(-z * w * tt)
            val a = d; val bq = (v + z * w * d) / wd
            val c = cos(wd * tt); val s = sin(wd * tt)
            val x = e * (a * c + bq * s)
            val dx = e * ((bq * wd - z * w * a) * c - (a * wd + z * w * bq) * s)
            x.toFloat() to dx.toFloat()
        }
    }

    /** [a] brought into −π..π: the short way round for a turn. */
    fun shortest(a: Float): Float {
        var x = a % TWO_PI
        if (x > PI) x -= TWO_PI
        if (x < -PI) x += TWO_PI
        return x
    }

    /**
     * Seconds piece [index] waits before it moves when the layout fans out from piece [focus]: the focused piece
     * first, its neighbours [stepMs] apart, never more than [maxMs] in all (a long file does not make the last piece late).
     */
    fun fanDelay(index: Int, focus: Int, stepMs: Int = 45, maxMs: Int = 360): Float =
        minOf(abs(index - focus) * stepMs, maxMs) / 1000f

    /** The reverse for gathering: the outermost pieces of [count] leave first, the focused one last; all within [maxMs]. */
    fun gatherDelay(index: Int, focus: Int, count: Int, maxMs: Int = 120): Float {
        val far = maxOf(focus, count - 1 - focus)
        if (far <= 0) return 0f // one piece: nothing to wait for
        return (far - abs(index - focus)).coerceAtLeast(0) * (maxMs.toFloat() / far) / 1000f
    }

    /**
     * Where a piece starts from (and gathers back to): the place of the focused piece, a little lower and nearer the
     * seat, small, facing the seat. All pieces share it, so the layout opens like a hand of cards and closes into one.
     * [focusTarget] is the focused piece's pose; [towardSeat] = unit vector (x, z) from that pose to the seat.
     */
    fun stack(focusTarget: FloatArray, towardSeatX: Float, towardSeatZ: Float): FloatArray = floatArrayOf(
        focusTarget[X] + towardSeatX * STACK_NEARER, focusTarget[Y] - STACK_LOWER, focusTarget[Z] + towardSeatZ * STACK_NEARER,
        focusTarget[YAW], focusTarget[SCALE] * STACK_SCALE, focusTarget[TILT],
    )

    /** Longest step solved at once (seconds): after a stall the panels go on from where they were, they do not finish the whole way unseen. */
    const val MAX_DT = 0.25f
    private const val TWO_PI = (2.0 * PI).toFloat()
    const val STACK_NEARER = 0.18f
    const val STACK_LOWER = 0.10f
    const val STACK_SCALE = 0.32f
    private const val E = 2.7182817f
    /** How far the rehearsal screen gives when the slide changes: 1 % of its size. */
    const val SLIDE_PRESS = 0.01f
}
