package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ES8311Test {

    private fun reg(m: MockConnection, r: Int): Int = m.registers()[r] ?: 0

    @Test
    fun initSequence() {
        val m = MockConnection()
        ES8311Minimal(m)
        assertArrayEquals(byteArrayOf(0x00, 0x1F), m.writes()[0])
        assertArrayEquals(byteArrayOf(0x00, 0x00), m.writes()[1])
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()), m.writes()[2])
        assertEquals(0x3F, reg(m, 0x01))
        assertEquals(0x00, reg(m, 0x02))
        assertEquals(0x10, reg(m, 0x03))
        assertEquals(0x10, reg(m, 0x04))
        assertEquals(0x03, reg(m, 0x06))
        assertEquals(0xFF, reg(m, 0x08))
        assertEquals(0x0C, reg(m, 0x09))
        assertEquals(0x0C, reg(m, 0x0A))
        assertEquals(0x01, reg(m, 0x0D))
        assertEquals(0x02, reg(m, 0x0E))
        assertEquals(0x10, reg(m, 0x13))
        assertEquals(0x6A, reg(m, 0x1C))
        assertEquals(0x08, reg(m, 0x37))
        assertEquals(0x18, reg(m, 0x14))
        assertEquals(0xBF, reg(m, 0x17))
        assertEquals(0x00, reg(m, 0x32))
        assertEquals(0x04, reg(m, 0x16))
    }

    @Test
    fun minimalApi() {
        val m = MockConnection()
        val chip = ES8311Minimal(m)
        chip.setVolume(0.0)
        assertEquals(0xBF, reg(m, 0x32))
        chip.setVolume(-95.5)
        assertEquals(0x00, reg(m, 0x32))
        chip.setVolume(32.0)
        assertEquals(0xFF, reg(m, 0x32))
        chip.setVolume(100.0)
        assertEquals(0xFF, reg(m, 0x32))
        chip.setVolume(-10.2)
        assertEquals(171, reg(m, 0x32))
        m.setRegister(0x31, 0x81)
        chip.setMute(true)
        assertEquals(0xE1, reg(m, 0x31))
        chip.setMute(false)
        assertEquals(0x81, reg(m, 0x31))
        chip.setMicGain(30.0)
        assertEquals(0x1A, reg(m, 0x14))
        chip.setMicGain(7.4)
        assertEquals(0x12, reg(m, 0x14))
        chip.setMicGain(99.0)
        assertEquals(10, reg(m, 0x14) and 0x0F)
    }

    @Test
    fun clockSolver() {
        assertThrows(IllegalArgumentException::class.java) { ES8311Minimal(MockConnection(), 1000, 48000) }
        var m = MockConnection()
        ES8311Minimal(m, 24576000, 48000)
        assertEquals(0x20, reg(m, 0x02))
        m = MockConnection()
        ES8311Minimal(m, 12288000, 16000)
        val r02 = reg(m, 0x02)
        val mults = longArrayOf(1, 2, 4, 8)
        val ratio = 12288000L * mults[(r02 shr 3) and 3] / (((r02 shr 5) and 7) + 1) / ((reg(m, 0x05) and 0x0F) + 1) / 16000
        assertTrue(ratio == 256L || ratio == 384L || ratio == 512L || ratio == 768L)
        assertEquals(reg(m, 0x03), reg(m, 0x04))
        assertTrue(reg(m, 0x03) in 16..63)
    }

    @Test
    fun fullApi() {
        val m = MockConnection()
        val chip = ES8311Full(m)
        chip.setVolume(-20.0)
        assertEquals(-20.0, chip.getVolume())
        chip.setMicGain(21.0)
        assertEquals(21.0, chip.getMicGain())

        val n = m.writes().size
        chip.reset()
        assertEquals(n + 3, m.writes().size)
        assertArrayEquals(byteArrayOf(0, 0x1F), m.writes()[n])
        assertArrayEquals(byteArrayOf(0, 0x80.toByte()), m.writes()[n + 2])

        chip.powerDown()
        assertEquals(0x1F, reg(m, 0x00))
        assertEquals(0x60, reg(m, 0x31) and 0x60)
        assertEquals(0xFC, reg(m, 0x0D))
        assertEquals(0x70, reg(m, 0x0E) and 0x70)
        chip.powerUp()
        assertEquals(0x80, reg(m, 0x00))
        assertEquals(0x01, reg(m, 0x0D))
        chip.setLowPower(dac = true, integrator = true)
        assertEquals(0x81, reg(m, 0x0F))
        chip.setLowPower(pga = true, adc = true, adcRef = true, flash = true)
        assertEquals(0x40 or 0x10 or 0x08 or 0x02, reg(m, 0x0F))

        chip.configureClock(12288000, 48000, true, true)
        assertEquals(0xFF, reg(m, 0x01))
        chip.configureClock(12288000, 48000)
        assertEquals(0x3F, reg(m, 0x01))
        assertThrows(IllegalArgumentException::class.java) { chip.configureClock(1000, 48000) }

        chip.configureSerial("left_justified", 24)
        assertEquals(0x01, reg(m, 0x09))
        assertEquals(0x01, reg(m, 0x0A))
        chip.configureSerial("dsp_b", 32, dacChannel = "right")
        assertEquals(0x80 or 0x20 or (4 shl 2) or 3, reg(m, 0x09))
        assertEquals(0x20 or (4 shl 2) or 3, reg(m, 0x0A))
        chip.configureSerial("dsp_a", 16, lrckInvert = true)
        assertEquals(0x20 or (3 shl 2) or 3, reg(m, 0x0A))
        assertEquals(0x04, reg(m, 0x00) and 0x44)
        chip.setSdpMute(input = true)
        assertTrue((reg(m, 0x09) and 0x40) != 0 && (reg(m, 0x0A) and 0x40) == 0)
        chip.configureSerial()
        assertTrue((reg(m, 0x09) and 0x40) != 0)
        chip.configureSerial(master = true, bclkDiv = 4, lrckDiv = 256)
        assertEquals(0x40, reg(m, 0x00) and 0x44)
        assertEquals(0x03, reg(m, 0x06))
        assertEquals(0x00, reg(m, 0x07) and 0x0F)
        assertEquals(0xFF, reg(m, 0x08))
        chip.configureSerial(master = true, bclkInvert = true, bclkDiv = 32, lrckDiv = 64, continuousBclk = false)
        assertEquals(0x40 or 0x20 or 24, reg(m, 0x06))
        assertThrows(IllegalArgumentException::class.java) { chip.configureSerial("x") }
        assertThrows(IllegalArgumentException::class.java) { chip.configureSerial(wordLength = 8) }

        chip.setTristate(bclkLrck = true)
        assertEquals(0x20, reg(m, 0x07) and 0x30)
        chip.setTristate(adcdat = true)
        assertEquals(0x10, reg(m, 0x07) and 0x30)

        chip.setInput("digital")
        assertTrue((reg(m, 0x14) and 0x70) == 0x40 && (reg(m, 0x0E) and 0x40) != 0)
        chip.setInput("analog")
        assertTrue((reg(m, 0x14) and 0x70) == 0x10 && (reg(m, 0x0E) and 0x40) == 0)
        chip.setInput("none")
        assertTrue((reg(m, 0x14) and 0x70) == 0x00 && (reg(m, 0x0E) and 0x40) != 0)
        assertThrows(IllegalArgumentException::class.java) { chip.setInput("x") }
        chip.configureDmic(true)
        assertEquals(1, reg(m, 0x15) and 1)
        chip.configureDmic()
        assertEquals(0, reg(m, 0x15) and 1)

        chip.setAdcVolume(-95.5)
        assertEquals(0, reg(m, 0x17))
        chip.configureAdc(42, 128, false, false, 0x05, 0x0A)
        assertEquals(7, reg(m, 0x16) and 7)
        assertEquals(32, reg(m, 0x03))
        assertEquals(0x0A, reg(m, 0x1C) and 0x3F)
        assertEquals(0x05, reg(m, 0x1B) and 0x1F)
        chip.configureAdc(doubleSpeed = true)
        assertEquals(0x40 or 32, reg(m, 0x03))
        assertTrue((reg(m, 0x1C) and 0x20) != 0)
        assertThrows(IllegalArgumentException::class.java) { chip.configureAdc(osr = 8) }
        assertThrows(IllegalArgumentException::class.java) { chip.configureAdc(osr = 20, doubleSpeed = true) }
        chip.setAdcRamp(7)
        assertEquals(7, reg(m, 0x15) shr 4)
        chip.setAdcRamp(99)
        assertEquals(15, reg(m, 0x15) shr 4)

        chip.configureAlc(true, -12.0, -18.1, 3, true, 2, -60.0)
        assertEquals(0xC3, reg(m, 0x18))
        assertEquals((7 shl 4) or 3, reg(m, 0x19))
        assertEquals((2 shl 4) or 6, reg(m, 0x1A))
        chip.configureAlc(false)
        assertEquals(0, reg(m, 0x18))
        assertEquals(0xF0, reg(m, 0x19))
        assertEquals(0, reg(m, 0x1A))

        chip.setAdcEq(longArrayOf(1, 2, 3, 4, 0x3FFFFFFFL))
        assertEquals(0, reg(m, 0x1C) and 0x40)
        assertEquals(1, reg(m, 0x20))
        assertEquals(0x3F, reg(m, 0x2D))
        assertEquals(0xFF, reg(m, 0x30))
        assertTrue(m.writes().any { it.size == 21 && it[0].toInt() == 0x1D })
        chip.bypassAdcEq()
        assertEquals(0x40, reg(m, 0x1C) and 0x40)
        assertThrows(IllegalArgumentException::class.java) { chip.setAdcEq(longArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { chip.setAdcEq(longArrayOf(1L shl 30, 0, 0, 0, 0)) }
        chip.setDacEq(longArrayOf(0x100, 0x200, 0x300))
        assertEquals(0, reg(m, 0x37) and 0x08)
        assertEquals(1, reg(m, 0x3A))
        assertEquals(2, reg(m, 0x3E))
        assertEquals(3, reg(m, 0x42))
        chip.bypassDacEq()
        assertEquals(0x08, reg(m, 0x37) and 0x08)

        chip.setDacRamp(8)
        assertTrue((reg(m, 0x37) shr 4) == 8 && (reg(m, 0x37) and 0x08) != 0)
        chip.configureDrc(true, -12.0, -24.1, 5)
        assertEquals(0x85, reg(m, 0x34))
        assertEquals((7 shl 4) or 1, reg(m, 0x35))
        chip.setDacMuteOptions(dsm = true, dem = false, dsmTarget = true)
        assertEquals(0xC0, reg(m, 0x31) and 0xE0)
        chip.setDacMuteOptions()
        assertEquals(0x60, reg(m, 0x31) and 0xE0)

        chip.setLoopback(true)
        assertTrue((reg(m, 0x44) and 0x80) != 0)
        chip.setAdcdatMode("dac_adc")
        assertEquals(0xC0, reg(m, 0x44))
        chip.setAdcdatMode("dac_dac")
        assertEquals(6, (reg(m, 0x44) shr 4) and 7)
        chip.setLoopback(false)
        assertEquals(0x60, reg(m, 0x44))
        assertThrows(IllegalArgumentException::class.java) { chip.setAdcdatMode("x") }

        chip.writeRegister(0x40, 0x34)
        assertEquals(0x34, reg(m, 0x40))
        assertEquals(0x34, chip.readRegister(0x40))
    }
}
