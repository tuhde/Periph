package it.uhde.periph.chips.io_expander

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

@CompileStatic
class Pcf8575Minimal {

    protected final Connection connection
    protected int[] shadow = [0xFF, 0xFF] as int[]

    Pcf8575Minimal(Connection connection) {
        this.connection = connection
        writeBoth()
    }

    private void writeBoth() {
        connection.write([(byte) shadow[0], (byte) shadow[1]] as byte[])
    }

    int readPort(int port) {
        byte[] buf = connection.read(2)
        return buf[port] & 0xFF
    }

    void writePort(int port, int mask) {
        shadow[port] = mask & 0xFF
        writeBoth()
    }

    Pin pin(int n) {
        return new Pin(this, n)
    }

    protected void setPin(int n, boolean high) {
        int portIdx = n / 8
        int bit = n % 8
        if (high) shadow[portIdx] |=   (1 << bit)
        else      shadow[portIdx] &= ~((1 << bit))
        writeBoth()
    }

    @CompileStatic
    static class Pin {
        protected final Pcf8575Minimal chip
        protected final int n

        protected Pin(Pcf8575Minimal chip, int n) {
            this.chip = chip
            this.n = n
        }

        void setInput() { chip.setPin(n, true) }
        void setOutput() { chip.setPin(n, false) }
        void setHigh() { chip.setPin(n, true) }
        void setLow() { chip.setPin(n, false) }

        boolean read() {
            int port = n / 8
            int bit = n % 8
            byte[] buf = chip.connection.read(2)
            return ((buf[port] >> bit) & 1) == 1
        }

        void toggle() {
            int portIdx = n / 8
            int bit = n % 8
            chip.setPin(n, ((chip.shadow[portIdx] >> bit) & 1) == 0)
        }
    }
}
