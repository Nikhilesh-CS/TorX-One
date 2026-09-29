package com.torxone.app.ui.permissions

import android.Manifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PermissionHelperTest {
    @Test
    fun `startup networking permissions exclude notification permission`() {
        val startup = PermissionHelper.getStartupPermissions()

        assertArrayEquals(PermissionHelper.getNearbyPermissions(), startup)
        assertFalse(startup.contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}
