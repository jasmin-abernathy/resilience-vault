package org.lepotager.resiliencevault.crypto

import org.junit.Assert.*
import org.junit.Test

class VaultProvisioningJournalTest {
    private val begin = VaultProvisioningJournal(
        "01".repeat(32), "ab".repeat(32), 1,
        VaultProvisioningPolicy.JournalPhase.BEGIN
    )

    @Test fun fixedLengthRoundTripAndSingleStepTransitions() {
        val encoded = VaultProvisioningJournalCodec.encode(begin)
        assertEquals(VaultProvisioningJournalCodec.FILE_BYTES, encoded.size)
        assertEquals(begin, VaultProvisioningJournalCodec.decode(encoded))
        val created = begin.next(VaultProvisioningPolicy.JournalPhase.KEY_CREATED)
        assertEquals(created, VaultProvisioningJournalCodec.decode(
            VaultProvisioningJournalCodec.encode(created)))
        assertThrows(IllegalArgumentException::class.java) {
            begin.next(VaultProvisioningPolicy.JournalPhase.COMMITTED)
        }
    }

    @Test fun truncationTamperingAndUnknownVersionFailClosed() {
        val encoded = VaultProvisioningJournalCodec.encode(begin)
        for (bytes in listOf(
            encoded.copyOf(encoded.size - 1),
            encoded.copyOf().also { it[15] = (it[15].toInt() xor 1).toByte() },
            encoded.copyOf().also { it[0] = 0 },
            encoded + 0.toByte()
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                VaultProvisioningJournalCodec.decode(bytes)
            }
        }
    }

    @Test fun invalidIdentityAndEpochAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            begin.copy(vaultIdHex = "AA".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            begin.copy(epoch = 0)
        }
    }
}
