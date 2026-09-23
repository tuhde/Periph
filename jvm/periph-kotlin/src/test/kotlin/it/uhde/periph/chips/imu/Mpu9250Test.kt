package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Mpu9250Test {

    // AK8963 register constants (private in MPU9250Full, so mirrored here).
    private val AK8963_REG_CNTL1 = 0x0A
    private val AK8963_REG_ASAX = 0x10
    private val AK8963_REG_ASAY = 0x11
    private val AK8963_REG_ASAZ = 0x12
    private val AK8963_REG_HXL = 0x03

    // Encode a signed 16-bit value as its two big-endian bytes.
    private fun s16(value: Int): IntArray = intArrayOf((value shr 8) and 0xFF, value and 0xFF)

    // Encode a signed 16-bit value as its two little-endian bytes.
    private fun s16le(value: Int): IntArray = intArrayOf(value and 0xFF, (value shr 8) and 0xFF)

    private fun lastWrite(connection: MockConnection): ByteArray {
        val writes = connection.writes()
        return writes[writes.size - 1]
    }

    private fun newInitializedSensor(connection: MockConnection, magConnection: MockConnection): MPU9250Full {
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, MPU9250Minimal.WHO_AM_I_VALUE)
        return MPU9250Full(connection, magConnection)
    }

    @Test
    fun initWritesSequence() {
        val connection = MockConnection()
        newInitializedSensor(connection, MockConnection())

        // MPU9250 additionally writes ACCEL_CONFIG2, unlike MPU6050.
        val expected = arrayOf(
            byteArrayOf(MPU9250Minimal.REG_PWR_MGMT_1.toByte(), 0x80.toByte()),
            byteArrayOf(MPU9250Minimal.REG_PWR_MGMT_1.toByte(), 0x01.toByte()),
            byteArrayOf(MPU9250Minimal.REG_WHO_AM_I.toByte()),
            byteArrayOf(MPU9250Minimal.REG_GYRO_CONFIG.toByte(), 0x00.toByte()),
            byteArrayOf(MPU9250Minimal.REG_ACCEL_CONFIG.toByte(), 0x00.toByte()),
            byteArrayOf(MPU9250Minimal.REG_ACCEL_CONFIG2.toByte(), 0x03.toByte()),
            byteArrayOf(MPU9250Minimal.REG_CONFIG.toByte(), 0x03.toByte()),
            byteArrayOf(MPU9250Minimal.REG_SMPLRT_DIV.toByte(), 0x04.toByte()),
        )
        val writes = connection.writes()
        assertEquals(expected.size, writes.size)
        for (i in expected.indices) {
            assertArrayEquals(expected[i], writes[i], "write[$i]")
        }
    }

    @Test
    fun whoAmIMismatchThrows() {
        val connection = MockConnection()
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, 0x00)
        assertThrows(IOException::class.java) { MPU9250Minimal(connection) }
    }

    @Test
    fun fullApi() {
        val connection = MockConnection()
        val magConnection = MockConnection()
        val sensor = newInitializedSensor(connection, magConnection)

        // accel(): raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g).
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(16384) + s16(-8192) + s16(4096)))
        val a = sensor.accel()
        assertEquals(9.80665, a[0], 1e-9)
        assertEquals(-4.903325, a[1], 1e-9)
        assertEquals(2.4516625, a[2], 1e-9)

        // gyro(): raw (131, -131, 262) at default GYRO_FS_SEL=0 -> (1, -1, 2) dps.
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(131) + s16(-131) + s16(262)))
        val g = sensor.gyro()
        assertEquals(Math.toRadians(1.0), g[0], 1e-9)
        assertEquals(Math.toRadians(-1.0), g[1], 1e-9)
        assertEquals(Math.toRadians(2.0), g[2], 1e-9)

        sensor.configureGyro(2)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_GYRO_CONFIG.toByte(), (2 shl 3).toByte()), lastWrite(connection))
        // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(328) + s16(0) + s16(0)))
        val g2 = sensor.gyro()
        assertEquals(Math.toRadians(10.0), g2[0], 1e-6)

        sensor.configureAccel(1)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_ACCEL_CONFIG.toByte(), (1 shl 3).toByte()), lastWrite(connection))
        // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(8192) + s16(0) + s16(0)))
        val a2 = sensor.accel()
        assertEquals(9.80665, a2[0], 1e-6)

        sensor.configureDlpf(5, 2)
        val dlpfWrites = connection.writes()
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_CONFIG.toByte(), 5.toByte()), dlpfWrites[dlpfWrites.size - 2])
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_ACCEL_CONFIG2.toByte(), 2.toByte()), lastWrite(connection))

        sensor.configureSampleRate(9)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_SMPLRT_DIV.toByte(), 9.toByte()), lastWrite(connection))

        // temperature(): raw=340 -> 340/333.87 + 21.0.
        connection.setRegister(MPU9250Minimal.REG_TEMP_OUT_H, *s16(340))
        assertEquals(340.0 / 333.87 + 21.0, sensor.temperature(), 1e-9)

        // accelRaw() / gyroRaw()
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, *(s16(100) + s16(-200) + s16(300)))
        assertArrayEquals(intArrayOf(100, -200, 300), sensor.accelRaw())
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, *(s16(-50) + s16(60) + s16(-70)))
        assertArrayEquals(intArrayOf(-50, 60, -70), sensor.gyroRaw())

        // dataReady()
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x01)
        assertTrue(sensor.dataReady())
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x00)
        assertFalse(sensor.dataReady())

        // setSleep(): PWR_MGMT_1 is 0x01 in the register map after init.
        sensor.setSleep(true)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_PWR_MGMT_1.toByte(), 0x41.toByte()), lastWrite(connection))
        sensor.setSleep(false)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_PWR_MGMT_1.toByte(), 0x01.toByte()), lastWrite(connection))

        // fifoCount()
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x03, 0x45)
        assertEquals(((0x03 and 0x1F) shl 8) or 0x45, sensor.fifoCount())

        // readFifo()
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x02)
        connection.setRegister(MPU9250Minimal.REG_FIFO_R_W, 0xAA, 0xBB)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), sensor.readFifo())

        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x00)
        assertEquals(0, sensor.readFifo().size)

        // enableFifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
        // USER_CTRL read (whose writeRead phase also appends a bytes([reg])
        // entry), then the USER_CTRL write.
        sensor.enableFifo(gyro = true, accel = true, temp = false)
        val writes = connection.writes()
        val n = writes.size
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_FIFO_EN.toByte(), ((1 shl 3) or (1 shl 4)).toByte()), writes[n - 3])
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_USER_CTRL.toByte()), writes[n - 2])
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_USER_CTRL.toByte(), 0x40.toByte()), writes[n - 1])

        // resetFifo(): USER_CTRL is 0x40 in the register map after enableFifo().
        sensor.resetFifo()
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_USER_CTRL.toByte(), 0x44.toByte()), lastWrite(connection))
    }

    @Test
    fun magApi() {
        val connection = MockConnection()
        val magConnection = MockConnection()
        val sensor = newInitializedSensor(connection, magConnection)

        // enableMag(): INT_PIN_CFG write (on the primary connection), AK8963
        // CNTL1 power-down, CNTL1 fuse ROM access, ASAX/ASAY/ASAZ reads,
        // CNTL1 power-down, then CNTL1 mode write (bits=16 -> 0x10 | mode) -
        // all on magConnection.
        magConnection.setRegister(AK8963_REG_ASAX, 200)
        magConnection.setRegister(AK8963_REG_ASAY, 100)
        magConnection.setRegister(AK8963_REG_ASAZ, 50)
        sensor.enableMag(16, 6)
        assertArrayEquals(byteArrayOf(MPU9250Minimal.REG_INT_PIN_CFG.toByte(), 0x22.toByte()), lastWrite(connection))

        val magWrites = magConnection.writes()
        assertEquals(7, magWrites.size)
        assertArrayEquals(byteArrayOf(AK8963_REG_CNTL1.toByte(), 0x00.toByte()), magWrites[0])
        assertArrayEquals(byteArrayOf(AK8963_REG_CNTL1.toByte(), 0x0F.toByte()), magWrites[1])
        assertArrayEquals(byteArrayOf(AK8963_REG_ASAX.toByte()), magWrites[2])
        assertArrayEquals(byteArrayOf(AK8963_REG_ASAY.toByte()), magWrites[3])
        assertArrayEquals(byteArrayOf(AK8963_REG_ASAZ.toByte()), magWrites[4])
        assertArrayEquals(byteArrayOf(AK8963_REG_CNTL1.toByte(), 0x00.toByte()), magWrites[5])
        assertArrayEquals(byteArrayOf(AK8963_REG_CNTL1.toByte(), 0x16.toByte()), magWrites[6])  // 16-bit | mode=6

        // mag(): raw (1000, -500, 250) with scale factors derived from ASAX/ASAY/ASAZ
        // above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625, (50-128)/256+1=0.6953125.
        magConnection.setRegister(AK8963_REG_HXL, *(s16le(1000) + s16le(-500) + s16le(250) + intArrayOf(0x00)))
        val m = sensor.mag()
        assertEquals(1000 * 0.15 * 1.28125, m[0], 1e-9)
        assertEquals(-500 * 0.15 * 0.890625, m[1], 1e-9)
        assertEquals(250 * 0.15 * 0.6953125, m[2], 1e-9)

        // magRaw()
        magConnection.setRegister(AK8963_REG_HXL, *(s16le(111) + s16le(-222) + s16le(333) + intArrayOf(0x00)))
        assertArrayEquals(intArrayOf(111, -222, 333), sensor.magRaw())
    }

    @Test
    fun magNotEnabledThrows() {
        val sensor = newInitializedSensor(MockConnection(), MockConnection())
        assertThrows(IllegalStateException::class.java) { sensor.mag() }
        assertThrows(IllegalStateException::class.java) { sensor.magRaw() }
    }
}
