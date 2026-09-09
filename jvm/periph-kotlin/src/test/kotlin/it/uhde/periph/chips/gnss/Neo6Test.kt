package it.uhde.periph.chips.gnss

import it.uhde.periph.connection.Neo6ConnectionMock
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Unit tests for [Neo6Minimal] / [Neo6Full] against [Neo6ConnectionMock] — no hardware, no
 * bus. Mirrors the coverage of python/tests/gnss/neo6_test_unit.py and
 * go/periph/chips/gnss/neo6_test.go. The JVM connection library has no SPI connection type,
 * so [BusType] only offers UART and I2C.
 */
class Neo6Test {

    private val eps = 1e-6

    // Field lists are built explicitly and joined with ',' rather than hand-typed as
    // comma-heavy literals, to avoid miscounting empty fields.
    private val ggaFix = listOf(
        "GPGGA", "092750.000", "5321.6802", "N", "00630.3372", "W",
        "1", "08", "1.03", "61.7", "M", "55.2", "M", "", ""
    ).joinToString(",")
    private val ggaNoFix = listOf(
        "GPGGA", "092750.000", "", "", "", "",
        "0", "00", "", "", "", "", "", "", ""
    ).joinToString(",")
    private val rmc = listOf(
        "GPRMC", "092750.000", "A", "5321.6802", "N", "00630.3372", "W",
        "022.4", "084.4", "230394", "003.1", "W", "A"
    ).joinToString(",")
    private val vtg = listOf(
        "GPVTG", "084.4", "T", "077.4", "M", "022.4", "N", "041.5", "K", "A"
    ).joinToString(",")

    /** Builds a "$<body>*XX\r\n" NMEA sentence with a correct XOR checksum. */
    private fun nmeaSentence(body: String): ByteArray {
        var checksum = 0
        for (b in body.toByteArray(StandardCharsets.US_ASCII)) {
            checksum = checksum xor (b.toInt() and 0xFF)
        }
        val s = "$" + body + "*%02X\r\n".format(checksum)
        return s.toByteArray(StandardCharsets.US_ASCII)
    }

    /**
     * Builds a UBX frame with a correct Fletcher checksum, computed here — independent of
     * Neo6Minimal's own (internal) ubxChecksum().
     */
    private fun ubxFrame(msgClass: Int, msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val length = payload.size
        val body = ByteArrayOutputStream()
        body.write(msgClass)
        body.write(msgId)
        body.write(length and 0xFF)
        body.write((length shr 8) and 0xFF)
        body.write(payload)
        val bodyBytes = body.toByteArray()

        var ckA = 0
        var ckB = 0
        for (b in bodyBytes) {
            ckA = (ckA + (b.toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }

        val frame = ByteArrayOutputStream()
        frame.write(0xB5)
        frame.write(0x62)
        frame.write(bodyBytes)
        frame.write(ckA)
        frame.write(ckB)
        return frame.toByteArray()
    }

    /** Queues data and drives update() enough times to consume it all. */
    private fun feed(gps: Neo6Minimal, conn: Neo6ConnectionMock, data: ByteArray): Boolean {
        conn.queueBytes(data)
        var gotFix = false
        for (i in data.indices) {
            if (gps.update()) gotFix = true
        }
        return gotFix
    }

    private fun lastWrite(conn: Neo6ConnectionMock): ByteArray {
        val writes = conn.writes()
        return writes[writes.size - 1]
    }

    // --- Neo6Minimal: GGA decode across both bus types (UART, I2C) ---

    @Test
    fun ggaDecodeAcrossBusTypes() {
        for (busType in BusType.values()) {
            val conn = Neo6ConnectionMock()
            val gps = Neo6Minimal(conn, busType)
            assertEquals(0, gps.fix(), "fix() for $busType")
            assertNull(gps.latitude(), "latitude() for $busType")

            val gotFix = feed(gps, conn, nmeaSentence(ggaFix))
            assertTrue(gotFix, "update() never reported a fix for $busType")
            assertEquals(1, gps.fix())
            assertEquals(8, gps.satellites())
            assertEquals(53.361336667, gps.latitude()!!, eps)
            assertEquals(-6.505620, gps.longitude()!!, eps)
            assertEquals(61.7, gps.altitude()!!, 1e-3)
        }
    }

    // --- Neo6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---

    @Test
    fun noFixKeepsLastPosition() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Minimal(conn)
        feed(gps, conn, nmeaSentence(ggaFix))
        val gotFix = feed(gps, conn, nmeaSentence(ggaNoFix))
        assertFalse(gotFix)
        assertEquals(0, gps.fix())
        assertEquals(53.361336667, gps.latitude()!!, eps)
    }

    // --- Checksum validation: a corrupted sentence is silently discarded ---

    @Test
    fun badChecksumDiscarded() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Minimal(conn)
        val bad = nmeaSentence(ggaFix)
        bad[bad.size - 4] = (bad[bad.size - 4].toInt() xor 0xFF).toByte()
        val gotFix = feed(gps, conn, bad)
        assertFalse(gotFix)
        assertEquals(0, gps.fix())
    }

    // --- Leading 0xFF idle-filler bytes before '$' are ignored ---

    @Test
    fun leadingGarbageIgnored() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Minimal(conn)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + nmeaSentence(ggaFix)
        val gotFix = feed(gps, conn, data)
        assertTrue(gotFix, "update() never reported a fix after leading garbage")
    }

    // --- Neo6Full: RMC (speed/course/utcTime/utcDate) ---

    @Test
    fun rmcFields() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        feed(gps, conn, nmeaSentence(rmc))
        assertEquals(22.4 * 0.514444, gps.speed()!!, 1e-4)
        assertEquals(84.4, gps.course()!!, eps)
        assertEquals("092750.000", gps.utcTime())
        assertEquals("230394", gps.utcDate())
    }

    // --- Neo6Full: VTG (course/speed) ---

    @Test
    fun vtgFields() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        feed(gps, conn, nmeaSentence(vtg))
        assertEquals(84.4, gps.course()!!, eps)
        assertEquals(41.5 / 3.6, gps.speed()!!, 1e-4)
    }

    // --- Neo6Full: GGA-derived HDOP ---

    @Test
    fun ggaHdop() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        feed(gps, conn, nmeaSentence(ggaFix))
        assertEquals(1.03, gps.hdop()!!, eps)
    }

    // --- Neo6Full: sendUbx frames a correct message ---

    @Test
    fun sendUbxFramesCorrectly() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        gps.sendUbx(0x06, 0x08, byteArrayOf(1, 2, 3))
        assertArrayEquals(ubxFrame(0x06, 0x08, byteArrayOf(1, 2, 3)), lastWrite(conn))
    }

    // --- Neo6Full: setRate / setPlatform / coldStart / saveConfig ---

    @Test
    fun setRateSendsCfgRate() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        gps.setRate(5)
        val measRateMs = 1000 / 5
        val expected = byteArrayOf(
            (measRateMs and 0xFF).toByte(), ((measRateMs shr 8) and 0xFF).toByte(),
            1, 0,
            0, 0
        )
        assertArrayEquals(ubxFrame(0x06, 0x08, expected), lastWrite(conn))
    }

    @Test
    fun setPlatformSendsCfgNav5() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        gps.setPlatform(4)
        val expected = ByteArray(36)
        expected[0] = 0x01
        expected[2] = 4
        assertArrayEquals(ubxFrame(0x06, 0x24, expected), lastWrite(conn))
    }

    @Test
    fun coldStartSendsCfgRst() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        gps.coldStart()
        val expected = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x02, 0x00)
        assertArrayEquals(ubxFrame(0x06, 0x04, expected), lastWrite(conn))
    }

    @Test
    fun saveConfigSendsCfgCfg() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        gps.saveConfig()
        val expected = byteArrayOf(
            0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0,
            0x07
        )
        assertArrayEquals(ubxFrame(0x06, 0x09, expected), lastWrite(conn))
    }

    // --- Neo6Full: pollUbx returns the response payload on a matching frame ---

    @Test
    fun pollUbxReturnsPayload() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        val responsePayload = ByteArray(28) { it.toByte() }
        conn.queueBytes(ubxFrame(0x01, 0x02, responsePayload))
        val payload = gps.pollUbx(0x01, 0x02)
        assertArrayEquals(responsePayload, payload)
        assertArrayEquals(ubxFrame(0x01, 0x02), conn.writes()[0])
    }

    // --- Neo6Full: pollUbx throws IOException on an ACK-NAK response ---

    @Test
    fun pollUbxNakThrows() {
        val conn = Neo6ConnectionMock()
        val gps = Neo6Full(conn)
        conn.queueBytes(ubxFrame(0x05, 0x00, byteArrayOf(0x06, 0x08))) // ACK-NAK for CFG-RATE
        assertThrows(IOException::class.java) { gps.pollUbx(0x06, 0x08) }
    }
}
