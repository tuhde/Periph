package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PCA9539 16-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * <p>Exposes the sixteen bidirectional pins (0–7 = P00–P07, 8–15 = P10–P17)
 * via {@link #pin(int)}. CONFIG bit = 1 means input, 0 means output. The chip
 * has no internal pull-ups. The registers form pairs (port 0 / port 1): a
 * two-byte transfer starting at a port-0 register covers both ports.
 * 16-bit shadows of OUTPUT and CONFIG make a single-pin change one register write; a
 * pin's OUTPUT bit is written before its CONFIG bit is cleared so the pin never
 * glitches to a stale level. Address {@code 0x74} (A1 = A0 = GND) to {@code 0x77}
 * (A1 = A0 = VCC).
 */
public class PCA9539Minimal {

    // Port-0 register addresses; port 1 is +1. A two-byte transfer at a port-0
    // register toggles to port 1 (register-pair layout).
    protected static final int REG_INPUT    = 0x00;
    protected static final int REG_OUTPUT   = 0x02;
    protected static final int REG_POLARITY = 0x04;
    protected static final int REG_CONFIG   = 0x06;

    protected final RegisterConnection connection;

    /** Shadow of OUTPUT0/1 (16 bits). */
    public int shadow = 0xFFFF;
    /** Shadow of CONFIG0/1 (1 = input, 16 bits). */
    public int direction = 0xFFFF;
    /** Shadow of POLARITY0/1. */
    protected int polarity = 0x0000;
    /** Last INPUT0/1 value read (interrupt reference). */
    protected int lastInput = 0x0000;

    /**
     * Construct the driver and run the initialisation sequence: write the reset
     * state (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF on both ports: all inputs), then read
     * INPUT0/1 to initialise the cached input value.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x74}–{@code 0x77}); informational
     * @throws IOException on bus error during init
     */
    public PCA9539Minimal(RegisterConnection connection, int addr) throws IOException {
        this.connection = connection;
        write16(REG_OUTPUT, 0xFFFF);
        write16(REG_POLARITY, 0x0000);
        write16(REG_CONFIG, 0xFFFF);
        lastInput = read16(REG_INPUT);
    }

    /**
     * Construct the driver at the default address {@code 0x74}.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @throws IOException on bus error during init
     */
    public PCA9539Minimal(RegisterConnection connection) throws IOException {
        this(connection, 0x74);
    }

    /* package */ int readReg(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    /* package */ void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    // One two-byte read at the port-0 register returns port 0 then port 1.
    /* package */ int read16(int base) throws IOException {
        byte[] b = connection.read(base, 2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    /* package */ void write16(int base, int value) throws IOException {
        connection.write(base, new byte[]{(byte) value, (byte) (value >> 8)});
    }

    /**
     * Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @return 8-bit bitmask (bit 0 = first pin of the port)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    public int readPort(int port) throws IOException {
        if (port != 0 && port != 1) throw new IllegalArgumentException("port must be 0 or 1");
        return readReg(REG_INPUT + port);
    }

    /**
     * Write all 8 output bits of one port via OUTPUTx and update the shadow. Bits
     * of input pins are stored but have no effect until the pin becomes an output.
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @param mask 8-bit output mask; bit n = 1 drives the port's pin n high
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    public void writePort(int port, int mask) throws IOException {
        if (port != 0 && port != 1) throw new IllegalArgumentException("port must be 0 or 1");
        mask &= 0xFF;
        shadow = (shadow & ~(0xFF << (8 * port))) | (mask << (8 * port));
        writeReg(REG_OUTPUT + port, mask);
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–15).
     *
     * @param n pin index (0–7 = port 0, 8–15 = port 1)
     * @return Pin proxy backed by this driver
     */
    public Pin pin(int n) {
        return new Pin(this, n & 15);
    }

    /* package */ void setPin(int n, boolean high) throws IOException {
        if (high) shadow |=  (1 << n);
        else      shadow &= ~(1 << n);
        int port = n >> 3;
        writeReg(REG_OUTPUT + port, shadow >> (8 * port));
    }

    /* package */ void setDirection(int n, boolean input) throws IOException {
        int port = n >> 3;
        if (input) {
            direction |= (1 << n);
        } else {
            writeReg(REG_OUTPUT + port, shadow >> (8 * port));   // latch the output level first
            direction &= ~(1 << n);
        }
        writeReg(REG_CONFIG + port, direction >> (8 * port));
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link PCA9539Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    public static class Pin implements OutputPin {

        protected final PCA9539Minimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–15)
         */
        protected Pin(PCA9539Minimal chip, int n) {
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
            return ((chip.readPort(n >> 3) >> (n & 7)) & 1) == 1;
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
