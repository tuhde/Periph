package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class FXOS8700Test {

    private fun newConnection(): MockConnection {
        val c = MockConnection()
        c.setRegister(0x0D, 0xC7)
        return c
    }

    private fun newChip(c: MockConnection) = FXOS8700Full(c)

    /** Data bytes of the last write to [reg], or null. */
    private fun lastWrite(c: MockConnection, reg: Int): ByteArray? {
        var out: ByteArray? = null
        for (w in c.writes()) {
            if (w.size >= 2 && (w[0].toInt() and 0xFF) == reg) {
                out = w.copyOfRange(1, w.size)
            }
        }
        return out
    }

    private fun lastByte(c: MockConnection, reg: Int): Int {
        val v = lastWrite(c, reg)
        return if (v == null) -1 else v[0].toInt() and 0xFF
    }

    private fun wasRead(c: MockConnection, reg: Int): Boolean =
        c.writes().any { it.size == 1 && (it[0].toInt() and 0xFF) == reg }

    /** Clears M_CTRL_REG1.m_ost once read, like the hardware. */
    private class OneShotMock : MockConnection() {
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val out = super.writeRead(data, n)
            if ((data[0].toInt() and 0xFF) == 0x5B) {
                setRegister(0x5B, (registers().getOrDefault(0x5B, 0)) and 0x20.inv())
            }
            return out
        }
    }

    /** Returns shifted accelerometer data while CTRL_REG2.st is set. */
    private class SelfTestMock(private val delta: IntArray) : MockConnection() {
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            val out = super.writeRead(data, n)
            if ((data[0].toInt() and 0xFF) == 0x01 && n == 6 &&
                (registers().getOrDefault(0x2B, 0) and 0x80) != 0
            ) {
                for (axis in 0 until 3) {
                    val raw = (delta[axis] shl 2) and 0xFFFF
                    out[2 * axis] = (raw shr 8).toByte()
                    out[2 * axis + 1] = raw.toByte()
                }
            }
            return out
        }
    }

    @Test
    fun initSequence() {
        val c = newConnection()
        newChip(c)
        val expect = arrayOf(
            intArrayOf(0x2B, 0x40), intArrayOf(0x2A, 0x00), intArrayOf(0x5B, 0x1F), intArrayOf(0x5C, 0x20),
            intArrayOf(0x0E, 0x00), intArrayOf(0x2B, 0x00), intArrayOf(0x2A, 0x11)
        )
        var i = 0
        for (w in c.writes()) {
            if (w.size == 2) {
                assertEquals(expect[i][0], w[0].toInt() and 0xFF)
                assertEquals(expect[i][1], w[1].toInt() and 0xFF)
                i++
            }
        }
        assertEquals(7, i)
    }

    @Test
    fun whoAmI() {
        val pre = MockConnection()
        pre.setRegister(0x0D, 0xC4)
        FXOS8700Minimal(pre)
        val bad = MockConnection()
        bad.setRegister(0x0D, 0x1A)
        assertThrows(IOException::class.java) { FXOS8700Minimal(bad) }
        assertEquals(-1, lastByte(bad, 0x2A))
    }

    @Test
    fun toleratesNackOnResetWrite() {
        val c = newConnection()
        c.failNextWrites(1, 1)
        FXOS8700Minimal(c)
    }

    @Test
    fun measurements() {
        val c = newConnection()
        val chip = newChip(c)
        c.setRegister(0x01, 0x40, 0x00, 0xC0, 0x00, 0x00, 0x04)
        c.setRegister(0x07, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
        c.setRegister(0x33, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
        val a = chip.readAccel()
        assertEquals(1.0, a[0], 1e-9)
        assertEquals(-1.0, a[1], 1e-9)
        assertEquals(1 / 4096.0, a[2], 1e-9)
        assertArrayEquals(intArrayOf(4096, -4096, 1), chip.readRawAccel())
        val m = chip.readMag()
        assertEquals(10.0, m[0], 1e-9)
        assertEquals(-10.0, m[1], 1e-9)
        assertEquals(50.0, m[2], 1e-9)
        assertArrayEquals(intArrayOf(100, -100, 500), chip.readRawMag())
        val h = chip.readHybrid()
        assertEquals(1.0, h[0], 1e-9)
        assertEquals(50.0, h[5], 1e-9)
        c.setRegister(0x0B, 0)
        chip.setRange(4)
        assertEquals(2.0, chip.readAccel()[0], 1e-9)
        chip.setRange(8)
        assertEquals(4.0, chip.readAccel()[0], 1e-9)
        assertEquals(0x02, lastByte(c, 0x0E))
        assertThrows(IllegalArgumentException::class.java) { chip.setRange(3) }
        assertThrows(IllegalArgumentException::class.java) { chip.setLowNoise(true) }
        c.setRegister(0x51, 0xFB)
        assertEquals(-5 * 0.96, chip.readTemperature(), 1e-9)
        chip.setMode(0)
        assertThrows(IllegalStateException::class.java) { chip.readTemperature() }
        assertThrows(IllegalStateException::class.java) { chip.readHybrid() }
    }

    @Test
    fun standbyHandling() {
        val c = newConnection()
        val chip = newChip(c)
        val before = c.writes().size
        chip.setDataRate(100.0)
        val w = c.writes().subList(before, c.writes().size)
            .filter { it.size == 2 && (it[0].toInt() and 0xFF) == 0x2A }
            .map { it[1].toInt() and 0xFF }
        assertEquals(listOf(0x10, 0x18, 0x19), w)
        assertThrows(IllegalArgumentException::class.java) { chip.setDataRate(77.0) }
        chip.setDataRate(1.56)
        assertEquals(7, (lastByte(c, 0x2A) shr 3) and 7)
    }

    @Test
    fun configuration() {
        val c = newConnection()
        val chip = newChip(c)
        chip.setLowNoise(true)
        assertEquals(0x05, lastByte(c, 0x2A) and 0x05)
        chip.setResolution(8)
        assertEquals(0x02, lastByte(c, 0x2A) and 0x02)
        c.setRegister(0x01, 0x40)
        c.setRegister(0x03, 0xC0)
        c.setRegister(0x05, 0x20)
        assertEquals(1.0, chip.readAccel()[0], 1e-9)
        chip.setResolution(14)
        chip.setAccelOversampling(2, 3)
        assertEquals(0x1A, lastByte(c, 0x2B) and 0x1B)
        chip.setMagOversampling(5, 2)
        assertEquals(5 shl 2, lastByte(c, 0x5B) and 0x1C)
        assertEquals(2 shl 4, lastByte(c, 0x5D) and 0x70)
        chip.setHighPassOutput(true, 2)
        assertEquals(0x10, lastByte(c, 0x0E) and 0x10)
        assertEquals(2, lastByte(c, 0x0F) and 0x03)
    }

    @Test
    fun offsets() {
        val c = newConnection()
        val chip = newChip(c)
        chip.setAccelOffset(0.06, -0.06, 1.0)
        assertArrayEquals(byteArrayOf(30, 0xE2.toByte(), 127), lastWrite(c, 0x2F))
        val ao = chip.getAccelOffset()
        assertEquals(0.06, ao[0], 1e-9)
        assertEquals(0.254, ao[2], 1e-9)
        chip.setMagOffset(60.0, -60.0, 0.0)
        assertArrayEquals(
            byteArrayOf(0x04, 0xB0.toByte(), 0xFB.toByte(), 0x50, 0x00, 0x00), lastWrite(c, 0x3F)
        )
        val mo = chip.getMagOffset()
        assertEquals(60.0, mo[0], 1e-9)
        assertEquals(-60.0, mo[1], 1e-9)
    }

    @Test
    fun magnetometerHelpers() {
        val c = OneShotMock()
        c.setRegister(0x0D, 0xC7)
        val chip = newChip(c)
        chip.setMagAutoCalibration(true)
        assertEquals(0x80, lastByte(c, 0x5B) and 0x80)
        chip.setMagAutoCalibration(false)
        assertEquals(0, lastByte(c, 0x5B) and 0x80)
        chip.resetMagMinMax()
        assertEquals(0x04, lastByte(c, 0x5C) and 0x04)
        chip.triggerMagReset()
        assertEquals(0x40, lastByte(c, 0x5B) and 0x40)
        chip.setMagReset(2)
        assertEquals(2, lastByte(c, 0x5C) and 0x03)
        c.setRegister(0x4B, 0xFF, 0x9C)
        c.setRegister(0x45, 0x00, 0x64)
        val mm = chip.readMagMinMax()
        assertEquals(-10.0, mm[0], 1e-9)
        assertEquals(10.0, mm[3], 1e-9)
        assertEquals(3, chip.measureMagOnce().size)
    }

    @Test
    fun heading() {
        val c = newConnection()
        val chip = newChip(c)
        chip.setMode(0)
        c.setRegister(0x01, 0, 0, 0, 0, 0x40, 0)
        c.setRegister(0x33, 0x01, 0xF4, 0, 0, 0, 0)
        assertEquals(0.0, chip.heading(0.0), 1e-6)
        assertEquals(10.0, chip.heading(10.0), 1e-6)
        c.setRegister(0x33, 0x00, 0x00, 0xFE, 0x0C, 0, 0)
        assertEquals(90.0, chip.heading(0.0), 1e-6)
    }

    @Test
    fun fifo() {
        val c = newConnection()
        val chip = newChip(c)
        c.setRegister(0x00, 0x42)
        c.setRegister(0x01, 0x40)
        chip.setFifo(1, 16)
        assertEquals(0x50, lastByte(c, 0x09))
        val before = c.writes().size
        chip.setFifo(2, 8)
        val f = c.writes().subList(before, c.writes().size)
            .filter { it.size == 2 && (it[0].toInt() and 0xFF) == 0x09 }
            .map { it[1].toInt() and 0xFF }
        assertEquals(listOf(0x00, 0x88), f)
        assertEquals(2, chip.fifoLevel())
        assertArrayEquals(intArrayOf(0, 1, 2), chip.readFifoStatus())
        val samples = chip.readFifo(0)
        assertEquals(2, samples.size)
        assertEquals(1.0, samples[0][0], 1e-9)
        assertThrows(IllegalArgumentException::class.java) { chip.setResolution(8) }
        chip.setFifoTrigger(true, false, false, true)
        assertEquals(0x24, lastByte(c, 0x0A))
        assertThrows(IllegalArgumentException::class.java) { chip.setFifo(4, 0) }
    }

    @Test
    fun detectors() {
        val c = newConnection()
        val chip = newChip(c)
        chip.setFreefallMotion(true, 7, 0.3, 60.0, true, false)
        assertEquals(0xF8, lastByte(c, 0x15))
        assertEquals(5, lastByte(c, 0x17))
        assertEquals(6, lastByte(c, 0x18))
        chip.setFreefallMotion(false, 3, 0.2, 0.0, false, true)
        assertEquals(0x18, lastByte(c, 0x15))
        assertEquals(0x83, lastByte(c, 0x17))
        chip.setTransient(6, 0.5, 40.0, true, true)
        assertEquals(0x1D, lastByte(c, 0x1D))
        assertEquals(8, lastByte(c, 0x1F))
        assertEquals(4, lastByte(c, 0x20))
        chip.setPulse(1, 4, doubleArrayOf(0.5, 0.5, 1.0), 30.0, 100.0, 300.0, true, false, true, true)
        assertEquals(0x61, lastByte(c, 0x21))
        assertArrayEquals(byteArrayOf(8, 8, 16), lastWrite(c, 0x23))
        assertEquals(3, lastByte(c, 0x26))
        assertEquals(5, lastByte(c, 0x27))
        assertEquals(15, lastByte(c, 0x28))
        assertEquals(0x10, lastByte(c, 0x0F) and 0x30)
        chip.setPulse(1, 0, doubleArrayOf(0.5, 0.5, 0.5), 30.0, 100.0, 300.0, false, false, false, false)
        assertEquals(12, lastByte(c, 0x26))
        assertEquals(0x20, lastByte(c, 0x0F) and 0x30)
        chip.setOrientation(true, 100.0, 45, 4, 4, 2)
        assertEquals(0xC0, lastByte(c, 0x11))
        assertEquals(10, lastByte(c, 0x12))
        assertEquals(0x84, lastByte(c, 0x14))
        assertEquals(0x84, lastByte(c, 0x13))
        assertThrows(IllegalArgumentException::class.java) { chip.setOrientation(true, 0.0, 75, 7, 4, 2) }
        assertThrows(IllegalArgumentException::class.java) { chip.setOrientation(true, 0.0, 50, 4, 4, 2) }
        c.setRegister(0x10, 0x80 or 0x40 or (3 shl 1) or 1)
        assertArrayEquals(intArrayOf(1, 1, 3, 1), chip.readOrientation())
        chip.setAutoSleep(true, 12.5, 10.0, FXOS8700Full.SOURCE_FF_MT or FXOS8700Full.SOURCE_PULSE)
        assertEquals(1, lastByte(c, 0x2A) shr 6)
        assertEquals(31, lastByte(c, 0x29))
        assertEquals(0x04, lastByte(c, 0x2B) and 0x04)
        assertEquals(0x18, lastByte(c, 0x2C) and 0x7C)
        assertThrows(IllegalArgumentException::class.java) { chip.setAutoSleep(true, 50.0, 10.0, 0x01) }
        chip.setFifoGate(true)
        assertEquals(0x80, lastByte(c, 0x2C) and 0x80)
        chip.setInterruptPins(true, true)
        assertEquals(0x03, lastByte(c, 0x2C) and 0x03)
    }

    @Test
    fun interrupts() {
        val c = newConnection()
        val chip = newChip(c)
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT or FXOS8700Full.SOURCE_ORIENTATION, 1)
        assertEquals(0x14, lastByte(c, 0x2D))
        assertEquals(0x14, lastByte(c, 0x2E))
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT, 2)
        assertEquals(0x10, lastByte(c, 0x2E))
        chip.disableInterrupt(FXOS8700Full.SOURCE_ORIENTATION)
        assertEquals(0x04, lastByte(c, 0x2D))
        assertThrows(IllegalArgumentException::class.java) { chip.enableInterrupt(1, 3) }
        c.setRegister(0x0C, 0x14)
        assertEquals(0x14, chip.pollInterrupt())
        assertTrue(wasRead(c, 0x16) && wasRead(c, 0x10))
    }

    @Test
    fun registersAndReset() {
        val c = newConnection()
        val chip = newChip(c)
        chip.writeRegister(0x5F, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), chip.readRegister(0x5F, 2))
        c.setRegister(0x00, 0x08)
        assertTrue(chip.newAccelData())
        assertFalse(chip.newMagData())
        c.setRegister(0x0B, 0x02)
        assertEquals(2, chip.readSystemMode())
        c.setRegister(0x0B, 0x00)
        chip.reset()
        assertThrows(IllegalStateException::class.java) { chip.readHybrid() }
        chip.init()
        chip.readHybrid()
    }

    @Test
    fun selfTest() {
        val c = SelfTestMock(intArrayOf(200, 300, 1300))
        c.setRegister(0x0D, 0xC7)
        assertArrayEquals(intArrayOf(1, 200, 300, 1300), newChip(c).selfTest())
        assertEquals(0x11, lastByte(c, 0x2A))
        val weak = SelfTestMock(intArrayOf(10, 10, 10))
        weak.setRegister(0x0D, 0xC7)
        assertEquals(0, newChip(weak).selfTest()[0])
    }
}
