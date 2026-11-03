package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the card listing means, and what to ask the printer to print from it. */
class CardFileTest {

    @Test
    fun gcodeAndThreeMfArePrintable() {
        assertTrue(CardFile.printable("Bambu Spool_plate_1.gcode"))
        assertTrue(CardFile.printable("test.3mf"))
        assertTrue(CardFile.printable("status-network-earing.gcode.3mf"))
    }

    @Test
    fun otherThingsOnTheCardAreNot() {
        assertFalse(CardFile.printable("verify_job"))
        assertFalse(CardFile.printable("logger"))
        assertFalse(CardFile.printable("preview.jpg"))
    }

    /** a 3mf is printed through the gcode inside it; a bare gcode is printed as itself */
    @Test
    fun thePrintPathDependsOnTheFileKind() {
        assertEquals("Metadata/plate_1.gcode",
            CardFile.printPath(CardFile("test.3mf", "test.3mf", 1, 0)))
        assertEquals("Bambu Spool_plate_1.gcode",
            CardFile.printPath(CardFile("Bambu Spool_plate_1.gcode", "Bambu Spool_plate_1.gcode", 1, 0)))
    }

    @Test
    fun sizesReadLikeSomething() {
        assertEquals("512 B", CardFile.humanSize(512))
        assertEquals("12 kB", CardFile.humanSize(12_000))
        assertEquals("1.8 MB", CardFile.humanSize(1_786_537))
    }
}
