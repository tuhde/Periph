package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PCA9536Test {

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
        val chip = PCA9536Full(c, 0x41)

        assertEquals(0xFF, c.registers()[REG_OUTPUT])
        assertEquals(0x00, c.registers()[REG_POLARITY])
        assertEquals(0xFF, c.registers()[REG_CONFIG])
        assertEquals(0xFF, chip.shadow)
        assertEquals(0xFF, chip.direction)
        assertFalse(dataWrites(c, 0).contains(REG_INPUT), "init must not write INPUT")

        c.setRegister(REG_INPUT, 0xF5)
        assertEquals(0x05, chip.readPort(0))
        assertEquals(0x05, chip.readPort(1))

        chip.writePort(0, 0x0C)
        assertEquals(0xFC, c.registers()[REG_OUTPUT])
        assertEquals(0xFC, chip.shadow)

        // P3 is a normal bidirectional pin.
        chip.pin(3).setOutput()
        assertEquals(0xF7, c.registers()[REG_CONFIG])
        chip.pin(3).setInput()
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
        assertEquals(0xF1, c.registers()[REG_OUTPUT])
        chip.pin(2).setHigh()
        assertEquals(0xF5, c.registers()[REG_OUTPUT])
        chip.pin(0).setLow()
        assertEquals(0xF4, c.registers()[REG_OUTPUT])
        chip.pin(0).toggle()
        assertEquals(0xF5, c.registers()[REG_OUTPUT])

        // The weak pull-up is fixed.
        assertThrows(UnsupportedOperationException::class.java) { chip.pin(3).setPullUp(true) }

        chip.configurePolarity(0x0F)
        assertEquals(0x0F, c.registers()[REG_POLARITY])
        chip.configurePolarity(0x00)

        before = c.writes().size
        chip.setDirection(0x0C)
        assertEquals(0xFC, c.registers()[REG_CONFIG])
        assertEquals(listOf(REG_OUTPUT, REG_CONFIG), dataWrites(c, before))
        assertEquals(0xFC, chip.direction)

        assertEquals(0xFC, chip.readRegister(REG_CONFIG))
        chip.writeRegister(REG_OUTPUT, 0xF1)
        assertEquals(0xF1, chip.shadow)
        chip.writeRegister(REG_CONFIG, 0x0F)
        assertEquals(0x0F, chip.direction)
        assertThrows(IllegalArgumentException::class.java) { chip.writeRegister(REG_INPUT, 0) }
        assertThrows(IllegalArgumentException::class.java) { chip.readRegister(4) }

        // pollChanges: changed mask limited to inputs, cache updated.
        chip.setDirection(0x0C)
        c.setRegister(REG_INPUT, 0x00)
        chip.pollChanges()
        c.setRegister(REG_INPUT, 0x0D)
        assertEquals(0x0C, chip.pollChanges())
        assertEquals(0x00, chip.pollChanges())

        // A polarity change refreshes the cache and is not reported.
        c.setRegister(REG_INPUT, 0x00)
        chip.configurePolarity(0x0C)
        assertEquals(0x00, chip.pollChanges())
        chip.configurePolarity(0x00)
        chip.pollChanges()

        chip.reset()
        assertEquals(0xFF, c.registers()[REG_OUTPUT])
        assertEquals(0x00, c.registers()[REG_POLARITY])
        assertEquals(0xFF, c.registers()[REG_CONFIG])
        assertEquals(0xFF, chip.shadow)
        assertEquals(0xFF, chip.direction)
    }

    @Test
    fun watchChangesAndPinWatch() {
        val c = MockConnection()
        val chip = PCA9536Full(c, 0x41)
        val changes = java.util.concurrent.LinkedBlockingQueue<Int>()
        chip.watchChanges({ changes.add(it) }, 0.002)
        c.setRegister(REG_INPUT, 0x04)
        assertEquals(0x04, changes.poll(2, java.util.concurrent.TimeUnit.SECONDS))
        chip.unwatchChanges()

        val hits = java.util.concurrent.LinkedBlockingQueue<Int>()
        chip.pin(3).watch({ hits.add(3) }, it.uhde.periph.connection.EdgeTrigger.RISING)
        c.setRegister(REG_INPUT, 0x0C)
        assertEquals(3, hits.poll(2, java.util.concurrent.TimeUnit.SECONDS))
        chip.unwatchChanges()
    }

    @Test
    fun watchOutputThrows() {
        val chip = PCA9536Full(MockConnection(), 0x41)
        chip.pin(0).setOutput()
        assertThrows(IllegalStateException::class.java) { chip.pin(0).watch({ }) }
    }

    @Test
    fun minimalIsConstructible() {
        assertEquals(0, PCA9536Minimal(MockConnection(), 0x41).readPort())
    }
}
