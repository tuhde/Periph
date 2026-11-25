package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection

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
@CompileStatic
class Mcp23x08Minimal {

    /** Register-level access to the chip over its serial interface. */
    static abstract class Bus {
        /** Bring-up before the first regular access (SPI: set IOCON.HAEN). */
        void init() { }

        /** IOCON value the driver keeps after init and reset (0x00 on I²C, HAEN on SPI). */
        abstract int baseIocon()

        /** Read one register. */
        abstract int read(int reg)

        /** Write one register. */
        abstract void write(int reg, int value)
    }

    protected static final int REG_IODIR   = 0x00
    protected static final int REG_IPOL    = 0x01
    protected static final int REG_GPINTEN = 0x02
    protected static final int REG_DEFVAL  = 0x03
    protected static final int REG_INTCON  = 0x04
    protected static final int REG_IOCON   = 0x05
    protected static final int REG_GPPU    = 0x06
    protected static final int REG_INTF    = 0x07
    protected static final int REG_INTCAP  = 0x08
    protected static final int REG_GPIO    = 0x09
    protected static final int REG_OLAT    = 0x0A

    protected static final int IOCON_SEQOP  = 0x20
    protected static final int IOCON_HAEN   = 0x08
    protected static final int IOCON_ODR    = 0x04
    protected static final int IOCON_INTPOL = 0x02

    protected final Bus bus
    protected final Connection connection

    /** Shadow of OLAT. */
    public int shadow = 0x00
    /** Shadow of IODIR (1 = input). */
    public int direction = 0xFF

    /**
     * Construct the driver and run the initialisation sequence: bus bring-up,
     * then OLAT ← 0x00. IODIR stays at its reset value 0xFF (all inputs).
     *
     * @param bus        register access for the chip's serial interface
     * @param connection the underlying connection (for its INT pin)
     */
    protected Mcp23x08Minimal(Bus bus, Connection connection) {
        this.bus = bus
        this.connection = connection
        bus.init()
        bus.write(REG_OLAT, 0x00)
    }

    /**
     * Read all 8 pins as a bitmask (GPIO register, actual pin levels).
     *
     * @param port ignored; the chip has a single port
     * @return 8-bit bitmask (bit 0 = GP0)
     */
    int readPort(int port = 0) {
        return bus.read(REG_GPIO)
    }

    /**
     * Write all 8 output bits via OLAT and update the shadow. Bits of input
     * pins are stored but have no effect until the pin becomes an output.
     *
     * @param port ignored; the chip has a single port
     * @param mask 8-bit output mask; bit n = 1 drives GPn high
     */
    void writePort(int port, int mask) {
        shadow = mask & 0xFF
        bus.write(REG_OLAT, shadow)
    }

    /**
     * Write all 8 output bits via OLAT.
     *
     * @param mask 8-bit output mask
     */
    void writePort(int mask) {
        writePort(0, mask)
    }

    /**
     * Return a {@link Pin} proxy for pin {@code n} (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy backed by this driver
     */
    Pin pin(int n) {
        return new Pin(this, n & 7)
    }

    protected void setPin(int n, boolean high) {
        if (high) shadow |= (1 << n)
        else      shadow &= ~(1 << n)
        bus.write(REG_OLAT, shadow)
    }

    protected void setDirection(int n, boolean input) {
        if (input) {
            direction |= (1 << n)
        } else {
            bus.write(REG_OLAT, shadow)   // latch the output level first
            direction &= ~(1 << n)
        }
        bus.write(REG_IODIR, direction)
    }

    // =========================================================================
    // Pin — GPIO proxy for a single pin
    // =========================================================================

    /**
     * GPIO proxy for a single pin. Obtain via {@link Mcp23x08Minimal#pin(int)};
     * do not instantiate directly. Implements {@link OutputPin} so an
     * output-configured pin can be used directly as another chip's EN pin.
     */
    @CompileStatic
    static class Pin implements OutputPin {

        protected final Mcp23x08Minimal chip
        protected final int n

        /**
         * Construct a Pin proxy.
         *
         * @param chip parent driver
         * @param n    pin index (0–7)
         */
        protected Pin(Mcp23x08Minimal chip, int n) {
            this.chip = chip
            this.n = n
        }

        /** Set this pin as an input (IODIR bit = 1). */
        void setInput() { chip.setDirection(n, true) }

        /** Set this pin as an output (OLAT bit written first, then IODIR bit = 0). */
        void setOutput() { chip.setDirection(n, false) }

        /** Drive the pin high. */
        void setHigh() { chip.setPin(n, true) }

        /** Drive the pin low. */
        void setLow() { chip.setPin(n, false) }

        /**
         * Read the actual logic level at the pin (a bus read of GPIO).
         *
         * @return {@code true} if the pin is high
         */
        boolean read() {
            return ((chip.readPort() >> n) & 1) == 1
        }

        /** Invert the output latch bit for this pin. */
        void toggle() {
            chip.setPin(n, ((chip.shadow >> n) & 1) == 0)
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

    // =========================================================================
    // Bus implementations
    // =========================================================================

    /** I²C transport: 1-byte register address through a {@link RegisterConnection}. */
    @CompileStatic
    static final class I2cBus extends Bus {
        private final RegisterConnection conn

        I2cBus(RegisterConnection conn) { this.conn = conn }

        @Override int baseIocon() { return 0x00 }

        @Override
        int read(int reg) {
            return conn.read(reg, 1)[0] & 0xFF
        }

        @Override
        void write(int reg, int value) {
            conn.write(reg, [(byte) value] as byte[])
        }
    }

    /**
     * SPI transport: {@code [0x40 | (addr << 1) | R/W] [reg] [data]} over a
     * plain {@link Connection}.
     */
    @CompileStatic
    static final class SpiBus extends Bus {
        private final Connection conn
        private final int addr

        SpiBus(Connection conn, int addr) {
            this.conn = conn
            this.addr = addr & 3
        }

        @Override int baseIocon() { return IOCON_HAEN }

        @Override
        void init() {
            // HAEN resets to 0, so an un-addressed device decodes address 00:
            // the first IOCON write goes out under the address-0 opcode.
            conn.write([(byte) 0x40, (byte) REG_IOCON, (byte) IOCON_HAEN] as byte[])
        }

        @Override
        int read(int reg) {
            return conn.writeRead([(byte) (0x40 | (addr << 1) | 1), (byte) reg] as byte[], 1)[0] & 0xFF
        }

        @Override
        void write(int reg, int value) {
            conn.write([(byte) (0x40 | (addr << 1)), (byte) reg, (byte) value] as byte[])
        }
    }
}
