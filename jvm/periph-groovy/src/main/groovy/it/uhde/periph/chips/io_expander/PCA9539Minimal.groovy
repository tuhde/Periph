package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

/**
 * PCA9539 16-bit I/O expander (I²C / SMBus) — minimal interface.
 *
 * <p>Exposes the sixteen bidirectional pins (0–7 = P00–P07, 8–15 = P10–P17)
 * via {@link #pin(int)}. CONFIG bit = 1 means input, 0 means output. The chip
 * has no internal pull-ups. The registers form pairs (port 0 / port 1): a
 * two-byte transfer starting at a port-0 register covers both ports. 16-bit
 * shadows of OUTPUT and CONFIG make a single-pin change one register write; a
 * pin's OUTPUT bit is written before its CONFIG bit is cleared so the pin never
 * glitches to a stale level. Address {@code 0x74} (A1 = A0 = GND) to {@code 0x77}
 * (A1 = A0 = VCC).
 */
@CompileStatic
class PCA9539Minimal {

    // Port-0 register addresses; port 1 is +1. A two-byte transfer at a port-0
    // register toggles to port 1 (register-pair layout).
    protected static final int REG_INPUT    = 0x00
    protected static final int REG_OUTPUT   = 0x02
    protected static final int REG_POLARITY = 0x04
    protected static final int REG_CONFIG   = 0x06

    protected final RegisterConnection connection

    /** Shadow of OUTPUT0/1 (16 bits). */
    public int shadow = 0xFFFF
    /** Shadow of CONFIG0/1 (1 = input, 16 bits). */
    public int direction = 0xFFFF
    /** Shadow of POLARITY0/1. */
    protected int polarity = 0x0000
    /** Last INPUT0/1 value read (interrupt reference). */
    protected int lastInput = 0x0000

    /**
     * Construct the driver and run the initialisation sequence: write the reset
     * state (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF on both ports: all inputs), then read
     * INPUT0/1 to initialise the cached input value.
     *
     * @param connection I²C or SMBus (no PEC) register connection bound to the device address
     * @param addr       7-bit I²C address ({@code 0x74}–{@code 0x77}); informational
     */
    PCA9539Minimal(RegisterConnection connection, int addr = 0x74) {
        this.connection = connection
        write16(REG_OUTPUT, 0xFFFF)
        write16(REG_POLARITY, 0x0000)
        write16(REG_CONFIG, 0xFFFF)
        this.@lastInput = read16(REG_INPUT)
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    // One two-byte read at the port-0 register returns port 0 then port 1.
    protected int read16(int base) {
        byte[] b = connection.read(base, 2)
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8)
    }

    protected void write16(int base, int value) {
        connection.write(base, [(byte) value, (byte) (value >> 8)] as byte[])
    }

    /**
     * Read one 8-pin port as a bitmask (INPUTx register, actual pin levels).
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @return 8-bit bitmask (bit 0 = first pin of the port)
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    int readPort(int port = 0) {
        if (port != 0 && port != 1) throw new IllegalArgumentException('port must be 0 or 1')
        return readReg(REG_INPUT + port)
    }

    /**
     * Write all 8 output bits of one port via OUTPUTx and update the shadow. Bits
     * of input pins are stored but have no effect until the pin becomes an output.
     *
     * @param port 0 (P00–P07) or 1 (P10–P17)
     * @param mask 8-bit output mask; bit n = 1 drives the port's pin n high
     * @throws IllegalArgumentException if port is not 0 or 1
     */
    void writePort(int port, int mask) {
        if (port != 0 && port != 1) throw new IllegalArgumentException('port must be 0 or 1')
        mask &= 0xFF
        this.@shadow = (this.@shadow & ~(0xFF << (8 * port))) | (mask << (8 * port))
        writeReg(REG_OUTPUT + port, mask)
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–15).
     *
     * @param n pin index (0–7 = port 0, 8–15 = port 1)
     * @return Pin proxy backed by this driver
     */
    Pin pin(int n) {
        return new Pin(this, n & 15)
    }

    protected void setPin(int n, boolean high) {
        if (high) this.@shadow |= (1 << n)
        else      this.@shadow &= ~(1 << n)
        int port = n >> 3
        writeReg(REG_OUTPUT + port, this.@shadow >> (8 * port))
    }

    protected void setPinDirection(int n, boolean input) {
        int port = n >> 3
        if (input) {
            this.@direction |= (1 << n)
        } else {
            writeReg(REG_OUTPUT + port, this.@shadow >> (8 * port))   // latch the output level first
            this.@direction &= ~(1 << n)
        }
        writeReg(REG_CONFIG + port, this.@direction >> (8 * port))
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link PCA9539Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    @CompileStatic
    static class Pin implements OutputPin {

        protected final PCA9539Minimal chip
        protected final int n

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–15)
         */
        protected Pin(PCA9539Minimal chip, int n) {
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
            return ((chip.readPort(n >> 3) >> (n & 7)) & 1) == 1
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
