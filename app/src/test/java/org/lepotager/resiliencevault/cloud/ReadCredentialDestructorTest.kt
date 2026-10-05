package org.lepotager.resiliencevault.cloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadCredentialDestructorTest {
    private class Aliases(
        values: Set<String>,
        private val refuseDelete: String? = null,
    ) : CredentialAliasStore {
        val values = values.toMutableSet()
        val deleted = mutableListOf<String>()

        override fun aliases(): Set<String> = values.toSet()

        override fun delete(alias: String) {
            deleted += alias
            if (alias != refuseDelete) values.remove(alias)
        }

        override fun exists(alias: String): Boolean = alias in values
    }

    private class Files(
        var residual: Boolean = true,
        private val fail: Boolean = false,
    ) : ReadCredentialFileStore {
        var destroyCalls = 0

        override fun destroyAll() {
            destroyCalls += 1
            if (fail) throw IOException("disk unavailable")
            residual = false
        }

        override fun hasResidualState(): Boolean = residual
    }

    @Test
    fun panicDeletesOnlyReadCredentialNamespace() {
        val readOne = CredentialNamespaces.READ_ALIAS_PREFIX + "one"
        val readTwo = CredentialNamespaces.READ_ALIAS_PREFIX + "two"
        val deleteOnly = CredentialNamespaces.DELETE_ONLY_ALIAS_PREFIX + "keep"
        val unrelated = "unrelated.alias"
        val aliases = Aliases(setOf(readOne, readTwo, deleteOnly, unrelated))
        val files = Files()

        ReadCredentialDestructor(aliases, files).destroyAll()

        assertFalse(readOne in aliases.values)
        assertFalse(readTwo in aliases.values)
        assertTrue(deleteOnly in aliases.values)
        assertTrue(unrelated in aliases.values)
        assertEquals(setOf(readOne, readTwo), aliases.deleted.toSet())
        assertEquals(1, files.destroyCalls)
        assertFalse(files.residual)
    }

    @Test
    fun unconfirmedAliasDeletionFailsBeforeFilePurge() {
        val read = CredentialNamespaces.READ_ALIAS_PREFIX + "stuck"
        val aliases = Aliases(setOf(read), refuseDelete = read)
        val files = Files()

        assertThrows(IllegalStateException::class.java) {
            ReadCredentialDestructor(aliases, files).destroyAll()
        }

        assertTrue(read in aliases.values)
        assertEquals(0, files.destroyCalls)
    }

    @Test
    fun fileDeletionFailureNeverMeansSuccess() {
        val read = CredentialNamespaces.READ_ALIAS_PREFIX + "one"
        val aliases = Aliases(setOf(read))
        val files = Files(fail = true)

        assertThrows(IOException::class.java) {
            ReadCredentialDestructor(aliases, files).destroyAll()
        }

        assertFalse(read in aliases.values)
        assertTrue(files.residual)
    }

    @Test
    fun emptyOwnerIsIdempotentAndDeleteOnlySurvivesRetries() {
        val deleteOnly = CredentialNamespaces.DELETE_ONLY_ALIAS_PREFIX + "capsule"
        val aliases = Aliases(setOf(deleteOnly))
        val files = Files(residual = false)

        repeat(2) {
            ReadCredentialDestructor(aliases, files).destroyAll()
        }

        assertTrue(deleteOnly in aliases.values)
        assertTrue(aliases.deleted.isEmpty())
        assertEquals(2, files.destroyCalls)
    }
}
