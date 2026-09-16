package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ade7953Test {

    @Test
    fun fullApi() {
        val connection = MockConnection()
        connection.setRegister(0x21C, 0x89, 0xD1, 0x47)
        val chip = Ade7953Full(connection, 100.0, 10.0)
        assertEquals(35.35533905932738, chip.voltage(), 1e-3)
        connection.setRegister(0x21A, 0x89, 0xD1, 0x47)
        assertEquals(3.53553390593, chip.current(), 1e-3)
        connection.setRegister(0x212, 0x4A, 0x31, 0xC1)
        assertEquals(125.0, chip.activePower(), 1e-3)
        val writesBefore = connection.writes().size
        chip.reset()
        var foundSwrst = false
        for (i in (writesBefore + 1) until connection.writes().size) {
            val w = connection.writes()[i]
            if (w.size >= 4 && (w[3].toInt() and 0x80) != 0) {
                foundSwrst = true
                break
            }
        }
        assertTrue(foundSwrst, "reset writes SWRST")
    }
}