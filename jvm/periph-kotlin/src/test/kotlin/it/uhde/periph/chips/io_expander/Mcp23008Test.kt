package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class Mcp23008Test {

    companion object {
        private const val REG_IODIR = 0x00
        private const val REG_IPOL = 0x01
        private const val REG_GPINTEN = 0x02
        private const val REG_DEFVAL = 0x03
        private const val REG_IOCON = 0x05
        private const val REG_GPPU = 0x06
        private const val REG_INTF = 0x07
        private const val REG_INTCAP = 0x08
        private const val REG_GPIO = 0x09
        private const val REG_OLAT = 0x0A

        private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }

    /**
     * The MCP23S08 read header is `[opcode, reg]`; MockConnection treats the
     * first byte as the register, so resolve reads from the second byte instead.
     */
    private class SpiMock : MockConnection() {
        override fun writeRead(data: ByteArray, n: Int): ByteArray {
            writes().add(data.clone())
            return ByteArray(n) { i -> (registers()[(data[1].toInt() and 0xFF) + i] ?: 0).toByte() }
        }
    }

    @Test
    fun i2cFullApi() {
        val c = MockConnection()
        val chip = Mcp23008Full(c, 0x20)

        assertEquals(0x00, c.registers()[REG_OLAT])
        assertEquals(0x00, chip.shadow)
        assertEquals(0xFF, chip.direction)
        assertTrue(c.writes().none { (it[0].toInt() and 0xFF) == REG_IODIR }, "init must not write IODIR")

        c.setRegister(REG_GPIO, 0xA5)
        assertEquals(0xA5, chip.readPort(0))
        assertEquals(0xA5, chip.readPort(1))

        chip.writePort(0, 0x3C)
        assertEquals(0x3C, c.registers()[REG_OLAT])
        assertEquals(0x3C, chip.shadow)

        // GP7 is a normal bidirectional pin.
        chip.pin(7).setOutput()
        assertEquals(0x7F, c.registers()[REG_IODIR])
        chip.pin(7).setInput()
        assertEquals(0xFF, c.registers()[REG_IODIR])

        // OLAT is written before IODIR when a pin becomes an output.
        chip.writePort(0, 0x00)
        val before = c.writes().size
        chip.pin(1).setOutput()
        assertEquals(before + 2, c.writes().size)
        assertEquals(REG_OLAT, c.writes()[before][0].toInt() and 0xFF)
        assertEquals(REG_IODIR, c.writes()[before + 1][0].toInt() and 0xFF)

        chip.writePort(0, 0x00)
        c.setRegister(REG_GPIO, 0x04)
        assertTrue(chip.pin(2).read())
        chip.pin(0).setHigh()
        assertEquals(0x01, c.registers()[REG_OLAT])
        chip.pin(2).setHigh()
        assertEquals(0x05, c.registers()[REG_OLAT])
        chip.pin(0).setLow()
        assertEquals(0x04, c.registers()[REG_OLAT])
        chip.pin(0).toggle()
        assertEquals(0x05, c.registers()[REG_OLAT])
        chip.pin(0).toggle()
        assertEquals(0x04, c.registers()[REG_OLAT])

        // OutputPin contract.
        chip.pin(3).set(true)
        assertEquals(0x0C, c.registers()[REG_OLAT])

        chip.configurePullup(0xF0)
        assertEquals(0xF0, c.registers()[REG_GPPU])
        chip.pin(0).setPullUp(true)
        assertEquals(0xF1, c.registers()[REG_GPPU])
        chip.configurePullup(0x00)
        chip.configurePolarity(0x0F)
        assertEquals(0x0F, c.registers()[REG_IPOL])
        chip.setDefaultValue(0x11)
        assertEquals(0x11, c.registers()[REG_DEFVAL])
        chip.enablePinInterrupt(0xF0)
        assertEquals(0xF0, c.registers()[REG_GPINTEN])

        chip.configureIntPin(openDrain = false, activeHigh = true)
        assertEquals(0x02, c.registers()[REG_IOCON])
        chip.configureIntPin(openDrain = true, activeHigh = true)
        assertEquals(0x04, c.registers()[REG_IOCON])
        chip.configureIntPin()
        chip.setSequential(false)
        assertEquals(0x20, c.registers()[REG_IOCON])
        chip.setSequential(true)
        assertEquals(0x00, c.registers()[REG_IOCON])

        c.setRegister(REG_INTF, 0x10)
        c.setRegister(REG_INTCAP, 0xFF)
        assertEquals(0x10, chip.pollInterrupt())
        c.setRegister(REG_INTCAP, 0x22)
        assertEquals(0x22, chip.readCapture())

        chip.writeRegister(REG_IODIR, 0x0F)
        assertEquals(0x0F, chip.direction)
        chip.writeRegister(REG_OLAT, 0x81)
        assertEquals(0x81, chip.shadow)
        assertEquals(0x81, chip.readRegister(REG_OLAT))

        // Interrupt delivery by polling: INTF/INTCAP drive callback and pin watch.
        val wc = MockConnection()
        val wchip = Mcp23008Full(wc, 0x20)
        val seenStatus = AtomicInteger(-1)
        val watched = AtomicInteger()
        wchip.pin(4).watch({ watched.incrementAndGet() }, EdgeTrigger.FALLING)
        assertEquals(0x10, wc.registers()[REG_GPINTEN])
        wc.setRegister(REG_INTF, 0x10)
        wc.setRegister(REG_INTCAP, 0x00)
        wchip.onInterrupt({ seenStatus.set(it) })
        Thread.sleep(60)
        wchip.offInterrupt()
        assertEquals(0x10, seenStatus.get())
        assertTrue(watched.get() >= 1, "falling watch should fire")
        wchip.pin(4).unwatch()
        assertEquals(0x00, wc.registers()[REG_GPINTEN])

        // Error cases.
        val chip2 = Mcp23008Full(MockConnection(), 0x20)
        assertThrows(IllegalStateException::class.java) { chip2.onInterrupt({ }) }
        chip2.pin(0).setOutput()
        assertThrows(IllegalStateException::class.java) { chip2.pin(0).watch({ }) }

        chip.reset()
        assertEquals(0xFF, c.registers()[REG_IODIR])
        assertEquals(0x00, c.registers()[REG_OLAT])
        assertEquals(0x00, c.registers()[REG_GPPU])
        assertEquals(0x00, c.registers()[REG_GPINTEN])
        assertEquals(0x00, chip.shadow)
        assertEquals(0xFF, chip.direction)

        assertEquals(0, Mcp23008Minimal(MockConnection(), 0x20).readPort(0))
    }

    @Test
    fun spiFraming() {
        val c = SpiMock()
        val chip = Mcp23S08Full(c, 2)

        // Init: IOCON <- HAEN under the address-0 opcode, then OLAT <- 0 under the real opcode.
        assertArrayEquals(b(0x40, 0x05, 0x08), c.writes()[0])
        assertArrayEquals(b(0x44, 0x0A, 0x00), c.writes()[1])

        chip.writePort(0, 0x5A)
        assertArrayEquals(b(0x44, 0x0A, 0x5A), c.writes().last())

        c.setRegister(REG_GPIO, 0xC3)
        assertEquals(0xC3, chip.readPort(0))
        assertArrayEquals(b(0x45, 0x09), c.writes().last())

        chip.configurePullup(0x0F)
        assertArrayEquals(b(0x44, 0x06, 0x0F), c.writes().last())
        chip.configureIntPin(openDrain = true)
        assertArrayEquals(b(0x44, 0x05, 0x0C), c.writes().last())
        chip.reset()
        assertTrue(c.writes().any { it.contentEquals(b(0x44, 0x05, 0x08)) }, "reset must rewrite IOCON to HAEN")

        assertEquals(0, Mcp23S08Minimal(SpiMock(), 0).readPort(0))
    }
}
