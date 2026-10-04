package com.torxone.app.transport.tor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TorNativeLifecycleTest {
    @Test fun nativeStartupAndCleanupMustFinishBeforeReplacementIsAuthorized() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            release.await()
        }
        val lifecycle = TorNativeLifecycle({ thread }, { Thread() })
        try {
            assertEquals(Thread.State.NEW, thread.state)
            assertFalse("An unstarted native thread can still create its socket", lifecycle.terminated())
            thread.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("An absent socket must not authorize replacement while startup/cleanup runs", lifecycle.terminated())
            release.countDown()
            thread.join(2_000)
            assertFalse(thread.isAlive)
            assertTrue(lifecycle.terminated())
        } finally {
            release.countDown()
            if (thread.state != Thread.State.NEW) thread.join(2_000)
        }
    }

    @Test fun missingOrInaccessibleThreadCannotProveNativeShutdown() {
        val completed = Thread {}.apply { start(); join(2_000) }
        assertEquals(Thread.State.TERMINATED, completed.state)
        val unavailable = listOf<() -> Thread?>(
            { null },
            { throw NoSuchFieldException("contract changed") },
            { throw IllegalAccessException("not accessible") },
            { throw SecurityException("not accessible") },
            { throw IllegalArgumentException("wrong instance") }
        )
        for (snapshot in unavailable) {
            assertFalse(TorNativeLifecycle(snapshot, { Thread() }).terminated())
            assertFalse(TorNativeLifecycle({ completed }, snapshot).terminated())
        }
    }

    @Test fun blockedNativeThreadCannotAuthorizeRestartUntilItExits() {
        val lock = Any()
        val thread = Thread { synchronized(lock) {} }
        val lifecycle = TorNativeLifecycle({ thread }, { Thread() })
        try {
            synchronized(lock) {
                thread.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, thread.state)
                assertFalse(lifecycle.terminated())
            }
            thread.join(2_000)
            assertFalse(thread.isAlive)
            assertTrue(lifecycle.terminated())
        } finally {
            if (thread.state != Thread.State.NEW) thread.join(2_000)
        }
    }

    @Test fun nativeCompletionCannotReplaceSocketWhileItsControlWatcherStillRuns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val watcher = Thread { entered.countDown(); release.await() }
        val native = Thread {}
        val lifecycle = TorNativeLifecycle({ native }, { watcher })
        try {
            watcher.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            native.start()
            native.join(2_000)
            assertEquals(Thread.State.TERMINATED, native.state)
            assertFalse("An old watcher could attach to the replacement daemon's socket", lifecycle.terminated())
            release.countDown()
            watcher.join(2_000)
            assertFalse(watcher.isAlive)
            assertTrue(lifecycle.terminated())
        } finally {
            release.countDown()
            if (watcher.state != Thread.State.NEW) watcher.join(2_000)
            if (native.state != Thread.State.NEW) native.join(2_000)
        }
    }
}
