package local.watchremote

import org.junit.Assert.*
import org.junit.Test

class SmoothProgressTest {
    @Test fun movesBetweenNetworkSamples() {
        val p = SmoothProgress()
        p.update("song", 10000.0, 200000.0, true, 0)
        assertEquals(10016.0, p.position(16), .001)
        assertEquals(10250.0, p.position(250), .001)
        assertEquals(10999.0, p.position(999), .001)
    }
    @Test fun smallBackwardCorrectionIsContinuousAndStillMovesForward() {
        val p = SmoothProgress()
        p.update("song", 10000.0, 200000.0, true, 0)
        val before = p.position(1000)
        p.update("song", 10800.0, 200000.0, true, 1000)
        assertEquals(before, p.position(1000), .001)
        var previous = before
        for (now in 1016L..2400L step 16) {
            val value = p.position(now)
            assertTrue("must not move backwards", value >= previous)
            previous = value
        }
        assertEquals(12000.0, p.position(2200), .001)
    }
    @Test fun consecutiveCorrectionsDoNotJumpAtSampleBoundaries() {
        val p = SmoothProgress()
        p.update("song", 0.0, 200000.0, true, 0)
        for (now in 1000L..20000L step 1000) {
            val before = p.position(now)
            p.update("song", now.toDouble() - 200, 200000.0, true, now)
            assertEquals(before, p.position(now), .001)
        }
    }
    @Test fun pauseStopsAndResumeAdvancesWithoutHiddenAnimationWork() {
        val p = SmoothProgress()
        p.update("song", 10000.0, 200000.0, true, 0)
        p.update("song", 10500.0, 200000.0, false, 500)
        assertEquals(10500.0, p.position(5000), .001)
        assertFalse(p.needsFrames(5000))
        p.update("song", 10500.0, 200000.0, true, 5000)
        assertEquals(11500.0, p.position(6000), .001)
    }
    @Test fun trackChangesAndSeeksResetInsteadOfAnimatingAcrossTracks() {
        val p = SmoothProgress()
        p.update("first", 120000.0, 200000.0, true, 0)
        p.update("second", 1000.0, 200000.0, true, 500)
        assertEquals(1000.0, p.position(500), .001)
        p.update("second", 90000.0, 200000.0, true, 1000)
        assertEquals(90000.0, p.position(1000), .001)
    }
    @Test fun playheadIsBoundedAndStopsRequestingFramesAtTheEnd() {
        val p = SmoothProgress()
        p.update("song", 1900.0, 2000.0, true, 100)
        assertEquals(2000.0, p.position(10000), .001)
        assertFalse(p.needsFrames(10000))
        p.update("empty", -100.0, 0.0, false, 10000)
        assertEquals(0.0, p.position(10000), .001)
        assertFalse(p.needsFrames(10000))
    }
}
