package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class TMC5160Test {

    /** Scripted TMC5160 over SPI: pipelined 40-bit datagrams, instant ramp generator. */
    private class SpiSim : Connection {
        val regs = mutableMapOf<Int, Long>(0x04 to 0x30000000L)
        val writes = mutableListOf<Pair<Int, Long>>()
        var pending = 0L

        fun lastWrite(reg: Int): Long = writes.last { it.first == reg }.second

        private fun datagram(data: ByteArray): Long {
            val f = data.copyOf(5)
            val reply = pending
            val reg = f[0].toInt() and 0x7F
            if ((f[0].toInt() and 0x80) != 0) {
                val v = ((f[1].toLong() and 0xFF) shl 24) or ((f[2].toLong() and 0xFF) shl 16) or
                    ((f[3].toLong() and 0xFF) shl 8) or (f[4].toLong() and 0xFF)
                regs[reg] = v
                writes.add(Pair(reg, v))
                if (reg == 0x2D) {
                    regs[0x21] = v
                    regs[0x35] = 1L shl 9
                }
                pending = v
            } else {
                pending = regs[reg] ?: 0L
            }
            return reply
        }

        override fun write(data: ByteArray) { datagram(data) }
        override fun read(n: Int): ByteArray = ByteArray(n)
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val r = datagram(data)
            return byteArrayOf((r shr 24).toByte(), (r shr 16).toByte(), (r shr 8).toByte(), r.toByte())
        }
        override fun enable() {}
        override fun disable() {}
        override fun isEnabled() = true
        override fun intPin(): InputPin? = null
        override fun enPin(): OutputPin? = null
        override fun close() {}
    }

    /** Scripted TMC5160 over single-wire UART with echo, CRC8 and IFCNT. */
    private class UartSim : Connection {
        val regs = mutableMapOf<Int, Long>(0x04 to 0x30000000L)
        val writes = mutableListOf<Pair<Int, Long>>()
        var ifcnt = 0
        var rx = ByteArray(0)

        override fun write(data: ByteArray) {
            rx += data
            if (data.size == 8) {
                val reg = data[2].toInt() and 0x7F
                val v = ((data[3].toLong() and 0xFF) shl 24) or ((data[4].toLong() and 0xFF) shl 16) or
                    ((data[5].toLong() and 0xFF) shl 8) or (data[6].toLong() and 0xFF)
                regs[reg] = v
                writes.add(Pair(reg, v))
                ifcnt = (ifcnt + 1) and 0xFF
            }
        }
        override fun read(n: Int): ByteArray {
            val out = rx.copyOf(n)
            rx = rx.copyOfRange(n, rx.size)
            return out
        }
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = data[2].toInt() and 0x7F
            val v = if (reg == 0x02) ifcnt.toLong() else (regs[reg] ?: 0L)
            val r = byteArrayOf(5, 0xFF.toByte(), reg.toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(), 0)
            r[7] = TMC5160Minimal.crc8(r, 7).toByte()
            return (data + r).copyOf(n)
        }
        override fun enable() {}
        override fun disable() {}
        override fun isEnabled() = true
        override fun intPin(): InputPin? = null
        override fun enPin(): OutputPin? = null
        override fun close() {}
    }

    @Test
    fun crcVector() {
        assertEquals(0x48, TMC5160Minimal.crc8(byteArrayOf(5, 0, 0), 3))
    }

    @Test
    fun spiInitWritesDefaults() {
        val sim = SpiSim()
        TMC5160Minimal(sim, 0.075)
        assertEquals(0x000100C3L, sim.lastWrite(0x6C))
        assertEquals(0x4L, sim.lastWrite(0x00))
        assertEquals(500L, sim.lastWrite(0x13))
        assertEquals(7L, sim.lastWrite(0x01))
        assertEquals(71583L, sim.lastWrite(0x27))
        assertEquals(1564L, sim.lastWrite(0x26))
    }

    @Test
    fun rejectsWrongChipAndStepDir() {
        val wrong = SpiSim().also { it.regs[0x04] = 0x11000000L }
        assertThrows(IOException::class.java) { TMC5160Minimal(wrong, 0.075) }
        val sd = SpiSim().also { it.regs[0x04] = 0x30000040L }
        assertThrows(IOException::class.java) { TMC5160Minimal(sd, 0.075) }
    }

    @Test
    fun motionAndPosition() {
        val sim = SpiSim()
        val m = TMC5160Minimal(sim, 0.075)
        m.moveTo(400.0)
        assertEquals(400L * 256, sim.lastWrite(0x2D))
        m.waitUntilReached(1.0)
        assertEquals(400.0, m.position(), 1e-9)
        m.run(-200.0)
        assertEquals(2L, sim.lastWrite(0x20))
        m.stop()
        assertEquals(0L, sim.lastWrite(0x27))
        assertThrows(IllegalArgumentException::class.java) { m.setCurrent(10.0) }
        assertThrows(IllegalArgumentException::class.java) { m.setSpeed(0.0) }
        m.setCurrent(1.0)
    }

    @Test
    fun fullRampAndUartOnly() {
        val sim = SpiSim()
        val f = TMC5160Full(sim, 0.075)
        f.configureRamp(0.0, 800.0, 400.0, 400.0, 200.0, 400.0, 800.0, 1.0)
        assertEquals(3127L, sim.lastWrite(0x24))
        assertThrows(IllegalStateException::class.java) { f.readIfcnt() }
        f.setMicrosteps(16)
        assertEquals(4L, (sim.lastWrite(0x6C) shr 24) and 0xF)
        assertThrows(IllegalArgumentException::class.java) { f.setMicrosteps(3) }
        assertThrows(IllegalStateException::class.java) { f.setCoilCurrents(1, 1) }
        assertThrows(IllegalStateException::class.java) { f.readDiag(0) }
    }

    @Test
    fun uartInitAndIfcnt() {
        val sim = UartSim()
        val f = TMC5160Full(sim, 0.075, 0, null, true, false)
        assertTrue(sim.writes.any { it.first == 0x6C && it.second == 0x000100C3L })
        assertTrue(f.readIfcnt() > 0)
        f.setSendDelay(2)
        assertEquals(2L shl 8, sim.regs[0x03])
        assertThrows(IllegalArgumentException::class.java) { TMC5160Minimal(UartSim(), 0.075, 254, null, true, false) }
    }
}
