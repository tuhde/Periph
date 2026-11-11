package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

class ADS7828Test {

    private fun last(c: MockConnection): ByteArray = c.writes().last()

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun constructionMakesNoBusTrafficAndInitEnablesReference() {
        val c = MockConnection()
        val adc = ADS7828Full(c)
        assertEquals(0, c.writes().size)
        adc.init()
        assertEquals(1, c.writes().size)
        assertArrayEquals(b(0x08), last(c))
        assertEquals(2.5, adc.getReference(), 0.0)
        assertTrue(adc.usesInternalReference())
    }

    @Test
    fun singleEndedCommandsAndResults() {
        val c = MockConnection()
        val adc = ADS7828Full(c)
        adc.init()

        c.setRegister(0x8C, 0x0A, 0xBC)
        assertEquals(0xABC, adc.readRaw(0))
        assertArrayEquals(b(0x8C), last(c))

        c.setRegister(0xCC, 0x0F, 0xFF)
        assertEquals(0xFFF, adc.readRaw(1))
        assertArrayEquals(b(0xCC), last(c))
        adc.readRaw(2)
        assertArrayEquals(b(0x9C), last(c))
        adc.readRaw(7)
        assertArrayEquals(b(0xFC), last(c))

        c.setRegister(0x8C, 0xF1, 0x23)
        assertEquals(0x123, adc.readRaw(0))

        adc.readRaw(99)
        assertArrayEquals(b(0xFC), last(c))
        adc.readRaw(-3)
        assertArrayEquals(b(0x8C), last(c))

        c.setRegister(0x8C, 0x08, 0x00)
        assertEquals(1.25, adc.readVoltage(0), 1e-9)
        val all = adc.readAll()
        assertEquals(8, all.size)
        assertEquals(1.25, all[0], 1e-9)
    }

    @Test
    fun differentialAndSigned() {
        val c = MockConnection()
        val adc = ADS7828Full(c)

        c.setRegister(0x0C, 0x04, 0x00)
        assertEquals(0x400, adc.readDifferentialRaw(0, false))
        assertArrayEquals(b(0x0C), last(c))
        adc.readDifferentialRaw(3, true)
        assertArrayEquals(b(0x7C), last(c))
        assertEquals(0x400 / 4096.0 * 2.5, adc.readDifferential(0, false), 1e-9)

        c.setRegister(0x0C, 0x00, 0x00)
        c.setRegister(0x4C, 0x02, 0x00)
        assertEquals(-0x200 / 4096.0 * 2.5, adc.readSignedDifferential(0), 1e-9)
        c.setRegister(0x0C, 0x02, 0x00)
        c.setRegister(0x4C, 0x00, 0x00)
        assertEquals(0x200 / 4096.0 * 2.5, adc.readSignedDifferential(0), 1e-9)
    }

    @Test
    fun referencePowerModeAndScan() {
        val c = MockConnection()
        val adc = ADS7828Full(c, 5.0)
        adc.init()
        assertEquals(0, c.writes().size)
        assertFalse(adc.usesInternalReference())
        assertEquals(5.0, adc.getReference(), 0.0)
        c.setRegister(0x80, 0x08, 0x00)
        assertEquals(2.5, adc.readVoltage(0), 1e-9)
        assertArrayEquals(b(0x80), last(c))
        adc.setPowerMode(ADS7828Full.POWER_DOWN_BETWEEN)

        adc.setReference(null)
        assertArrayEquals(b(0x08), last(c))
        assertEquals(2.5, adc.getReference(), 0.0)
        assertThrows(IllegalArgumentException::class.java) { adc.setPowerMode(1) }
        assertThrows(IllegalArgumentException::class.java) { adc.setPowerMode(7) }
        adc.setPowerMode(ADS7828Full.REF_ON_ADC_OFF)
        adc.readRaw(0)
        assertArrayEquals(b(0x88), last(c))
        adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON)

        adc.powerDown()
        assertArrayEquals(b(0x00), last(c))
        c.setRegister(0x8C, 0x00, 0x01)
        c.setRegister(0xCC, 0x00, 0x02)
        val scan = adc.readScan(intArrayOf(0, 1))
        assertArrayEquals(intArrayOf(1, 2), scan)
        val w = c.writes()
        assertArrayEquals(b(0x08), w[w.size - 3])
        assertEquals(2.5 / 4096, adc.lsb(), 1e-12)

        adc.setReference(3.3)
        assertFalse(adc.usesInternalReference())
        adc.readRaw(0)
        assertArrayEquals(b(0x80), last(c))
    }

    @Test
    fun minimalStandsAlone() {
        val c = MockConnection()
        val adc = ADS7828Minimal(c)
        assertEquals(0, adc.readRaw(0))
        assertEquals(2, c.writes().size)
        assertArrayEquals(b(0x08), c.writes()[0])
        assertArrayEquals(b(0x8C), c.writes()[1])
    }
}
