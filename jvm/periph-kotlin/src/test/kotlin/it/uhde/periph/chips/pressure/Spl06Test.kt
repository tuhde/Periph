package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.ArrayDeque
import java.util.function.IntConsumer

class Spl06Test {

    companion object {
        // Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
        val COEF = intArrayOf(0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
            0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA)
        val TRAW = byteArrayOf(0x02, 0x5C, 0x28)                                 // 154664
        val PRAW = byteArrayOf(0xFE.toByte(), 0x94.toByte(), 0xB7.toByte())     // -93001
        const val T_EXP = 25.0054779053
        const val P_EXP = 98263.8611522177
    }

    /** MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops queued FIFO entries. */
    private class Mock : MockConnection() {
        var flags = 0xF0
        var fifo: ArrayDeque<ByteArray>? = null
        var pin: InputPin? = null

        init {
            setRegister(0x0D, 0x10)
            setRegister(0x08, 0xF0)
            setRegister(0x10, *COEF)
            setRegister(0x00, 0xFE, 0x94, 0xB7, 0x02, 0x5C, 0x28)
        }

        override fun write(data: ByteArray) {
            super.write(data)
            if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0x08) setRegister(0x08, (data[1].toInt() and 0x07) or flags)
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = data[0].toInt() and 0xFF
            val f = fifo
            if (f != null && reg == 0x0B) setRegister(0x0B, if (f.isEmpty()) 0x01 else 0x00)
            if (f != null && reg == 0x00 && n == 3 && f.isNotEmpty()) return f.pollFirst().clone()
            return super.writeRead(data, n)
        }

        override fun intPin(): InputPin? = pin
    }

    private class FakePin : InputPin {
        var handler: EdgeHandler? = null
        var trigger: EdgeTrigger? = null
        override fun onEdge(handler: EdgeHandler, trigger: EdgeTrigger) { this.handler = handler; this.trigger = trigger }
        override fun offEdge(handler: EdgeHandler) { if (handler === this.handler) this.handler = null }
        override fun isHigh(): Boolean = true
        override fun close() {}
    }

    private lateinit var c: Mock

    private fun last(reg: Int): Int {
        var v = -1
        for (w in c.writes()) if (w.size >= 2 && (w[0].toInt() and 0xFF) == reg) v = w[1].toInt() and 0xFF
        return v
    }

    private fun count(reg: Int) = c.writes().count { it.size >= 2 && (it[0].toInt() and 0xFF) == reg }

    private fun make(): Spl06Full {
        c = Mock()
        val s = Spl06Full(c)
        assertTrue(c.writes().isEmpty())
        s.init()
        return s
    }

    @Test
    fun initDecodesCoefficientsAndWritesDefaults() {
        val s = make()
        assertEquals(0x80, last(0x07))
        assertEquals(0x04, last(0x06))
        assertEquals(0x04, last(0x09))
        assertEquals(Spl06Minimal.Coefficients(204, -261, 80469, -54769, -2659, 1258, -10529, 94, -1350), s.coefficients())
    }

    @Test
    fun readMeasuresTemperatureThenPressure() {
        val s = make()
        val m = s.read()
        assertEquals(T_EXP, m.temperatureC, 1e-6)
        assertEquals(P_EXP, m.pressurePa, 1e-6)
        val meas = c.writes().filter { it.size >= 2 && it[0].toInt() == 0x08 }.map { it[1].toInt() }
        assertEquals(listOf(0x02, 0x01), meas.takeLast(2))
        assertEquals(P_EXP, s.readPressure(), 1e-6)
        assertEquals(T_EXP, s.readTemperature(), 1e-6)
        assertEquals(0x02, last(0x08))
        assertArrayEquals(intArrayOf(-93001, 154664), s.readRaw())
        assertEquals(258.0291543011, s.altitude(), 1e-3)
        assertEquals(0.0, s.altitude(P_EXP), 1e-6)
    }

    @Test
    fun compensateIsPure() {
        val s = make()
        var m = s.compensate(-93001, 154664, 16, 1)
        assertEquals(P_EXP, m.pressurePa, 1e-6)
        assertEquals(T_EXP, m.temperatureC, 1e-6)
        m = s.compensate(-93001, 154664, 8, 2)
        assertEquals(80852.2826419888, m.pressurePa, 1e-6)
        assertEquals(76.3351593018, m.temperatureC, 1e-6)
        assertThrows(IllegalArgumentException::class.java) { s.compensate(0, 0, 3, 1) }
    }

    @Test
    fun configurationSetsRegistersAndShiftBits() {
        val s = make()
        s.setPressureConfig(128, 8)
        assertEquals(0x37, last(0x06))
        assertNotEquals(0, last(0x09) and 0x04)
        s.setPressureConfig(8, 1)
        assertEquals(0x03, last(0x06))
        assertEquals(0, last(0x09) and 0x04)
        s.setTemperatureConfig(32, 4)
        assertEquals(0xA5, last(0x07))
        assertNotEquals(0, last(0x09) and 0x08)
        s.setTemperatureConfig(2, 1)
        assertEquals(0x81, last(0x07))
        assertEquals(0, last(0x09) and 0x08)
        assertEquals(80852.2826419888, s.read().pressurePa, 1e-6)
        assertThrows(IllegalArgumentException::class.java) { s.setPressureConfig(3, 1) }
        assertThrows(IllegalArgumentException::class.java) { s.setPressureConfig(16, 3) }
        assertThrows(IllegalArgumentException::class.java) { s.setTemperatureConfig(0, 1) }
    }

    @Test
    fun backgroundModeReadsWithoutCommand() {
        val s = make()
        s.startContinuous(true, true)
        assertEquals(0x07, last(0x08))
        s.startContinuous(true, false)
        assertEquals(0x05, last(0x08))
        s.startContinuous(false, true)
        assertEquals(0x06, last(0x08))
        assertThrows(IllegalArgumentException::class.java) { s.startContinuous(false, false) }
        s.startContinuous()
        val n = count(0x08)
        assertEquals(P_EXP, s.readContinuous().pressurePa, 1e-6)
        assertEquals(P_EXP, s.read().pressurePa, 1e-6)
        assertEquals(n, count(0x08))
        c.setRegister(0x08, 0xD7)
        assertEquals(Pair(true, false), s.dataReady())
        c.setRegister(0x08, 0xF7)
        s.stop()
        assertEquals(0x00, last(0x08))
    }

    @Test
    fun fifoDrainsAndClassifiesEntries() {
        val s = make()
        s.enableFifo()
        assertNotEquals(0, last(0x09) and 0x02)
        c.fifo = ArrayDeque(listOf(TRAW, PRAW))
        val e = s.readFifo()
        assertEquals(2, e.size)
        assertEquals(Spl06Full.KIND_TEMPERATURE, e[0].kind)
        assertEquals(T_EXP, e[0].value, 1e-6)
        assertEquals(Spl06Full.KIND_PRESSURE, e[1].kind)
        assertEquals(98264.0472093194, e[1].value, 1e-6)
        assertTrue(s.readFifo().isEmpty())
        c.fifo = null
        c.setRegister(0x0B, 0x02)
        assertEquals(Pair(false, true), s.fifoStatus())
        s.flushFifo()
        assertEquals(0x80, last(0x0C))
        s.disableFifo()
        assertEquals(0, last(0x09) and 0x02)
    }

    @Test
    fun interruptsMapSourcesAndDispatch() {
        val s = make()
        s.enableInterrupt(Spl06Full.SOURCE_PRESSURE)
        assertNotEquals(0, last(0x09) and 0x20)
        s.enableInterrupt(Spl06Full.SOURCE_TEMPERATURE or Spl06Full.SOURCE_FIFO_FULL)
        assertEquals(0x74, last(0x09) and 0x74)
        s.disableInterrupt(Spl06Full.SOURCE_PRESSURE or Spl06Full.SOURCE_FIFO_FULL)
        assertEquals(0x10, last(0x09) and 0x70)
        c.setRegister(0x0A, 0xFA)
        assertEquals(0x02, s.pollInterrupt())

        val pin = FakePin()
        val seen = mutableListOf<Int>()
        s.onInterrupt(IntConsumer { seen.add(it) }, pin)
        assertEquals(EdgeTrigger.FALLING, pin.trigger)
        c.setRegister(0x0A, 0x01)
        pin.handler!!.onEdge()
        c.setRegister(0x0A, 0x00)
        pin.handler!!.onEdge()
        assertEquals(listOf(0x01), seen)
        s.offInterrupt()
        assertNull(pin.handler)
    }

    @Test
    fun resetRestoresDefaults() {
        val s = make()
        s.setPressureConfig(64, 2)
        s.reset()
        assertEquals(0x09, last(0x0C))
        assertEquals(0x04, last(0x06))
        assertEquals(0x80, last(0x07))
        assertEquals(0x04, last(0x09))
        assertEquals(P_EXP, s.read().pressurePa, 1e-6)
    }

    @Test
    fun minimalReadsAndRejectsBadChips() {
        val s = Spl06Minimal(Mock())
        s.init()
        assertEquals(P_EXP, s.read().pressurePa, 1e-6)
        val bad = Mock().apply { setRegister(0x0D, 0x58) }
        assertThrows(IOException::class.java) { Spl06Minimal(bad).init() }
        val slow = Mock().apply { setRegister(0x08, 0x40) }
        assertThrows(IOException::class.java) { Spl06Minimal(slow).init() }
    }
}
