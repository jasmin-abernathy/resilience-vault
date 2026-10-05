package org.lepotager.resiliencevault.crypto

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class VaultAliasDestructionTest {
    @Test fun allEpochsRotationsAndOrphansAreDeletedWithoutTouchingOtherNamespaces() {
        val keys = mutableSetOf("rv.kek.v1.old.e1", "rv.kek.v1.old.e1.r2", "rv.kek.v1.orphan.e7", "other.key")
        val destroyer = VaultAliasDestruction({ keys.toSet() }, { keys.remove(it); Unit }, { it in keys })
        destroyer.destroyAll(); destroyer.destroyAll()
        assertEquals(setOf("other.key"), keys)
    }
    @Test fun partialFailureStillAttemptsOthersAndRetryConfirmsAbsence() {
        val keys = mutableSetOf("rv.kek.v1.a", "rv.kek.v1.b")
        var fail = true
        val destroyer = VaultAliasDestruction({ keys.toSet() }, {
            if (fail && it.endsWith("a")) throw IOException("unavailable")
            keys.remove(it); Unit
        }, { it in keys })
        assertThrows(Exception::class.java) { destroyer.destroyAll() }
        assertEquals(setOf("rv.kek.v1.a"), keys)
        fail = false; destroyer.destroyAll(); assertTrue(keys.isEmpty())
    }
    @Test fun enumerationFailureAndSilentDeletionAreNotAbsence() {
        assertThrows(Exception::class.java) {
            VaultAliasDestruction({ throw IOException("unavailable") }, {}, { false }).destroyAll()
        }
        assertThrows(Exception::class.java) {
            VaultAliasDestruction({ setOf("rv.kek.v1.a") }, {}, { true }).destroyAll()
        }
    }
}
