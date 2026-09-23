package it.uhde.periph.chips.tof;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VL53L0XTest {

    /**
     * Page-aware VL53L0X simulator on top of {@link MockConnection}. Registers written while
     * {@code 0xFF != 0} go to a separate per-page store, so the private-bank tuning writes don't
     * clobber page-0 registers. Starting a ranging (or calibration) raises
     * RESULT_INTERRUPT_STATUS; the interrupt clear drops it unless continuous mode is active. The
     * SPAD-info handshake (page 7, 0x83) completes immediately.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>();
        final Map<Integer, Integer> pages = new ConcurrentHashMap<>();
        final List<int[]> log = new CopyOnWriteArrayList<>();   // {page, bytes...}
        volatile int page = 0;
        volatile boolean continuous = false;

        Sim() {
            set(0xC0, 0xEE, 0xAA, 0x10);
            set(0x84, 0x11);
            set(0xB0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF);
            set(0xF8, 0x00, 0x10);
            // Result block: status 11, 10.0 SPADs, 5.0 MCPS signal, 0.5 MCPS ambient, 250 mm.
            set(0x14, 11 << 3, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x02, 0x80, 0x00, 0x40, 0x00, 0xFA);
            pages.put(key(1, 0x91), 0x3C);
            pages.put(key(7, 0x92), 0x85);
        }

        static int key(int page, int reg) {
            return (page << 8) | reg;
        }

        void set(int reg, int... values) {
            for (int i = 0; i < values.length; i++) regs.put(reg + i, values[i]);
        }

        int reg(int r) {
            return regs.getOrDefault(r, 0);
        }

        int reg16(int r) {
            return (reg(r) << 8) | reg(r + 1);
        }

        int pageReg(int page, int r) {
            return pages.getOrDefault(key(page, r), 0);
        }

        List<int[]> page0Writes(int r) {
            List<int[]> out = new ArrayList<>();
            for (int[] w : log) {
                if (w[0] == 0 && w.length >= 3 && w[1] == r) out.add(Arrays.copyOfRange(w, 1, w.length));
            }
            return out;
        }

        boolean logged(int... bytes) {
            for (int[] w : log) if (Arrays.equals(Arrays.copyOfRange(w, 1, w.length), bytes)) return true;
            return false;
        }

        int[] lastWrite(int back) {
            int[] w = log.get(log.size() - 1 - back);
            return Arrays.copyOfRange(w, 1, w.length);
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            int reg = data[0] & 0xFF;
            if (reg == 0xFF) page = data[1] & 0xFF;
            int[] entry = new int[data.length + 1];
            entry[0] = page;
            for (int i = 0; i < data.length; i++) entry[i + 1] = data[i] & 0xFF;
            log.add(entry);
            if (page != 0 && reg != 0xFF) {
                for (int i = 1; i < data.length; i++) pages.put(key(page, reg + i - 1), data[i] & 0xFF);
                if (page == 7 && reg == 0x83 && data[1] == 0x00) pages.put(key(7, 0x83), 0x01);
                return;
            }
            for (int i = 1; i < data.length; i++) regs.put(reg + i - 1, data[i] & 0xFF);
            if (reg == 0x00 && data.length == 2) {
                int v = data[1] & 0xFF;
                if ((v & 0x06) != 0) {
                    continuous = true;
                    regs.put(0x13, 0x04);
                } else if ((v & 0x01) != 0) {
                    if (continuous) continuous = false;
                    else regs.put(0x13, 0x04);
                }
                regs.put(0x00, 0x00);
            } else if (reg == 0x0B && data[1] == 0x01 && !continuous) {
                regs.put(0x13, 0x00);
            }
        }

        @Override
        public synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int r = (data[0] & 0xFF) + i;
                out[i] = (byte) (page != 0 ? pageReg(page, r) : reg(r));
            }
            return out;
        }
    }

    private static int[] lastSecond(List<int[]> ws, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = ws.get(ws.size() - n + i)[1];
        return out;
    }

    @Test
    void rejectsWrongModelId() {
        Sim sim = new Sim();
        sim.set(0xC0, 0xEF);
        assertThrows(IOException.class, () -> new VL53L0XMinimal(sim));
    }

    @Test
    void initSequence() throws IOException {
        Sim sim = new Sim();
        new VL53L0XMinimal(sim);
        assertEquals(0x01, sim.reg(0x89) & 0x01);
        assertEquals(0x00, sim.reg(0x88));
        assertArrayEquals(new int[]{0x60, 0x12}, sim.page0Writes(0x60).get(0));
        assertArrayEquals(new int[]{0x44, 0x00, 0x20}, sim.page0Writes(0x44).get(0));
        assertArrayEquals(new int[]{0x00, 0xF0, 0x01, 0, 0, 0},
                new int[]{sim.reg(0xB0), sim.reg(0xB1), sim.reg(0xB2), sim.reg(0xB3), sim.reg(0xB4), sim.reg(0xB5)});
        assertEquals(0xB4, sim.reg(0xB6));
        assertEquals(0x2C, sim.pageReg(1, 0x4E));
        assertEquals(0x25, sim.reg(0x46));
        assertEquals(0x05, sim.pageReg(1, 0x46));
        assertEquals(0x04, sim.reg(0x0A));
        assertEquals(0x01, sim.reg(0x84));
        assertEquals(0xE8, sim.reg(0x01));
        assertArrayEquals(new int[]{0x41, 0x00, 0x01, 0x00}, lastSecond(sim.page0Writes(0x00), 4));
        assertArrayEquals(new int[]{0xE8, 0x01, 0x02, 0xE8}, lastSecond(sim.page0Writes(0x01), 4));
        assertEquals(0, sim.page);
    }

    @Test
    void singleShot() throws IOException {
        Sim sim = new Sim();
        VL53L0XMinimal sensor = new VL53L0XMinimal(sim);
        sim.log.clear();
        assertEquals(250, sensor.distance());
        assertTrue(sensor.rangeValid());
        int[][] pre = {{0x80, 0x01}, {0xFF, 0x01}, {0x00, 0x00}, {0x91, 0x3C}, {0x00, 0x01}, {0xFF, 0x00},
                {0x80, 0x00}, {0x00, 0x01}};
        for (int i = 0; i < pre.length; i++) {
            int[] w = sim.log.get(i);
            assertArrayEquals(pre[i], Arrays.copyOfRange(w, 1, w.length));
        }
        assertArrayEquals(new int[]{0x0B, 0x01}, sim.lastWrite(0));
        sim.set(0x14, 4 << 3);
        sim.set(0x1E, 0x1F, 0xFF);
        assertEquals(8191, sensor.distance());
        assertFalse(sensor.rangeValid());
    }

    @Test
    void measurementAndContinuous() throws IOException {
        Sim sim = new Sim();
        VL53L0XFull full = new VL53L0XFull(sim);
        full.distance();
        assertEquals(new VL53L0XFull.Measurement(250, 11, 5.0, 0.5, 10.0), full.readMeasurement());
        assertEquals(11, full.rangeStatus());

        sim.log.clear();
        full.startContinuous();
        assertArrayEquals(new int[]{0x00, 0x02}, sim.lastWrite(0));
        assertTrue(sim.logged(0x91, 0x3C));
        assertTrue(full.dataReady());
        assertEquals(250, full.readContinuous());
        full.stopContinuous();
        int[][] stop = {{0x00, 0x01}, {0xFF, 0x01}, {0x00, 0x00}, {0x91, 0x00}, {0x00, 0x01}, {0xFF, 0x00}};
        for (int i = 0; i < 6; i++) assertArrayEquals(stop[i], sim.lastWrite(5 - i));
        full.startContinuous(100);
        assertArrayEquals(new int[]{0x00, 0x00, 0x06, 0x40},
                new int[]{sim.reg(0x04), sim.reg(0x05), sim.reg(0x06), sim.reg(0x07)});
        assertArrayEquals(new int[]{0x00, 0x04}, sim.lastWrite(0));
        full.stopContinuous();
    }

    @Test
    void timingVcselProfiles() throws IOException {
        Sim sim = new Sim();
        VL53L0XFull full = new VL53L0XFull(sim);
        int budget = full.timingBudget();
        assertTrue(budget >= 32000 && budget <= 34000, "default budget " + budget);
        full.setTimingBudget(50000);
        assertTrue(Math.abs(full.timingBudget() - 50000) < 50);
        assertThrows(IllegalArgumentException.class, () -> full.setTimingBudget(19999));

        full.setSignalRateLimit(0.1);
        assertEquals(13, sim.reg16(0x44));
        assertEquals(13 / 128.0, full.signalRateLimit());
        assertThrows(IllegalArgumentException.class, () -> full.setSignalRateLimit(-1));

        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE));
        assertEquals(10, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE));
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 18);
        assertEquals(18, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE));
        assertEquals(0x50, sim.reg(0x57));
        full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE, 14);
        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.FINAL_RANGE));
        assertEquals(0x48, sim.reg(0x48));
        assertEquals(0x07, sim.reg(0x30));
        assertEquals(0x20, sim.pageReg(1, 0x30));
        assertEquals(0xE8, sim.reg(0x01));
        assertTrue(Math.abs(full.timingBudget() - 50000) < 300);
        assertThrows(IllegalArgumentException.class,
                () -> full.setVcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE, 13));

        full.setProfile(VL53L0XFull.Profile.HIGH_SPEED);
        assertEquals(14, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE));
        assertTrue(Math.abs(full.timingBudget() - 20000) < 50);
        assertEquals(32, sim.reg16(0x44));
        full.setProfile(VL53L0XFull.Profile.LONG_RANGE);
        assertEquals(18, full.vcselPulsePeriod(VL53L0XFull.VcselPeriodType.PRE_RANGE));
        assertEquals(13, sim.reg16(0x44));
    }

    @Test
    void offsetThresholdsInterrupts() throws Exception {
        Sim sim = new Sim();
        VL53L0XFull full = new VL53L0XFull(sim);
        full.setOffset(-10.25);
        assertEquals(-41 & 0x0FFF, sim.reg16(0x28));
        assertEquals(-10.25, full.offset());
        full.setOffset(12.5);
        assertEquals(12.5, full.offset());
        assertThrows(IllegalArgumentException.class, () -> full.setOffset(512.0));
        full.setCrosstalkCompensation(0.5);
        assertEquals(4096, sim.reg16(0x20));
        assertThrows(IllegalArgumentException.class, () -> full.setCrosstalkCompensation(8.0));

        sim.log.clear();
        full.recalibrate();
        assertTrue(sim.logged(0x00, 0x41) && sim.logged(0x01, 0x02));
        assertEquals(0xE8, sim.reg(0x01));

        full.setInterruptThresholds(100, 801);
        assertEquals(50, sim.reg16(0x0E));
        assertEquals(400, sim.reg16(0x0C));
        assertArrayEquals(new int[]{100, 800}, full.interruptThresholds());
        assertThrows(IllegalArgumentException.class, () -> full.setInterruptThresholds(500, 100));
        full.setAddress(0x30);
        assertEquals(0x30, sim.reg(0x8A));
        assertThrows(IllegalArgumentException.class, () -> full.setAddress(0x78));
        assertEquals(0xEE, full.modelId());
        assertEquals(0x10, full.revisionId());

        full.enableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);
        full.disableInterrupt(VL53L0XFull.SOURCE_LEVEL_LOW);
        assertEquals(0x03, sim.reg(0x0A));
        full.disableInterrupt(VL53L0XFull.SOURCE_OUT_OF_WINDOW);
        assertEquals(0x00, sim.reg(0x0A));
        sim.set(0x13, 0x03 | 0x08);
        assertEquals(VL53L0XFull.SOURCE_OUT_OF_WINDOW, full.pollInterrupt());
        assertEquals(0, sim.reg(0x13));
        assertEquals(0, full.pollInterrupt());
        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(5));

        // Polling fallback (no intPin on the mock).
        List<Integer> got = new CopyOnWriteArrayList<>();
        sim.set(0x13, 0x04);
        full.onInterrupt(got::add);
        for (int i = 0; i < 200 && got.isEmpty(); i++) Thread.sleep(5);
        full.offInterrupt();
        assertEquals(List.of(VL53L0XFull.SOURCE_NEW_SAMPLE_READY), got);
    }

    @Test
    void timeout() throws IOException {
        Sim sim = new Sim();
        VL53L0XFull full = new VL53L0XFull(sim);
        full.startContinuous();
        sim.continuous = false;
        sim.set(0x13, 0x00);
        assertThrows(IOException.class, full::readContinuous);
    }
}
