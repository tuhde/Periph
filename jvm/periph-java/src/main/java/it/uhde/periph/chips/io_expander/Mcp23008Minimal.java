package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * MCP23008 8-bit I/O port expander (I²C) — minimal interface.
 *
 * <p>Exposes the eight bidirectional pins GP0–GP7 via {@link #pin(int)}.
 * Address range {@code 0x20}–{@code 0x27} (A2:A0 added to {@code 0x20}).
 */
public class Mcp23008Minimal extends Mcp23x08Minimal {

    /**
     * Construct the driver. Clears OLAT; IODIR stays at its reset value
     * {@code 0xFF} (all inputs).
     *
     * @param connection I²C register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x20}–{@code 0x27})
     * @throws IOException on I²C error during init
     */
    public Mcp23008Minimal(RegisterConnection connection, int addr) throws IOException {
        super(new I2cBus(connection), connection);
    }
}
