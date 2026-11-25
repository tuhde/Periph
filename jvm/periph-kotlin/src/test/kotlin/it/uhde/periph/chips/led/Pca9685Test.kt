package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pca9685Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** Data writes only: the register-pointer writes of reads (one byte) are dropped. */
    private fun data(c: MockConnection): List<ByteArray> = c.writes().filter { it.size > 1 }

    private fun last(c: MockConnection): ByteArray = data(c).last()

    @Test
    fun fullApi() {
val c = MockConnection()
        val d = Pca9685Full(c)
        var w = data(c)
        assertEquals(3, w.size)
        assertArrayEquals(b(0x00, 0x30), w[0])
        assertArrayEquals(b(0xFE, 5), w[1])
        assertArrayEquals(b(0x00, 0x20), w[2])

        c.writes().clear()
        d.setDuty(0, 0.5)
        assertArrayEquals(b(0x06, 0, 0, 0x00, 0x08), last(c))
        d.setDuty(15, 0.0)
        assertArrayEquals(b(0x42, 0, 0, 0, 0x10), last(c))
        d.setDuty(1, 1.0)
        assertArrayEquals(b(0x0A, 0, 0x10, 0, 0), last(c))
        assertThrows(IllegalArgumentException::class.java) { d.setDuty(16, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { d.setDuty(0, 1.1) }
        assertThrows(IllegalArgumentException::class.java) { d.setDuty(-1, 0.5) }

        d.setPwm(2, 409, 1228)
        assertArrayEquals(b(0x0E, 0x99, 0x01, 0xCC, 0x04), last(c))
        assertArrayEquals(intArrayOf(409, 1228), d.getPwm(2))
        assertThrows(IllegalArgumentException::class.java) { d.setPwm(0, 4096, 1) }
        assertThrows(IllegalArgumentException::class.java) { d.setPwm(0, 5, 5) }

        d.setFullOn(3)
        assertArrayEquals(b(0x12, 0, 0x10, 0, 0), last(c))
        d.setFullOff(3)
        assertArrayEquals(b(0x12, 0, 0, 0, 0x10), last(c))
        d.setAllDuty(0.25)
        assertArrayEquals(b(0xFA, 0, 0, 0x00, 0x04), last(c))
        d.setAllPwm(0, 100)
        assertArrayEquals(b(0xFA, 0, 0, 100, 0), last(c))

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.setFrequency(50.0)
        w = data(c)
        assertEquals(3, w.size)
        assertArrayEquals(b(0x00, 0x30), w[0])
        assertArrayEquals(b(0xFE, 121), w[1])
        assertArrayEquals(b(0x00, 0x20), w[2])
        assertEquals(25e6 / (4096 * 122), d.getFrequency(), 1e-6)
        d.setFrequency(1e6)
        w = data(c)
        assertArrayEquals(b(0xFE, 3), w[w.size - 2])
        d.setFrequency(1.0)
        w = data(c)
        assertArrayEquals(b(0xFE, 255), w[w.size - 2])
        c.setRegister(0x00, 0xA0)
        c.writes().clear()
        d.setFrequency(200.0)
        w = data(c)
        assertArrayEquals(b(0xFE, 30), w[1])
        assertArrayEquals(b(0x00, 0xA0), w[w.size - 1])
        assertThrows(IllegalArgumentException::class.java) { d.setFrequency(0.0) }

        c.setRegister(0xFE, 121)
        d.setPulseWidth(15, 0.0015)
        assertArrayEquals(b(0x42, 0, 0, 307 and 0xFF, 307 shr 8), last(c))

        c.setRegister(0x00, 0x20)
        d.sleep()
        assertArrayEquals(b(0x00, 0x30), last(c))
        c.setRegister(0x00, 0xB0)
        c.writes().clear()
        d.wake()
        w = data(c)
        assertArrayEquals(b(0x00, 0x20), w[0])
        assertArrayEquals(b(0x00, 0xA0), w[1])
        d.restart()
        assertArrayEquals(b(0x00, 0xA0), last(c))

        c.setRegister(0x01, 0x04)
        d.setOutputMode(true, true, Pca9685Full.OFF_HIGHZ)
        assertArrayEquals(b(0x01, 0x12), last(c))
        d.setOutputMode(false, false, Pca9685Full.OFF_LOW)
        assertArrayEquals(b(0x01, 0x04), last(c))
        assertThrows(IllegalArgumentException::class.java) { d.setOutputMode(false, false, 3) }
        d.setUpdateOnAck(true)
        assertArrayEquals(b(0x01, 0x0C), last(c))
        d.setUpdateOnAck(false)
        assertArrayEquals(b(0x01, 0x04), last(c))

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.useExternalClock()
        w = data(c)
        assertArrayEquals(b(0x00, 0x30), w[0])
        assertArrayEquals(b(0x00, 0x70), w[1])
        assertArrayEquals(b(0x00, 0x60), w[2])

        c.setRegister(0x00, 0x20)
        c.writes().clear()
        d.setAllcall(true)
        w = data(c)
        assertArrayEquals(b(0x05, 0xE0), w[0])
        assertArrayEquals(b(0x00, 0x21), w[1])
        d.setSubaddress(2, 0x71, true)
        w = data(c)
        assertArrayEquals(b(0x03, 0xE2), w[w.size - 2])
        assertArrayEquals(b(0x00, 0x25), w[w.size - 1])
        assertThrows(IllegalArgumentException::class.java) { d.setSubaddress(4, 0x71, true) }
        assertThrows(IllegalArgumentException::class.java) { d.setAllcall(true, 0x80) }

        c.writes().clear()
        d.reset()
        w = data(c)
        assertArrayEquals(b(0x00, 0x10), w[0])
        assertArrayEquals(b(0x00, 0x11), w[w.size - 1])
        assertTrue(w.any { it.contentEquals(b(0xFE, 0x1E)) })

        val g = MockConnection()
        Pca9685Full.softwareReset(g)
        assertEquals(1, g.writes().size)
        assertArrayEquals(b(0x06), g.writes()[0])

        c.setRegister(0x00, 0x11, 0x04)
        assertArrayEquals(intArrayOf(0x11, 0x04), d.readMode())
        assertEquals(16, Pca9685Minimal.CHANNELS)
    }
}
