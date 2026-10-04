package com.torxone.app.transport.tor

import org.torproject.jni.TorService

/**
 * The pinned TorService does not expose completion of its native runMain/finally block.
 * A missing control endpoint alone is insufficient: it is also absent during startup.
 * Its control watcher can outlive failed native startup and attach to a replacement socket.
 * Read both preserved dependency thread fields and fail closed if their contract changes.
 */
internal class TorNativeLifecycle(
    private val nativeThreadSnapshot: () -> Thread?,
    private val controlThreadSnapshot: () -> Thread?
) {
    fun terminated(): Boolean = try {
        // NEW is not alive either, but may subsequently start; only TERMINATED is final.
        if (nativeThreadSnapshot()?.state != Thread.State.TERMINATED) false
        else controlThreadSnapshot()?.let { !it.isAlive } == true
        // A NEW watcher is safe only after the native thread can no longer start it.
    } catch (_: ReflectiveOperationException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        // This is an app dependency field, not an Android hidden framework API. R8 keeps
        // org.torproject.jni; the APK contract verifier checks this exact field descriptor.
        private val torThreadField by lazy {
            TorService::class.java.getDeclaredField("torThread").apply { isAccessible = true }
        }
        private val controlPortThreadField by lazy {
            TorService::class.java.getDeclaredField("controlPortThread").apply { isAccessible = true }
        }

        fun forService(service: TorService?): TorNativeLifecycle = TorNativeLifecycle(
            { service?.let { torThreadField.get(it) as? Thread } },
            { service?.let { controlPortThreadField.get(it) as? Thread } }
        )
    }
}
