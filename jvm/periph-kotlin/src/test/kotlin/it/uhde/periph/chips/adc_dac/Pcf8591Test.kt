package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pcf8591Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val adc = Pcf8591Full(connection)

        // readChannel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh.
        connection.queueRead(byteArrayOf(0x11, 0x7F))
        assertEquals(0x7F, adc.readChannel(2))
        assertArrayEquals(byteArrayOf(0x02), connection.writes().last())

        // readChannel clamps an out-of-range channel to 0.
        connection.queueRead(byteArrayOf(0x00, 0x55))
        assertEquals(0x55, adc.readChannel(9))
        assertArrayEquals(byteArrayOf(0x00), connection.writes().last())

        // readAll(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte.
        connection.queueRead(byteArrayOf(0x00, 0x10, 0x20, 0x30, 0x40))
        assertArrayEquals(intArrayOf(0x10, 0x20, 0x30, 0x40), adc.readAll())
        assertArrayEquals(byteArrayOf(0x04), connection.writes().last())

        // configure(inputMode=3, autoIncrement=true, dacEnabled=true) -> 0x74.
        adc.configure(3, true, true)
        assertArrayEquals(byteArrayOf(0x74), connection.writes().last())

        // readChannelVoltage(0, vref=3.3, vagnd=0.0): raw=128.
        connection.queueRead(byteArrayOf(0x00, 128.toByte()))
        val v = adc.readChannelVoltage(0, 3.3, 0.0)
        assertEquals(128 * 3.3 / 256.0, v, 1e-9)

        // readAllVoltage(vref=3.3, vagnd=0.0): raws [0, 64, 128, 255].
        connection.queueRead(byteArrayOf(0x00, 0, 64, 128.toByte(), 255.toByte()))
        val voltages = adc.readAllVoltage(3.3, 0.0)
        val expected = doubleArrayOf(0.0, 64 * 3.3 / 256.0, 128 * 3.3 / 256.0, 255 * 3.3 / 256.0)
        assertArrayEquals(expected, voltages, 1e-9)

        // readDifferential(1): raw byte 200 -> signed two's complement = -56.
        connection.queueRead(byteArrayOf(0x00, 200.toByte()))
        assertEquals(-56, adc.readDifferential(1))

        // raw byte 100 (< 128) stays positive.
        connection.queueRead(byteArrayOf(0x00, 100))
        assertEquals(100, adc.readDifferential(1))

        // setDac(200): sets AOE=1, AI=0, writes [ctrl, value].
        adc.setDac(200)
        val last = connection.writes().last()
        assertEquals(200.toByte(), last[1])
        assertTrue((last[0].toInt() and 0x40) != 0)
        assertEquals(0, last[0].toInt() and 0x04)

        // setDacVoltage(0.5): Kotlin's Double.toInt() truncates (0.5*255).toInt() to 127, not 128.
        adc.setDacVoltage(0.5)
        assertEquals(127.toByte(), connection.writes().last()[1])

        // disableDac(): clears AOE bit.
        adc.disableDac()
        assertEquals(0, connection.writes().last()[0].toInt() and 0x40)
    }
}
