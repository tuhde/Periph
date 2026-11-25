package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hy3131Test {

    /** Models the HY3131 framing: (addr&lt;&lt;1)|R/W command, one blank clock on reads, auto-increment. */
    static class Chip implements Connection {
        final Map<Integer, Integer> mem = new HashMap<>();
        final List<byte[]> log = new ArrayList<>();
        int intf = 0x07;

        void set(int reg, int... vals) {
            for (int i = 0; i < vals.length; i++) mem.put(reg + i, vals[i] & 0xFF);
        }

        int get(int reg) {
            return mem.getOrDefault(reg, 0);
        }

        @Override public void write(byte[] data) {
            log.add(data.clone());
            int addr = (data[0] & 0xFF) >> 1;
            for (int i = 1; i < data.length; i++) mem.put(addr + i - 1, data[i] & 0xFF);
        }

        @Override public byte[] read(int n) {
            return new byte[n];
        }

        @Override public byte[] writeRead(byte[] data, int n) {
            int addr = (data[0] & 0xFF) >> 1;
            int[] vals = new int[n];
            for (int i = 0; i < n; i++) {
                int a = addr + i;
                if (a == 0x1E) {
                    vals[i] = intf;
                    intf = 0;
                } else {
                    vals[i] = get(a);
                }
            }
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = (byte) (i == 0 ? vals[0] >> 1 : ((vals[i - 1] & 1) << 7) | (vals[i] >> 1));
            }
            return out;
        }

        @Override public void enable() { }
        @Override public void disable() { }
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }
        @Override public void close() { }
    }

    @Test
    void initDefaults() throws IOException {
        Chip c = new Chip();
        new Hy3131Minimal(c, 1.2f);
        assertArrayEquals(new byte[]{(byte) (0x37 << 1), 0x60}, c.log.get(0));
        assertEquals(0x28, c.get(0x33));
        assertEquals(0xC0, c.get(0x31));
        assertEquals(0x00, c.get(0x30));
        assertEquals(0xA3, c.get(0x24));
        assertEquals(0x11, c.get(0x28));
        assertEquals(0x07, c.get(0x22));
        assertEquals(0x80, c.get(0x23));
    }

    @Test
    void registerFraming() throws IOException {
        Chip c = new Chip();
        Hy3131Minimal adc = new Hy3131Minimal(c, 1.2f);
        c.set(0x70, 0xA5, 0x5A);
        assertArrayEquals(new byte[]{(byte) 0xA5, 0x5A}, adc.readRegister(0x70, 2));
        adc.writeRegister(0x60, new byte[]{1, 2, 3});
        assertEquals(1, c.get(0x60));
        assertEquals(2, c.get(0x61));
        assertEquals(3, c.get(0x62));
    }

    @Test
    void readRawAndVoltage() throws IOException {
        Chip c = new Chip();
        Hy3131Minimal adc = new Hy3131Minimal(c, 1.2f);
        c.set(0x00, 0, 0, 0x40);
        c.intf = 0x04;
        assertEquals(0x400000, adc.readRaw());
        c.intf = 0x04;
        assertEquals(0.5 * 1.2 / 0.9, adc.readVoltage(), 1e-5);
        c.set(0x00, 0, 0, 0x80);
        c.intf = 0x04;
        assertEquals(-8388608, adc.readRaw());
        c.intf = 0x04;
        assertTrue(adc.dataReady());
        c.set(0x00, 1, 0, 0);
        assertEquals(1, adc.readRaw());
    }

    @Test
    void fullFeatures() throws IOException {
        Chip c = new Chip();
        Hy3131Full f = new Hy3131Full(c, 1.2f);
        f.configureAd1(4, 2, 3, 1, 1, 3);
        assertEquals((1 << 5) | (2 << 3) | 4, c.get(0x22));
        assertEquals(0x93, c.get(0x23));
        assertEquals(3, (c.get(0x25) >> 4) & 3);
        c.set(0x00, 0, 0, 0x40);
        c.intf = 0x04;
        assertEquals((0.5 * 0.3333333333 - 0.25) * 1.2 / 3.6, f.readVoltage(), 1e-5);

        f.setAd1Input(0x8, 0x5, 1);
        f.setAd1Reference(2, 6);
        assertEquals(0x85, c.get(0x24));
        assertEquals(1, (c.get(0x33) >> 2) & 3);
        assertEquals(0x26, c.get(0x28));

        f.enableAd2(3, 2, 0, false, false, 1, true);
        assertEquals(0x83, c.get(0x26));
        assertEquals(0x84, c.get(0x34) & 0x84);
        assertEquals(2, (c.get(0x25) >> 6) & 3);
        c.set(0x03, 0xFF, 0xFF, 0xFF);
        c.intf = 0x02;
        assertEquals(-1, f.readAd2Raw());

        f.enableLpf(3);
        f.enableRms();
        assertEquals(0xC0 | (3 << 3), c.get(0x29));
        c.set(0x09, 0x00, 0x90, 0x01, 0x00, 0x00);
        c.intf = 0x10;
        assertEquals(320.0, f.readRms(), 1e-3);

        f.enablePeakHold(2);
        assertEquals(0x06, c.get(0x29) & 0x07);
        c.set(0x0E, 0x00, 0x00, 0xFE, 0x00, 0x00, 0x01);
        assertArrayEquals(new int[]{-131072, 65536}, f.readPeak());

        f.startCounter(0x1234);
        assertEquals(0x34, c.get(0x1C));
        assertEquals(0x12, c.get(0x1D));
        assertEquals(0x0A, c.get(0x20) & 0x0A);
        c.set(0x14, 0x01);
        c.intf = 0x01;
        assertEquals(1, f.readCounter()[3]);

        c.intf = 0x14;
        assertEquals(0x14, f.readFlags());
        f.enableInterrupts(0xFF);
        assertEquals(0x1F, c.get(0x1F));
    }
}
