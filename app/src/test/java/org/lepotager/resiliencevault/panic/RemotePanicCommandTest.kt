package org.lepotager.resiliencevault.panic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePanicCommandTest {
    @Test
    fun exact_command_round_trips() {
        val generation = "a".repeat(64)
        val secret = "b".repeat(64)
        val command = RemotePanicCommand.build(generation, secret)
        assertEquals(133, command.length)
        assertEquals(RemotePanicCommand.Parsed(generation, secret), RemotePanicCommand.parseExact(command))
    }

    @Test
    fun grammar_rejects_whitespace_case_and_unicode_variants() {
        val command = RemotePanicCommand.build("a".repeat(64), "b".repeat(64))
        assertNull(RemotePanicCommand.parseExact(" $command"))
        assertNull(RemotePanicCommand.parseExact("$command\n"))
        assertNull(RemotePanicCommand.parseExact(command.uppercase()))
        assertNull(RemotePanicCommand.parseExact(command.replace(" ", "\u00a0")))
    }

    @Test
    fun verifier_is_bound_to_generation_contact_and_secret() {
        val generation = "a".repeat(64)
        val secret = "b".repeat(64)
        val number = "+33600000000"
        val verifier = RemotePanicCommand.verifierHex(generation, number, secret)

        assertTrue(RemotePanicCommand.verifierMatches(verifier, generation, number, secret))
        assertFalse(RemotePanicCommand.verifierMatches(verifier, generation, "+33600000001", secret))
        assertFalse(RemotePanicCommand.verifierMatches(verifier, "c".repeat(64), number, secret))
    }
}
