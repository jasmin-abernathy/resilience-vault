package org.lepotager.resiliencevault.cloud

import android.content.Context
import android.security.keystore.KeyInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.crypto.SecretKeyFactory
import java.security.KeyStore
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI compiles this test. Run it on the physical API/device matrix before opening production gates.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDeleteOnlyCapsuleInstrumentedTest {
    @Test
    fun delete_only_kek_round_trip_requires_no_user_authentication() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = DeleteOnlyCapsuleIdentity(
            capsuleIdHex = "7".repeat(32),
            tenantId = "instrumented",
            vaultIdHex = "8".repeat(64),
            vaultGenerationHex = "9".repeat(64),
            serviceId = "test-service",
        )
        val token = ByteArray(DeleteOnlyCredential.TOKEN_BYTES) { (it + 3).toByte() }
        val alias = CredentialNamespaces.DELETE_ONLY_ALIAS_PREFIX +
            identity.vaultIdHex + "." + identity.vaultGenerationHex
        val file = File(
            CredentialNamespaces.deleteOnlyDirectory(context),
            identity.capsuleIdHex + ".bin",
        )

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            file.delete()

            val owner = AndroidDeleteOnlyCapsuleOwner(context)
            val intent = owner.provision(
                identity,
                DeleteOnlyCredential.fromBytes(token),
            )
            assertArrayEquals(token, owner.open(intent).copyToken())

            val key = keyStore.getKey(alias, null) as SecretKey
            val keyFactory = SecretKeyFactory.getInstance(
                key.algorithm,
                "AndroidKeyStore",
            )
            val info = keyFactory.getKeySpec(key, KeyInfo::class.java)
            val authRequired = KeyInfo::class.java
                .getMethod("isUserAuthenticationRequired")
                .invoke(info) as Boolean
            assertFalse(authRequired)
        } finally {
            val cleanupStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (cleanupStore.containsAlias(alias)) cleanupStore.deleteEntry(alias)
            file.delete()
        }
    }
}
