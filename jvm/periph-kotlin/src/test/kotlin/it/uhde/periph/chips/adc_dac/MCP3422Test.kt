package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException

class MCP3422Test {

    companion object {
        @JvmStatic
        @BeforeAll
        fun noWaits() {
            MCP3422Minimal.realWaits = false
        }

        private fun frame(raw: Int, bits: Int, rdy: Boolean): ByteArray {
            val n = if (bits == 18) 3 else 2
            val out = ByteArray(n + 1)
            for (i in 0 until n) out[i] = (raw shr (8 * (n - 1 - i))).toByte()
            out[n] = (if (rdy) 0x80 else 0x00).toByte()
            return out
        }

        private fun mock(vararg frames: ByteArray): MockConnection {
            val c = MockConnection()
            for (f in frames) c.queueRead(f)
            return c
        }

        private fun lastWrite(c: MockConnection): Int = c.writes().last()[0].toInt() and 0xFF
    }

    @Test
    fun initAndConfigBytes() {
        val c = mock(frame(0, 16, false), frame(0, 16, false), frame(0, 16, false), frame(0, 16, false))
        val chip = MCP3422Full(c)
        assertEquals(1, c.writes().size)
        assertEquals(0x08, lastWrite(c))
        chip.readRaw(0); assertEquals(0x88, lastWrite(c))
        chip.readRaw(1); assertEquals(0xA8, lastWrite(c))
        chip.readRaw(7); assertEquals(0xA8, lastWrite(c))
        chip.readRaw(-3); assertEquals(0x88, lastWrite(c))
    }

    @Test
    fun decoding() {
        val cases = arrayOf(intArrayOf(12, -100), intArrayOf(12, 2047), intArrayOf(12, -2048), intArrayOf(14, -8192),
            intArrayOf(14, 8191), intArrayOf(16, 12345), intArrayOf(16, -12345), intArrayOf(16, -32768),
            intArrayOf(18, 131071), intArrayOf(18, -131072), intArrayOf(18, -1))
        for (cs in cases) {
            val chip = MCP3422Full(mock(frame(cs[1], cs[0], false)))
            chip.setResolution(cs[0])
            assertEquals(cs[1], chip.readRaw(0), "${cs[0]} bit ${cs[1]}")
        }
        val chip = MCP3422Full(mock(byteArrayOf(0xFC.toByte(), 0x00, 0x05, 0x00)))
        chip.setResolution(18)
        assertEquals(5, chip.readRaw(0))
    }

    @Test
    fun voltagesAndDerivedValues() {
        val chip = MCP3422Full(mock(frame(16384, 16, false), frame(-32768, 16, false)))
        assertEquals(16384 * 2 * 2.048 / 65536, chip.readVoltage(0), 1e-9)
        chip.setGain(8)
        assertEquals(-0.256, chip.readVoltage(0), 1e-9)
        assertEquals(0.256, chip.fullScale(), 1e-9)
        assertEquals(62.5e-6, chip.lsb(), 1e-12)
        chip.setResolution(12)
        assertEquals(1e-3, chip.lsb(), 1e-12)
        chip.setResolution(18)
        assertEquals(15.625e-6, chip.lsb(), 1e-12)
    }

    @Test
    fun readAllAndScan() {
        val c = mock(frame(100, 16, false), frame(200, 16, false), frame(10, 16, false), frame(20, 16, false), frame(30, 16, false))
        val chip = MCP3422Full(c)
        val v = chip.readAll()
        assertEquals(100 * 2 * 2.048 / 65536, v[0], 1e-9)
        assertEquals(200 * 2 * 2.048 / 65536, v[1], 1e-9)
        assertEquals(0x88, c.writes()[1][0].toInt() and 0xFF)
        assertEquals(0xA8, c.writes()[2][0].toInt() and 0xFF)
        assertEquals(3, chip.readScan(intArrayOf(1, 0, 1)).size)
        assertEquals(0xA8, c.writes()[3][0].toInt() and 0xFF)
        assertEquals(0x88, c.writes()[4][0].toInt() and 0xFF)
        assertEquals(0xA8, c.writes()[5][0].toInt() and 0xFF)
    }

    @Test
    fun pollingAndTimeout() {
        val chip = MCP3422Full(mock(frame(1, 16, true), frame(1, 16, true), frame(777, 16, false)))
        assertEquals(777, chip.readRaw(0))
        val stale = Array(40) { frame(1, 16, true) }
        val chip2 = MCP3422Full(mock(*stale))
        assertThrows(TimeoutException::class.java) { chip2.readRaw(0) }
    }

    @Test
    fun selection() {
        val c = mock(frame(0, 18, false))
        val chip = MCP3422Full(c)
        assertThrows(IllegalArgumentException::class.java) { chip.setResolution(10) }
        assertThrows(IllegalArgumentException::class.java) { chip.setGain(3) }
        chip.setResolution(18)
        chip.setGain(8)
        assertEquals(18, chip.getResolution())
        assertEquals(8, chip.getGain())
        chip.readRaw(0)
        assertEquals(0x8F, lastWrite(c))
    }

    @Test
    fun continuousMode() {
        val c = mock(frame(5, 12, false), frame(300, 12, false), frame(301, 12, false))
        val chip = MCP3422Full(c)
        chip.setResolution(12)
        chip.startContinuous()
        assertEquals(0x10, lastWrite(c))
        assertTrue(chip.isContinuous())
        assertEquals(300, chip.readContinuousRaw())
        assertEquals(301, chip.readContinuousRaw())
        chip.startContinuous(1)
        assertEquals(0x30, lastWrite(c))
        chip.stopContinuous()
        assertEquals(0x20, lastWrite(c))
        assertFalse(chip.isContinuous())
        assertThrows(IllegalStateException::class.java) { chip.readContinuousRaw() }

        val c2 = mock(frame(1, 12, false), frame(2, 12, false), frame(3, 12, false), frame(4, 12, false))
        val chip2 = MCP3422Full(c2)
        chip2.setResolution(12)
        chip2.startContinuous()
        chip2.readContinuousRaw()
        val n = c2.writes().size
        chip2.setGain(2)
        assertEquals(n + 1, c2.writes().size)
        assertEquals(0x11, lastWrite(c2))
        assertEquals(4, chip2.readContinuousRaw())

        val chip3 = MCP3422Full(mock(frame(5, 16, false), frame(16384, 16, false)))
        chip3.startContinuous()
        assertEquals(16384 * 2 * 2.048 / 65536, chip3.readContinuous(), 1e-9)
    }

    @Test
    fun configAndGeneralCalls() {
        val chip = MCP3422Full(mock(frame(0, 16, true), frame(0, 16, false)))
        assertEquals(0x80, chip.readConfig())
        assertTrue(chip.isReady())

        val c = mock(frame(321, 16, false))
        val chip2 = MCP3422Full(c)
        chip2.setResolution(18)
        chip2.setGain(8)
        chip2.generalCallReset()
        assertArrayEquals(byteArrayOf(0x00, 0x06), c.writes().last())
        assertEquals(12, chip2.getResolution())
        assertEquals(1, chip2.getGain())
        assertTrue(chip2.isContinuous())
        chip2.stopContinuous()
        chip2.setResolution(16)
        chip2.generalCallConvert()
        assertArrayEquals(byteArrayOf(0x00, 0x08), c.writes().last())
        assertEquals(321, chip2.readPendingRaw())
    }
}
