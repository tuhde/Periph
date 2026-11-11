package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PCA6408ATest {

    companion object {
        private const val REG_INPUT = 0x00
        private const val REG_OUTPUT = 0x01
        private const val REG_POLARITY = 0x02
        private const val REG_CONFIG = 0x03

        /** Register bytes of the data writes (length > 1) after index [from]; read-pointer writes are skipped. */
        private fun dataWrites(c: MockConnection, from: Int): List<Int> =
            c.writes().drop(from).filter { it.size > 1 }.map { it[0].toInt() and 0xFF }
    }

    @Test
    fun fullApi() {
        val c = MockConnection()
        val chip = PCA6408AFull(c, 0x20)

        assertEquals(0xFF, c.registers()[REG_OUTPUT])
        assertEquals(0x00, c.registers()[REG_POLARITY])
        assertEquals(0xFF, c.registers()[REG_CONFIG])
        assertEquals(0xFF, chip.shadow)
        assertEquals(0xFF, chip.direction)
        assertFalse(dataWrites(c, 0).contains(REG_INPUT), "init must not write INPUT")

        c.setRegister(REG_INPUT, 0xA5)
        assertEquals(0xA5, chip.readPort(0))
        assertEquals(0xA5, chip.readPort(1))

        chip.writePort(0, 0x3C)
        assertEquals(0x3C, c.registers()[REG_OUTPUT])
        assertEquals(0x3C, chip.shadow)

        // P7 is a normal bidirectional pin.
        chip.pin(7).setOutput()
        assertEquals(0x7F, c.registers()[REG_CONFIG])
        chip.pin(7).setInput()
        assertEquals(0xFF, c.registers()[REG_CONFIG])

        // OUTPUT is written before CONFIG when a pin becomes an output.
        chip.writePort(0, 0x00)
        var before = c.writes().size
        chip.pin(1).setOutput()
        assertEquals(listOf(REG_OUTPUT, REG_CONFIG), dataWrites(c, before))

        chip.writePort(0, 0x00)
        c.setRegister(REG_INPUT, 0x04)
        assertTrue(chip.pin(2).read())
        chip.pin(0).setHigh()
        assertEquals(0x01, c.registers()[REG_OUTPUT])
        chip.pin(2).setHigh()
        assertEquals(0x05, c.registers()[REG_OUTPUT])
        chip.pin(0).setLow()
        assertEquals(0x04, c.registers()[REG_OUTPUT])
        chip.pin(0).toggle()
        assertEquals(0x05, c.registers()[REG_OUTPUT])

        // No pull-ups.
        assertThrows(UnsupportedOperationException::class.java) { chip.pin(3).setPullUp(true) }

        chip.configurePolarity(0x0F)
        assertEquals(0x0F, c.registers()[REG_POLARITY])

        before = c.writes().size
        chip.setDirection(0xF0)
        assertEquals(0xF0, c.registers()[REG_CONFIG])
        assertEquals(listOf(REG_OUTPUT, REG_CONFIG), dataWrites(c, before))
        assertEquals(0xF0, chip.direction)

        assertEquals(0xF0, chip.readRegister(REG_CONFIG))
        chip.writeRegister(REG_OUTPUT, 0x81)
        assertEquals(0x81, chip.shadow)
        chip.writeRegister(REG_CONFIG, 0x0F)
        assertEquals(0x0F, chip.direction)
        assertThrows(IllegalArgumentException::class.java) { chip.writeRegister(REG_INPUT, 0) }
        assertThrows(IllegalArgumentException::class.java) { chip.readRegister(4) }

        // pollInterrupt: changed mask limited to inputs, cache updated.
        chip.setDirection(0xF0)
        c.setRegister(REG_INPUT, 0x00)
        chip.pollInterrupt()
        c.setRegister(REG_INPUT, 0x35)
        assertEquals(0x30, chip.pollInterrupt())
        assertEquals(0x00, chip.pollInterrupt())

        chip.reset()
        assertEquals(0xFF, c.registers()[REG_OUTPUT])
        assertEquals(0x00, c.registers()[REG_POLARITY])
        assertEquals(0xFF, c.registers()[REG_CONFIG])
        assertEquals(0xFF, chip.shadow)
        assertEquals(0xFF, chip.direction)
    }

    @Test
    fun watchOutputThrows() {
        val chip = PCA6408AFull(MockConnection(), 0x20)
        chip.pin(0).setOutput()
        assertThrows(IllegalStateException::class.java) { chip.pin(0).watch({ }) }
    }

    @Test
    fun minimalIsConstructible() {
        assertEquals(0, PCA6408AMinimal(MockConnection(), 0x20).readPort())
    }
}
