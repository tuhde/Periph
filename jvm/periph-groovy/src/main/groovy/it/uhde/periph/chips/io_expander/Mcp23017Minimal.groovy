package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * MCP23017 16-bit bidirectional I/O port expander — minimal interface.
 *
 * Provides 16 GPIO pins split into two 8-bit ports (PORTA: GPA0–GPA7,
 * PORTB: GPB0–GPB7). Pins are accessed via the {@link #pin(int)} factory.
 * A 2-element shadow array tracks OLATA/OLATB for bit-level read-modify-write
 * without an I²C read.
 *
 * At construction, all pins initialise as inputs except GPA7 and GPB7
 * which are output-only on the hardware and are forced to output mode.
 * OLATA and OLATB are cleared to {@code 0x00}.
 *
 * <p>IOCON.BANK is left at 0 (power-on default) throughout.
 *
 * <p>Address range: {@code 0x20}–{@code 0x27} (A2, A1, A0 select offset;
 * default {@code 0x20}).
 */
@CompileStatic
class Mcp23017Minimal {

    protected final Transport transport
    protected final int addr

    /** Output latch shadow. shadow[0] = OLATA, shadow[1] = OLATB. */
    protected final int[] shadow = new int[2]

    protected static final int REG_IODIRA = 0x00
    protected static final int REG_IODIRB = 0x01
    protected static final int REG_GPIOA  = 0x12
    protected static final int REG_GPIOB  = 0x13
    protected static final int REG_OLATA  = 0x14
    protected static final int REG_OLATB  = 0x15

    /**
     * Construct the driver and initialise all pins.
     *
     * Clears OLATA and OLATB to {@code 0x00}, then sets IODIRA and IODIRB
     * to {@code 0x7F} (pins 0–6 as inputs, pins 7 as outputs). Pull-ups are
     * disabled.
     *
     * @param transport I²C transport bound to the device address
     * @param addr      7-bit I²C address ({@code 0x20}–{@code 0x27})
     */
    Mcp23017Minimal(Transport transport, int addr = 0x20) {
        this.transport = transport
        this.addr     = addr
        writeReg(REG_OLATA, 0x00)
        writeReg(REG_OLATB, 0x00)
        writeReg(REG_IODIRA, 0x7F)
        writeReg(REG_IODIRB, 0x7F)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void writeReg(int reg, int value) {
        transport.write([(byte) reg, (byte) (value & 0xFF)] as byte[])
    }

    private int readReg(int reg) {
        byte[] buf = transport.writeRead([(byte) reg] as byte[], 1)
        return buf[0] & 0xFF
    }

    // -------------------------------------------------------------------------
    // Public driver API
    // -------------------------------------------------------------------------

    /**
     * Read all 8 pins of a port as a bitmask.
     *
     * @param port 0 = PORTA (GPIOA), 1 = PORTB (GPIOB)
     * @return 8-bit bitmask (bit 0 = pin 0 of the port)
     */
    int readPort(int port) {
        return readReg(REG_GPIOA + port)
    }

    /**
     * Write all 8 output pins of a port via the output latch.
     * Updates the internal shadow register.
     *
     * @param port 0 = PORTA (OLATA), 1 = PORTB (OLATB)
     * @param mask 8-bit output mask; bit n = 1 drives pin n high
     */
    void writePort(int port, int mask) {
        shadow[port] = mask & 0xFF
        writeReg(REG_OLATA + port, shadow[port])
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–15).
     *
     * <p>Pins 0–7 map to PORTA (GPA0–GPA7); pins 8–15 map to PORTB (GPB0–GPB7).
     * GPA7 (pin 7) and GPB7 (pin 15) are output-only on the hardware.
     *
     * @param n pin index (0–15)
     * @return Pin proxy backed by this driver
     */
    Pin pin(int n) {
        return new Pin(this, n)
    }

    // -------------------------------------------------------------------------
    // Internal pin operations
    // -------------------------------------------------------------------------

    /*package*/ void setPin(int n, boolean high) {
        int port = n >> 3
        int bit  = n & 7
        if (high) shadow[port] |=   (1 << bit)
        else      shadow[port] &= ~((1 << bit))
        writeReg(REG_OLATA + port, shadow[port])
    }

    /*package*/ int readPin(int n) {
        int port = n >> 3
        return (readPort(port) >> (n & 7)) & 1
    }

    // =========================================================================
    // Pin — GPIO proxy for a single MCP23017 pin
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin.
     *
     * <p>Obtain via {@link Mcp23017Minimal#pin(int)}. Do not instantiate directly.
     */
    @CompileStatic
    static class Pin {

        protected final Mcp23017Minimal chip
        protected final int n

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent {@link Mcp23017Minimal} driver
         * @param n    pin index (0–15)
         */
        protected Pin(Mcp23017Minimal chip, int n) {
            this.chip = chip
            this.n    = n
        }

        /** Set this pin as an input (IODIR bit = 1). */
        void setInput() {
            int port = n >> 3
            int bit  = n & 7
            int reg  = REG_IODIRA + port
            int cur  = chip.readReg(reg)
            chip.writeReg(reg, cur | (1 << bit))
        }

        /** Set this pin as an output (IODIR bit = 0). */
        void setOutput() {
            int port = n >> 3
            int bit  = n & 7
            int reg  = REG_IODIRA + port
            int cur  = chip.readReg(reg)
            chip.writeReg(reg, cur & ~(1 << bit))
        }

        /** Drive the pin high. */
        void setHigh() { chip.setPin(n, true) }

        /** Drive the pin low. */
        void setLow()  { chip.setPin(n, false) }

        /**
         * Read the actual logic level at the pin.
         *
         * @return {@code true} if the pin is high; {@code false} if low
         */
        boolean read() { chip.readPin(n) == 1 }

        /** Invert the output latch bit for this pin. */
        void toggle() {
            int port = n >> 3
            int bit  = n & 7
            boolean current = ((chip.shadow[port] >> bit) & 1) == 1
            chip.setPin(n, !current)
        }
    }
}