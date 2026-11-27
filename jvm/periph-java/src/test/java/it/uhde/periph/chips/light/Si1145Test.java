package it.uhde.periph.chips.light;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Si1145Test {

    /** Simulates the command engine: RESPONSE counter, parameter RAM, forced measurements. */
    static class Sim extends MockConnection {
        final int[] regs = new int[0x40];
        final int[] params = new int[32];
        final int[] ps = {1500, 1600, 1700};
        final List<Integer> commands = new ArrayList<>();
        boolean ignore = false;
        int failParam = -1;
        InputPin pin;

        Sim(int part, int seq) {
            regs[0] = part;
            regs[2] = seq;
            params[0x00] = 0x60;
            params[0x02] = 0x21;
            params[0x0C] = 0x04;
            params[0x0F] = 0x65;
        }

        int last() { return commands.get(commands.size() - 1); }

        void put16(int r, int v) { regs[r] = v & 0xFF; regs[r + 1] = (v >> 8) & 0xFF; }

        void measure(boolean p, boolean a) {
            int ch = params[1];
            int st = 0;
            if (a && (ch & 0xF0) != 0) {
                if ((ch & 0x10) != 0) put16(0x22, 260);
                if ((ch & 0x20) != 0) put16(0x24, 300);
                if ((ch & 0x80) != 0) put16(0x2C, 345);
                else if ((ch & 0x40) != 0) put16(0x2C, params[0x0F] == 0x65 ? 0x1111 : 0x2222);
                st |= 0x01;
            }
            if (p) {
                for (int i = 0; i < 3; i++) {
                    if ((ch & (1 << i)) != 0) { put16(0x26 + 2 * i, ps[i]); st |= 0x04 << i; }
                }
            }
            regs[0x21] |= st & regs[0x04];
        }

        void command(int cmd) {
            commands.add(cmd);
            if (ignore) return;
            int resp = regs[0x20];
            if (cmd == 0x00) { regs[0x20] = 0; return; }
            if (cmd == 0x01) { regs[0x20] = 0; regs[0x07] = 0; return; }
            if ((resp & 0x80) != 0) return;
            if ((cmd & 0xE0) == 0xA0) {
                int off = cmd & 0x1F;
                if (off == failParam) { regs[0x20] = 0x80; return; }
                params[off] = regs[0x17];
                regs[0x2E] = regs[0x17];
            } else if ((cmd & 0xE0) == 0x80) {
                regs[0x2E] = params[cmd & 0x1F];
            } else if (cmd >= 0x05 && cmd <= 0x07) {
                measure(cmd != 0x06, cmd != 0x05);
            } else if (cmd == 0x12) {
                for (int i = 0; i < 12; i++) regs[0x22 + i] = 0x10 + i;
            } else if (!List.of(0x02, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F).contains(cmd)) {
                regs[0x20] = 0x80;
                return;
            }
            regs[0x20] = (resp + 1) & 0x0F;
        }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            int reg = data[0] & 0xFF;
            for (int i = 1; i < data.length; i++) {
                int r = reg + i - 1, v = data[i] & 0xFF;
                if (r == 0x18) command(v);
                else if (r == 0x21) regs[0x21] &= ~v;
                else regs[r] = v;
            }
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            super.writeRead(data, n);
            int reg = data[0] & 0xFF;
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) regs[(reg + i) & 0x3F];
            return out;
        }

        @Override
        public InputPin intPin() { return pin; }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (h == handler) handler = null; }
        @Override public boolean isHigh() { return true; }
        @Override public void close() {}
    }

    private Sim c;

    private Si1145Full make(int part, int seq) throws IOException {
        c = new Sim(part, seq);
        Si1145Full s = new Si1145Full(c);
        assertTrue(c.writes().isEmpty(), "constructor touched the bus");
        s.init();
        return s;
    }

    @Test
    void initAndRead() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        assertEquals(0x17, c.regs[0x07]);
        assertArrayEquals(new int[]{0x7B, 0x6B, 0x01, 0x00}, java.util.Arrays.copyOfRange(c.regs, 0x13, 0x17));
        assertEquals(0xB1, c.params[1]);
        assertEquals(0x01, c.params[2]);
        assertEquals(0x03, c.regs[0x0F]);
        assertEquals(0x05, c.regs[0x04]);
        Si1145Minimal.Reading r = s.read();
        assertEquals(1500, r.proximity());
        assertEquals(3.45, r.uvIndex(), 1e-9);
        assertEquals(260, r.visible());
        assertEquals(300, r.infrared());
        assertEquals(0, c.regs[0x21]);
        assertThrows(IOException.class, () -> new Si1145Minimal(new Sim(0x32, 0)).init());
    }

    @Test
    void singleReads() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        assertEquals(1500, s.readProximity());
        assertEquals(0x05, c.last());
        assertThrows(IllegalArgumentException.class, () -> s.readProximity(2));
        assertEquals(3.45, s.readUvIndex(), 1e-9);
        assertEquals(0x06, c.last());
        assertEquals(260, s.readVisible());
        assertEquals(300, s.readInfrared());
        assertEquals(new Si1145Full.Raw(260, 300, 1500, 0, 0, 345), s.readRaw());
        assertEquals(0x1111, s.readTemperatureRaw());
        assertEquals(0xB1, c.params[1]);
        assertEquals(0x2222, s.readVddRaw());
        assertEquals(0x75, c.params[0x0F]);
        s.setChannels(false, true, false, true);
        assertEquals(0x11, c.params[1]);
        assertThrows(IllegalArgumentException.class, s::readUvIndex);
        assertThrows(IllegalArgumentException.class, () -> s.setChannels(true, false, false, false, false, false, true));
        assertThrows(IllegalArgumentException.class, () -> s.setChannels(false, false, false, true, true, false, false));
    }

    @Test
    void configuration() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        s.setLedCurrent(1, 15);
        assertEquals(0x0F, c.regs[0x0F]);
        assertThrows(IllegalArgumentException.class, () -> s.setLedCurrent(2, 1));
        assertThrows(IllegalArgumentException.class, () -> s.setLedCurrent(1, 16));
        assertThrows(IllegalArgumentException.class, () -> s.setPsLed(1, 2));
        s.setPsLed(1, 0);
        assertEquals(0, c.params[2]);
        s.setPsPhotodiode(1, false);
        assertEquals(0, c.params[7]);
        s.setGain(3, 2, 1);
        assertArrayEquals(new int[]{3, 0x40, 2, 0x50, 1, 0x60},
                new int[]{c.params[0x11], c.params[0x10], c.params[0x1E], c.params[0x1D], c.params[0x0B], c.params[0x0A]});
        assertThrows(IllegalArgumentException.class, () -> s.setGain(8, 0, 0));
        s.setHighRange(true, true, true);
        assertArrayEquals(new int[]{0x20, 0x20, 0x24}, new int[]{c.params[0x12], c.params[0x1F], c.params[0x0C]});
        s.setAlignment(true, false, true, false, false);
        assertEquals(0x10, c.params[6]);
        assertEquals(0x10, c.params[5]);
        s.setUvCoefficients(new int[]{1, 2, 3, 4});
        assertEquals(1, c.regs[0x13]);
        assertEquals(4, c.regs[0x16]);
        int[] cal = s.readCalibration();
        assertEquals(0x10, cal[0]);
        assertEquals(0x1B, cal[11]);

        Si1145Full s47 = make(0x47, 0x08);
        s47.setLedCurrent(2, 5);
        s47.setLedCurrent(3, 9);
        assertEquals(0x53, c.regs[0x0F]);
        assertEquals(0x09, c.regs[0x10]);
        s47.setPsLed(2, 2);
        s47.setPsLed(3, 4);
        assertEquals(0x21, c.params[2]);
        assertEquals(0x04, c.params[3]);
        s47.setChannels(false, false, false, true, true, true, false);
        assertEquals(1700, s47.readProximity(3));
    }

    @Test
    void autonomousAndResponse() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        s.startAutonomous(0.1);
        assertEquals(0x80, c.regs[0x08]);
        assertEquals(0x0C, c.regs[0x09]);
        assertEquals(0x0F, c.last());
        s.startAutonomous(0.01, false, true);
        assertEquals(0x0E, c.last());
        s.startAutonomous(0.01, true, false);
        assertEquals(0x0D, c.last());
        assertThrows(IllegalArgumentException.class, () -> s.startAutonomous(3.0));
        assertThrows(IllegalArgumentException.class, () -> s.startAutonomous(0.1, false, false));
        s.pause();
        assertEquals(0x0B, c.last());
        s.force();
        assertEquals(0x07, c.last());
        s.force(false, true);
        assertEquals(0x06, c.last());
        Sim main = c;
        Si1145Full sw = make(0x45, 0x01);
        sw.startAutonomous(0.1);
        assertEquals(0x80, c.regs[0x0A]);
        assertEquals(0x0C, c.regs[0x08]);
        c = main;
        assertEquals(new Si1145Full.Response(c.regs[0x20], 0), s.readResponse());
        c.failParam = 0x0B;
        assertThrows(IOException.class, () -> s.setGain(0, 0, 1));
        assertEquals(0x00, c.last());
        assertEquals(0, c.regs[0x20]);
        c.failParam = -1;
        c.regs[0x20] = 0x8C;
        assertEquals(new Si1145Full.Response(0, 0x8C), s.readResponse());
        s.setGain(0, 0, 0);
        assertEquals(0, c.regs[0x20] & 0x80);
    }

    @Test
    void interruptsAddressReset() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        s.enableInterrupt(Si1145Full.SOURCE_CMD);
        assertEquals(0x01, c.regs[0x03]);
        assertEquals(0x25, c.regs[0x04]);
        s.disableInterrupt(Si1145Full.SOURCE_CMD);
        assertEquals(0x05, c.regs[0x04]);
        c.regs[0x21] = 0x05;
        assertEquals(0x05, s.pollInterrupt());
        assertEquals(0, s.pollInterrupt());
        FakePin pin = new FakePin();
        c.pin = pin;
        List<Integer> got = new ArrayList<>();
        s.onInterrupt(got::add);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        c.regs[0x21] = 0x04;
        pin.handler.onEdge();
        pin.handler.onEdge();
        assertEquals(List.of(0x04), got);
        s.offInterrupt();
        assertNull(pin.handler);
        s.disableInterrupt(0x3D);
        assertEquals(1500, s.read().proximity());
        assertEquals(new Si1145Full.Id(0x45, 0, 0x08), s.readId());
        s.setI2cAddress(0x30);
        assertEquals(0x30, c.params[0]);
        assertEquals(0x02, c.last());
        assertThrows(IllegalArgumentException.class, () -> s.setI2cAddress(0x80));
        c.regs[0x0F] = 0x0F;
        s.reset();
        assertTrue(c.commands.contains(0x01));
        assertEquals(0x17, c.regs[0x07]);
        assertEquals(0xB1, c.params[1]);
        assertEquals(0x03, c.regs[0x0F]);
    }

    @Test
    void timeouts() throws IOException {
        Si1145Full s = make(0x45, 0x08);
        c.ignore = true;
        assertThrows(IOException.class, s::read);
        assertThrows(IOException.class, s::pause);
    }
}
