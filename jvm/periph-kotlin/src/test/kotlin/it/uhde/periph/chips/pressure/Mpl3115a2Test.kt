package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.function.IntConsumer

class Mpl3115a2Test {

    /** RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip. */
    private class Mock : MockConnection() {
        override fun write(data: ByteArray) {
            super.write(data)
            if (data.size == 2 && (data[0].toInt() and 0xFF) == 0x26) setRegister(0x26, data[1].toInt() and 0x06.inv() and 0xFF)
        }
    }

    private lateinit var c: Mock
    private lateinit var s: Mpl3115a2Full

    private fun make() {
        c = Mock()
        c.setRegister(0x0C, 0xC4)
        c.setRegister(0x06, 0x0E)
        c.setRegister(0x01, 0x62, 0xF3, 0x80, 0x19, 0x80)   // 101326.0 Pa, 25.5 °C
        s = Mpl3115a2Full(c)
    }

    private fun last(reg: Int, idx: Int): Int {
        var v = -1
        for (w in c.writes()) if (w.size > idx && (w[0].toInt() and 0xFF) == reg) v = w[idx].toInt() and 0xFF
        return v
    }

    @Test
    fun initConfiguresChip() {
        make()
        assertTrue(c.writes().isEmpty())
        s.init()
        assertEquals(0x07, last(0x13, 1))
        assertEquals(0x38, last(0x26, 1))
    }

    @Test
    fun initRejectsWrongChip() {
        make()
        c.setRegister(0x0C, 0x00)
        assertThrows(IOException::class.java) { s.init() }
    }

    @Test
    fun readConverts() {
        make()
        s.init()
        val m = s.read()
        assertEquals(101326.0, m[0], 1e-6)
        assertEquals(25.5, m[1], 1e-6)
        assertEquals(0x3A, last(0x26, 1))
        c.setRegister(0x04, 0xE7, 0x00)
        assertEquals(-25.0, s.readTemperature(), 1e-6)
    }

    @Test
    fun altitudeAndBackToBarometer() {
        make()
        s.init()
        c.setRegister(0x01, 0x00, 0x7B, 0x30)
        assertEquals(123.1875, s.readAltitude(), 1e-6)
        assertEquals(0xBA, last(0x26, 1))
        c.setRegister(0x01, 0xFF, 0x9C, 0x00)
        assertEquals(-100.0, s.readAltitude(), 1e-6)
        c.setRegister(0x01, 0x62, 0xF3, 0x80)
        assertEquals(101326.0, s.read()[0], 1e-6)
    }

    @Test
    fun oversample() {
        make()
        s.init()
        s.setOversample(16)
        assertEquals(16, s.getOversample())
        assertEquals(0x20, last(0x26, 1))
        assertThrows(IllegalArgumentException::class.java) { s.setOversample(3) }
    }

    @Test
    fun configurationWrites() {
        make()
        s.init()
        s.setSeaLevelPressure(101326.0)
        assertEquals(0xC5, last(0x14, 1))
        assertEquals(0xE7, last(0x14, 2))
        s.setOffsets(-8.0, 1.0, -3)
        assertEquals(0xFE, last(0x2B, 1))
        assertEquals(16, last(0x2C, 1))
        assertEquals(0xFD, last(0x2D, 1))
        assertThrows(IllegalArgumentException::class.java) { s.setOffsets(1000.0) }
        s.setTarget(100000.0, 200.0, -5, 3)
        assertEquals(0xC3, last(0x16, 1))
        assertEquals(0x50, last(0x16, 2))
        assertEquals(0xFB, last(0x18, 1))
        assertEquals(0x64, last(0x19, 2))
        assertEquals(3, last(0x1B, 1))
    }

    @Test
    fun continuousAndDelta() {
        make()
        s.init()
        s.startContinuous(2)
        assertEquals(2, last(0x27, 1) and 0x0F)
        assertEquals(0x39, last(0x26, 1))
        assertThrows(IllegalArgumentException::class.java) { s.startContinuous(16) }
        assertTrue(s.dataReady())
        val v = s.readContinuous()
        assertEquals(101326.0, v[0], 1e-6)
        assertEquals(25.5, v[1], 1e-6)
        s.stopContinuous()
        assertEquals(0x38, last(0x26, 1))
        c.setRegister(0x07, 0x00, 0x00, 0x40, 0x00, 0x10)
        val d = s.readDelta()
        assertEquals(1.0, d[0], 1e-6)
        assertEquals(0.0625, d[1], 1e-6)
    }

    @Test
    fun minMaxAndFifo() {
        make()
        s.init()
        c.setRegister(0x1C, 0x62, 0xF3, 0x80, 0x19, 0x80)
        assertEquals(101326.0, s.readMinMax()[0], 1e-6)
        s.clearMinMax()
        assertEquals(0, last(0x1C, 3))
        s.fifoSetup(1, 16)
        assertEquals(0x50, last(0x0F, 1))
        assertThrows(IllegalArgumentException::class.java) { s.fifoSetup(2) }
        c.setRegister(0x0D, 0xC2)
        assertArrayEquals(intArrayOf(1, 1, 2), s.fifoStatus())
        c.setRegister(0x0E, 0x62, 0xF3, 0x80, 0x19, 0x80)
        val samples = s.fifoRead()
        assertEquals(2, samples.size)
        assertEquals(101326.0, samples[0][0], 1e-6)
    }

    @Test
    fun interrupts() {
        make()
        s.init()
        s.setInterruptPins(true, true)
        assertEquals(0x30, last(0x28, 1))
        s.enableInterrupt(Mpl3115a2Full.SOURCE_TTH, 1)
        assertNotEquals(0, last(0x29, 1) and 0x04)
        assertNotEquals(0, last(0x2A, 1) and 0x04)
        s.enableInterrupt(Mpl3115a2Full.SOURCE_DRDY, 2)
        assertEquals(0, last(0x2A, 1) and 0x80)
        s.disableInterrupt(Mpl3115a2Full.SOURCE_TTH)
        assertEquals(0, last(0x29, 1) and 0x04)
        assertThrows(IllegalArgumentException::class.java) { s.enableInterrupt(1, 3) }
        c.setRegister(0x12, 0x84)
        assertEquals(0x84, s.pollInterrupt())

        c.setRegister(0x2A, 0x04)
        val got = intArrayOf(-1)
        s.onInterrupt(IntConsumer { got[0] = it }, 1)
        Thread.sleep(300)
        s.offInterrupt()
        assertEquals(0x04, got[0])
    }

    @Test
    fun identityAndReset() {
        make()
        s.init()
        assertEquals(0xC4, s.whoAmI())
        c.setRegister(0x11, 0x01)
        assertTrue(s.isActive())
        s.reset()
        assertEquals(0x38, last(0x26, 1))
    }
}
