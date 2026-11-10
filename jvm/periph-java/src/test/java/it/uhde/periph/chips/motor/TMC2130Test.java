package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TMC2130Test {

    /** Scripted TMC2130 implementing the pipelined 40-bit datagram protocol. */
    static class Sim implements Connection {
        final Map<Integer, Long> regs = new HashMap<>();
        final List<byte[]> frames = new ArrayList<>();
        final List<long[]> writes = new ArrayList<>();
        long pending;
        boolean mirrorBroken;
        int gstatReads;

        Sim() {
            regs.put(0x04, 0x11000040L);
            regs.put(0x01, 1L);
        }

        long lastWrite(int reg) {
            for (int i = writes.size() - 1; i >= 0; i--) if (writes.get(i)[0] == reg) return writes.get(i)[1];
            throw new AssertionError("register 0x" + Integer.toHexString(reg) + " never written");
        }

        private long datagram(byte[] data) {
            byte[] f = Arrays.copyOf(data, 5);
            frames.add(f);
            long reply = pending;
            int reg = f[0] & 0x7F;
            if ((f[0] & 0x80) != 0) {
                long v = ((f[1] & 0xFFL) << 24) | ((f[2] & 0xFFL) << 16) | ((f[3] & 0xFFL) << 8) | (f[4] & 0xFFL);
                regs.put(reg, v);
                writes.add(new long[] {reg, v});
                pending = mirrorBroken ? 0 : v;
            } else {
                if (reg == 0x01) gstatReads++;
                pending = regs.getOrDefault(reg, 0L);
                if (reg == 0x01) regs.put(0x01, 0L);
            }
            return reply;
        }

        @Override public void write(byte[] data) {
            assertEquals(5, data.length);
            datagram(data);
        }

        @Override public byte[] read(int n) { return new byte[n]; }

        @Override public byte[] writeRead(byte[] data, int n) {
            assertEquals(4, n);
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

    private static class Pin implements OutputPin {
        final List<Boolean> levels = new ArrayList<>();
        @Override public void set(boolean high) { levels.add(high); }
        @Override public void close() {}
        long highs() { return levels.stream().filter(b -> b).count(); }
        boolean last() { return levels.get(levels.size() - 1); }
    }

    private static class DiagPin implements InputPin {
        @Override public boolean isHigh() { return true; }
        @Override public void onEdge(EdgeHandler handler, EdgeTrigger trigger) {}
        @Override public void offEdge(EdgeHandler handler) {}
        @Override public void close() {}
    }

    @Test
    void initWritesDefaults() throws Exception {
        Sim sim = new Sim();
        new TMC2130Minimal(sim, new Pin(), new Pin());
        assertTrue(sim.frames.stream().anyMatch(f -> Arrays.equals(f, new byte[] {(byte) 0xEC, 0x14, 0x03, 0x00, (byte) 0xC3})));
        assertEquals(10, sim.lastWrite(0x11));
        assertEquals(0x4, sim.lastWrite(0x00));
        assertEquals(500, sim.lastWrite(0x13));
        assertEquals(0x000401C8L, sim.lastWrite(0x70));
        long ih = sim.lastWrite(0x10);
        assertEquals(15, (ih >> 8) & 0x1F);
        assertEquals(5, ih & 0x1F);
        assertEquals(6, (ih >> 16) & 0xF);
        assertEquals(1, sim.gstatReads);
    }

    @Test
    void initFailures() {
        Sim a = new Sim();
        a.regs.put(0x04, 0L);
        assertThrows(IOException.class, () -> new TMC2130Minimal(a, new Pin(), new Pin()));
        Sim b = new Sim();
        b.regs.put(0x04, 0x11000000L);
        assertThrows(IOException.class, () -> new TMC2130Minimal(b, new Pin(), new Pin()));
        Sim c = new Sim();
        c.mirrorBroken = true;
        assertThrows(IOException.class, () -> new TMC2130Minimal(c, new Pin(), new Pin()));
    }

    @Test
    void currentMotionAndFaults() throws Exception {
        Sim sim = new Sim();
        Pin step = new Pin();
        Pin dir = new Pin();
        TMC2130Minimal m = new TMC2130Minimal(sim, step, dir);
        m.setCurrent(1.0);
        assertEquals(17, (sim.lastWrite(0x10) >> 8) & 0x1F);
        assertEquals(0, sim.lastWrite(0x6C) & (1L << 17));
        m.setCurrent(0.5);
        assertEquals(15, (sim.lastWrite(0x10) >> 8) & 0x1F);
        assertTrue((sim.lastWrite(0x6C) & (1L << 17)) != 0);
        assertThrows(IllegalArgumentException.class, () -> m.setCurrent(1.5));
        m.move(2, 1e6);
        assertEquals(32, step.highs());
        assertTrue(dir.last());
        m.move(-1, 1e6);
        assertFalse(dir.last());
        assertEquals(48, step.highs());
        assertThrows(IllegalArgumentException.class, () -> m.move(1, 0));
        m.disable();
        assertEquals(0, sim.lastWrite(0x6C) & 0xF);
        m.enable();
        assertEquals(3, sim.lastWrite(0x6C) & 0xF);
        sim.regs.put(0x6F, (1L << 31) | (1L << 26) | (1L << 28) | (1L << 30));
        assertTrue(m.isStandstill());
        assertEquals(TMC2130Minimal.FAULT_OVERTEMP_WARNING | TMC2130Minimal.FAULT_SHORT_GND | TMC2130Minimal.FAULT_OPEN_LOAD,
                m.readFault());
    }

    @Test
    void enPin() throws Exception {
        Pin en = new Pin();
        TMC2130Minimal m = new TMC2130Minimal(new Sim(), new Pin(), new Pin(), 0.11, en);
        assertEquals(List.of(true), en.levels);
        m.enable();
        assertFalse(en.last());
    }

    @Test
    void fullConfiguration() throws Exception {
        Sim sim = new Sim();
        Pin step = new Pin();
        Pin dir = new Pin();
        TMC2130Full f = new TMC2130Full(sim, step, dir, 0.11, null, new DiagPin(), null);
        f.setMicrosteps(256);
        assertEquals(0, (sim.lastWrite(0x6C) >> 24) & 0xF);
        f.setMicrosteps(1);
        assertEquals(8, (sim.lastWrite(0x6C) >> 24) & 0xF);
        assertThrows(IllegalArgumentException.class, () -> f.setMicrosteps(3));
        f.setMicrosteps(32);
        f.move(1, 1e6);
        assertEquals(32, step.highs());
        f.setChopperMode(true);
        assertEquals(0, sim.lastWrite(0x00) & 0x4);
        f.setChopperMode(false);
        assertEquals(0x4, sim.lastWrite(0x00) & 0x4);
        f.setDirectionInverted(true);
        assertEquals(0x10, sim.lastWrite(0x00) & 0x10);
        f.configureDiag(false, false, true, false, true, false, true, false);
        long g = sim.lastWrite(0x00);
        assertEquals(1, (g >> 7) & 1);
        assertEquals(1, (g >> 9) & 1);
        assertEquals(1, (g >> 12) & 1);
        assertEquals(0, (g >> 5) & 1);
        assertEquals(0x14, g & 0x14);
        f.setVsense(false);
        f.setCurrent(0.5);
        assertEquals(8, (sim.lastWrite(0x10) >> 8) & 0x1F);
        assertEquals(0, (sim.lastWrite(0x6C) >> 17) & 1);
        f.setHoldCurrent(0.2);
        assertEquals(3, sim.lastWrite(0x10) & 0x1F);
        f.setCurrentDelay(2, 100);
        assertEquals(2, (sim.lastWrite(0x10) >> 16) & 0xF);
        assertEquals(100, sim.lastWrite(0x11));
        f.configureChopper(4, 1, 3, 2);
        long c = sim.lastWrite(0x6C);
        assertEquals(4, c & 0xF);
        assertEquals(1, (c >> 15) & 3);
        assertEquals(3, (c >> 4) & 7);
        assertEquals(2, (c >> 7) & 0xF);
        assertEquals(0, (c >> 14) & 1);
        f.configureConstantOffTime(5, 2, 11, -3, true, true);
        c = sim.lastWrite(0x6C);
        assertEquals(5, c & 0xF);
        assertEquals(1, (c >> 14) & 1);
        assertEquals(3, (c >> 4) & 7);
        assertEquals(1, (c >> 11) & 1);
        assertEquals(0, (c >> 7) & 0xF);
        assertEquals(3, (c >> 12) & 3);
        f.configureStealthchop(true, false, 40, 5, 1);
        f.setFreewheel(1);
        long p = sim.lastWrite(0x70);
        assertEquals(40, p & 0xFF);
        assertEquals(5, (p >> 8) & 0xFF);
        assertEquals(1, (p >> 16) & 3);
        assertEquals(1, (p >> 20) & 3);
        assertEquals(1, (p >> 18) & 1);
        f.setStallThreshold(-5);
        f.setStallguardFilter(true);
        f.configureCoolstep(5, 2, 1, 1, 0);
        long cc = sim.lastWrite(0x6D);
        assertEquals(5, cc & 0xF);
        assertEquals(-5 & 0x7F, (cc >> 16) & 0x7F);
        assertEquals(1, (cc >> 24) & 1);
        assertEquals(2, (cc >> 8) & 0xF);
        assertThrows(IllegalArgumentException.class, () -> f.setStallThreshold(64));
        assertThrows(IllegalArgumentException.class, () -> f.setStallThreshold(-65));
        assertThrows(IllegalArgumentException.class, () -> f.configureCoolstep(16, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> f.setCoolstepThreshold(1 << 20));
        f.setCoolstepThreshold(500);
        assertEquals(500, sim.lastWrite(0x14));
        assertThrows(IllegalStateException.class, () -> f.setCoilCurrents(1, 1));
        f.setDirectMode(true);
        f.setCoilCurrents(100, -100);
        assertEquals(100L | ((-100L & 0x1FF) << 16), sim.lastWrite(0x2D));
        f.setDcstep(1000, 200, 3);
        assertEquals(1000, sim.lastWrite(0x33));
        assertEquals(200 | (3L << 16), sim.lastWrite(0x6E));
        f.writeMicrostepTable(new long[] {1, 2, 3, 4, 5, 6, 7, 8}, 0x12345678L, 0x00F70000L);
        assertEquals(1, sim.lastWrite(0x60));
        assertEquals(8, sim.lastWrite(0x67));
        assertEquals(0x12345678L, sim.lastWrite(0x68));
        f.setInternalRsense(true);
        assertThrows(IllegalStateException.class, () -> f.setCurrent(0.5));
        f.setInternalRsense(false);
        f.step(3, 0);
        assertEquals(35, step.highs());
        assertTrue(dir.last());
        f.step(-2, 0);
        assertFalse(dir.last());
    }

    @Test
    void fullReadbacks() throws Exception {
        Sim sim = new Sim();
        TMC2130Full f = new TMC2130Full(sim, new Pin(), new Pin(), 0.11, null, new DiagPin(), null);
        sim.regs.put(0x6A, 513L);
        sim.regs.put(0x6B, (0x1FFL << 16) | 0x100);
        sim.regs.put(0x12, 1234L);
        sim.regs.put(0x71, 77L);
        sim.regs.put(0x73, 0x123456L);
        sim.regs.put(0x6F, (1L << 31) | (1L << 15) | (17L << 16) | (1L << 25) | (1L << 24) | 300);
        sim.regs.put(0x04, 0x11000000L | 0x40 | 0x1 | 0x10);
        sim.regs.put(0x01, 3L);
        assertEquals(513, f.microstepPosition());
        assertEquals(new TMC2130Full.CoilCurrents(-1, -256), f.readCoilCurrents());
        assertEquals(1234, f.readTstep());
        assertEquals(77, f.readPwmScale());
        assertEquals(0x23456, f.readLostSteps());
        assertEquals(17, f.readCurrentScale());
        assertEquals(300, f.readStallguard());
        assertTrue(f.isStalled());
        TMC2130Full.Status st = f.readStatus();
        assertTrue(st.stst() && st.fsactive() && st.ot() && st.stallguard() && !st.otpw());
        assertEquals(17, st.csActual());
        assertEquals(300, st.sgResult());
        assertEquals(new TMC2130Full.GlobalStatus(true, true, false), f.readGlobalStatus());
        assertEquals(0L, sim.regs.get(0x01));
        TMC2130Full.Ioin io = f.readIoin();
        assertTrue(io.step() && io.enn() && !io.dir());
        assertEquals(0x11, io.version());
        assertEquals(513, f.readRegister(0x6A));
        assertThrows(IllegalArgumentException.class, () -> f.readRegister(0x10));
        f.writeRegister(0x13, 5, true);
        assertEquals(5, sim.lastWrite(0x13));
        assertThrows(IllegalArgumentException.class, () -> f.writeRegister(0x72, 1, false));
        assertTrue(f.readDiag(0));
        assertThrows(IllegalStateException.class, () -> f.readDiag(1));
        assertThrows(IllegalArgumentException.class, () -> f.readDiag(2));
    }
}
