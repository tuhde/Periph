package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Aht21Test {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private val measurement = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x08, 0x00, 0x00)

    private fun writesContain(connection: MockConnection, expected: ByteArray): Boolean =
        connection.writes().any { it.contentEquals(expected) }

    @Test
    fun fullApi() {
        val connection = MockConnection()

        // Status byte with both CAL (0x08) and the paired 0x10 bit set, so the
        // (status and 0x18) != 0x18 check in init passes and no soft-reset
        // /recalibration path runs.
        connection.queueRead(byteArrayOf(0x18))

        val sensor = Aht21Full(connection)
        assertFalse(writesContain(connection, Aht21Minimal.CMD_SOFT_RESET), "init should not soft-reset")

        connection.queueRead(measurement.copyOf())
        val (t, h) = sensor.read()
        assertTrue(writesContain(connection, Aht21Minimal.CMD_TRIGGER), "read() should trigger")
        assertEquals(50.0, t)
        assertEquals(50.0, h)

        connection.queueRead(measurement.copyOf())
        assertEquals(50.0, sensor.readTemperature())

        connection.queueRead(measurement.copyOf())
        assertEquals(50.0, sensor.readHumidity())

        // read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
        connection.queueRead(measurement.copyOf() + 0x8B.toByte())
        val (t2, h2, crcOk) = sensor.readWithCrc()
        assertEquals(50.0, t2)
        assertEquals(50.0, h2)
        assertTrue(crcOk)

        connection.queueRead(measurement.copyOf() + 0x00.toByte())
        assertFalse(sensor.readWithCrc().third)

        sensor.softReset()
        assertTrue(writesContain(connection, Aht21Minimal.CMD_SOFT_RESET))

        connection.queueRead(byteArrayOf(0x08))
        assertTrue(sensor.isCalibrated())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(sensor.isCalibrated())

        connection.queueRead(byteArrayOf(0x80.toByte()))
        assertTrue(sensor.isBusy())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(sensor.isBusy())
    }
}
