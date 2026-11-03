package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * The bundled error table, tested against the real asset rather than a fixture of it.
 *
 * Uncompressed it is ~680 kB of Bambu's own wording; a wrong lookup would put a confident, wrong
 * sentence in front of somebody at 2 a.m., so the two mappings are pinned here.
 */
class ErrorTextTest {

    private val table: Map<String, String> by lazy {
        val f = java.io.File("src/main/assets/errors.tsv.gz")
        GZIPInputStream(f.inputStream()).bufferedReader().useLines { ErrorText.parse(it) }
    }

    @Test
    fun theTableIsSubstantial() {
        assertTrue("expected thousands of codes, got ${table.size}", table.size > 5_000)
    }

    /** the code Václav's printer reported: 83935248 = 0x0500C010 */
    @Test
    fun hisPrintErrorSaysTheCardIsFaulty() {
        val text = table["0500C010"]
        assertNotNull("0500C010 must be in the table", text)
        assertTrue("unexpected wording: $text", text!!.lowercase().contains("microsd"))
    }

    @Test
    fun printErrorsAreLookedUpAsHex() {
        assertEquals("%08X".format(83935248), "0500C010")
        assertNotNull(table["%08X".format(83935248)])
        assertEquals(table["0500C010"], ErrorText.explainOrNull(table, "print_error:83935248"))
    }

    /** HMS arrives as HMS_0300_0100_0001_0001 and the table wants 0300010000010001 */
    @Test
    fun hmsCodesAreNormalised() {
        val text = ErrorText.explainOrNull(table, "hms:HMS_0300_0100_0001_0001")
        assertNotNull("HMS normalisation failed", text)
        assertTrue("expected a heatbed message: $text", text!!.lowercase().contains("heatbed"))
    }

    @Test
    fun anUnknownCodeGivesNothingRatherThanAGuess() {
        assertNull(ErrorText.explainOrNull(table, "print_error:1"))
        assertNull(ErrorText.explainOrNull(table, "hms:HMS_FFFF_FFFF_FFFF_FFFF"))
        assertNull(ErrorText.explainOrNull(table, "not a code"))
    }
}
