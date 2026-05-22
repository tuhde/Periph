package it.uhde.periph.chips.io_expander;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

public class Pcf8575Minimal {

    protected final Transport transport;
    protected int[] shadow = {0xFF, 0xFF};

    public Pcf8575Minimal(Transport transport) throws IOException {
        this.transport = transport;
        writeBoth();
    }

    private void writeBoth() throws IOException {
        transport.write(new byte[]{(byte) shadow[0], (byte) shadow[1]});
    }

    public int readPort(int port) throws IOException {
        byte[] buf = transport.read(2);
        return buf[port] & 0xFF;
    }

    public void writePort(int port, int mask) throws IOException {
        shadow[port] = mask & 0xFF;
        writeBoth();
    }

    public Pin pin(int n) {
        return new Pin(this, n);
    }

    protected void setPin(int n, boolean high) throws IOException {
        int portIdx = n / 8;
        int bit = n % 8;
        if (high) shadow[portIdx] |=   (1 << bit);
        else      shadow[portIdx] &= ~((1 << bit));
        writeBoth();
    }

    public static class Pin {
        protected final Pcf8575Minimal chip;
        protected final int n;

        protected Pin(Pcf8575Minimal chip, int n) {
            this.chip = chip;
            this.n = n;
        }

        public void setInput() throws IOException { chip.setPin(n, true); }
        public void setOutput() throws IOException { chip.setPin(n, false); }
        public void setHigh() throws IOException { chip.setPin(n, true); }
        public void setLow() throws IOException { chip.setPin(n, false); }

        public boolean read() throws IOException {
            int port = n / 8;
            int bit = n % 8;
            byte[] buf = chip.transport.read(2);
            return ((buf[port] >> bit) & 1) == 1;
        }

        public void toggle() throws IOException {
            int portIdx = n / 8;
            int bit = n % 8;
            chip.setPin(n, ((chip.shadow[portIdx] >> bit) & 1) == 0);
        }
    }
}