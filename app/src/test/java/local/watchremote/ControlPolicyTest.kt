package local.watchremote

import org.junit.Assert.*
import org.junit.Test

class ControlPolicyTest {
    @Test fun commandBodiesAreNeverParsedAsObjects() {
        for (method in listOf("POST", "PUT", "DELETE")) {
            for (body in listOf("", "opaque-response", "\"opaque-response\"", "{}")) {
                assertFalse(ControlPolicy.needsObject(method, 200, body))
            }
        }
    }
    @Test fun readAndEmptyResponsesAreDistinguished() {
        assertTrue(ControlPolicy.needsObject("GET", 200, "{}"))
        assertFalse(ControlPolicy.needsObject("GET", 204, ""))
        assertFalse(ControlPolicy.needsObject("GET", 200, " "))
    }
    @Test fun rapidTapsAreDroppedAndNotReplayed() {
        var now = 100L
        val gate = CommandGate({ now })
        assertTrue(gate.acquire())
        repeat(100) { now++; assertFalse(gate.acquire()) }
        gate.release()
        now += 799
        assertFalse(gate.acquire())
        now++
        assertTrue(gate.acquire())
    }
    @Test fun restrictionDoesNotBlameSubscription() {
        assertFalse(ControlPolicy.forbidden("RESTRICTION_VIOLATED").contains("Premium"))
        assertFalse(ControlPolicy.forbidden("unknown").contains("白名单"))
        assertTrue(ControlPolicy.forbidden("PREMIUM_REQUIRED").contains("Premium"))
    }
    @Test fun requestTimeIsNotAddedToPollingInterval() {
        assertEquals(300L, PollPolicy.delay(1000, 1700, 0, true, 0, true))
        assertEquals(100L, PollPolicy.delay(1000, 2300, 0, true, 0, true))
        assertEquals(800L, PollPolicy.delay(1000, 1700, 0, false, 0, true))
    }
    @Test fun limitsAndFailuresOverrideFastPolling() {
        assertEquals(8300L, PollPolicy.delay(1000, 1700, 10000, true, 0, true))
        assertEquals(3300L, PollPolicy.delay(1000, 1700, 0, true, 2, true))
        assertEquals(4300L, PollPolicy.delay(1000, 1700, 0, true, 0, false))
    }
    @Test fun serverErrorsDistinguishReadsAndAmbiguousCommands() {
        assertTrue(ControlPolicy.serverFailure(502, "GET").contains("同步"))
        assertTrue(ControlPolicy.serverFailure(502, "POST").contains("结果未确认"))
        assertFalse(ControlPolicy.serverFailure(502, "POST").contains("会员"))
    }
    @Test fun retriesExcludeRateLimitsAndPermissionFailures() {
        for (code in listOf(401, 408, 500, 502, 503, 504)) assertTrue(RetryPolicy.mayRetryRead(code))
        for (code in listOf(0, 400, 403, 404, 429, 501)) assertFalse(RetryPolicy.mayRetryRead(code))
    }
    @Test fun retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(10000L, RetryPolicy.retryAfterMillis("10", 0))
        assertEquals(5000L, RetryPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 0))
        assertEquals(30000L, RetryPolicy.retryAfterMillis("bad-header", 0))
        assertEquals(1000L, RetryPolicy.retryAfterMillis("-5", 0))
    }
}
