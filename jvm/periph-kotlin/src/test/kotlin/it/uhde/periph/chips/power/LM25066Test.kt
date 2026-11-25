package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class LM25066Test {

    /** One byte string per PMBus command; writes do not overlap neighbours. */
    class CmdMock : MockConnection() {
        val cmds = HashMap<Int, ByteArray>()

        fun word(cmd: Int, v: Int) {
            cmds[cmd] = byteArrayOf(v.toByte(), (v shr 8).toByte())
        }

        fun bytes(cmd: Int, vararg v: Int) {
            cmds[cmd] = ByteArray(v.size) { v[it].toByte() }
        }

        fun block(cmd: Int, vararg words: Int) {
            val d = ByteArray(1 + 2 * words.size)
            d[0] = 12
            for (i in words.indices) {
                d[1 + 2 * i] = words[i].toByte()
                d[2 + 2 * i] = (words[i] shr 8).toByte()
            }
            cmds[cmd] = d
        }

        @Throws(IOException::class)
        override fun read(reg: Int, length: Int): ByteArray {
            val out = ByteArray(length)
            cmds[reg]?.let { System.arraycopy(it, 0, out, 0, minOf(length, it.size)) }
            return out
        }

        @Throws(IOException::class)
        override fun write(reg: Int, data: ByteArray) {
            writes().add(byteArrayOf(reg.toByte()) + data)
            if (data.isNotEmpty()) cmds[reg] = data.clone()
        }

        fun lastIs(vararg want: Int): Boolean {
            val w = writes().last()
            return w.size == want.size && w.indices.all { (w[it].toInt() and 0xFF) == want[it] }
        }
    }

    private fun near(want: Double, got: Double) = assertEquals(want, got, 1e-3)

    @Test
    fun minimalClGnd() {
        val conn = CmdMock()
        val m = LM25066Minimal(conn, 5.0, false)
        assertTrue(conn.writes().isEmpty())
        conn.word(0x88, 2000)
        near(201800.0 / 22070.0, m.voltageIn())
        conn.word(0x8B, 0xF000 or 1500)
        near(151800.0 / 22070.0, m.voltageOut())
        conn.word(0xD1, 648)
        near(70000.0 / 68305.0, m.current())
        conn.word(0xD2, 1000)
        near(103300.0 / (736.0 * 5.0), m.power())
        conn.word(0x8D, 0x0190)
        near(25.0, m.temperature())
        conn.word(0x8D, 0xFF00)
        near(-16.0, m.temperature())
    }

    @Test
    fun minimalClVdd() {
        val conn = CmdMock()
        val m = LM25066Minimal(conn, 5.0, true)
        conn.word(0xD1, 648)
        near(67900.0 / (6854.0 * 5.0), m.current())
        conn.word(0xD2, 1000)
        near(101900.0 / (369.0 * 5.0), m.power())
    }

    @Test
    fun blocksAndAux() {
        val conn = CmdMock()
        val f = LM25066Full(conn)
        conn.word(0xD0, 3546 - 3)
        near(1.0, f.voltageAux())
        conn.block(0xDA, 0x0460, 648, 1500, 2000, 1000, 0x0190)
        val b = f.readBlock()
        assertEquals(0x0460, b.diagnostic)
        near(70000.0 / 13661.0, b.current)
        near(151800.0 / 22070.0, b.voltageOut)
        near(201800.0 / 22070.0, b.voltageIn)
        near(103300.0 / 736.0, b.power)
        near(25.0, b.temperature)
        conn.block(0xE2, 0, 0, 0, 0, 0, 0)
        near(1800.0 / 22070.0, f.readAverage().voltageIn)
        conn.block(0xE0, 1, 0, 0, 0, 0, 0)
        assertEquals(1, f.blackBox().diagnostic)
        conn.cmds[0xDA] = ByteArray(13).also { it[0] = 5 }
        assertThrows(IOException::class.java) { f.readBlock() }
    }

    @Test
    fun averagesAndPeak() {
        val conn = CmdMock()
        val f = LM25066Full(conn)
        conn.word(0xDC, 2000)
        near(201800.0 / 22070.0, f.voltageInAvg())
        conn.word(0xD5, 1000)
        near(103300.0 / 736.0, f.powerPeak())
        f.clearPowerPeak()
        assertTrue(conn.lastIs(0xD6))
        f.setAveraging(6)
        assertTrue(conn.lastIs(0xDB, 6))
        assertThrows(IllegalArgumentException::class.java) { f.setAveraging(13) }
        assertEquals(6, f.getAveraging())
    }

    @Test
    fun limits() {
        val conn = CmdMock()
        val f = LM25066Full(conn)
        f.setVinUvWarn(10.0)
        assertTrue(conn.lastIs(0x58, 0x8D, 0x08))
        f.setVinUvWarn(null)
        assertTrue(conn.lastIs(0x58, 0, 0))
        f.setVinOvWarn(null)
        assertTrue(conn.lastIs(0x57, 0xFF, 0x0F))
        f.setOtFault(150.0)
        assertTrue(conn.lastIs(0x4F, 0x60, 0x09))
        f.setOtWarn(125.0)
        assertTrue(conn.lastIs(0x51, 0xD0, 0x07))
        f.setPinWarn(1e9)
        assertTrue(conn.lastIs(0xD4, 0xFE, 0x0F))
        f.setIinWarn(1.02)
        val y = Math.round((13661 * 1.02 - 5200) / 100.0).toInt()
        assertTrue(conn.lastIs(0xD3, y and 0xFF, y shr 8))
        conn.word(0x57, 0x0FFF)
        conn.word(0x58, 0)
        conn.word(0x43, 0)
        conn.word(0x4F, 0x0960)
        conn.word(0x51, 0x07D0)
        conn.word(0xD3, 0x0FFF)
        conn.word(0xD4, 0x0FFF)
        val l = f.getLimits()
        assertNull(l.vinOvWarn)
        assertNull(l.vinUvWarn)
        assertNull(l.voutUvWarn)
        assertNull(l.iinWarn)
        assertNull(l.pinWarn)
        assertNotNull(l.otFault)
        near(150.0, l.otFault!!)
        near(125.0, l.otWarn!!)
    }

    @Test
    fun statusAndControl() {
        val conn = CmdMock()
        val f = LM25066Full(conn)
        conn.bytes(0x78, 0x49)
        conn.word(0x79, 0x3849)
        conn.bytes(0x7A, 0x20)
        conn.bytes(0x7C, 0x10)
        conn.bytes(0x7D, 0x80)
        conn.bytes(0x7E, 0x02)
        conn.bytes(0x80, 0x10)
        conn.word(0xE1, 0x0460)
        assertEquals(0x49, f.statusByte())
        assertEquals(0x3849, f.statusWord())
        assertEquals(0x20, f.statusVout())
        assertEquals(0x10, f.statusInput())
        assertEquals(0x80, f.statusTemperature())
        assertEquals(0x02, f.statusCml())
        assertEquals(0x10, f.statusMfr())
        assertEquals(0x0460, f.diagnosticWord())
        assertEquals(0x0460, f.pollInterrupt())
        f.clearFaults()
        assertTrue(conn.lastIs(0x03))
        f.operationOn()
        assertTrue(conn.lastIs(0x01, 0x80))
        f.operationOff()
        assertTrue(conn.lastIs(0x01, 0x00))
        conn.bytes(0x01, 0x80)
        conn.bytes(0x78, 0x00)
        assertTrue(f.isOn())
        conn.bytes(0x78, 0x40)
        assertFalse(f.isOn())
    }

    @Test
    fun deviceSetupGateMaskAndAlerts() {
        val conn = CmdMock()
        val f = LM25066Full(conn)
        f.configureDevice(7, true, false)
        assertTrue(conn.lastIs(0xD9, 0xE0 or 0x04 or 0x10 or 0x02))
        conn.word(0xD1, 648)
        near(67900.0 / 6854.0, f.current())
        assertThrows(IllegalArgumentException::class.java) { f.configureDevice(8) }
        conn.bytes(0xD9, 0x16)
        assertEquals(0x16, f.deviceSetup())
        f.setGateMask(0x04)
        assertTrue(conn.lastIs(0xD7, 0x04))
        assertThrows(IllegalArgumentException::class.java) { f.setGateMask(0x01) }
        assertEquals(0x04, f.getGateMask())
        conn.bytes(0x19, 0xB0)
        assertEquals(0xB0, f.capability())
        conn.bytes(0x99, 3, 'N'.code, 'S'.code, 'C'.code)
        conn.bytes(0x9A, 8, 'L'.code, 'M'.code, '2'.code, '5'.code, '0'.code, '6'.code, '6'.code, 0)
        conn.bytes(0x9B, 2, 'A'.code, 'A'.code)
        assertEquals("NSC", f.manufacturerId())
        assertEquals("LM25066", f.model())
        assertEquals("AA", f.revision())
        conn.word(0xD8, 0x0820)
        f.enableInterrupt(LM25066Full.SOURCE_VIN_UV_FAULT)
        assertTrue(conn.lastIs(0xD8, 0x00, 0x08))
        f.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN)
        assertTrue(conn.lastIs(0xD8, 0x00, 0x28))
    }
}
