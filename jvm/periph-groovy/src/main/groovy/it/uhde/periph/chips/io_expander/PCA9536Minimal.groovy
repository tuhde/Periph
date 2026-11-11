package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9536 4-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * <p>Exposes the four bidirectional pins P0–P3 via {@link #pin(int)}. CONFIG
 * bit = 1 means input, 0 means output. Every I/O pin has a weak (about 100 kΩ)
 * pull-up that is always on and cannot be switched (an undriven input reads 1);
 * the chip has no register auto-increment: every register is accessed one byte
 * at a time. Bits 7:4 of every register are unused.
 * Shadows of OUTPUT and CONFIG make a single-pin change one register write; a
 * pin's OUTPUT bit is written before its CONFIG bit is cleared so the pin never
 * glitches to a stale level. The address is fixed at {@code 0x41}; there is no
 * interrupt, reset or address pin.
 */
@CompileStatic
class PCA9536Minimal {

    protected static final int REG_INPUT    = 0x00
    protected static final int REG_OUTPUT   = 0x01
    protected static final int REG_POLARITY = 0x02
    protected static final int REG_CONFIG   = 0x03
    /** Bits 7:4 are unused. */
    protected static final int MASK         = 0x0F

    /** Fixed 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x41

    protected final RegisterConnection connection

    /** Shadow of OUTPUT. */
    public int shadow = 0xFF
    /** Shadow of CONFIG (1 = input). */
    public int direction = 0xFF
    /** Shadow of POLARITY. */
    protected int polarity = 0x00
    /** Last INPUT value read (change-detection reference). */
    protected int lastInput = 0x00

    /**
     * Construct the driver and run the initialisation sequence: write the reset
     * state (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF: all inputs), then read INPUT
     * to initialise the cached input value.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address (fixed {@code 0x41}); informational
     */
    PCA9536Minimal(RegisterConnection connection, int addr = 0x41) {
        this.connection = connection
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        this.@lastInput = readReg(REG_INPUT) & MASK
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    /**
     * Read all 4 pins as a bitmask (INPUT register, actual pin levels, bits 7:4 masked off).
     *
     * @param port ignored; the chip has a single port
     * @return 4-bit bitmask (bit 0 = P0)
     */
    int readPort(int port = 0) {
        return readReg(REG_INPUT) & MASK
    }

    /**
     * Write all 4 output bits via OUTPUT (bits 7:4 are written as 1) and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 4-bit output mask; bit n = 1 drives Pn high
     */
    void writePort(int port, int mask) {
        this.@shadow = (mask & MASK) | 0xF0
        writeReg(REG_OUTPUT, this.@shadow)
    }

    /**
     * Write all 4 output bits via OUTPUT.
     *
     * @param mask 4-bit output mask
     */
    void writePort(int mask) {
        writePort(0, mask)
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–3).
     *
     * @param n pin index (0–3)
     * @return Pin proxy backed by this driver
     */
    Pin pin(int n) {
        return new Pin(this, n & 3)
    }

    protected void setPin(int n, boolean high) {
        if (high) this.@shadow |= (1 << n)
        else      this.@shadow &= ~(1 << n)
        writeReg(REG_OUTPUT, this.@shadow)
    }

    protected void setPinDirection(int n, boolean input) {
        if (input) {
            this.@direction |= (1 << n)
        } else {
            writeReg(REG_OUTPUT, this.@shadow)   // latch the output level first
            this.@direction &= ~(1 << n)
        }
        writeReg(REG_CONFIG, this.@direction)
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link PCA9536Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    @CompileStatic
    static class Pin implements OutputPin {

        protected final PCA9536Minimal chip
        protected final int n

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–3)
         */
        protected Pin(PCA9536Minimal chip, int n) {
            this.chip = chip
            this.n = n
        }

        /** Set this pin as an input (CONFIG bit = 1). */
        void setInput() { chip.setPinDirection(n, true) }

        /** Set this pin as an output (OUTPUT bit written first, then CONFIG bit = 0). */
        void setOutput() { chip.setPinDirection(n, false) }

        /** Drive the pin high. */
        void setHigh() { chip.setPin(n, true) }

        /** Drive the pin low. */
        void setLow() { chip.setPin(n, false) }

        /**
         * Read the actual logic level at the pin (a bus read of INPUT).
         *
         * @return {@code true} if the pin is high
         */
        boolean read() {
            return ((chip.readPort() >> n) & 1) == 1
        }

        /** Invert the output latch bit for this pin. */
        void toggle() {
            chip.setPin(n, ((chip.@shadow >> n) & 1) == 0)
        }

        /**
         * {@link OutputPin} contract: drive the pin high or low.
         *
         * @param high {@code true} to drive high
         */
        @Override
        void set(boolean high) {
            chip.setPin(n, high)
        }

        /** No-op; the shadow state is preserved. */
        @Override
        void close() { }
    }
}
