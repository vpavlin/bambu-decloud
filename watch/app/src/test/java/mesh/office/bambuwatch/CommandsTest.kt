package mesh.office.bambuwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only is a rule, not a label. These tests are the rule.
 * Any payload not explicitly classified as a state request must be refused.
 */
class CommandsTest {

    @Test
    fun pushAllIsAllowedInReadOnly() {
        assertTrue(Commands.isReadOnlySafe(Commands.PUSH_ALL))
    }

    @Test
    fun pauseIsRefusedInReadOnly() {
        assertFalse(Commands.isReadOnlySafe(Commands.pause("1")))
    }

    @Test
    fun resumeIsRefusedInReadOnly() {
        assertFalse(Commands.isReadOnlySafe(Commands.resume("1")))
    }

    @Test
    fun stopIsRefusedInReadOnly() {
        assertFalse(Commands.isReadOnlySafe(Commands.stop("1")))
    }

    /** a stop dressed up as a read request must not pass */
    @Test
    fun pushAllCarryingAnExtraCommandIsRefused() {
        assertFalse(Commands.isReadOnlySafe(
            """{"pushing":{"sequence_id":"0","command":"pushall"},"print":{"command":"stop"}}"""))
    }

    @Test
    fun pushAllWithAnotherCommandIsRefused() {
        assertFalse(Commands.isReadOnlySafe("""{"pushing":{"sequence_id":"0","command":"pushtest"}}"""))
    }

    @Test
    fun garbageIsRefused() {
        assertFalse(Commands.isReadOnlySafe("not json"))
        assertFalse(Commands.isReadOnlySafe(""))
        assertFalse(Commands.isReadOnlySafe("{}"))
    }

    @Test
    fun controlPayloadsCarryTheirCommand() {
        assertTrue(Commands.stop("7").contains("\"command\":\"stop\""))
        assertTrue(Commands.stop("7").contains("\"sequence_id\":\"7\""))
        assertTrue(Commands.pause("8").contains("\"pause\""))
        assertTrue(Commands.resume("9").contains("\"resume\""))
    }
}

/** The decision Printer.send() defers to, tested directly rather than through a copy of it. */
class ReadOnlyPolicyTest {

    @Test
    fun pushAllPassesWhenReadOnly() {
        org.junit.Assert.assertNull(Commands.refuseIfReadOnly(true, Commands.PUSH_ALL))
    }

    @Test
    fun stopIsRefusedWithAReason() {
        org.junit.Assert.assertEquals(
            Commands.READ_ONLY_REFUSAL, Commands.refuseIfReadOnly(true, Commands.stop("1")))
    }

    @Test
    fun pauseAndResumeAreRefusedWhenReadOnly() {
        listOf(Commands.pause("1"), Commands.resume("1")).forEach {
            org.junit.Assert.assertEquals(Commands.READ_ONLY_REFUSAL, Commands.refuseIfReadOnly(true, it))
        }
    }

    @Test
    fun commandsPassOnceReadOnlyIsOff() {
        listOf(Commands.pause("1"), Commands.resume("1"), Commands.stop("1"), Commands.PUSH_ALL).forEach {
            org.junit.Assert.assertNull(Commands.refuseIfReadOnly(false, it))
        }
    }
}

/** Reprint uses filament and cannot be undone: it is a command like any other. */
class ReprintTest {

    @Test
    fun reprintIsRefusedWhileReadOnly() {
        val p = Commands.reprint("1", "Metadata/plate_1.gcode", "job")
        org.junit.Assert.assertEquals(Commands.READ_ONLY_REFUSAL, Commands.refuseIfReadOnly(true, p))
    }

    @Test
    fun reprintCarriesTheFileAndTheName() {
        val p = Commands.reprint("7", "Metadata/plate_1.gcode", "lambda-bookend")
        org.junit.Assert.assertTrue(p.contains("\"command\":\"project_file\""))
        org.junit.Assert.assertTrue(p.contains("\"file\":\"Metadata/plate_1.gcode\""))
        org.junit.Assert.assertTrue(p.contains("\"subtask_name\":\"lambda-bookend\""))
        org.junit.Assert.assertTrue(p.contains("\"sequence_id\":\"7\""))
    }

    /** a quote in a file name must not break the JSON or, worse, inject a command */
    @Test
    fun quotesAreStripped() {
        val p = Commands.reprint("1", "a\"b.gcode", "na\"me")
        org.json.JSONObject(p)   // throws if the payload is malformed
    }
}

/**
 * The print payloads, checked against the reference shapes rather than against my memory of them.
 * Source: OpenBambuAPI mqtt.md — "print.gcode_file" and "print.project_file".
 */
