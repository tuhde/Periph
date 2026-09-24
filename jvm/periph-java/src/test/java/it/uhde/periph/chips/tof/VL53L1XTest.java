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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VL53L1XTest {

    /**
     * VL53L1X simulator on top of {@link MockConnection} with explicit 16-bit register indices.
     * GPIO__TIO_HV_STATUS (0x0031) is computed: bit 0 is 0 (active-low line asserted) while a
     * result is pending. Starting a single-shot or timed ranging makes a result pending; the
     * interrupt clear drops it unless timed ranging is running.
     */
    static class Sim extends MockConnection {
        final Map<Integer, Integer> regs = new ConcurrentHashMap<>();
        final List<int[]> log = new CopyOnWriteArrayList<>();
        volatile boolean pending = false;
        volatile boolean ranging = false;

        Sim() {
            set(0x00E5, 0x01);
            set(0x010F, 0xEA, 0xCC, 0x10);
            set(0x013E, 0x91);
            set(0x00DE, 0x00, 0x50);
            // Result block: raw status 9 (valid), 10.0 SPADs, 0.5 MCPS ambient, 250 mm, 5.0 MCPS signal.
            set(0x0089, 9, 0, 0, 0x0A, 0x00, 0, 0, 0x00, 0x40, 0, 0, 0, 0, 0x00, 0xFA, 0x02, 0x80);
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

        List<Integer> writesTo(int r) {
            List<Integer> out = new ArrayList<>();
            for (int[] w : log) if (w.length == 3 && ((w[0] << 8) | w[1]) == r) out.add(w[2]);
            return out;
        }

        int[] lastWrite() {
            return log.get(log.size() - 1);
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            int[] w = new int[data.length];
            for (int i = 0; i < data.length; i++) w[i] = data[i] & 0xFF;
            log.add(w);
            int reg = (w[0] << 8) | w[1];
            for (int i = 2; i < w.length; i++) regs.put(reg + i - 2, w[i]);
            if (reg == 0x0087 && w.length == 3) {
                if (w[2] == 0x10 || w[2] == 0x40) {
                    pending = true;
                    ranging = w[2] == 0x40;
                } else {
                    ranging = false;
                }
            } else if (reg == 0x0086 && w[2] == 0x01) {
                pending = ranging;
            }
        }

        @Override
        public synchronized byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                int r = reg + i;
                out[i] = (byte) (r == 0x0031 ? (pending ? 0x00 : 0x01) : reg(r));
            }
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
        public void close() {}
    }

    @Test
    void rejectsWrongSensorIdAndBootTimeout() {
        Sim bad = new Sim();
        bad.set(0x0110, 0xCD);
        assertThrows(IOException.class, () -> new VL53L1XMinimal(bad));
        Sim unbooted = new Sim();
        unbooted.set(0x00E5, 0x00);
        assertThrows(IOException.class, () -> new VL53L1XMinimal(unbooted));
    }

    @Test
    void initSequence() throws IOException {
        Sim sim = new Sim();
        new VL53L1XMinimal(sim);
        for (int i = 0; i < 91; i++) {
            int[] w = sim.log.get(i);
            assertEquals(3, w.length);
            assertEquals(0x2D + i, (w[0] << 8) | w[1]);
        }
        assertEquals(0x20, sim.writesTo(0x0046).get(0));
        assertEquals(0x9B, sim.writesTo(0x0081).get(0));
        assertArrayEquals(new int[]{0x01, 0x01, 0x11}, new int[]{sim.reg(0x002E), sim.reg(0x002F), sim.reg(0x0030)});
        List<Integer> starts = sim.writesTo(0x0087);
        assertEquals(List.of(0x40, 0x00), starts.subList(starts.size() - 2, starts.size()));
        assertEquals(0x09, sim.reg(0x0008));
        assertEquals(0x00, sim.reg(0x000B));
        assertFalse(sim.ranging);
    }

    @Test
    void singleShot() throws IOException {
        Sim sim = new Sim();
        VL53L1XMinimal sensor = new VL53L1XMinimal(sim);
        sim.log.clear();
        assertEquals(250, sensor.distance());
        assertTrue(sensor.rangeValid());
        assertArrayEquals(new int[]{0x00, 0x86, 0x01}, sim.log.get(0));
        assertArrayEquals(new int[]{0x00, 0x87, 0x10}, sim.log.get(1));
        assertArrayEquals(new int[]{0x00, 0x86, 0x01}, sim.lastWrite());
        sim.set(0x0089, 4);
        assertEquals(250, sensor.distance());
        assertFalse(sensor.rangeValid());
    }

    @Test
    void measurementBudgetMode() throws IOException {
        Sim sim = new Sim();
        VL53L1XFull full = new VL53L1XFull(sim);
        full.distance();
        assertEquals(new VL53L1XFull.Measurement(250, 0, 5.0, 0.5, 10.0), full.readMeasurement());
        assertEquals(0, full.rangeStatus());
        sim.set(0x0089, 0x1F);
        assertEquals(255, full.readMeasurement().rangeStatus());
        sim.set(0x0089, 9);

        assertEquals(100000, full.timingBudget());
        assertEquals(VL53L1XFull.DistanceMode.LONG, full.distanceMode());
        full.setTimingBudget(33000);
        assertEquals(0x0060, sim.reg16(0x005E));
        assertEquals(0x006E, sim.reg16(0x0061));
        assertEquals(33000, full.timingBudget());
        assertThrows(IllegalArgumentException.class, () -> full.setTimingBudget(15000));
        assertThrows(IllegalArgumentException.class, () -> full.setTimingBudget(40000));
        full.setDistanceMode(VL53L1XFull.DistanceMode.SHORT);
        assertArrayEquals(new int[]{0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606, 0x00D6},
                new int[]{sim.reg(0x004B), sim.reg(0x0060), sim.reg(0x0063), sim.reg(0x0069), sim.reg16(0x0078),
                        sim.reg16(0x007A), sim.reg16(0x005E)});
        full.setTimingBudget(15000);
        assertEquals(0x001D, sim.reg16(0x005E));
        assertEquals(0x0027, sim.reg16(0x0061));
        assertThrows(IllegalArgumentException.class, () -> full.setDistanceMode(VL53L1XFull.DistanceMode.LONG));
        full.setTimingBudget(100000);
        full.setDistanceMode(VL53L1XFull.DistanceMode.LONG);
        assertArrayEquals(new int[]{0x0A, 0x0F0D, 0x01CC, 0x01EA},
                new int[]{sim.reg(0x004B), sim.reg16(0x0078), sim.reg16(0x005E), sim.reg16(0x0061)});
        sim.set(0x004B, 0x33);
        assertThrows(IOException.class, full::distanceMode);
    }

    @Test
    void continuousAndThresholds() throws IOException {
        Sim sim = new Sim();
        VL53L1XFull full = new VL53L1XFull(sim);
        full.setInterMeasurement(200);
        assertEquals(0x50 * 200 * 1075 / 1000, (sim.reg16(0x006C) << 16) | sim.reg16(0x006E));
        assertEquals(200, full.interMeasurement());
        assertThrows(IllegalArgumentException.class, () -> full.setInterMeasurement(0));
        full.startContinuous();
        assertEquals(100, full.interMeasurement());
        assertArrayEquals(new int[]{0x00, 0x87, 0x40}, sim.lastWrite());
        assertTrue(full.dataReady());
        assertEquals(250, full.readContinuous());
        assertTrue(full.dataReady());
        full.stopContinuous();
        assertArrayEquals(new int[]{0x00, 0x87, 0x00}, sim.lastWrite());
        full.startContinuous(50);
        assertEquals(100, full.interMeasurement());
        full.stopContinuous();
        full.startContinuous(500);
        assertEquals(500, full.interMeasurement());
        full.stopContinuous();
        assertThrows(IllegalArgumentException.class, () -> full.startContinuous(-1));

        full.setInterruptThresholds(100, 801);
        assertEquals(100, sim.reg16(0x0074));
        assertEquals(801, sim.reg16(0x0072));
        assertArrayEquals(new int[]{100, 801}, full.interruptThresholds());
        assertThrows(IllegalArgumentException.class, () -> full.setInterruptThresholds(500, 100));
    }

    @Test
    void signalSigmaRoiOffsetCrosstalk() throws IOException {
        Sim sim = new Sim();
        VL53L1XFull full = new VL53L1XFull(sim);
        assertEquals(1.0, full.signalRateLimit());
        full.setSignalRateLimit(0.25);
        assertEquals(32, sim.reg16(0x0066));
        assertThrows(IllegalArgumentException.class, () -> full.setSignalRateLimit(-1));
        assertEquals(90, full.sigmaThreshold());
        full.setSigmaThreshold(45);
        assertEquals(180, sim.reg16(0x0064));
        assertThrows(IllegalArgumentException.class, () -> full.setSigmaThreshold(16384));

        assertArrayEquals(new int[]{16, 16}, full.roi());
        assertEquals(199, full.roiCenter());
        full.setRoiCenter(167);
        full.setRoi(8, 8);
        assertEquals(0x77, sim.reg(0x0080));
        assertEquals(167, full.roiCenter());
        full.setRoi(8, 16);
        assertArrayEquals(new int[]{8, 16}, full.roi());
        assertEquals(199, full.roiCenter());
        assertThrows(IllegalArgumentException.class, () -> full.setRoi(3, 8));
        assertThrows(IllegalArgumentException.class, () -> full.setRoiCenter(256));
        assertEquals(0x91, full.opticalCenter());

        full.setOffset(-10.25);
        assertEquals(-41 & 0x1FFF, sim.reg16(0x001E));
        assertEquals(0, sim.reg16(0x0020));
        assertEquals(0, sim.reg16(0x0022));
        assertEquals(-10.25, full.offset());
        full.setOffset(700.5);
        assertEquals(700.5, full.offset());
        assertThrows(IllegalArgumentException.class, () -> full.setOffset(1024.0));
        full.setCrosstalkCompensation(0.01);
        assertEquals(5120, sim.reg16(0x0016));
        assertEquals(0.01, full.crosstalkCompensation());
        assertThrows(IllegalArgumentException.class, () -> full.setCrosstalkCompensation(0.128));
    }

    @Test
    void calibrationAndRecalibrate() throws IOException {
        Sim sim = new Sim();
        VL53L1XFull full = new VL53L1XFull(sim);
        assertEquals(10.0, full.calibrateOffset(260));
        assertEquals(10.0, full.offset());
        assertFalse(sim.ranging);
        assertEquals(0.127, full.calibrateCrosstalk(500));
        sim.set(0x0098, 0x00, 0x20);
        assertEquals(0.0125, full.calibrateCrosstalk(500), 1e-9);
        assertEquals(6400, sim.reg16(0x0016));
        assertThrows(IllegalArgumentException.class, () -> full.calibrateCrosstalk(0));

        sim.log.clear();
        full.recalibrate();
        assertEquals(List.of(0x81, 0x09), sim.writesTo(0x0008));
        assertEquals(List.of(0x92, 0x00), sim.writesTo(0x000B));
        assertEquals(List.of(0x40, 0x00), sim.writesTo(0x0087));
    }

    @Test
    void addressIdentificationInterrupts() throws IOException {
        Sim sim = new Sim();
        VL53L1XFull full = new VL53L1XFull(sim);
        full.setAddress(0x30);
        assertEquals(0x30, sim.reg(0x0001));
        assertThrows(IllegalArgumentException.class, () -> full.setAddress(0x78));
        assertEquals(0xEA, full.modelId());
        assertEquals(0xCC, full.moduleType());
        assertEquals(0x10, full.revisionId());

        full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW);
        assertEquals(0x02, sim.reg(0x0046));
        full.enableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);
        assertEquals(0x03, sim.reg(0x0046));
        full.disableInterrupt(VL53L1XFull.SOURCE_LEVEL_LOW);
        assertEquals(0x03, sim.reg(0x0046));
        full.disableInterrupt(VL53L1XFull.SOURCE_IN_WINDOW);
        assertEquals(0x20, sim.reg(0x0046));
        full.disableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
        assertEquals(0x20, sim.reg(0x0046));
        sim.pending = false;
        assertEquals(0, full.pollInterrupt());
        full.enableInterrupt(VL53L1XFull.SOURCE_OUT_OF_WINDOW);
        sim.pending = true;
        assertEquals(VL53L1XFull.SOURCE_OUT_OF_WINDOW, full.pollInterrupt());
        assertFalse(sim.pending);
        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(6));

        full.enableInterrupt(VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
        sim.pending = true;
        FakePin pin = new FakePin();
        List<Integer> got = new ArrayList<>();
        full.onInterrupt(got::add, pin);
        pin.handler.onEdge();
        full.offInterrupt();
        assertEquals(List.of(VL53L1XFull.SOURCE_NEW_SAMPLE_READY), got);
        assertNull(pin.handler);
    }
}
