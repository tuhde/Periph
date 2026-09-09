package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Mcp23017Test {

    // Mcp23017Full's interrupt-related register constants are private to
    // that class, so this test mirrors their values locally.
    companion object {
        private const val REG_DEFVALA = 0x06
        private const val REG_INTFA = 0x0E
        private const val REG_INTCAPA = 0x10
        private const val REG_INTCAPB = 0x11
    }

    // MCP23017 reads are register-addressed (writeRead), so MockConnection's
    // registers map can be preloaded directly via setRegister().
    @Test
    fun fullApi() {
        val connection = MockConnection()
        val chip = Mcp23017Full(connection, 0x20)

        // Init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7
        // forced output-only), IPOLA/IPOLB=0x00, GPPUA/GPPUB=0x00.
        assertEquals(0x00, connection.registers()[Mcp23017Minimal.REG_OLATA])
        assertEquals(0x00, connection.registers()[Mcp23017Minimal.REG_OLATB])
        assertEquals(0x7F, connection.registers()[Mcp23017Minimal.REG_IODIRA])
        assertEquals(0x7F, connection.registers()[Mcp23017Minimal.REG_IODIRB])
        assertEquals(0x00, connection.registers()[Mcp23017Minimal.REG_IPOLA])
        assertEquals(0x00, connection.registers()[Mcp23017Minimal.REG_GPPUA])

        // readPort(0)/(1) -> GPIOA/GPIOB.
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0xA5)
        assertEquals(0xA5, chip.readPort(0))
        connection.setRegister(Mcp23017Minimal.REG_GPIOB, 0x5A)
        assertEquals(0x5A, chip.readPort(1))

        // writePort updates OLAT register and shadow.
        chip.writePort(0, 0x3C)
        assertEquals(0x3C, connection.registers()[Mcp23017Minimal.REG_OLATA])
        assertEquals(0x3C, chip.shadow[0])

        // pin() read on PORTA and PORTB.
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x01)
        val pin0 = chip.pin(0)
        assertTrue(pin0.read())

        connection.setRegister(Mcp23017Minimal.REG_GPIOB, 0x02)
        val pin9 = chip.pin(9)
        assertTrue(pin9.read())

        // Pin direction: setOutput() clears the IODIRA bit; setInput() sets it.
        val pin1 = chip.pin(1)
        pin1.setOutput()
        assertEquals(0x7F and 0x02.inv(), connection.registers()[Mcp23017Minimal.REG_IODIRA])
        pin1.setInput()
        assertEquals(0x7F, connection.registers()[Mcp23017Minimal.REG_IODIRA])

        // Pin set high/low preserves other output bits (shadow read-modify-write).
        chip.writePort(0, 0x00)
        pin0.setHigh()
        assertEquals(0x01, connection.registers()[Mcp23017Minimal.REG_OLATA])
        val pin2 = chip.pin(2)
        pin2.setHigh()
        assertEquals(0x05, connection.registers()[Mcp23017Minimal.REG_OLATA])
        pin0.setLow()
        assertEquals(0x04, connection.registers()[Mcp23017Minimal.REG_OLATA])

        // Toggle: reads GPIOA (the actual pin level, which on real hardware
        // matches OLAT for an output pin) — keep the mock's GPIOA in sync.
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x04)
        pin0.toggle()
        assertEquals(0x05, connection.registers()[Mcp23017Minimal.REG_OLATA])
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x05)
        pin0.toggle()
        assertEquals(0x04, connection.registers()[Mcp23017Minimal.REG_OLATA])

        // Full: configurePullup / configurePolarity / setDefaultValue.
        chip.configurePullup(0, 0xFF)
        assertEquals(0xFF, connection.registers()[Mcp23017Minimal.REG_GPPUA])
        chip.configurePolarity(1, 0x0F)
        assertEquals(0x0F, connection.registers()[Mcp23017Minimal.REG_IPOLB])
        chip.setDefaultValue(0, 0x11)
        assertEquals(0x11, connection.registers()[REG_DEFVALA])

        // pollInterrupt(port): reads INTF then INTCAP (discarded); returns INTF value.
        connection.setRegister(REG_INTFA, 0x08)
        connection.setRegister(REG_INTCAPA, 0xFF)
        assertEquals(0x08, chip.pollInterrupt(0))

        // readCapture(port): reads INTCAP directly.
        connection.setRegister(REG_INTCAPB, 0x22)
        assertEquals(0x22, chip.readCapture(1))
    }
}
