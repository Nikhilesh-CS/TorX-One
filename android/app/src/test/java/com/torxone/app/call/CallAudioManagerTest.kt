package com.torxone.app.call

import android.content.Context
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallAudioManagerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @Test
    fun testStartCallAudio_setsCommunicationModeAndFocus() {
        val callAudioManager = CallAudioManager(context)
        assertFalse(callAudioManager.isMuted)
        assertFalse(callAudioManager.isSpeaker)

        callAudioManager.startCallAudio()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        callAudioManager.stopCallAudio()
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        callAudioManager.release()
    }

    @Test
    fun testMuteAndSpeakerToggle_persistsDeterministically() {
        val callAudioManager = CallAudioManager(context)
        callAudioManager.startCallAudio()

        // Toggle Mute
        assertFalse(callAudioManager.isMuted)
        callAudioManager.toggleMute()
        assertTrue(callAudioManager.isMuted)

        // Toggle Speaker
        assertFalse(callAudioManager.isSpeaker)
        callAudioManager.toggleSpeaker()
        assertTrue(callAudioManager.isSpeaker)

        // Simulate restoreAudioState (e.g. after network handover or reconnect)
        callAudioManager.restoreAudioState()
        assertTrue("Mute must be preserved after restore", callAudioManager.isMuted)
        assertTrue("Speaker must be preserved after restore", callAudioManager.isSpeaker)

        callAudioManager.stopCallAudio()
        callAudioManager.release()
    }

    @Test
    fun testTransientFocusLoss_doesNotAlterUserMuteState() {
        val callAudioManager = CallAudioManager(context)
        callAudioManager.startCallAudio()

        // User sets mute to false (unmuted)
        assertFalse(callAudioManager.isMuted)

        // Simulate transient focus loss (e.g. navigation prompt or notification beep)
        val method = CallAudioManager::class.java.getDeclaredMethod("handleAudioFocusChange", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        method.invoke(callAudioManager, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        // isMuted must NOT be flipped behind the user's back!
        assertFalse("isMuted must remain unchanged during transient focus loss", callAudioManager.isMuted)

        // Regain focus
        method.invoke(callAudioManager, AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("isMuted must remain false after focus regain", callAudioManager.isMuted)

        // Now test when user is muted:
        callAudioManager.setMuted(true)
        method.invoke(callAudioManager, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue("isMuted must remain true during transient focus loss", callAudioManager.isMuted)

        callAudioManager.stopCallAudio()
        callAudioManager.release()
    }

    @Test
    fun testPermanentFocusLoss_triggersCallback() {
        val permanentLossCalled = AtomicBoolean(false)
        val callAudioManager = CallAudioManager(context, onPermanentFocusLoss = {
            permanentLossCalled.set(true)
        })
        callAudioManager.startCallAudio()

        val method = CallAudioManager::class.java.getDeclaredMethod("handleAudioFocusChange", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        method.invoke(callAudioManager, AudioManager.AUDIOFOCUS_LOSS)

        assertTrue(permanentLossCalled.get())
        callAudioManager.stopCallAudio()
        callAudioManager.release()
    }
}
