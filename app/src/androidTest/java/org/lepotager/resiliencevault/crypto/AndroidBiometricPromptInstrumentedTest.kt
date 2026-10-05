package org.lepotager.resiliencevault.crypto

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.lepotager.resiliencevault.MainActivity

@RunWith(AndroidJUnit4::class)
class AndroidBiometricPromptInstrumentedTest {
    @Test
    fun mainActivityCanOwnCancellableBiometricPrompt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(AndroidBiometricCipherPrompt(activity))
            }
        }
    }

    @Test
    fun authenticatorPolicyNeverAddsLegacyCredentialFallback() {
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            AndroidBiometricCipherPrompt.allowedAuthenticatorsForSdk(29),
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            AndroidBiometricCipherPrompt.allowedAuthenticatorsForSdk(30),
        )
        val current = AndroidBiometricCipherPrompt.allowedAuthenticatorsForSdk(Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT < 30) {
            assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, current)
        }
    }
}
