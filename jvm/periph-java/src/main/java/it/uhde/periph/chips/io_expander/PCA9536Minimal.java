package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.io.UncheckedIOException;

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
public class PCA9536Minimal {

    protected static final int REG_INPUT    = 0x00;
    protected static final int REG_OUTPUT   = 0x01;
    protected static final int REG_POLARITY = 0x02;
    protected static final int REG_CONFIG   = 0x03;
    /** Bits 7:4 are unused. */
    protected static final int MASK         = 0x0F;

    /** Fixed 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x41;

    protected final RegisterConnection connection;

    /** Shadow of OUTPUT. */
    public int shadow = 0xFF;
    /** Shadow of CONFIG (1 = input). */
    public int direction = 0xFF;
    /** Shadow of POLARITY. */
    protected int polarity = 0x00;
    /** Last INPUT value read (change-detection reference). */
    protected int lastInput = 0x00;

    /**
     * Construct the driver and run the initialisation sequence: write the reset
     * state (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF: all inputs), then read INPUT
     * to initialise the cached input value.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address (fixed {@code 0x41}); informational
     * @throws IOException on bus error during init
     */
    public PCA9536Minimal(RegisterConnection connection, int addr) throws IOException {
        this.connection = connection;
        writeReg(REG_OUTPUT, 0xFF);
        writeReg(REG_POLARITY, 0x00);
        writeReg(REG_CONFIG, 0xFF);
        lastInput = readReg(REG_INPUT) & MASK;
    }

    /**
     * Construct the driver at the fixed address {@code 0x41}.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @throws IOException on bus error during init
     */
    public PCA9536Minimal(RegisterConnection connection) throws IOException {
        this(connection, DEFAULT_ADDRESS);
    }

    /* package */ int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    /* package */ void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read all 4 pins as a bitmask (INPUT register, actual pin levels, bits 7:4 masked off).
     *
     * @param port ignored; the chip has a single port
     * @return 4-bit bitmask (bit 0 = P0)
     * @throws IOException on bus error
     */
    public int readPort(int port) throws IOException {
        return readReg(REG_INPUT) & MASK;
    }

    /**
     * Read all 4 pins as a bitmask.
     *
     * @return 4-bit bitmask (bit 0 = P0)
     * @throws IOException on bus error
     */
    public int readPort() throws IOException {
        return readPort(0);
    }

    /**
     * Write all 4 output bits via OUTPUT (bits 7:4 are written as 1) and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 4-bit output mask; bit n = 1 drives Pn high
     * @throws IOException on bus error
     */
    public void writePort(int port, int mask) throws IOException {
        shadow = (mask & MASK) | 0xF0;
        writeReg(REG_OUTPUT, shadow);
    }

    /**
     * Write all 4 output bits via OUTPUT.
     *
     * @param mask 4-bit output mask
     * @throws IOException on bus error
     */
    public void writePort(int mask) throws IOException {
        writePort(0, mask);
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–3).
     *
     * @param n pin index (0–3)
     * @return Pin proxy backed by this driver
     */
    public Pin pin(int n) {
        return new Pin(this, n & 3);
    }

    /* package */ void setPin(int n, boolean high) throws IOException {
        if (high) shadow |=  (1 << n);
        else      shadow &= ~(1 << n);
        writeReg(REG_OUTPUT, shadow);
    }

    /* package */ void setDirection(int n, boolean input) throws IOException {
        if (input) {
            direction |= (1 << n);
        } else {
            writeReg(REG_OUTPUT, shadow);   // latch the output level first
            direction &= ~(1 << n);
        }
        writeReg(REG_CONFIG, direction);
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link PCA9536Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    public static class Pin implements OutputPin {

        protected final PCA9536Minimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–3)
         */
        protected Pin(PCA9536Minimal chip, int n) {
            this.chip = chip;
            this.n = n;
        }

        /**
         * Set this pin as an input (CONFIG bit = 1).
         *
         * @throws IOException on bus error
         */
        public void setInput() throws IOException {
            chip.setDirection(n, true);
        }

        /**
         * Set this pin as an output (OUTPUT bit written first, then CONFIG bit = 0).
         *
         * @throws IOException on bus error
         */
        public void setOutput() throws IOException {
            chip.setDirection(n, false);
        }

        /**
         * Drive the pin high.
         *
         * @throws IOException on bus error
         */
        public void setHigh() throws IOException {
            chip.setPin(n, true);
        }

        /**
         * Drive the pin low.
         *
         * @throws IOException on bus error
         */
        public void setLow() throws IOException {
            chip.setPin(n, false);
        }

        /**
         * Read the actual logic level at the pin (a bus read of INPUT).
         *
         * @return {@code true} if the pin is high
         * @throws IOException on bus error
         */
        public boolean read() throws IOException {
            return ((chip.readPort() >> n) & 1) == 1;
        }

        /**
         * Invert the output latch bit for this pin.
         *
         * @throws IOException on bus error
         */
        public void toggle() throws IOException {
            chip.setPin(n, ((chip.shadow >> n) & 1) == 0);
        }

        /**
         * {@link OutputPin} contract: drive the pin high or low.
         *
         * @param high {@code true} to drive high
         */
        @Override
        public void set(boolean high) {
            try {
                chip.setPin(n, high);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** No-op; the shadow state is preserved. */
        @Override
        public void close() { }
    }
}
