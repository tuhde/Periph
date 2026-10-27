package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class LSM303Test {

    private fun mocks(): Pair<MockConnection, MockConnection> {
        val accel = MockConnection()
        val mag = MockConnection()
        mag.setRegister(0x0A, 0x48, 0x34, 0x33)
        accel.setRegister(0x28 or 0x80, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00)
        mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4)
        return accel to mag
    }

    private fun lastWrite(mock: MockConnection, reg: Int): Int {
        var value = -1
        for (w in mock.writes()) if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) value = w[1].toInt() and 0xFF
        return value
    }

    @Test
    fun initAndRead() {
        val (accel, mag) = mocks()
        val chip = LSM303Minimal(accel, mag)
        assertEquals(0x88, lastWrite(accel, 0x23))
        assertEquals(0x57, lastWrite(accel, 0x20))
        assertEquals(0x20, lastWrite(mag, 0x01))
        val a = chip.readAcceleration()
        assertEquals(0.001, a[0], 1e-9)
        assertEquals(0.002, a[1], 1e-9)
        assertEquals(0.003, a[2], 1e-9)
        val m = chip.readMagnetic()
        assertEquals(100.0, m[0], 1e-6)
        assertEquals(-100.0, m[1], 1e-6)
        assertEquals(100.0, m[2], 1e-6)
    }

    @Test
    fun wrongIdentityThrows() {
        assertThrows(IOException::class.java) { LSM303Minimal(MockConnection(), MockConnection()) }
    }

    @Test
    fun fullConfiguration() {
        val (accel, mag) = mocks()
        val chip = LSM303Full(accel, mag)
        chip.setRange(4)
        assertEquals(0.002, chip.readAcceleration()[0], 1e-9)
        assertEquals(0x10, lastWrite(accel, 0x23) and 0x30)
        chip.setDataRate(400)
        assertEquals(0x07, (lastWrite(accel, 0x20) shr 4) and 0x0F)
        chip.setMode(LSM303Minimal.MODE_NORMAL)
        assertEquals(0, lastWrite(accel, 0x23) and 0x08)
        chip.setRange(2)
        chip.setMotionInterrupt(1, 0x3F, 0.5, 0.0, true)
        assertEquals(32, lastWrite(accel, 0x32))
        chip.setFreeFall(1, 0.35, 30.0)
        assertEquals(0x95, lastWrite(accel, 0x30))
        chip.setClick(0, 0x04, 0.5, 10.0, 20.0, 50.0)
        assertEquals(0x20, lastWrite(accel, 0x38) and 0x20)
        chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1)
        assertEquals(0x10, lastWrite(accel, 0x22) and 0x10)
        chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 2)
        assertEquals(0x80, lastWrite(accel, 0x25) and 0x80)
        assertThrows(IllegalArgumentException::class.java) {
            chip.enableInterrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1)
        }
        chip.setMagRange(470)
        assertEquals(0xA0, lastWrite(mag, 0x01))
        mag.setRegister(0x03, 0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C)
        assertEquals(100.0, chip.readMagnetic()[0], 1e-6)
        chip.enableTemperature(true)
        chip.setMagDataRate(30.0)
        assertEquals(0x14, lastWrite(mag, 0x00) and 0x1C)
        assertEquals(0x80, lastWrite(mag, 0x00) and 0x80)
        assertArrayEquals(byteArrayOf(0x48, 0x34, 0x33), chip.readMagIdentity())
        mag.setRegister(0x31, 0x00, 0x80)
        assertEquals(1.0, chip.readTemperatureDelta(), 1e-9)
    }
}
