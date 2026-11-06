package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * MCP23S08 full interface (SPI) — pull-ups, polarity, interrupt-on-change and
 * raw register access on top of the minimal GPIO API. See {@link Mcp23x08Full}.
 */
@CompileStatic
class Mcp23S08Full extends Mcp23x08Full {

    /**
     * Construct the Full driver (SPI mode 0, MSB first, CS active low).
     *
     * @param connection SPI connection
     * @param addr       hardware device address 0–3 (A1:A0 pins)
     */
    Mcp23S08Full(Connection connection, int addr = 0) {
        super(new Mcp23x08Minimal.SpiBus(connection, addr), connection)
    }
}
