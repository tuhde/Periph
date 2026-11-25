package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Shared implementation of the MCP23008 (I²C) and MCP23S08 (SPI) drivers —
 * minimal interface. Use {@link Mcp23008Minimal} or {@link Mcp23S08Minimal}.
 *
 * <p>Both chips have an identical register map and GPIO behaviour and differ
 * only in the serial interface, which is hidden behind {@link Bus}. All eight
 * pins (GP0–GP7) are bidirectional. Shadows of OLAT and IODIR make a
 * single-pin change one register write; a pin's OLAT bit is written before its
 * IODIR bit is cleared so the pin never glitches to a stale level.
 */
public class Mcp23x08Minimal {

    /** Register-level access to the chip over its serial interface. */
    public interface Bus {
        /** Bring-up before the first regular access (SPI: set IOCON.HAEN). */
        default void init() throws IOException { }

        /** IOCON value the driver keeps after init and reset (0x00 on I²C, HAEN on SPI). */
        int baseIocon();

        /** Read one register. */
        int read(int reg) throws IOException;

        /** Write one register. */
        void write(int reg, int value) throws IOException;
    }

    protected static final int REG_IODIR   = 0x00;
    protected static final int REG_IPOL    = 0x01;
    protected static final int REG_GPINTEN = 0x02;
    protected static final int REG_DEFVAL  = 0x03;
    protected static final int REG_INTCON  = 0x04;
    protected static final int REG_IOCON   = 0x05;
    protected static final int REG_GPPU    = 0x06;
    protected static final int REG_INTF    = 0x07;
    protected static final int REG_INTCAP  = 0x08;
    protected static final int REG_GPIO    = 0x09;
    protected static final int REG_OLAT    = 0x0A;

    protected static final int IOCON_SEQOP  = 0x20;
    protected static final int IOCON_HAEN   = 0x08;
    protected static final int IOCON_ODR    = 0x04;
    protected static final int IOCON_INTPOL = 0x02;

    protected final Bus bus;
    protected final Connection connection;

    /** Shadow of OLAT. */
    public int shadow = 0x00;
    /** Shadow of IODIR (1 = input). */
    public int direction = 0xFF;

    /**
     * Construct the driver and run the initialisation sequence: bus bring-up,
     * then OLAT ← 0x00. IODIR stays at its reset value 0xFF (all inputs).
     *
     * @param bus        register access for the chip's serial interface
     * @param connection the underlying connection (for its INT pin)
     * @throws IOException on bus error during init
     */
    protected Mcp23x08Minimal(Bus bus, Connection connection) throws IOException {
        this.bus = bus;
        this.connection = connection;
        bus.init();
        bus.write(REG_OLAT, 0x00);
    }

    /**
     * Read all 8 pins as a bitmask (GPIO register, actual pin levels).
     *
     * @param port ignored; the chip has a single port
     * @return 8-bit bitmask (bit 0 = GP0)
     * @throws IOException on bus error
     */
    public int readPort(int port) throws IOException {
        return bus.read(REG_GPIO);
    }

    /**
     * Read all 8 pins as a bitmask.
     *
     * @return 8-bit bitmask (bit 0 = GP0)
     * @throws IOException on bus error
     */
    public int readPort() throws IOException {
        return readPort(0);
    }

    /**
     * Write all 8 output bits via OLAT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 8-bit output mask; bit n = 1 drives GPn high
     * @throws IOException on bus error
     */
    public void writePort(int port, int mask) throws IOException {
        shadow = mask & 0xFF;
        bus.write(REG_OLAT, shadow);
    }

    /**
     * Write all 8 output bits via OLAT.
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
        bus.write(REG_OLAT, shadow);
    }

    /* package */ void setDirection(int n, boolean input) throws IOException {
        if (input) {
            direction |= (1 << n);
        } else {
            bus.write(REG_OLAT, shadow);   // latch the output level first
            direction &= ~(1 << n);
        }
        bus.write(REG_IODIR, direction);
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link Mcp23x08Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    public static class Pin implements OutputPin {

        protected final Mcp23x08Minimal chip;
        protected final int n;

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–7)
         */
        protected Pin(Mcp23x08Minimal chip, int n) {
            this.chip = chip;
            this.n = n;
        }

        /**
         * Set this pin as an input (IODIR bit = 1).
         *
         * @throws IOException on bus error
         */
        public void setInput() throws IOException {
            chip.setDirection(n, true);
        }

        /**
         * Set this pin as an output (OLAT bit written first, then IODIR bit = 0).
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
         * Read the actual logic level at the pin (a bus read of GPIO).
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

    // =========================================================================
    // Bus implementations
    // =========================================================================

    /** I²C transport: 1-byte register address through a {@link RegisterConnection}. */
    /* package */ static final class I2cBus implements Bus {
        private final RegisterConnection conn;

        I2cBus(RegisterConnection conn) { this.conn = conn; }

        @Override public int baseIocon() { return 0x00; }

        @Override
        public int read(int reg) throws IOException {
            return conn.read(reg, 1)[0] & 0xFF;
        }

        @Override
        public void write(int reg, int value) throws IOException {
            conn.write(reg, new byte[]{(byte) value});
        }
    }

    /**
     * SPI transport: {@code [0x40 | (addr << 1) | R/W] [reg] [data]} over a
     * plain {@link Connection}.
     */
    /* package */ static final class SpiBus implements Bus {
        private final Connection conn;
        private final int addr;

        SpiBus(Connection conn, int addr) {
            this.conn = conn;
            this.addr = addr & 3;
        }

        @Override public int baseIocon() { return IOCON_HAEN; }

        @Override
        public void init() throws IOException {
            // HAEN resets to 0, so an un-addressed device decodes address 00:
            // the first IOCON write goes out under the address-0 opcode.
            conn.write(new byte[]{0x40, (byte) REG_IOCON, (byte) IOCON_HAEN});
        }

        @Override
        public int read(int reg) throws IOException {
            return conn.writeRead(new byte[]{(byte) (0x40 | (addr << 1) | 1), (byte) reg}, 1)[0] & 0xFF;
        }

        @Override
        public void write(int reg, int value) throws IOException {
            conn.write(new byte[]{(byte) (0x40 | (addr << 1)), (byte) reg, (byte) value});
        }
    }
}
