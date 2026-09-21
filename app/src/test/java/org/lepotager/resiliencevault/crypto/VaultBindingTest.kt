package org.lepotager.resiliencevault.crypto

import org.junit.Assert.*
import org.junit.Test

class VaultBindingTest {
    private val binding = VaultBinding(VaultBinding.Purpose.OBJECT_DATA,
        "00".repeat(32), "11".repeat(32), "22".repeat(32), 1L, 2L)

    @Test
    fun canonical_cross_language_vector_is_exactly_120_bytes() {
        val expectedHex = "5256423100010101" + "00".repeat(32) + "11".repeat(32) +
            "22".repeat(32) + "00000000000000010000000000000002"
        val expected = expectedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(120, binding.associatedData().size)
        assertArrayEquals(expected, binding.associatedData())
    }

    @Test
    fun every_domain_field_changes_binding() {
        val alternatives = listOf(
            binding.copy(purpose = VaultBinding.Purpose.OBJECT_KEY),
            binding.copy(vaultIdHex = "33".repeat(32)),
            binding.copy(generationHex = "33".repeat(32)),
            binding.copy(objectIdHex = "33".repeat(32)),
            binding.copy(keyEpoch = 2L), binding.copy(revision = 3L)
        )
        alternatives.forEach { assertFalse(binding.associatedData().contentEquals(it.associatedData())) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun noncanonical_identifier_is_rejected() { binding.copy(vaultIdHex = "AB".repeat(32)).associatedData() }

    @Test(expected = IllegalArgumentException::class)
    fun negative_revision_is_rejected() { binding.copy(revision = -1L).associatedData() }
}
