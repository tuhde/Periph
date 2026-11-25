package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ICM20948Test {

    /** I²C fake that models the ICM-20948 user banks (REG_BANK_SEL = 0x7F). */
    class BankedMock : MockConnection() {
        var bank = 0
        val regs = HashMap<Int, Int>()
        val log = ArrayList<IntArray>()   // {bank, byte0, byte1, ...}

        init {
            set(0, 0x00, 0xEA)
            set(0, 0x17, 0x40)   // I2C_MST_STATUS: SLV4_DONE
        }

        fun set(b: Int, reg: Int, vararg values: Int) {
            values.forEachIndexed { i, v -> regs[(b shl 8) or (reg + i)] = v and 0xFF }
        }

        fun get(b: Int, reg: Int): Int = regs[(b shl 8) or reg] ?: 0

        override fun write(data: ByteArray) {
            log.add(intArrayOf(bank) + data.map { it.toInt() and 0xFF }.toIntArray())
            if ((data[0].toInt() and 0xFF) == 0x7F) {
                bank = (data[1].toInt() and 0xFF) shr 4
                return
            }
            for (i in 1 until data.size) regs[(bank shl 8) or ((data[0].toInt() and 0xFF) + i - 1)] = data[i].toInt() and 0xFF
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            log.add(intArrayOf(bank, data[0].toInt() and 0xFF))
            return ByteArray(n) { get(bank, (data[0].toInt() and 0xFF) + it).toByte() }
        }

        /** Register writes (without bank selects) that landed in the given bank. */
        fun writesIn(b: Int): List<IntArray> =
            log.filter { it[0] == b && it.size > 2 && it[1] != 0x7F }.map { it.copyOfRange(1, it.size) }

        fun lastIn(b: Int): IntArray = writesIn(b).last()

        fun wrote(b: Int, vararg bytes: Int): Boolean = writesIn(b).any { it.contentEquals(bytes) }
    }

    private fun s16(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)
    private fun s16le(v: Int) = intArrayOf(v and 0xFF, (v shr 8) and 0xFF)
    private fun cat(vararg parts: IntArray): IntArray = parts.fold(IntArray(0)) { acc, p -> acc + p }

    @Test
    fun initWritesSequence() {
        val m = BankedMock()
        ICM20948Full(m)
        val expected = arrayOf(
            intArrayOf(0, 0x7F, 0x00), intArrayOf(0, 0x00), intArrayOf(0, 0x06, 0x80), intArrayOf(0, 0x06, 0x01),
            intArrayOf(0, 0x07, 0x00),
            intArrayOf(0, 0x7F, 0x20), intArrayOf(2, 0x01, 0x19), intArrayOf(2, 0x7F, 0x00),
            intArrayOf(0, 0x7F, 0x20), intArrayOf(2, 0x14, 0x19), intArrayOf(2, 0x7F, 0x00),
            intArrayOf(0, 0x7F, 0x20), intArrayOf(2, 0x00, 0x0A), intArrayOf(2, 0x7F, 0x00),
            intArrayOf(0, 0x7F, 0x20), intArrayOf(2, 0x10, 0x00), intArrayOf(2, 0x7F, 0x00),
            intArrayOf(0, 0x7F, 0x20), intArrayOf(2, 0x11, 0x0A), intArrayOf(2, 0x7F, 0x00)
        )
        assertEquals(expected.size, m.log.size)
        for (i in expected.indices) assertArrayEquals(expected[i], m.log[i], "init write $i")
        assertEquals(0, m.bank)
    }

    @Test
    fun whoAmIMismatchThrows() {
        val m = BankedMock()
        m.set(0, 0x00, 0x00)
        assertThrows(IOException::class.java) { ICM20948Full(m) }
    }

    @Test
    fun accelGyroAndConfiguration() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        m.set(0, 0x2D, *cat(s16(16384), s16(-8192), s16(4096)))
        val a = s.accel()
        assertEquals(9.80665, a[0], 1e-9)
        assertEquals(-4.903325, a[1], 1e-9)
        assertEquals(2.4516625, a[2], 1e-9)
        m.set(0, 0x33, *cat(s16(131), s16(-131), s16(262)))
        val g = s.gyro()
        assertEquals(Math.toRadians(1.0), g[0], 1e-9)
        assertEquals(Math.toRadians(-1.0), g[1], 1e-9)
        assertEquals(Math.toRadians(2.0), g[2], 1e-9)

        s.configureGyro(2)
        assertArrayEquals(intArrayOf(0x01, 0x1D), m.lastIn(2))
        m.set(0, 0x33, *cat(s16(328), s16(0), s16(0)))
        assertEquals(Math.toRadians(10.0), s.gyro()[0], 1e-9)
        s.configureAccel(1)
        assertArrayEquals(intArrayOf(0x14, 0x1B), m.lastIn(2))
        m.set(0, 0x2D, *cat(s16(8192), s16(0), s16(0)))
        assertEquals(9.80665, s.accel()[0], 1e-9)

        s.configureFilters(5, 2, false, true)
        var w2 = m.writesIn(2)
        assertArrayEquals(intArrayOf(0x01, (5 shl 3) or (2 shl 1)), w2[w2.size - 2])
        assertArrayEquals(intArrayOf(0x14, (2 shl 3) or (1 shl 1) or 1), w2[w2.size - 1])
        s.configureFilters(3, 3, true, true)

        s.configureSampleRate(9, 0x123)
        w2 = m.writesIn(2)
        assertArrayEquals(intArrayOf(0x00, 9), w2[w2.size - 3])
        assertArrayEquals(intArrayOf(0x10, 0x01), w2[w2.size - 2])
        assertArrayEquals(intArrayOf(0x11, 0x23), w2[w2.size - 1])
        assertEquals(0, m.bank)
    }

    @Test
    fun burstRawAndTemperature() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        s.configureAccel(1)
        s.configureGyro(2)
        m.set(0, 0x2D, *cat(s16(8192), s16(0), s16(0), s16(262), s16(0), s16(0), s16(340)))
        val sample = s.readAll()
        assertEquals(9.80665, sample.accel[0], 1e-9)
        assertEquals(Math.toRadians(262 / 32.8), sample.gyro[0], 1e-9)
        assertEquals(340 / 333.87 + 21.0, sample.tempC, 1e-9)

        m.set(0, 0x39, *s16(340))
        assertEquals(340 / 333.87 + 21.0, s.temperature(), 1e-9)
        m.set(0, 0x2D, *cat(s16(100), s16(-200), s16(300)))
        assertArrayEquals(intArrayOf(100, -200, 300), s.accelRaw())
        m.set(0, 0x33, *cat(s16(-50), s16(60), s16(-70)))
        assertArrayEquals(intArrayOf(-50, 60, -70), s.gyroRaw())
        m.set(0, 0x1A, 0x01)
        assertTrue(s.dataReady())
        m.set(0, 0x1A, 0x00)
        assertFalse(s.dataReady())
    }

    @Test
    fun powerAndOffsets() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        s.setSensors(false, true)
        assertArrayEquals(intArrayOf(0x07, 0x38), m.lastIn(0))
        s.setSensors(true, false)
        assertArrayEquals(intArrayOf(0x07, 0x07), m.lastIn(0))
        s.setSensors(true, true)
        assertArrayEquals(intArrayOf(0x07, 0x00), m.lastIn(0))
        s.setSleep(true)
        assertArrayEquals(intArrayOf(0x06, 0x41), m.lastIn(0))
        s.setSleep(false)
        assertArrayEquals(intArrayOf(0x06, 0x01), m.lastIn(0))

        s.setGyroOffset(Math.toRadians(1.0), 0.0, Math.toRadians(-1.0))
        assertArrayEquals(intArrayOf(0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF), m.lastIn(2))
        s.setAccelOffset(0x1234, -1, 0)
        val w1 = m.writesIn(1)
        assertArrayEquals(intArrayOf(0x14, 0x24, 0x68), w1[w1.size - 3])
        assertArrayEquals(intArrayOf(0x17, 0xFF, 0xFE), w1[w1.size - 2])
        m.set(1, 0x14, 0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02)
        assertArrayEquals(intArrayOf(0x1234, -1, 1), s.getAccelOffset())
        m.set(1, 0x28, 0xF6)
        assertEquals(-10 * 0.079, s.readTimebaseCorrection(), 1e-9)
        assertEquals(0, m.bank)
    }

    @Test
    fun fifo() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        m.set(0, 0x70, 0x03, 0x45)
        assertEquals(0x345, s.fifoCount())
        m.set(0, 0x70, 0x00, 0x02)
        m.set(0, 0x72, 0xAA, 0xBB)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), s.readFifo(-1))
        assertArrayEquals(byteArrayOf(0xAA.toByte()), s.readFifo(1))
        m.set(0, 0x70, 0x00, 0x00)
        assertEquals(0, s.readFifo(-1).size)

        s.enableFifo(true, true, false)
        assertTrue(m.wrote(0, 0x67, 0x1E))
        assertTrue(m.wrote(0, 0x03, 0x40))
        val w0 = m.writesIn(0)
        assertArrayEquals(intArrayOf(0x68, 0x1F), w0[w0.size - 2])
        assertArrayEquals(intArrayOf(0x68, 0x00), w0[w0.size - 1])
        s.setFifoMode(true)
        assertArrayEquals(intArrayOf(0x69, 0x1F), m.lastIn(0))
        s.setFifoMode(false)
        assertArrayEquals(intArrayOf(0x69, 0x00), m.lastIn(0))
    }

    @Test
    fun interruptsAndWakeOnMotion() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        s.setInterruptPin(true, false, true, true)
        assertArrayEquals(intArrayOf(0x0F, 0xB0), m.lastIn(0))
        s.enableInterrupt(ICM20948Full.SOURCE_DATA_READY)
        assertArrayEquals(intArrayOf(0x11, 0x01), m.lastIn(0))
        s.enableInterrupt(ICM20948Full.SOURCE_PLL_READY)
        assertArrayEquals(intArrayOf(0x10, 0x04), m.lastIn(0))
        s.disableInterrupt(ICM20948Full.SOURCE_PLL_READY)
        assertArrayEquals(intArrayOf(0x10, 0x00), m.lastIn(0))
        s.enableInterrupt(ICM20948Full.SOURCE_FIFO_OVERFLOW)
        assertArrayEquals(intArrayOf(0x12, 0x1F), m.lastIn(0))
        assertThrows(IllegalArgumentException::class.java) { s.enableInterrupt(0x80) }

        m.set(0, 0x19, 0x09, 0x01, 0x02)
        assertEquals(0x01 or 0x02 or 0x04 or 0x10, s.pollInterrupt())
        m.set(0, 0x19, 0x04, 0x00, 0x00)
        assertEquals(0x08, s.pollInterrupt())

        s.setWakeOnMotion(200.0, true)
        assertTrue(m.wrote(2, 0x13, 50))
        assertTrue(m.wrote(2, 0x12, 0x03))
        assertArrayEquals(intArrayOf(0x10, 0x08), m.lastIn(0))
    }

    @Test
    fun lowPowerGuard() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        m.set(0, 0x06, 0x01)
        s.setLowPower(true, true, 2, 3)
        assertTrue(m.wrote(0, 0x05, 0x70))
        assertArrayEquals(intArrayOf(0x06, 0x21), m.lastIn(0))
        assertTrue(m.wrote(2, 0x15, 0x02) && m.wrote(2, 0x02, 0x03))
        m.log.clear()
        s.configureGyro(0)
        val w0 = m.writesIn(0)
        assertArrayEquals(intArrayOf(0x06, 0x01), w0.first())
        assertArrayEquals(intArrayOf(0x06, 0x21), w0.last())
        s.setLowPower(false, false, 0, 0)
        assertEquals(0, m.get(0, 0x06) and 0x20)
    }

    @Test
    fun magnetometer() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        assertThrows(IllegalStateException::class.java) { s.mag() }
        assertThrows(IllegalStateException::class.java) { s.magRaw() }
        assertThrows(IllegalStateException::class.java) { s.magDataReady() }

        m.set(3, 0x17, 0x09)   // slave-4 read returns WIA2 = 0x09
        s.enableMag()
        assertEquals(0, m.bank)
        assertTrue(m.wrote(3, 0x01, 0x07))
        assertTrue(m.wrote(3, 0x16, 0x01) && m.wrote(3, 0x14, 0x32))
        assertTrue(m.wrote(3, 0x16, 0x08) && m.wrote(3, 0x14, 0x31))
        val w3 = m.writesIn(3)
        assertArrayEquals(intArrayOf(0x03, 0x8C), w3[w3.size - 3])
        assertArrayEquals(intArrayOf(0x04, 0x10), w3[w3.size - 2])
        assertArrayEquals(intArrayOf(0x05, 0x89), w3[w3.size - 1])
        assertEquals(0x20, m.get(0, 0x03) and 0x20)

        m.set(0, 0x3B, *cat(intArrayOf(0x01), s16le(1000), s16le(-500), s16le(250), intArrayOf(0x00, 0x00)))
        val mag = s.mag()
        assertEquals(150.0, mag[0], 1e-9)
        assertEquals(-75.0, mag[1], 1e-9)
        assertEquals(37.5, mag[2], 1e-9)
        assertArrayEquals(intArrayOf(1000, -500, 250), s.magRaw())
        assertTrue(s.magDataReady())
        m.set(0, 0x3B, 0x00)
        assertFalse(s.magDataReady())
        m.set(0, 0x3B, *cat(intArrayOf(0x01), s16le(1), s16le(2), s16le(3), intArrayOf(0x00, 0x08)))
        assertThrows(IOException::class.java) { s.mag() }
        assertArrayEquals(intArrayOf(1, 2, 3), s.magRaw())

        s.magSetMode(2)
        assertTrue(m.wrote(3, 0x16, 0x02))
        m.set(3, 0x17, 0x5A)
        assertEquals(0x5A, s.auxReadByte(0x1E, 0x0A))
        assertTrue(m.wrote(3, 0x13, 0x9E))
        s.auxWriteByte(0x1E, 0x0B, 0xA5)
        assertTrue(m.wrote(3, 0x13, 0x1E) && m.wrote(3, 0x16, 0xA5))
        assertEquals(0, m.bank)
    }

    @Test
    fun magnetometerErrors() {
        val s1 = ICM20948Full(BankedMock())   // WIA2 reads 0 -> mismatch
        assertThrows(IOException::class.java) { s1.enableMag() }

        val nack = BankedMock()
        nack.set(0, 0x17, 0x10)
        val s2 = ICM20948Full(nack)
        assertThrows(IOException::class.java) { s2.auxReadByte(0x0C, 0x01) }
    }

    @Test
    fun selfTestAndReset() {
        val m = BankedMock()
        val s = ICM20948Full(m)
        val r = s.selfTest()
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), r[0])
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), r[1])
        assertEquals(0, m.bank)
        s.reset()
        assertArrayEquals(intArrayOf(0x06, 0x80), m.lastIn(0))
    }
}
