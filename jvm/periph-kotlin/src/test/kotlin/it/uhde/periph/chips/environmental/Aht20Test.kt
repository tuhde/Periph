package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Aht20Test {

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // rawRh = data[1]<<12 | data[2]<<4 | data[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // rawT  = (data[3]&0x0F)<<16 | data[4]<<8 | data[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    private val measurement = byteArrayOf(0x08, 0x80.toByte(), 0x00, 0x08, 0x00, 0x00)

    private fun writesContain(connection: MockConnection, expected: ByteArray): Boolean =
        connection.writes().any { it.contentEquals(expected) }

    @Test
    fun fullApi() {
        val connection = MockConnection()

        // Status 0x18 (idle + calibrated): no reset or calibration init.
        connection.queueRead(byteArrayOf(0x18))

        val sensor = Aht20Full(connection)
        assertTrue(writesContain(connection, Aht20Minimal.CMD_STATUS), "init should send 0x71")
        assertFalse(writesContain(connection, Aht20Minimal.CMD_SOFT_RESET), "init should not soft-reset")

        connection.queueRead(measurement.clone())
        val (t, h) = sensor.read()
        assertTrue(writesContain(connection, Aht20Minimal.CMD_TRIGGER), "read() should trigger")
        assertEquals(50.0, t)
        assertEquals(50.0, h)

        connection.queueRead(measurement.clone())
        assertEquals(50.0, sensor.readTemperature())

        connection.queueRead(measurement.clone())
        assertEquals(50.0, sensor.readHumidity())

        connection.queueRead(measurement.clone())
        assertEquals(Pair(0x80000, 0x80000), sensor.readRaw())

        // BUSY on the first read, cleared on the second.
        connection.queueRead(byteArrayOf(0x88.toByte(), 0x80.toByte(), 0x00, 0x08, 0x00, 0x00))
        connection.queueRead(measurement.clone())
        assertEquals(50.0, sensor.read().first)

        // Spec example: raw_t = 0x6B300 -> 33.74 degC
        connection.queueRead(byteArrayOf(0x08, 0x80.toByte(), 0x00, 0x06, 0xB3.toByte(), 0x00))
        assertEquals(33.74, sensor.readTemperature(), 0.01)

        connection.queueRead(measurement.clone())
        assertEquals(36.7, sensor.dewPoint(), 0.1)

        connection.queueRead(byteArrayOf(0x08))
        assertEquals(0x08, sensor.readStatusByte())

        // CRC-8 (poly 0x31, init 0xFF).
        val frame = byteArrayOf(0x1C, 0x80.toByte(), 0x00, 0x08, 0x00, 0x00)
        val crc = sensor.crc8(frame, 6)
        connection.queueRead(frame + byteArrayOf(crc.toByte()))
        val (ct, ch, crcOk) = sensor.readWithCrc()
        assertTrue(crcOk)
        assertEquals(50.0, ct)
        assertEquals(50.0, ch)
        connection.queueRead(frame + byteArrayOf((crc xor 0xFF).toByte()))
        assertFalse(sensor.readWithCrc().third)
        assertEquals(0x92, sensor.crc8(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), 2))

        // Soft reset re-runs the calibration check (status 0x18: no calibration init).
        connection.queueRead(byteArrayOf(0x18))
        sensor.softReset()
        assertTrue(writesContain(connection, Aht20Minimal.CMD_SOFT_RESET))

        connection.queueRead(byteArrayOf(0x08))
        assertTrue(sensor.isCalibrated())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(sensor.isCalibrated())

        connection.queueRead(byteArrayOf(0x80.toByte()))
        assertTrue(sensor.isBusy())
        connection.queueRead(byteArrayOf(0x00))
        assertFalse(sensor.isBusy())
    }

    @Test
    fun softResetFixesCalibration() {
        val connection = MockConnection()
        connection.queueRead(byteArrayOf(0x00))
        connection.queueRead(byteArrayOf(0x18))
        Aht20Full(connection)
        assertTrue(writesContain(connection, Aht20Minimal.CMD_SOFT_RESET))
        assertFalse(writesContain(connection, Aht20Minimal.CMD_CAL_INIT[0]))
    }

    @Test
    fun calibrationRegistersWrittenWhenStillUncalibrated() {
        val connection = MockConnection()
        connection.queueRead(byteArrayOf(0x00))
        connection.queueRead(byteArrayOf(0x00))
        connection.queueRead(byteArrayOf(0x18))
        Aht20Full(connection)
        for (cmd in Aht20Minimal.CMD_CAL_INIT) assertTrue(writesContain(connection, cmd))
    }

    @Test
    fun uncalibratedAfterRetryThrows() {
        val connection = MockConnection()
        connection.queueRead(byteArrayOf(0x00))
        connection.queueRead(byteArrayOf(0x00))
        connection.queueRead(byteArrayOf(0x00))
        assertThrows(IOException::class.java) { Aht20Full(connection) }
        assertTrue(writesContain(connection, Aht20Minimal.CMD_SOFT_RESET))
    }
}
