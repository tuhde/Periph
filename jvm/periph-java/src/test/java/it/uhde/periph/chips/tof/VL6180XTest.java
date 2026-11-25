package it.uhde.periph.chips.tof;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VL6180XTest {

    /**
     * VL6180X simulator on top of {@link MockConnection} with explicit 16-bit register indices:
     * starting a measurement makes a result pending, SYSTEM__INTERRUPT_CLEAR drops it.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>();
        final List<int[]> log = new CopyOnWriteArrayList<>();

        Sim(int fresh) {
            setAddressWidth(2);
            set(0x000, 0xB4, 0x01, 0x03);
            set(0x016, fresh);
            set(0x062, 42);
            set(0x050, 0x01, 0x90);   // ALS 400 counts
        }

        void set(int reg, int... values) {
            for (int i = 0; i < values.length; i++) regs.put(reg + i, values[i]);
        }

        int reg(int r) {
            return regs.getOrDefault(r, 0);
        }

        List<Integer> writesTo(int r) {
            List<Integer> out = new ArrayList<>();
            for (int[] w : log) if (w.length == 3 && ((w[0] << 8) | w[1]) == r) out.add(w[2]);
            return out;
        }

        int last(int r) {
            List<Integer> w = writesTo(r);
            return w.isEmpty() ? -1 : w.get(w.size() - 1);
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            int[] w = new int[data.length];
            for (int i = 0; i < data.length; i++) w[i] = data[i] & 0xFF;
            log.add(w);
            int reg = (w[0] << 8) | w[1];
            for (int i = 2; i < w.length; i++) regs.put(reg + i - 2, w[i]);
            if (w.length == 3) {
                if (reg == 0x018 && (w[2] & 1) != 0) regs.put(0x04F, (reg(0x04F) & ~0x07) | 4);
                else if (reg == 0x038 && (w[2] & 1) != 0) regs.put(0x04F, (reg(0x04F) & ~0x38) | (4 << 3));
                else if (reg == 0x015) regs.put(0x04F, 0);
                else if (reg == 0x02E) regs.put(0x02E, 0);
            }
        }

        @Override
        public synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) reg(reg + i);
            return out;
        }
    }

    /** InputPin whose single handler the test triggers by hand. */
    static class FakePin implements InputPin {
        EdgeHandler handler;

        @Override
        public void onEdge(EdgeHandler handler, EdgeTrigger trigger) {
            this.handler = handler;
        }

        @Override
        public void offEdge(EdgeHandler handler) {
            this.handler = null;
        }

        @Override
        public boolean isHigh() {
            return false;
        }

        @Override
        public void close() {}
    }

    @Test
    void initAppliesPrivateAndRecommendedSettings() throws IOException {
        Sim sim = new Sim(1);
        new VL6180XMinimal(sim);
        assertEquals(List.of(0x01), sim.writesTo(0x0207));
        assertEquals(0xCE, sim.last(0x00DB));
        assertEquals(0, sim.last(0x016));
        assertEquals(0x10, sim.last(0x011));
        assertEquals(0x30, sim.last(0x10A));
        assertEquals(0x46, sim.last(0x03F));
        assertEquals(0xFF, sim.last(0x031));
        assertEquals(0x24, sim.last(0x014));
        assertEquals(0x09, sim.last(0x01B));
        assertEquals(0x31, sim.last(0x03E));
        assertEquals(0x00, sim.reg(0x040));
        assertEquals(0x63, sim.reg(0x041));
    }

    @Test
    void initSkipsPrivateSettingsWhenNotFresh() throws IOException {
        Sim sim = new Sim(0);
        new VL6180XMinimal(sim);
        assertTrue(sim.writesTo(0x0207).isEmpty());
        assertEquals(0x24, sim.last(0x014));
    }

    @Test
    void readRange() throws IOException {
        Sim sim = new Sim(1);
        VL6180XMinimal chip = new VL6180XMinimal(sim);
        assertEquals(42, chip.readRange());
        assertTrue(sim.writesTo(0x018).contains(0x01));
        assertEquals(0x07, sim.last(0x015));
        sim.set(0x04D, 0x70);
        assertThrows(IOException.class, chip::readRange);
        assertEquals(7, chip.lastError());
    }

    @Test
    void fullRangeAndAls() throws IOException {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        sim.set(0x04D, 0x60);
        assertArrayEquals(new int[] {42, 6}, full.readRangeStatus());
        sim.set(0x04D, 0x00);

        assertEquals(400, full.readAlsRaw());
        assertEquals(0.32 * 400 / 1.01, full.readLux(), 1e-9);
        full.setAlsGain(0);
        assertEquals(0x40, sim.last(0x03F));
        full.setAlsIntegrationTime(50);
        assertEquals(0x00, sim.reg(0x040));
        assertEquals(0x31, sim.reg(0x041));
        assertEquals(0.32 * (400 / 20.0) * 2, full.readLux(), 1e-9);
        List<Integer> hold = sim.writesTo(0x017);
        assertEquals(1, hold.get(hold.size() - 2));
        assertEquals(0, hold.get(hold.size() - 1));
        assertThrows(IllegalArgumentException.class, () -> full.setAlsGain(8));
        assertThrows(IllegalArgumentException.class, () -> full.setAlsIntegrationTime(513));
        full.setAlsScaler(4);
        assertEquals(4, sim.last(0x120));
        assertThrows(IllegalArgumentException.class, () -> full.setAlsScaler(17));
    }

    @Test
    void continuousModes() throws IOException {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        full.startContinuousRange(50);
        assertEquals(0x04, sim.last(0x01B));
        assertEquals(0x03, sim.last(0x018));
        sim.regs.put(0x04F, 4);
        assertEquals(42, full.readRangeContinuous());
        full.stopContinuousRange();
        assertEquals(0x01, sim.last(0x018));
        assertThrows(IllegalArgumentException.class, () -> full.startContinuousRange(5));
        assertThrows(IllegalArgumentException.class, () -> full.startContinuousAls(2560));

        full.startContinuousAls(100);
        assertEquals(0x09, sim.last(0x03E));
        assertEquals(0x03, sim.last(0x038));
        full.stopContinuousAls();
        assertEquals(0x01, sim.last(0x038));
        full.setInterleaved(true);
        assertEquals(1, sim.last(0x2A3));
        full.setInterleaved(false);
        assertEquals(0, sim.last(0x2A3));
    }

    @Test
    void thresholdsAndTuning() throws IOException {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        full.setRangeThresholds(30, 80);
        assertEquals(80, sim.last(0x019));
        assertEquals(30, sim.last(0x01A));
        assertThrows(IllegalArgumentException.class, () -> full.setRangeThresholds(90, 80));
        full.setAlsThresholds(100, 5000);
        assertEquals(0x13, sim.reg(0x03A));
        assertEquals(0x88, sim.reg(0x03B));
        assertEquals(0x00, sim.reg(0x03C));
        assertEquals(100, sim.reg(0x03D));
        full.setMaxConvergenceTime(20);
        assertEquals(20, sim.last(0x01C));
        assertThrows(IllegalArgumentException.class, () -> full.setMaxConvergenceTime(64));
        full.setAveragingPeriod(100);
        assertEquals(100, sim.last(0x10A));

        full.setRangeOffset(-5);
        assertEquals(0xFB, sim.last(0x024));
        assertEquals(-5, full.getRangeOffset());
        assertThrows(IllegalArgumentException.class, () -> full.setRangeOffset(128));
        full.setCrosstalkRate(0.5);
        assertEquals(0x00, sim.reg(0x01E));
        assertEquals(0x40, sim.reg(0x01F));
        assertThrows(IllegalArgumentException.class, () -> full.setCrosstalkRate(-1.0));
        full.recalibrateVhv();
        assertEquals(0x01, sim.last(0x02E));
    }

    @Test
    void addressIdentificationHistory() throws IOException {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        full.setAddress(0x30);
        assertEquals(0x30, sim.last(0x212));
        assertThrows(IllegalArgumentException.class, () -> full.setAddress(0x80));
        assertEquals(0xB4, full.modelId());
        assertArrayEquals(new int[] {1, 3}, full.revision());
        sim.set(0x052, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 1, 0);
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 256}, full.readHistory());
    }

    @Test
    void interrupts() throws IOException {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        sim.regs.put(0x014, 0x24);
        full.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_OUT_OF_WINDOW);
        assertEquals(0x23, sim.last(0x014));
        full.enableInterrupt(VL6180XFull.SOURCE_ALS, VL6180XFull.MODE_LEVEL_LOW);
        assertEquals(0x0B, sim.last(0x014));
        full.disableInterrupt(VL6180XFull.SOURCE_RANGE);
        assertEquals(0x08, sim.last(0x014));
        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(VL6180XFull.SOURCE_ERROR, 1));
        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(VL6180XFull.SOURCE_RANGE, 5));

        sim.regs.put(0x04F, 0x04);
        assertEquals(VL6180XFull.SOURCE_RANGE, full.pollInterrupt());
        assertEquals(0, sim.reg(0x04F));
        sim.regs.put(0x04F, 0x24);
        assertEquals(VL6180XFull.SOURCE_RANGE | VL6180XFull.SOURCE_ALS, full.pollInterrupt());
        sim.regs.put(0x04F, 0x40);
        assertEquals(VL6180XFull.SOURCE_ERROR, full.pollInterrupt());
        assertEquals(0, full.pollInterrupt());

        FakePin pin = new FakePin();
        List<Integer> got = new CopyOnWriteArrayList<>();
        full.onInterrupt(got::add, pin);
        assertNotNull(pin.handler);
        sim.regs.put(0x04F, 0x04);
        pin.handler.onEdge();
        assertEquals(List.of(VL6180XFull.SOURCE_RANGE), got);
        full.offInterrupt();
        assertNull(pin.handler);
    }

    @Test
    void pollingFallbackDeliversEvents() throws Exception {
        Sim sim = new Sim(1);
        VL6180XFull full = new VL6180XFull(sim);
        sim.regs.put(0x04F, 0x04);
        List<Integer> got = new CopyOnWriteArrayList<>();
        full.onInterrupt(got::add, null);
        Thread.sleep(50);
        full.offInterrupt();
        assertTrue(got.contains(VL6180XFull.SOURCE_RANGE));
    }
}
