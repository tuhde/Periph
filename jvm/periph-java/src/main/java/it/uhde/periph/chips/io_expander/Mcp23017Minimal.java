package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MCP23017 16-bit bidirectional I/O port expander — minimal interface.
 *
 * <p>Provides 16 GPIO pins split into two 8-bit ports (PORTA: GPA0–GPA7,
 * PORTB: GPB0–GPB7). Pins are accessed via the {@link #pin(int)} factory.
 * A 2-element shadow array tracks OLATA/OLATB for bit-level read-modify-write
 * without an I²C read.
 *
 * <p>At construction, all pins initialise as inputs except GPA7 and GPB7
 * which are output-only on the hardware and are forced to output mode.
 * OLATA and OLATB are cleared to {@code 0x00}.
 *
 * <p>IOCON.BANK is left at 0 (power-on default) throughout; do not alter it.
 *
 * <p>Address range: {@code 0x20}–{@code 0x27} (A2, A1, A0 select offset;
 * default {@code 0x20}).
 */
public class Mcp23017Minimal {

    protected final Transport transport;
    protected final int addr;

    /** Output latch shadow. shadow[0] = OLATA, shadow[1] = OLATB. */
    public final int[] shadow = new int[2];

    protected static final int REG_IODIRA = 0x00;
    protected static final int REG_IODIRB = 0x01;
    protected static final int REG_IPOLA  = 0x02;
    protected static final int REG_IPOLB  = 0x03;
    protected static final int REG_GPPUA  = 0x0C;
    protected static final int REG_GPPUB  = 0x0D;
    protected static final int REG_GPIOA  = 0x12;
    protected static final int REG_GPIOB  = 0x13;
    protected static final int REG_OLATA  = 0x14;
    protected static final int REG_OLATB  = 0x15;

    /**
     * Construct the driver and initialise all pins.
     *
     * <p>Clears OLATA and OLATB to {@code 0x00}, then sets IODIRA and IODIRB
     * to {@code 0x7F} (pins 0–6 as inputs, pins 7 as outputs). Pull-ups are
     * disabled.
     *
     * @param transport I²C transport bound to the device address
     * @param addr      7-bit I²C address ({@code 0x20}–{@code 0x27})
     * @throws IOException on I²C error during init
     */
    public Mcp23017Minimal(Transport transport, int addr) throws IOException {
        this.transport = transport;
        this.addr     = addr;
        writeReg(REG_OLATA,  0x00);
        writeReg(REG_OLATB,  0x00);
        writeReg(REG_IODIRA, 0x7F);
        writeReg(REG_IODIRB, 0x7F);
        writeReg(REG_IPOLA,  0x00);
        writeReg(REG_IPOLB,  0x00);
        writeReg(REG_GPPUA,  0x00);
        writeReg(REG_GPPUB,  0x00);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    protected void writeReg(int reg, int value) throws IOException {
        transport.write(new byte[]{ (byte) reg, (byte) (value & 0xFF) });
    }

    protected int readReg(int reg) throws IOException {
        byte[] buf = transport.writeRead(new byte[]{ (byte) reg }, 1);
        return buf[0] & 0xFF;
    }

    // -------------------------------------------------------------------------
    // Public driver API
    // -------------------------------------------------------------------------

    /**
     * Read all 8 pins of a port as a bitmask.
     *
     * @param port 0 = PORTA (GPIOA), 1 = PORTB (GPIOB)
     * @return 8-bit bitmask (bit 0 = pin 0 of the port)
     * @throws IOException on I²C error
     */
    public int readPort(int port) throws IOException {
        return readReg(REG_GPIOA + port);
    }

    /**
     * Write all 8 output pins of a port via the output latch.
     * Updates the internal shadow register.
     *
     * @param port 0 = PORTA (OLATA), 1 = PORTB (OLATB)
     * @param mask 8-bit output mask; bit n = 1 drives pin n high
     * @throws IOException on I²C error
     */
    public void writePort(int port, int mask) throws IOException {
        shadow[port] = mask & 0xFF;
        writeReg(REG_OLATA + port, shadow[port]);
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
    public Pin pin(int n) {
        return new Pin(this, n);
    }

    // -------------------------------------------------------------------------
    // Internal pin operations
    // -------------------------------------------------------------------------

    /* package */ void setPin(int n, boolean high) throws IOException {
        int port = n >> 3;
        int bit  = n & 7;
        if (high) shadow[port] |=   (1 << bit);
        else      shadow[port] &= ~((1 << bit));
        writeReg(REG_OLATA + port, shadow[port]);
    }

    /* package */ int readPin(int n) throws IOException {
        int port = n >> 3;
        return (readPort(port) >> (n & 7)) & 1;
    }

    // =========================================================================
    // Pin — GPIO proxy for a single MCP23017 pin
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin.
     *
     * <p>Obtain via {@link Mcp23017Minimal#pin(int)}. Do not instantiate directly.
     *
     * <p>Setting direction as input or output updates the IODIR register for
     * that pin. GPA7 and GPB7 are output-only and cannot be configured as inputs.
     */
    public static class Pin {

        protected final Mcp23017Minimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent {@link Mcp23017Minimal} driver
         * @param n    pin index (0–15)
         */
        protected Pin(Mcp23017Minimal chip, int n) {
            this.chip = chip;
            this.n    = n;
        }

        /**
         * Set this pin as an input (IODIR bit = 1).
         *
         * <p>GPA7 and GPB7 ignore this call — they are always outputs.
         *
         * @throws IOException on I²C error
         */
        public void setInput() throws IOException {
            int port = n >> 3;
            int bit  = n & 7;
            int reg  = REG_IODIRA + port;
            int cur  = chip.readReg(reg);
            chip.writeReg(reg, cur | (1 << bit));
        }

        /**
         * Set this pin as an output (IODIR bit = 0).
         *
         * @throws IOException on I²C error
         */
        public void setOutput() throws IOException {
            int port = n >> 3;
            int bit  = n & 7;
            int reg  = REG_IODIRA + port;
            int cur  = chip.readReg(reg);
            chip.writeReg(reg, cur & ~(1 << bit));
        }

        /**
         * Drive the pin high.
         *
         * @throws IOException on I²C error
         */
        public void setHigh() throws IOException {
            chip.setPin(n, true);
        }

        /**
         * Drive the pin low.
         *
         * @throws IOException on I²C error
         */
        public void setLow() throws IOException {
            chip.setPin(n, false);
        }

        /**
         * Read the actual logic level at the pin.
         *
         * <p>Performs a bus read of GPIOA or GPIOB; the result reflects the
         * actual external signal, not the shadow register.
         *
         * @return {@code true} if the pin is high; {@code false} if low
         * @throws IOException on I²C error
         */
        public boolean read() throws IOException {
            return chip.readPin(n) == 1;
        }

        /**
         * Invert the output latch bit for this pin.
         *
         * @throws IOException on I²C error
         */
        public void toggle() throws IOException {
            int port = n >> 3;
            int bit  = n & 7;
            boolean current = ((chip.shadow[port] >> bit) & 1) == 1;
            chip.setPin(n, !current);
        }
    }
}