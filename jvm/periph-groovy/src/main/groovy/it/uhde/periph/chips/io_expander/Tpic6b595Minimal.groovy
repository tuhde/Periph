package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.SiPoConnection

@CompileStatic
class Tpic6b595Minimal {

    /** Maximum cascade depth supported by the driver's shadow buffer. */
    static final int MAX_DEVICES = 8

    protected final SiPoConnection connection
    protected final int numDevices
    protected final int[] shadow

    Tpic6b595Minimal(SiPoConnection connection) {
        this(connection, 1)
    }

    Tpic6b595Minimal(SiPoConnection connection, int numDevices) {
        if (numDevices < 1 || numDevices > MAX_DEVICES) {
            throw new IllegalArgumentException(
                "numDevices must be in [1, ${MAX_DEVICES}], got ${numDevices}")
        }
        this.connection = connection
        this.numDevices = numDevices
        this.shadow = new int[numDevices]
        try { connection.clear() } catch (IllegalStateException ignored) { /* SRCLR optional */ }
        flush()
    }

    protected void flush() {
        byte[] wire = new byte[numDevices]
        for (int i = 0; i < numDevices; i++) {
            wire[i] = (byte) (shadow[numDevices - 1 - i] & 0xFF)
        }
        connection.write(wire)
    }

    void writePort(int port, int mask) {
        shadow[port] = mask & 0xFF
        flush()
    }

    void fill(boolean value) {
        int b = value ? 0xFF : 0x00
        for (int i = 0; i < numDevices; i++) shadow[i] = b
        flush()
    }

    void off() { fill(false) }

    Pin pin(int n) {
        return new Pin(this, n)
    }

    protected void setPin(int n, boolean high) {
        int port = n >> 3
        int bit = n % 8
        if (high) shadow[port] |=   (1 << bit)
        else      shadow[port] &= ~((1 << bit))
        flush()
    }

    @CompileStatic
    static class Pin {
        protected final Tpic6b595Minimal chip
        protected final int n

        protected Pin(Tpic6b595Minimal chip, int n) {
            this.chip = chip
            this.n = n
        }

        private void set(boolean high) {
            int port = n >> 3
            int bit = n % 8
            if (high) chip.shadow[port] |=   (1 << bit)
            else      chip.shadow[port] &= ~((1 << bit))
            chip.flush()
        }

        void setHigh() { set(true) }
        void setLow()  { set(false) }

        void toggle() {
            int port = n >> 3
            int bit = n % 8
            set(((chip.shadow[port] >> bit) & 1) == 0)
        }

        boolean read() {
            int port = n >> 3
            int bit = n % 8
            return ((chip.shadow[port] >> bit) & 1) == 1
        }
    }
}
