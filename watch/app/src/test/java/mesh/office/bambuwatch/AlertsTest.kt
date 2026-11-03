package mesh.office.bambuwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Highlight when something is going on" — in-app only, by Václav's decision (23-10). These tests
 * pin that it shouts for the right things and stays quiet otherwise: a banner that cries wolf on
 * a healthy printer is worse than no banner.
 */
class AlertsTest {

    private val now = 1_700_000_000_000L
    private fun st(
        g: String? = "RUNNING",
        err: Long? = 0,
        hms: List<String> = emptyList(),
        trays: List<Tray> = emptyList(),
        connected: Boolean = true,
        asOf: Long? = now
    ) = PrinterState(connected = connected, gcodeState = g, statusWord = "printing", printError = err,
        hms = hms, trays = trays, asOf = asOf)

    @Test
    fun aHealthyPrintingPrinterSaysNothing() {
        assertEquals(emptyList<Alert>(), Alerts.build(st(), now))
    }

    @Test
    fun anErrorCodeIsLoudAndQuotesTheNumber() {
        val a = Alerts.build(st(err = 50348044), now)
        assertTrue("should be BAD", a.any { it.severity == Alert.Severity.BAD })
        assertTrue("must show the raw code",
            a.flatMap { it.detail }.any { it.contains("50348044") })
    }

    /** 0 means "no error", and treating it as one would put a red banner on a happy printer */
    @Test
    fun printErrorZeroIsNotAnAlert() {
        assertTrue(Alerts.build(st(err = 0), now).none { it.severity == Alert.Severity.BAD })
    }

    @Test
    fun hmsCodesAreLoudAndQuotedVerbatim() {
        val a = Alerts.build(st(hms = listOf("HMS_0300_0100_0001_0001")), now)
        val bad = a.first { it.severity == Alert.Severity.BAD }
        assertTrue(bad.detail.any { it == "HMS_0300_0100_0001_0001" })
        assertEquals("one code means one fault", "Printer reports a fault", bad.title)
    }

    @Test
    fun twoFaultsAreCounted() {
        val a = Alerts.build(st(hms = listOf("HMS_A", "HMS_B")), now)
        assertEquals("Printer reports 2 faults", a.first { it.severity == Alert.Severity.BAD }.title)
    }

    @Test
    fun lowFilamentWarnsAndNamesTheSlot() {
        val a = Alerts.build(st(trays = listOf(
            Tray("1", "PLA", "FFFFFFFF", 100, false),
            Tray("2", "PETG", "000000FF", 6, false))), now)
        val warn = a.first { it.severity == Alert.Severity.WARN }
        assertTrue(warn.title.contains("Filament low"))
        assertTrue(warn.detail.any { it.contains("slot 2") && it.contains("6%") })
    }

    @Test
    fun anEmptySlotIsNotLowFilament() {
        assertTrue(Alerts.build(st(trays = listOf(Tray("0", null, null, null, false))), now).isEmpty())
    }

    @Test
    fun aStaleStateIsSaidOutLoud() {
        val a = Alerts.build(st(connected = false, asOf = now - 12 * 60_000), now)
        val warn = a.first { it.severity == Alert.Severity.WARN }
        assertEquals("No fresh data", warn.title)
        assertTrue(warn.detail.first().contains("12 min ago"))
    }

    @Test
    fun pausedIsInformationalNotAlarming() {
        val a = Alerts.build(st(g = "PAUSE"), now)
        assertEquals(1, a.size)
        assertEquals(Alert.Severity.INFO, a[0].severity)
    }

    @Test
    fun aFailedPrintIsBad() {
        assertTrue(Alerts.build(st(g = "FAILED"), now).any { it.severity == Alert.Severity.BAD })
    }

    /** no invented meanings: the table is empty and says so */
    @Test
    fun theCodeTableAdmitsItIsEmpty() {
        assertEquals(0, Codes.tableSize())
        assertEquals(null, Codes.explain("hms:HMS_0300_0100_0001_0001"))
        assertFalse(Codes.UNKNOWN.isBlank())
    }
}

/** The address the printer reports about itself — the fallback that makes the card reachable. */
class PrinterAddressTest {

    /** the value the real A1 sends for 192.168.1.50 */
    @Test
    fun littleEndianBecomesDottedQuad() {
        assertEquals("192.168.1.50", ipv4FromLittleEndian(838969536L))
    }

    @Test
    fun nonsenseIsRefused() {
        org.junit.Assert.assertNull(ipv4FromLittleEndian(0))
        org.junit.Assert.assertNull(ipv4FromLittleEndian(-1))
        org.junit.Assert.assertNull(ipv4FromLittleEndian(0xFFFFFFFFL))
    }

    @Test
    fun theRealReportCarriesIt() {
        val json = javaClass.classLoader!!.getResource("real-a1-report.json")!!.readText()
        assertEquals("192.168.1.50", Reports.parse(org.json.JSONObject(json), null).ip)
    }
}
