package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun lastWriteTo(connection: MockConnection, reg: Int): Int {
    for (w in connection.writes().asReversed()) {
        if (w.size == 2 && (w[0].toInt() and 0xFF) == reg) return w[1].toInt() and 0xFF
    }
    return -1
}

class Hmc5883lTest {

    @Test
    fun constructionAndMagneticField() {
        val connection = MockConnection()
        val chip = Hmc5883lMinimal(connection)
        assertEquals(0x70, lastWriteTo(connection, 0x00))
        assertEquals(0x20, lastWriteTo(connection, 0x01))
        assertEquals(0x00, lastWriteTo(connection, 0x02))

        // X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0) // x=1000,z=-500,y=2000
        val xyz = chip.magneticField()
        assertEquals((1000 / 1090.0) * 1e-4, xyz[0]!!, 1e-9)
        assertEquals((2000 / 1090.0) * 1e-4, xyz[1]!!, 1e-9)
        assertEquals((-500 / 1090.0) * 1e-4, xyz[2]!!, 1e-9)
    }

    @Test
    fun magneticFieldOverflowReturnsNull() {
        val connection = MockConnection()
        val chip = Hmc5883lMinimal(connection)
        connection.setRegister(0x03, 0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8) // x overflow, z/y=1000
        val xyz = chip.magneticField()
        assertNull(xyz[0])
        assertTrue(xyz[1] != null)
    }

    @Test
    fun configureAndSetGain() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)

        full.configure(30.0, 4, 5)
        assertEquals(0x54, lastWriteTo(connection, 0x00)) // MA=10,DO=101
        assertEquals(0xA0, lastWriteTo(connection, 0x01)) // GN=101

        assertThrows(IllegalArgumentException::class.java) { full.configure(30.0, 3, 1) }
        assertThrows(IllegalArgumentException::class.java) { full.configure(100.0, 4, 1) }
        assertThrows(IllegalArgumentException::class.java) { full.configure(30.0, 4, 8) }

        full.setGain(2)
        assertEquals(2 shl 5, lastWriteTo(connection, 0x01))
        // Regression: Groovy's equivalent setGain() previously stack-overflowed
        // via "this.gain = gain" recursing into itself; confirm the cached
        // gain actually took effect (not just that the register write happened)
        // by checking a subsequent conversion uses gain=2's sensitivity (820).
        connection.setRegister(0x03, 0x03, 0xE8, 0x00, 0x00, 0x00, 0x00) // x=1000
        val xyz = full.magneticField()
        assertEquals((1000 / 820.0) * 1e-4, xyz[0]!!, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { full.setGain(9) }
    }

    @Test
    fun setMode() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)

        full.setMode("continuous")
        assertEquals(0b00, lastWriteTo(connection, 0x02))
        full.setMode("single")
        assertEquals(0b01, lastWriteTo(connection, 0x02))
        full.setMode("idle")
        assertEquals(0b10, lastWriteTo(connection, 0x02))
        assertThrows(IllegalArgumentException::class.java) { full.setMode("bogus") }
    }

    @Test
    fun dataReadyAndStatus() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)

        connection.setRegister(0x09, 0x01)
        assertTrue(full.dataReady())
        assertEquals(0x01, full.status())
        connection.setRegister(0x09, 0x02) // LOCK set, RDY clear
        assertFalse(full.dataReady())
    }

    @Test
    fun singleMeasurement() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)
        val xyz = full.singleMeasurement()
        assertEquals(0x01, lastWriteTo(connection, 0x02))
        assertEquals((1000 / Hmc5883lMinimal.GAIN_LSB_PER_GAUSS[1].toDouble()) * 1e-4, xyz[0]!!, 1e-9)
    }

    @Test
    fun identify() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)
        connection.setRegister(0x0A, 0x48, 0x34, 0x33)
        val id = full.identify()
        assertEquals(0x48, id[0])
        assertEquals(0x34, id[1])
        assertEquals(0x33, id[2])
    }

    @Test
    fun selfTestSetsAndRestoresBias() {
        val connection = MockConnection()
        val full = Hmc5883lFull(connection)
        connection.setRegister(0x00, 0x70) // current Config A (post-init)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)

        val xyz = full.selfTest(true)
        val writesA = connection.writes()
            .filter { it.size == 2 && (it[0].toInt() and 0xFF) == 0x00 }
            .map { it[1].toInt() and 0xFF }
        assertTrue(writesA.contains(0x71)) // 0x70|0b01
        assertEquals(0x70, writesA.last())
        assertEquals((1000 / Hmc5883lMinimal.GAIN_LSB_PER_GAUSS[1].toDouble()) * 1e-4, xyz[0]!!, 1e-9)
    }
}
