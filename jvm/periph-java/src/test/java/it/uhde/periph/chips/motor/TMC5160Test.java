package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TMC5160Test {

    /** Scripted TMC5160 over SPI: pipelined 40-bit datagrams, instant ramp generator. */
    static class SpiSim implements Connection {
        final Map<Integer, Long> regs = new HashMap<>();
        final List<long[]> writes = new ArrayList<>();
        long pending;
        boolean mirrorBad;

        SpiSim() {
            regs.put(0x04, 0x30000000L);
        }

        long lastWrite(int reg) {
            for (int i = writes.size() - 1; i >= 0; i--) if (writes.get(i)[0] == reg) return writes.get(i)[1];
            throw new AssertionError("register 0x" + Integer.toHexString(reg) + " never written");
        }

        private long datagram(byte[] data) {
            byte[] f = Arrays.copyOf(data, 5);
            long reply = pending;
            int reg = f[0] & 0x7F;
            if ((f[0] & 0x80) != 0) {
                long v = ((f[1] & 0xFFL) << 24) | ((f[2] & 0xFFL) << 16) | ((f[3] & 0xFFL) << 8) | (f[4] & 0xFFL);
                regs.put(reg, v);
                writes.add(new long[] {reg, v});
                if (reg == 0x2D) {
                    regs.put(0x21, v);
                    regs.put(0x35, 1L << 9);
                }
                pending = mirrorBad ? 0L : v;
            } else {
                pending = regs.getOrDefault(reg, 0L);
            }
            return reply;
        }

        @Override public void write(byte[] data) { datagram(data); }
        @Override public byte[] read(int n) { return new byte[n]; }
        @Override public byte[] writeRead(byte[] data, int n) {
            long r = datagram(data);
            return new byte[] {(byte) (r >> 24), (byte) (r >> 16), (byte) (r >> 8), (byte) r};
        }
        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }
        @Override public void close() {}
    }

    /** Scripted TMC5160 over single-wire UART with echo, CRC8 and IFCNT. */
    static class UartSim implements Connection {
        final Map<Integer, Long> regs = new HashMap<>();
        final List<long[]> writes = new ArrayList<>();
        int ifcnt;
        byte[] rx = new byte[0];

        UartSim() {
            regs.put(0x04, 0x30000000L);
        }

        @Override public void write(byte[] data) {
            rx = concat(rx, data);
            if (data.length == 8) {
                int reg = data[2] & 0x7F;
                long v = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16) | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL);
                regs.put(reg, v);
                writes.add(new long[] {reg, v});
                ifcnt = (ifcnt + 1) & 0xFF;
            }
        }
        @Override public byte[] read(int n) {
            byte[] out = Arrays.copyOf(rx, n);
            rx = Arrays.copyOfRange(rx, n, rx.length);
            return out;
        }
        @Override public byte[] writeRead(byte[] req, int n) {
            int reg = req[2] & 0x7F;
            long v = reg == 0x02 ? ifcnt : regs.getOrDefault(reg, 0L);
            byte[] r = {5, (byte) 0xFF, (byte) reg, (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v, 0};
            r[7] = (byte) TMC5160Minimal.crc8(r, 7);
            return Arrays.copyOf(concat(req, r), n);
        }
        private static byte[] concat(byte[] a, byte[] b) {
            byte[] o = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, o, a.length, b.length);
            return o;
        }
        @Override public void enable() {}
        @Override public void disable() {}
        @Override public boolean isEnabled() { return true; }
        @Override public InputPin intPin() { return null; }
        @Override public OutputPin enPin() { return null; }
        @Override public void close() {}
    }

    @Test
    void crcVector() {
        assertEquals(0x48, TMC5160Minimal.crc8(new byte[] {5, 0, 0}, 3));
    }

    @Test
    void spiInitWritesDefaults() throws IOException {
        SpiSim sim = new SpiSim();
        new TMC5160Minimal(sim, 0.075, null);
        assertEquals(0x000100C3L, sim.lastWrite(0x6C));
        assertEquals(0x4L, sim.lastWrite(0x00));
        assertEquals(500L, sim.lastWrite(0x13));
        assertEquals(7L, sim.lastWrite(0x01));
        assertEquals(71583L, sim.lastWrite(0x27));
        assertEquals(1564L, sim.lastWrite(0x26));
    }

    @Test
    void rejectsWrongChipAndStepDir() {
        SpiSim wrong = new SpiSim();
        wrong.regs.put(0x04, 0x11000000L);
        assertThrows(IOException.class, () -> new TMC5160Minimal(wrong, 0.075, null));
        SpiSim sd = new SpiSim();
        sd.regs.put(0x04, 0x30000040L);
        assertThrows(IOException.class, () -> new TMC5160Minimal(sd, 0.075, null));
    }

    @Test
    void motionAndPosition() throws IOException {
        SpiSim sim = new SpiSim();
        TMC5160Minimal m = new TMC5160Minimal(sim, 0.075, null);
        m.moveTo(400);
        assertEquals(400L * 256, sim.lastWrite(0x2D));
        m.waitUntilReached(1);
        assertEquals(400.0, m.position(), 1e-9);
        m.run(-200);
        assertEquals(2L, sim.lastWrite(0x20));
        m.stop();
        assertEquals(0L, sim.lastWrite(0x27));
        assertThrows(IllegalArgumentException.class, () -> m.setCurrent(10));
        assertThrows(IllegalArgumentException.class, () -> m.setSpeed(0));
        m.setCurrent(1.0);
    }

    @Test
    void fullRampAndUartOnly() throws IOException {
        SpiSim sim = new SpiSim();
        TMC5160Full f = new TMC5160Full(sim, 0.075, null, null, null);
        f.configureRamp(0, 800, 400, 400, 200, 400, 800, 1);
        assertEquals(3127L, sim.lastWrite(0x24));
        assertThrows(IllegalStateException.class, f::readIfcnt);
        f.writeRegister(0x13, 5, true);
        sim.mirrorBad = true;
        assertThrows(IOException.class, () -> f.writeRegister(0x13, 6, true));
        f.setMicrosteps(16);
        assertEquals(4L, (sim.lastWrite(0x6C) >> 24) & 0xF);
        assertThrows(IllegalArgumentException.class, () -> f.setMicrosteps(3));
        assertThrows(IllegalStateException.class, () -> f.setCoilCurrents(1, 1));
        assertThrows(IllegalStateException.class, () -> f.readDiag(0));
    }

    @Test
    void uartInitAndIfcnt() throws IOException {
        UartSim sim = new UartSim();
        TMC5160Full f = new TMC5160Full(sim, 0.075, 0, null, true, false);
        assertTrue(sim.writes.stream().anyMatch(w -> w[0] == 0x6C && w[1] == 0x000100C3L));
        assertTrue(f.readIfcnt() > 0);
        f.setSendDelay(2);
        assertEquals(2L << 8, (long) sim.regs.get(0x03));
        assertThrows(IllegalArgumentException.class, () -> new TMC5160Minimal(new UartSim(), 0.075, 254, null, true, false));
    }
}
