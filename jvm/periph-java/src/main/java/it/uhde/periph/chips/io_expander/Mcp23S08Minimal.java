package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MCP23S08 8-bit I/O port expander (SPI) — minimal interface.
 *
 * <p>Identical GPIO behaviour to {@link Mcp23008Minimal}. Every transaction is
 * {@code [0x40 | (addr << 1) | R/W] [reg] [data…]}; the driver builds the two
 * header bytes itself and talks to a plain {@link Connection}. IOCON.HAEN is
 * set during init so up to four devices can share one chip select.
 */
public class Mcp23S08Minimal extends Mcp23x08Minimal {

    /**
     * Construct the driver (SPI mode 0, MSB first, CS active low).
     *
     * @param connection SPI connection
     * @param addr       hardware device address 0–3 (A1:A0 pins)
     * @throws IOException on SPI error during init
     */
    public Mcp23S08Minimal(Connection connection, int addr) throws IOException {
        super(new SpiBus(connection, addr), connection);
    }
}
