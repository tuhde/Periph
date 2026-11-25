package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class QMI8658ATest {

    companion object {
        const val G = 9.80665
        const val D2R = Math.PI / 180.0
    }

    /** I²C fake that models the CTRL9 handshake, the self-test Avail flag and the FIFO_DATA port. */
    class QmiMock : MockConnection() {
        val regs = HashMap<Int, Int>().apply { put(0x00, 0x05) }
        val log = ArrayList<IntArray>()            // {byte0, byte1, ...}
        val cmds = ArrayList<IntArray>()           // {cmd, cal1..cal4h (8 values)}
        val fifo = ArrayDeque<Int>()

        fun set(reg: Int, vararg values: Int) {
            values.forEachIndexed { i, v -> regs[reg + i] = v and 0xFF }
        }

        fun setLe(reg: Int, vararg vals: Int) {
            vals.forEachIndexed { i, v -> set(reg + 2 * i, v and 0xFF, (v shr 8) and 0xFF) }
        }

        fun wrote(vararg bytes: Int): Boolean = log.any { it.contentEquals(bytes) }

        /** Last value written to a register, -1 if never written. */
        fun last(reg: Int): Int = log.lastOrNull { it.size == 2 && it[0] == reg }?.get(1) ?: -1

        fun lastCmd(): IntArray = cmds.last()
        fun cal(cmd: IntArray, i: Int): Int = cmd[i + 1]

        override fun write(data: ByteArray) {
            val e = IntArray(data.size) { data[it].toInt() and 0xFF }
            log.add(e)
            for (i in 1 until e.size) regs[e[0] + i - 1] = e[i]
            if (e.size == 2 && (e[0] == 0x03 || e[0] == 0x04)) {
                val st = regs[0x2D] ?: 0
                regs[0x2D] = if ((e[1] and 0x80) != 0) st or 0x01 else st and 0x01.inv()
            }
            if (e.size == 2 && e[0] == 0x0A) {
                val st = regs[0x2D] ?: 0
                if (e[1] != 0) {
                    val c = IntArray(9)
                    c[0] = e[1]
                    for (i in 0 until 8) c[i + 1] = regs[0x0B + i] ?: 0
                    cmds.add(c)
                    regs[0x2D] = st or 0x80
                } else {
                    regs[0x2D] = st and 0x80.inv()
                }
            }
        }

        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val reg = data[0].toInt() and 0xFF
            return ByteArray(n) {
                if (reg == 0x17) (if (fifo.isEmpty()) 0 else fifo.removeFirst()).toByte()
                else (regs[reg + it] ?: 0).toByte()
            }
        }
    }

    private lateinit var mock: QmiMock
    private lateinit var s: QMI8658AFull

    private fun make() {
        mock = QmiMock()
        s = QMI8658AFull(mock)
    }

    @Test
    fun initSequence() {
        make()
        assertArrayEquals(intArrayOf(0x60, 0xB0), mock.log[0])
        assertTrue(mock.wrote(0x02, 0x40) && mock.wrote(0x09, 0x80) && mock.wrote(0x03, 0x05))
        assertTrue(mock.wrote(0x04, 0x45) && mock.wrote(0x06, 0x77))
        assertArrayEquals(intArrayOf(0x08, 0x03), mock.log.last())
    }

    @Test
    fun whoAmIMismatchThrows() {
        assertThrows(IOException::class.java) { QMI8658AMinimal(MockConnection()) }
    }

    @Test
    fun accelGyroConversion() {
        make()
        mock.setLe(0x35, 16384, -8192, 0)
        val a = s.accel()
        assertEquals(G, a[0], 1e-3)
        assertEquals(-G / 2, a[1], 1e-3)
        mock.setLe(0x3B, 128, -256, 64)
        val g = s.gyro()
        assertEquals(D2R, g[0], 1e-6)
        assertEquals(-2 * D2R, g[1], 1e-6)
        assertEquals(0.5 * D2R, g[2], 1e-6)
    }

    @Test
    fun rawReadAllTemperature() {
        make()
        mock.setLe(0x33, 0x1980, 2048, 0, -2048, 16, 32, -64)
        assertArrayEquals(intArrayOf(2048, 0, -2048), s.accelRaw())
        assertArrayEquals(intArrayOf(16, 32, -64), s.gyroRaw())
        val sm = s.readAll()
        assertEquals(25.5, sm.tempC, 1e-3)
        assertEquals(2048.0 / 16384 * G, sm.accel[0], 1e-3)
        assertEquals(-64.0 / 128 * D2R, sm.gyro[2], 1e-6)
        assertEquals(25.5, s.temperature(), 1e-3)
    }

    @Test
    fun timestampDataReady() {
        make()
        mock.set(0x30, 1, 2, 3)
        assertEquals(0x030201, s.timestamp())
        mock.set(0x2E, 0x00)
        assertFalse(s.dataReady())
        mock.set(0x2E, 0x02)
        assertTrue(s.dataReady())
    }

    @Test
    fun configureUpdatesSensitivity() {
        make()
        s.configureAccel(2, 4)
        assertEquals(0x24, mock.last(0x03))
        mock.setLe(0x35, 4096, 0, 0)
        assertEquals(G, s.accel()[0], 1e-3)
        s.configureGyro(7, 3)
        assertEquals(0x73, mock.last(0x04))
        mock.setLe(0x3B, 16, 0, 0)
        assertEquals(D2R, s.gyro()[0], 1e-6)
        assertThrows(IllegalArgumentException::class.java) { s.configureAccel(4, 5) }
        s.configureFilters(true, 3, false, 1)
        assertEquals(0x27, mock.last(0x06))
    }

    @Test
    fun sensorsAndPower() {
        make()
        s.setSensors(true, false, false)
        assertEquals(0x01, mock.last(0x08))
        s.setSensors(true, true, true)
        assertEquals(0x13, mock.last(0x08))
        s.setSensors(true, true, false)
        s.setPowerDown(true)
        assertEquals(0x41, mock.last(0x02))
        assertEquals(0x00, mock.last(0x08))
        s.setPowerDown(false)
        assertEquals(0x40, mock.last(0x02))
    }

    @Test
    fun syncSample() {
        make()
        s.setSyncSample(true)
        assertEquals(0x12, mock.lastCmd()[0])
        assertEquals(1, mock.cal(mock.lastCmd(), 0))
        assertTrue(mock.wrote(0x08, 0x83))
        mock.regs[0x2D] = 0x03
        mock.setLe(0x35, 16384, 0, 0)
        assertEquals(G, s.accel()[0], 1e-3)
        s.setSyncSample(false)
        assertEquals(0x03, mock.last(0x08))
        assertEquals(0, mock.cal(mock.lastCmd(), 0))
    }

    @Test
    fun fifo() {
        make()
        s.configureFifo(2, 1, 16)
        assertTrue(mock.wrote(0x14, 0x06) && mock.wrote(0x13, 0x10))
        mock.set(0x16, 0x00)
        mock.set(0x15, 12)
        assertEquals(24, s.fifoCount())
        assertEquals(0, s.fifoStatus())
        for (i in 0 until 24) mock.fifo.addLast(i)
        mock.cmds.clear()
        val data = s.readFifo(-1)
        assertEquals(24, data.size)
        assertEquals(23, data[23].toInt())
        assertEquals(1, mock.cmds.size)
        assertEquals(0x05, mock.cmds[0][0])
        assertTrue(mock.wrote(0x02, 0x00))
        assertEquals(0x40, mock.last(0x02))
        assertEquals(0x06, mock.last(0x14))
        mock.fifo.clear()
        for (i in 0 until 24) mock.fifo.addLast(i)
        assertEquals(10, s.readFifo(10).size)
        s.resetFifo()
        assertEquals(0x04, mock.lastCmd()[0])
        s.configureFifo(0, 3, 0)
        assertEquals(0x03, mock.last(0x08))
    }

    @Test
    fun motionEngines() {
        make()
        s.configureAnyMotion(0.25, 3, 0x07, false)
        assertEquals(2, mock.cmds.size)
        val c1 = mock.cmds[0]
        val c2 = mock.cmds[1]
        assertEquals(0x0E, c1[0])
        assertEquals(0x0E, c2[0])
        assertEquals(8, mock.cal(c1, 0))
        assertEquals(4, mock.cal(c1, 3))
        assertEquals(0xF7, mock.cal(c1, 6))
        assertEquals(0x10, mock.cal(c1, 7))
        assertEquals(3, mock.cal(c2, 0))
        assertEquals(30, mock.cal(c2, 1))
        assertEquals(150, mock.cal(c2, 2))
        assertEquals(600 and 0xFF, mock.cal(c2, 4))
        assertEquals(600 shr 8, mock.cal(c2, 5))
        assertEquals(0x20, mock.cal(c2, 7))
        assertTrue(mock.wrote(0x08, 0x00))
        assertEquals(0x03, mock.last(0x08))
        s.configureSignificantMotion(100, 300)
        assertEquals(100, mock.cal(mock.lastCmd(), 2))
        assertEquals(300 and 0xFF, mock.cal(mock.lastCmd(), 4))
        assertEquals(1, mock.cal(mock.lastCmd(), 5))
        s.enableMotion(true, true, false)
        assertEquals(0x86, mock.last(0x09))
        s.enableMotion(false, false, false)
        assertEquals(0x80, mock.last(0x09))
    }

    @Test
    fun tapAndPedometer() {
        make()
        s.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4)
        assertEquals(2, mock.cmds.size)
        val t1 = mock.cmds[0]
        val t2 = mock.cmds[1]
        assertEquals(0x0C, t1[0])
        assertEquals(20, mock.cal(t1, 0))
        assertEquals(0, mock.cal(t1, 1))
        assertEquals(50, mock.cal(t1, 2))
        assertEquals(250, mock.cal(t1, 4))
        assertEquals(0x10, mock.cal(t1, 7))
        assertEquals(8, mock.cal(t2, 0))
        assertEquals(32, mock.cal(t2, 1))
        assertEquals(0x33, mock.cal(t2, 2))
        assertEquals(0x03, mock.cal(t2, 3))
        assertEquals(0x9A, mock.cal(t2, 4))
        assertEquals(0x01, mock.cal(t2, 5))
        assertEquals(0x20, mock.cal(t2, 7))
        s.enableTap(true)
        assertEquals(0x81, mock.last(0x09))
        mock.set(0x59, 0xA2)
        val ts = s.tapStatus()
        assertEquals(2, ts.count)
        assertEquals(2, ts.axis)
        assertTrue(ts.negative)
        s.enableTap(false)

        mock.cmds.clear()
        s.configurePedometer(50, 200.0, 100.0, 200, 20, 10, 0, 4)
        val p1 = mock.cmds[0]
        val p2 = mock.cmds[1]
        assertEquals(0x0D, p1[0])
        assertEquals(50, mock.cal(p1, 0))
        assertEquals(205, mock.cal(p1, 2))
        assertEquals(102, mock.cal(p1, 4))
        assertEquals(200, mock.cal(p2, 0))
        assertEquals(20, mock.cal(p2, 2))
        assertEquals(10, mock.cal(p2, 3))
        assertEquals(4, mock.cal(p2, 5))
        s.enablePedometer(true)
        assertEquals(0x90, mock.last(0x09))
        mock.set(0x5A, 0x34, 0x12, 0x00)
        assertEquals(0x1234, s.stepCount())
        s.resetStepCount()
        assertEquals(0x0F, mock.lastCmd()[0])
    }

    @Test
    fun wakeOnMotion() {
        make()
        s.setWakeOnMotion(100, 2, 1, 5)
        assertTrue(mock.wrote(0x03, 0x0C))
        assertEquals(0x08, mock.lastCmd()[0])
        assertEquals(100, mock.cal(mock.lastCmd(), 0))
        assertEquals(0xC5, mock.cal(mock.lastCmd(), 1))
        assertTrue(mock.wrote(0x08, 0x01))
        assertTrue((mock.last(0x02) and 0x10) != 0)
        s.setWakeOnMotion(0, 1, 0, 0)
        assertEquals(0, mock.cal(mock.lastCmd(), 0))
        assertEquals(0, mock.last(0x02) and 0x18)
    }

    @Test
    fun offsetsCalibrationSelfTest() {
        make()
        s.setAccelOffset(G, 0.0, -G)
        var c = mock.lastCmd()
        assertEquals(0x09, c[0])
        assertEquals(0x00, mock.cal(c, 0))
        assertEquals(0x10, mock.cal(c, 1))
        assertEquals(0x00, mock.cal(c, 4))
        assertEquals(0xF0, mock.cal(c, 5))
        s.setGyroOffset(D2R, 0.0, 0.0)
        c = mock.lastCmd()
        assertEquals(0x0A, c[0])
        assertEquals(32, mock.cal(c, 0))
        mock.set(0x46, 0x00)
        var cal = s.calibrateGyro()
        assertTrue(cal.success)
        assertEquals(0xA2, mock.lastCmd()[0])
        mock.set(0x46, 0x01)
        cal = s.calibrateGyro()
        assertFalse(cal.success)
        assertEquals(1, cal.codStatus)
        mock.setLe(0x51, 1000, 2000, 3000)
        assertArrayEquals(intArrayOf(1000, 2000, 3000), s.getGyroGains())
        s.setGyroGains(1000, 2000, 3000)
        c = mock.lastCmd()
        assertEquals(0xAA, c[0])
        assertEquals(0xE8, mock.cal(c, 0))
        assertEquals(0x03, mock.cal(c, 1))
        assertEquals(0xB8, mock.cal(c, 4))
        assertEquals(0x0B, mock.cal(c, 5))

        mock.setLe(0x51, 2048, 2048, -2048)
        var r = s.selfTestAccel()
        assertTrue(r.passed)
        assertEquals(G, r.values[0], 1e-3)
        mock.setLe(0x51, 16 * 400, 16 * 400, 16 * 400)
        r = s.selfTestGyro()
        assertTrue(r.passed)
        assertEquals(400 * D2R, r.values[0], 1e-3)
        mock.setLe(0x51, 16 * 100, 16 * 400, 16 * 400)
        assertFalse(s.selfTestGyro().passed)
    }

    @Test
    fun deviceInfoPullupsReset() {
        make()
        mock.set(0x01, 0x7C)
        mock.set(0x49, 1, 2, 0, 3)
        mock.set(0x4B, 3)
        mock.set(0x51, 1, 2, 3, 4, 5, 6)
        val info = s.deviceInfo()
        assertEquals(0x7C, info.revision)
        assertArrayEquals(byteArrayOf(1, 2, 3), info.firmware)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), info.usid)
        assertEquals(0x10, mock.lastCmd()[0])
        s.setPullups(false, true, true, false)
        assertEquals(0x11, mock.lastCmd()[0])
        assertEquals(0x09, mock.cal(mock.lastCmd(), 0))
        s.reset()
        assertTrue(mock.wrote(0x60, 0xB0))
    }

    @Test
    fun interrupts() {
        make()
        s.setInterruptPins(1, 1)
        assertTrue((mock.last(0x09) and 0x40) != 0)
        s.enableInterrupt(QMI8658AFull.SOURCE_TAP)
        assertTrue((mock.last(0x09) and 0x01) != 0 && (mock.last(0x02) and 0x08) != 0)
        s.enableInterrupt(QMI8658AFull.SOURCE_DATA_READY)
        assertTrue((mock.last(0x02) and 0x10) != 0)
        s.disableInterrupt(QMI8658AFull.SOURCE_DATA_READY)
        assertEquals(0, mock.last(0x02) and 0x10)
        assertTrue((mock.last(0x08) and 0x20) != 0)
        s.disableInterrupt(QMI8658AFull.SOURCE_TAP)
        assertEquals(0, mock.last(0x02) and 0x08)
        s.setInterruptPins(2, 2)
        mock.set(0x2E, 0x01, 0xA2)
        mock.set(0x16, 0xC0)
        val expect = QMI8658AFull.SOURCE_DATA_READY or QMI8658AFull.SOURCE_FIFO_WATERMARK or
            QMI8658AFull.SOURCE_FIFO_FULL or QMI8658AFull.SOURCE_ANY_MOTION or
            QMI8658AFull.SOURCE_SIGNIFICANT_MOTION or QMI8658AFull.SOURCE_TAP
        assertEquals(expect, s.pollInterrupt())
    }
}
