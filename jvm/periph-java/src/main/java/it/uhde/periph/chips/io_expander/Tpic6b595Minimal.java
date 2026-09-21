package it.uhde.periph.chips.io_expander;

import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.SiPoConnection;

import java.io.IOException;

/**
 * TPIC6B595 8-bit power SIPO shift register — minimal interface.
 *
 * <p>Drives up to {@code numDevices} cascaded TPIC6B595s through a SiPo
 * (serial-in/parallel-out) connection. Each device exposes 8 open-drain
 * outputs ({@code DRAIN0}–{@code DRAIN7}); every write shifts the entire
 * cascade MSB-first and pulses RCK to latch all outputs atomically. Outputs
 * only sink current — they never source it; an external pull-up or load
 * supply is required for the "off"/high state.
 *
 * <p>The driver owns a {@code numDevices}-byte shadow register so single-pin
 * updates work without re-reading the bus. Every write (pin, port, fill)
 * rebuilds and retransmits the entire reversed cascade — see
 * {@code specs/io_expander/tpic6b595.md} for the wire-order reversal that
 * cascading requires.
 *
 * <p>Initialises every output to OFF at construction (shadow zero, latched
 * once). If the SiPo connection has SRCLR wired, the constructor pulses
 * it to clear the shift register before the all-zero latch.
 */
public class Tpic6b595Minimal {

    /** Maximum cascade depth supported by the driver's shadow buffer. */
    public static final int MAX_DEVICES = 8;

    protected final SiPoConnection connection;
    protected final int numDevices;
    protected final int[] shadow;

    /**
     * Construct and initialise the TPIC6B595.
     *
     * @param connection Configured SiPo connection.
     * @param numDevices Number of cascaded TPIC6B595s on the wire; default 1.
     */
    public Tpic6b595Minimal(SiPoConnection connection, int numDevices) throws IOException {
        if (numDevices < 1 || numDevices > MAX_DEVICES) {
            throw new IllegalArgumentException(
                "numDevices must be in [1, " + MAX_DEVICES + "], got " + numDevices);
        }
        this.connection = connection;
        this.numDevices = numDevices;
        this.shadow = new int[numDevices];
        try { connection.clear(); } catch (IllegalStateException ignored) { /* SRCLR optional */ }
        flush();
    }

    /** Convenience overload with {@code numDevices = 1}. */
    public Tpic6b595Minimal(SiPoConnection connection) throws IOException {
        this(connection, 1);
    }

    private void flush() throws IOException {
        byte[] wire = new byte[numDevices];
        for (int i = 0; i < numDevices; i++) {
            wire[i] = (byte) (shadow[numDevices - 1 - i] & 0xFF);
        }
        connection.write(wire);
    }

    /**
     * Write all 8 outputs of cascaded device {@code port} from {@code mask}.
     *
     * @param port Cascaded device index (0 = nearest the controller).
     * @param mask 8-bit output mask. Bit 0 = {@code DRAIN0}, bit 7 = {@code DRAIN7}.
     *              1 = ON (DMOS conducting, sinks current); 0 = OFF (high-impedance).
     */
    public void writePort(int port, int mask) throws IOException {
        shadow[port] = mask & 0xFF;
        flush();
    }

    /**
     * Set every pin on every cascaded device to {@code value}.
     *
     * @param value true to turn every output ON, false to turn them OFF.
     */
    public void fill(boolean value) throws IOException {
        int b = value ? 0xFF : 0x00;
        for (int i = 0; i < numDevices; i++) shadow[i] = b;
        flush();
    }

    /** Turn every output off (equivalent to {@code fill(false)}). */
    public void off() throws IOException { fill(false); }

    /** Return a {@link Pin} proxy for global pin {@code n}. */
    public Pin pin(int n) {
        return new Pin(this, n);
    }

    /** GPIO proxy for a single TPIC6B595 pin — output-only. */
    public static class Pin implements OutputPin {
        protected final Tpic6b595Minimal chip;
        protected final int n;

        protected Pin(Tpic6b595Minimal chip, int n) {
            this.chip = chip;
            this.n = n;
        }

        @Override
        public void set(boolean high) throws IOException {
            int port = n / 8;
            int bit = n % 8;
            if (high) chip.shadow[port] |=   1 << bit;
            else      chip.shadow[port] &= ~(1 << bit);
            chip.flush();
        }

        /** Set the DMOS output ON (sink current through the external load). */
        public void setHigh() throws IOException { set(true);  }

        /** Set the DMOS output OFF (high-impedance). */
        public void setLow()  throws IOException { set(false); }

        /** Invert the shadow bit for this pin. */
        public void toggle() throws IOException {
            int port = n / 8;
            int bit = n % 8;
            set(((chip.shadow[port] >> bit) & 1) == 0);
        }

        /**
         * Read pin shadow bit (NOT a bus read — SiPo is write-only).
         * @return true if ON, false if OFF.
         */
        public boolean read() {
            int port = n / 8;
            int bit = n % 8;
            return ((chip.shadow[port] >> bit) & 1) == 1;
        }

        @Override
        public void close() { /* no-op for virtual pins */ }
    }
}
