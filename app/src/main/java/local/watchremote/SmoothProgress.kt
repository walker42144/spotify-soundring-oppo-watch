package local.watchremote

import kotlin.math.abs
import kotlin.math.max

/** A monotonic-clock playhead with continuous, rate-limited correction of small server drift. */
internal class SmoothProgress {
    private var initialized = false
    private var identity = ""
    private var anchor = 0.0
    private var anchorAt = 0L
    private var playing = false
    private var correction = 0.0
    private var correctionAt = 0L
    private var correctionLength = 1200.0
    var duration = 0.0
        private set

    fun update(key: String, position: Double, durationMs: Double, isPlaying: Boolean, now: Long) {
        val targetDuration = durationMs.coerceAtLeast(0.0)
        val target = position.coerceIn(0.0, targetDuration)
        val before = position(now)
        val reset = !initialized || identity != key || playing != isPlaying ||
            abs(before - target) > 3000.0 || abs(duration - targetDuration) > 1000.0
        correction = if (reset) 0.0 else before - target
        // smoothstep's maximum slope is 1.5. A >=2x correction duration keeps forward playback moving forward.
        correctionLength = max(1200.0, abs(correction) * 2.0)
        correctionAt = now; anchorAt = now; anchor = target
        duration = targetDuration; playing = isPlaying; identity = key; initialized = true
    }

    fun position(now: Long): Double {
        val elapsed = (now - anchorAt).coerceAtLeast(0).toDouble()
        val t = ((now - correctionAt).coerceAtLeast(0) / correctionLength).coerceIn(0.0, 1.0)
        val eased = t * t * (3.0 - 2.0 * t)
        return (anchor + if (playing) elapsed else 0.0).let { it + correction * (1.0 - eased) }.coerceIn(0.0, duration)
    }

    fun needsFrames(now: Long) = duration > 0 &&
        ((playing && position(now) < duration) || (abs(correction) > .1 && now - correctionAt < correctionLength))
}
