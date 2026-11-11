package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PCA6408A 8-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * <p>Exposes the eight bidirectional pins P0–P7 via {@link #pin(int)}. CONFIG
 * bit = 1 means input, 0 means output. The chip has no internal pull-ups and no
 * register auto-increment: every register is accessed one byte at a time.
 * Shadows of OUTPUT and CONFIG make a single-pin change one register write; a
 * pin's OUTPUT bit is written before its CONFIG bit is cleared so the pin never
 * glitches to a stale level. Address {@code 0x20} (ADDR = VSS) or {@code 0x21}
 * (ADDR = VDD(P)).
 */
public class PCA6408AMinimal {

    protected static final int REG_INPUT    = 0x00;
    protected static final int REG_OUTPUT   = 0x01;
    protected static final int REG_POLARITY = 0x02;
    protected static final int REG_CONFIG   = 0x03;

    protected final RegisterConnection connection;

    /** Shadow of OUTPUT. */
    public int shadow = 0xFF;
    /** Shadow of CONFIG (1 = input). */
    public int direction = 0xFF;
    /** Shadow of POLARITY. */
    protected int polarity = 0x00;
    /** Last INPUT value read (interrupt reference). */
    protected int lastInput = 0x00;

    /**
     * Construct the driver and run the initialisation sequence: write the reset
     * state (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF: all inputs), then read INPUT
     * to initialise the cached input value.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x20} or {@code 0x21}); informational
     * @throws IOException on bus error during init
     */
    public PCA6408AMinimal(RegisterConnection connection, int addr) throws IOException {
        this.connection = connection;
        writeReg(REG_OUTPUT, 0xFF);
        writeReg(REG_POLARITY, 0x00);
        writeReg(REG_CONFIG, 0xFF);
        lastInput = readReg(REG_INPUT);
    }

    /**
     * Construct the driver at the default address {@code 0x20}.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @throws IOException on bus error during init
     */
    public PCA6408AMinimal(RegisterConnection connection) throws IOException {
        this(connection, 0x20);
    }

    /* package */ int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    /* package */ void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    /**
     * Read all 8 pins as a bitmask (INPUT register, actual pin levels).
     *
     * @param port ignored; the chip has a single port
     * @return 8-bit bitmask (bit 0 = P0)
     * @throws IOException on bus error
     */
    public int readPort(int port) throws IOException {
        return readReg(REG_INPUT);
    }

    /**
     * Read all 8 pins as a bitmask.
     *
     * @return 8-bit bitmask (bit 0 = P0)
     * @throws IOException on bus error
     */
    public int readPort() throws IOException {
        return readPort(0);
    }

    /**
     * Write all 8 output bits via OUTPUT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 8-bit output mask; bit n = 1 drives Pn high
     * @throws IOException on bus error
     */
    public void writePort(int port, int mask) throws IOException {
        shadow = mask & 0xFF;
        writeReg(REG_OUTPUT, shadow);
    }

    /**
     * Write all 8 output bits via OUTPUT.
     *
     * @param mask 8-bit output mask
     * @throws IOException on bus error
     */
    public void writePort(int mask) throws IOException {
        writePort(0, mask);
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy backed by this driver
     */
    public Pin pin(int n) {
        return new Pin(this, n & 7);
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
     * GPIO proxy for a single pin. Obtain via {@link PCA6408AMinimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    public static class Pin implements OutputPin {

        protected final PCA6408AMinimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–7)
         */
        protected Pin(PCA6408AMinimal chip, int n) {
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
