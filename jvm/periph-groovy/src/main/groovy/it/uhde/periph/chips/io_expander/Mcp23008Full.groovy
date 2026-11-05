package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * MCP23008 full interface (I²C) — pull-ups, polarity, interrupt-on-change and
 * raw register access on top of the minimal GPIO API. See {@link Mcp23x08Full}.
 */
@CompileStatic
class Mcp23008Full extends Mcp23x08Full {

    /**
     * Construct the Full driver.
     *
     * @param connection I²C register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x20}–{@code 0x27})
     */
    Mcp23008Full(RegisterConnection connection, int addr = 0x20) {
        super(new Mcp23x08Minimal.I2cBus(connection), connection)
    }
}
