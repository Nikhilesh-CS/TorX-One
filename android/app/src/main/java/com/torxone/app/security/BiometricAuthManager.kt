package com.torxone.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class BiometricCapability {
    Available,
    NoneEnrolled,
    NoHardware,
    Unavailable
}

class BiometricAuthManager(private val activity: FragmentActivity) {

    companion object {
        const val PREFS_NAME = "biometric_prefs"
        private const val KEY_PWD_SALT = "pwd_salt"
        private const val KEY_PWD_HASH = "pwd_hash"
        private const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
    }

    private val prefs: SharedPreferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun canAuthenticate(): BiometricCapability {
        val mgr = BiometricManager.from(activity)
        return when (mgr.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricCapability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricCapability.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricCapability.NoHardware
            else -> BiometricCapability.Unavailable
        }
    }

    fun hasPasswordSet(): Boolean {
        return prefs.contains(KEY_PWD_HASH)
    }

    fun setupAppLockWithPassword(password: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hash = hashPassword(password.toCharArray(), salt)
        prefs.edit()
            .putString(KEY_PWD_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PWD_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(KEY_BIOMETRICS_ENABLED, true)
            .apply()
    }

    fun unlockAppWithPassword(password: String): Boolean {
        val saltBase64 = prefs.getString(KEY_PWD_SALT, null) ?: return false
        val expectedHashBase64 = prefs.getString(KEY_PWD_HASH, null) ?: return false
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val hash = hashPassword(password.toCharArray(), salt)
        val computedHashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        return MessageDigest.isEqual(computedHashBase64.toByteArray(), expectedHashBase64.toByteArray())
    }

    fun unlockApp(
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onFailedAttempt: () -> Unit = {}
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    onError(msg.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailedAttempt()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AstraMesh")
            .setSubtitle("Touch the fingerprint sensor or look at the screen to unlock")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError(e.message ?: "Authentication failed")
        }
    }

    private fun hashPassword(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 100_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hash
    }
}
