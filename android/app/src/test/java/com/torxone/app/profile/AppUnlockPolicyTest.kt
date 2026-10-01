package com.torxone.app.profile

import org.junit.Assert.*
import org.junit.Test

class AppUnlockPolicyTest {
    @Test fun backgroundTimeoutAppliesWithoutActivityResume() {
        assertTrue(AppUnlockPolicy.isAuthorized(true, 100, 500, 599))
        assertFalse(AppUnlockPolicy.isAuthorized(true, 100, 500, 600))
        assertFalse(AppUnlockPolicy.isAuthorized(true, 100, 500, 900))
    }
    @Test fun immediateLockAndInvalidClockFailClosed() {
        assertFalse(AppUnlockPolicy.isAuthorized(true, 100, 0, 100))
        assertFalse(AppUnlockPolicy.isAuthorized(true, 100, -1, 100))
        assertFalse(AppUnlockPolicy.isAuthorized(true, 100, 500, 99))
    }
    @Test fun activeActivityStillRequiresProvenUnlock() {
        assertTrue(AppUnlockPolicy.isAuthorized(true, 0, 0, 100))
        assertFalse(AppUnlockPolicy.isAuthorized(false, 0, 500, 100))
        assertFalse(AppUnlockPolicy.isAuthorized(false, 100, 500, 101))
    }
}
