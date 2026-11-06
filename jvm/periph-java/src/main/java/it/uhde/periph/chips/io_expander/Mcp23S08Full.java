package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MCP23S08 full interface (SPI) — pull-ups, polarity, interrupt-on-change and
 * raw register access on top of the minimal GPIO API. See {@link Mcp23x08Full}.
 */
public class Mcp23S08Full extends Mcp23x08Full {

    /**
     * Construct the Full driver (SPI mode 0, MSB first, CS active low).
     *
     * @param connection SPI connection
     * @param addr       hardware device address 0–3 (A1:A0 pins)
     * @throws IOException on SPI error during init
     */
    public Mcp23S08Full(Connection connection, int addr) throws IOException {
        super(new SpiBus(connection, addr), connection);
    }
}
