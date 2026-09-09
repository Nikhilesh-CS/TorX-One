package com.torxone.app.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNetworkHandoverTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testDebouncedNetworkHandover_coalescesRapidSwitches() = testScope.runTest {
        val handoverCalls = AtomicInteger(0)

        val monitor = CallNetworkMonitor(
            context = context,
            scope = testScope,
            debounceMs = 1200L,
            onNetworkHandover = {
                handoverCalls.incrementAndGet()
            }
        )

        // Simulate network callback internals via reflection or simulated method calls
        val callbackField = CallNetworkMonitor::class.java.getDeclaredField("networkCallback").apply {
            isAccessible = true
        }
        val callback = callbackField.get(monitor) as ConnectivityManager.NetworkCallback

        val network1 = mock<Network> { on { networkHandle } doReturn 101L }
        val network2 = mock<Network> { on { networkHandle } doReturn 102L }
        val network3 = mock<Network> { on { networkHandle } doReturn 103L }

        // 1. Initial network becomes available
        callback.onAvailable(network1)
        testScheduler.advanceTimeBy(100L)
        assertEquals(0, handoverCalls.get())

        // 2. Wi-Fi drops, Cellular available (network2)
        callback.onAvailable(network2)
        testScheduler.advanceTimeBy(300L) // 300ms elapsed, debounce not fired yet
        assertEquals(0, handoverCalls.get())

        // 3. Wi-Fi quickly blips back (network3) within debounce window
        callback.onAvailable(network3)
        testScheduler.advanceTimeBy(500L) // total 800ms, debounce reset by network3
        assertEquals(0, handoverCalls.get())

        // 4. Settle past 1200ms debounce
        testScheduler.advanceTimeBy(1300L)
        testScheduler.runCurrent()

        // Exactly ONE coalesced handover callback should have fired, not two!
        assertEquals(1, handoverCalls.get())

        monitor.stop()
    }
}
