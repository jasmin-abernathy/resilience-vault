package org.lepotager.resiliencevault.panic

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstInstallSecurityInspectorTest {
    @Test
    fun emptyNoBackupTreeWithNoVaultAliasIsPristine() {
        val root = Files.createTempDirectory("rv-first-install-empty").toFile()
        try {
            root.resolve("security/epochs").mkdirs()
            assertEquals(
                FirstInstallSecurityEvidence.PRISTINE,
                FirstInstallSecurityInspector.inspect(
                    root,
                    setOf("unrelated.alias"),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anyPersistedFileBlocksFreshInitialization() {
        val root = Files.createTempDirectory("rv-first-install-file").toFile()
        try {
            val artifact = root.resolve("security/provisioning/orphan.bin")
            artifact.parentFile!!.mkdirs()
            artifact.writeBytes(byteArrayOf(1))
            assertEquals(
                FirstInstallSecurityEvidence.FILES_PRESENT,
                FirstInstallSecurityInspector.inspect(root, emptySet()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicFileResidueAlsoBlocksFreshInitialization() {
        val root = Files.createTempDirectory("rv-first-install-residue").toFile()
        try {
            val residue = root.resolve("security/remote-panic-state.bin.new")
            residue.parentFile!!.mkdirs()
            residue.writeBytes(byteArrayOf(1))
            assertEquals(
                FirstInstallSecurityEvidence.FILES_PRESENT,
                FirstInstallSecurityInspector.inspect(root, emptySet()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resilienceVaultKekAliasBlocksEvenWhenDiskIsEmpty() {
        val root = Files.createTempDirectory("rv-first-install-kek").toFile()
        try {
            assertEquals(
                FirstInstallSecurityEvidence.LOCAL_KEK_PRESENT,
                FirstInstallSecurityInspector.inspect(
                    root,
                    setOf("rv.kek.v1." + "11".repeat(32)),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
