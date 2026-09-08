package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Ina219Test {

    private val eps = 1e-9

    @Test
    fun fullApi() {
        val connection = MockConnection()

        // rShunt=0.1, maxCurrent=2.0 -> currentLsb=2.0/32768,
        // cal=(0.04096/(currentLsb*rShunt)).toInt() and 0xFFFE = 0x1A36.
        val sensor = Ina219Full(connection, 0.1, 2.0)
        val lastInit = connection.writes().last()
        assertArrayEquals(byteArrayOf(0x05, 0x1A, 0x36), lastInit)

        // Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0.
        connection.setRegister(0x02, 0x1F, 0x42)
        assertEquals(4.0, sensor.voltage(), eps)
        assertTrue(sensor.conversionReady())
        assertFalse(sensor.overflow())

        // Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1.
        connection.setRegister(0x02, 0x1F, 0x41)
        assertTrue(sensor.overflow())

        // Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V.
        connection.setRegister(0x01, 0xFE, 0x0C)
        assertEquals(-0.005, sensor.shuntVoltage(), eps)

        // Current: raw=1000 (0x03E8) -> 1000 * currentLsb.
        connection.setRegister(0x04, 0x03, 0xE8)
        assertEquals(1000 * (2.0 / 32768), sensor.current(), eps)

        // Power: raw=2000 (0x07D0) -> 2000 * 20 * currentLsb.
        connection.setRegister(0x03, 0x07, 0xD0)
        assertEquals(2000 * 20 * (2.0 / 32768), sensor.power(), eps)

        // configure(brng=0, pga=1, badc=0x0B, sadc=0x02, mode=5) -> config = 0x0D95;
        // re-writes Calibration afterward.
        sensor.configure(0, 1, 0x0B, 0x02, 5)
        val writes = connection.writes()
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x95.toByte()), writes[writes.size - 2])
        assertArrayEquals(byteArrayOf(0x05, 0x1A, 0x36), writes[writes.size - 1])

        // shutdown(): MODE forced to 0, other CONFIG bits preserved (0x0D95 -> 0x0D90).
        sensor.shutdown()
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x90.toByte()), connection.writes().last())

        // wake(): restores the previously configured mode (5) -> 0x0D95.
        sensor.wake()
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x95.toByte()), connection.writes().last())

        // trigger(): re-writes the current config unchanged.
        sensor.trigger()
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x95.toByte()), connection.writes().last())

        // reset(): sets RST, restores the cached (last configure()'d) Configuration,
        // then re-writes Calibration — the Kotlin driver keeps a cached config
        // field and (unlike the other languages' drivers for this chip) fully
        // implements the spec's "restore previous Configuration" reset() contract.
        sensor.reset()
        val writes2 = connection.writes()
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte(), 0x00), writes2[writes2.size - 3])
        assertArrayEquals(byteArrayOf(0x00, 0x0D, 0x95.toByte()), writes2[writes2.size - 2])
        assertArrayEquals(byteArrayOf(0x05, 0x1A, 0x36), writes2[writes2.size - 1])
    }
}
