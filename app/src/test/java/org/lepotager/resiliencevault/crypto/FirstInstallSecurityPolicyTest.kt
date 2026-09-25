package org.lepotager.resiliencevault.crypto

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lepotager.resiliencevault.panic.PanicPersistentState
import org.lepotager.resiliencevault.panic.PanicStoreFailure
import org.lepotager.resiliencevault.panic.PanicStoreReadResult

class FirstInstallSecurityPolicyTest {
    private val missing = PanicStoreReadResult.Unavailable(PanicStoreFailure.MISSING)

    @Test
    fun onlyMissingUntouchedInstallIsEligible() {
        assertEquals(
            FirstInstallSecurityStatus.Eligible,
            FirstInstallSecurityPolicy.evaluate(
                panic = missing,
                firstInstallTime = 100,
                lastUpdateTime = 100,
                securityEntries = emptyList(),
                aliases = emptySet(),
            ),
        )
    }

    @Test
    fun existingPanicStateIsAlreadyInitialized() {
        assertEquals(
            FirstInstallSecurityStatus.AlreadyInitialized,
            FirstInstallSecurityPolicy.evaluate(
                panic = PanicStoreReadResult.Ready(PanicPersistentState.initial()),
                firstInstallTime = 100,
                lastUpdateTime = 200,
                securityEntries = listOf("anything"),
                aliases = setOf("rv.kek.v1.anything"),
            ),
        )
    }

    @Test
    fun corruptOrUnreadablePanicStateNeverBecomesFresh() {
        for (failure in listOf(
            PanicStoreFailure.CORRUPT,
            PanicStoreFailure.IO_ERROR,
            PanicStoreFailure.COMMIT_FAILED,
        )) {
            assertEquals(
                FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PANIC_STATE_UNAVAILABLE),
                FirstInstallSecurityPolicy.evaluate(
                    panic = PanicStoreReadResult.Unavailable(failure),
                    firstInstallTime = 100,
                    lastUpdateTime = 100,
                    securityEntries = emptyList(),
                    aliases = emptySet(),
                ),
            )
        }
    }

    @Test
    fun packageUpdateSecurityFootprintOrVaultAliasRefuseInitialization() {
        assertEquals(
            FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PACKAGE_ALREADY_UPDATED),
            FirstInstallSecurityPolicy.evaluate(missing, 100, 101, emptyList(), emptySet()),
        )
        assertEquals(
            FirstInstallSecurityStatus.Refused(FirstInstallRefusal.SECURITY_FOOTPRINT_PRESENT),
            FirstInstallSecurityPolicy.evaluate(
                missing, 100, 100, listOf("provisioning"), emptySet()
            ),
        )
        assertEquals(
            FirstInstallSecurityStatus.Refused(FirstInstallRefusal.VAULT_KEK_PRESENT),
            FirstInstallSecurityPolicy.evaluate(
                missing, 100, 100, emptyList(), setOf("rv.kek.v1.old")
            ),
        )
    }

    @Test
    fun unrelatedKeystoreAliasesDoNotBlockFreshSecurityBootstrap() {
        assertEquals(
            FirstInstallSecurityStatus.Eligible,
            FirstInstallSecurityPolicy.evaluate(
                missing,
                100,
                100,
                emptyList(),
                setOf("other.app.namespace"),
            ),
        )
    }
    @Test
    fun invalidPackageTimestampsFailClosed() {
        assertEquals(
            FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PLATFORM_EVIDENCE_UNAVAILABLE),
            FirstInstallSecurityPolicy.evaluate(missing, 0, 0, emptyList(), emptySet()),
        )
        assertEquals(
            FirstInstallSecurityStatus.Refused(FirstInstallRefusal.PLATFORM_EVIDENCE_UNAVAILABLE),
            FirstInstallSecurityPolicy.evaluate(missing, 200, 100, emptyList(), emptySet()),
        )
    }

}
