package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Is31fl3731Test {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** reg followed by n copies of v. */
    private fun rep(reg: Int, n: Int, v: Int): ByteArray =
        ByteArray(n + 1) { if (it == 0) reg.toByte() else v.toByte() }

    private fun last(c: MockConnection): ByteArray = c.writes().last()

    @Test
    fun fullApi() {
        val c = MockConnection()
        val d = Is31fl3731Full(c)
        val w = c.writes()

        assertEquals(10, w.size)
        assertArrayEquals(b(0xFD, 0x0B), w[0])
        assertArrayEquals(b(0x0A, 0x00), w[1])
        assertArrayEquals(b(0x00, 0x00, 0x00), w[2])
        assertArrayEquals(b(0x05, 0x00), w[3])
        assertArrayEquals(b(0xFD, 0x00), w[4])
        assertArrayEquals(rep(0x00, 18, 0xFF), w[5])
        assertArrayEquals(rep(0x12, 18, 0), w[6])
        assertArrayEquals(rep(0x24, 144, 0), w[7])
        assertArrayEquals(b(0xFD, 0x0B), w[8])
        assertArrayEquals(b(0x0A, 0x01), w[9])

        w.clear()
        d.setPixel(2, 5, 200)
        assertEquals(2, w.size)
        assertArrayEquals(b(0xFD, 0x00), w[0])
        assertArrayEquals(b(0x24 + 37, 200), w[1])
        w.clear()
        d.setPixel(8, 15, 1)
        assertEquals(1, w.size)
        assertArrayEquals(b(0x24 + 143, 1), w[0])
        assertEquals(200, d.getPixel(2, 5))
        assertEquals(0, d.getPixel(0, 0))
        assertThrows(IllegalArgumentException::class.java) { d.setPixel(9, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { d.setPixel(0, 16, 0) }
        assertThrows(IllegalArgumentException::class.java) { d.setPixel(0, 0, 256) }

        d.fill(0x40)
        assertArrayEquals(rep(0x24, 144, 0x40), last(c))
        d.clear()
        assertArrayEquals(rep(0x24, 144, 0), last(c))
        assertEquals(0, d.getPixel(2, 5))

        val ramp = ByteArray(144)
        for (i in 0 until 144) ramp[i] = i.toByte()
        d.setPixels(ramp)
        val expRamp = ByteArray(145)
        expRamp[0] = 0x24
        ramp.copyInto(expRamp, 1)
        assertArrayEquals(expRamp, last(c))
        assertEquals(17, d.getPixel(1, 1))
        assertThrows(IllegalArgumentException::class.java) { d.setPixels(ByteArray(143)) }

        d.setLedEnabled(1, 9, false)
        assertArrayEquals(b(0x03, 0xFD), last(c))
        d.setLedEnabled(1, 9, true)
        assertArrayEquals(b(0x03, 0xFF), last(c))
        d.setBlink(1, 9, true)
        assertArrayEquals(b(0x15, 0x02), last(c))
        d.setBlinkAll(true)
        assertArrayEquals(rep(0x12, 18, 0xFF), last(c))
        d.setBlinkAll(false)
        assertArrayEquals(rep(0x12, 18, 0), last(c))

        w.clear()
        d.selectFrame(3)
        assertEquals(1, w.size)
        assertArrayEquals(b(0xFD, 0x03), w[0])
        d.setPixel(0, 0, 9)
        assertArrayEquals(b(0x24, 9), last(c))
        d.copyFrame(3, 5)
        assertArrayEquals(b(0xFD, 0x05), w[w.size - 2])
        val copied = rep(0x24, 144, 0)
        copied[1] = 9
        assertArrayEquals(copied, last(c))
        assertThrows(IllegalArgumentException::class.java) { d.selectFrame(8) }
        assertThrows(IllegalArgumentException::class.java) { d.copyFrame(0, 8) }
        w.clear()
        d.clearAllFrames()
        assertEquals(8, w.count { it.size == 145 })
        d.selectFrame(0)

        w.clear()
        d.showFrame(4)
        assertEquals(2, w.size)
        assertArrayEquals(b(0xFD, 0x0B), w[0])
        assertArrayEquals(b(0x00, 0x00, 0x04), w[1])
        d.setPictureMode()
        assertArrayEquals(b(0x00, 0x00), last(c))

        d.startAutoPlay(3, 2, 121, 1)
        assertArrayEquals(b(0x02, 0x23, 11), w[w.size - 2])
        assertArrayEquals(b(0x00, 0x09), last(c))
        d.startAutoPlay(0, 0, 704, 0)
        assertArrayEquals(b(0x02, 0x00, 0x00), w[w.size - 2])
        assertThrows(IllegalArgumentException::class.java) { d.startAutoPlay(0, 0, 2000, 0) }
        assertThrows(IllegalArgumentException::class.java) { d.startAutoPlay(0, 8, 121, 0) }
        c.setRegister(0x07, 0x15)
        d.stopAutoPlay()
        assertArrayEquals(b(0x00, 0x00, 0x05), last(c))

        d.setAudioMode(true)
        assertArrayEquals(b(0x00, 0x10), last(c))
        d.setAudioMode(false)
        assertArrayEquals(b(0x00, 0x00), last(c))
        d.setAudioSync(true)
        assertArrayEquals(b(0x06, 0x01), last(c))
        d.setAudioGain(9, true, true)
        assertArrayEquals(b(0x0B, 0x1B), last(c))
        assertThrows(IllegalArgumentException::class.java) { d.setAudioGain(4, false, false) }
        assertThrows(IllegalArgumentException::class.java) { d.setAudioGain(24, false, false) }
        d.setAudioRate(100)
        assertArrayEquals(b(0x0C, 100), last(c))

        d.enableBlink(true, 3)
        assertArrayEquals(b(0x05, 0x0B), last(c))
        d.setIntensityControl(true)
        assertArrayEquals(b(0x05, 0x2B), last(c))
        d.enableBlink(false, 1)
        assertArrayEquals(b(0x05, 0x21), last(c))
        assertThrows(IllegalArgumentException::class.java) { d.enableBlink(true, 8) }
        d.setBreath(true, 2, 5, 3)
        assertArrayEquals(b(0x08, 0x52, 0x13), last(c))
        d.setBreath(false, 0, 0, 0)
        assertArrayEquals(b(0x08, 0x00, 0x00), last(c))

        d.shutdown(true)
        assertArrayEquals(b(0x0A, 0x00), last(c))
        d.shutdown(false)
        assertArrayEquals(b(0x0A, 0x01), last(c))

        c.setRegister(0x07, 0x13)
        assertEquals(0x13, d.frameState())
        assertEquals(3, d.currentFrame())
        assertTrue(d.movieFinished())
        c.setRegister(0x07, 0x02)
        assertFalse(d.movieFinished())

        d.selectFrame(2)
        w.clear()
        c.setRegister(0x03, 0x2A)
        assertEquals(0x2A, d.readFunctionRegister(0x03))
        assertArrayEquals(b(0xFD, 0x02), last(c))
        w.clear()
        d.writeFunctionRegister(0x03, 5)
        assertEquals(3, w.size)
        assertArrayEquals(b(0xFD, 0x0B), w[0])
        assertArrayEquals(b(0x03, 5), w[1])
        assertArrayEquals(b(0xFD, 0x02), w[2])
        assertThrows(IllegalArgumentException::class.java) { d.readFunctionRegister(0x0D) }

        c.setRegister(0x07, 0x14)
        assertEquals(0x14, d.pollInterrupt())
        assertArrayEquals(b(0xFD, 0x02), last(c))

        assertEquals(32, Is31fl3731Full.GAMMA_32.size)
        assertEquals(64, Is31fl3731Full.GAMMA_64.size)
        assertEquals(255, Is31fl3731Full.GAMMA_64[63])

        val m = Is31fl3731Minimal(MockConnection())
        m.setPixel(0, 0, 1)
        assertEquals(9, Is31fl3731Minimal.ROWS)
    }
}
