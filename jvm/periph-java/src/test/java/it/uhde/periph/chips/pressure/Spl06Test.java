package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Spl06Test {

    // Coefficients c0=204 c1=-261 c00=80469 c10=-54769 c01=-2659 c11=1258 c20=-10529 c21=94 c30=-1350
    private static final int[] COEF = {0x0C, 0xCE, 0xFB, 0x13, 0xA5, 0x5F, 0x2A, 0x0F, 0xF5,
            0x9D, 0x04, 0xEA, 0xD6, 0xDF, 0x00, 0x5E, 0xFA, 0xBA};
    private static final byte[] TRAW = {0x02, 0x5C, 0x28};               // 154664
    private static final byte[] PRAW = {(byte) 0xFE, (byte) 0x94, (byte) 0xB7};  // -93001
    private static final double T_EXP = 25.0054779053;
    private static final double P_EXP = 98263.8611522177;

    /** MEAS_CFG keeps its read-only ready flags on write; PRS_B2..0 pops queued FIFO entries. */
    private static class Mock extends MockConnection {
        int flags = 0xF0;
        Deque<byte[]> fifo;
        InputPin pin;

        Mock() {
            setRegister(0x0D, 0x10);
            setRegister(0x08, 0xF0);
            setRegister(0x10, COEF);
            setRegister(0x00, 0xFE, 0x94, 0xB7, 0x02, 0x5C, 0x28);
        }

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            if (data.length >= 2 && (data[0] & 0xFF) == 0x08) setRegister(0x08, (data[1] & 0x07) | flags);
        }

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = data[0] & 0xFF;
            if (fifo != null && reg == 0x0B) setRegister(0x0B, fifo.isEmpty() ? 0x01 : 0x00);
            if (fifo != null && reg == 0x00 && n == 3 && !fifo.isEmpty()) return fifo.pollFirst().clone();
            return super.writeRead(data, n);
        }

        @Override
        public InputPin intPin() {
            return pin;
        }
    }

    private static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (h == handler) handler = null; }
        @Override public boolean isHigh() { return true; }
        @Override public void close() {}
    }

    private Mock c;

    private int last(int reg) {
        int v = -1;
        for (byte[] w : c.writes()) if (w.length >= 2 && (w[0] & 0xFF) == reg) v = w[1] & 0xFF;
        return v;
    }

    private long count(int reg) {
        return c.writes().stream().filter(w -> w.length >= 2 && (w[0] & 0xFF) == reg).count();
    }

    private Spl06Full make() throws IOException {
        c = new Mock();
        Spl06Full s = new Spl06Full(c);
        assertTrue(c.writes().isEmpty(), "constructor touched the bus");
        s.init();
        return s;
    }

    @Test
    void initDecodesCoefficientsAndWritesDefaults() throws IOException {
        Spl06Full s = make();
        assertEquals(0x80, last(0x07));
        assertEquals(0x04, last(0x06));
        assertEquals(0x04, last(0x09));
        assertEquals(new Spl06Minimal.Coefficients(204, -261, 80469, -54769, -2659, 1258, -10529, 94, -1350),
                s.coefficients());
    }

    @Test
    void readMeasuresTemperatureThenPressure() throws IOException {
        Spl06Full s = make();
        Spl06Minimal.Measurement m = s.read();
        assertEquals(T_EXP, m.temperatureC(), 1e-6);
        assertEquals(P_EXP, m.pressurePa(), 1e-6);
        List<Integer> meas = new ArrayList<>();
        for (byte[] w : c.writes()) if (w.length >= 2 && w[0] == 0x08) meas.add((int) w[1]);
        assertEquals(List.of(0x02, 0x01), meas.subList(meas.size() - 2, meas.size()));
        assertEquals(P_EXP, s.readPressure(), 1e-6);
        assertEquals(T_EXP, s.readTemperature(), 1e-6);
        assertEquals(0x02, last(0x08));
        assertArrayEquals(new int[]{-93001, 154664}, s.readRaw());
        assertEquals(258.0291543011, s.altitude(), 1e-3);
        assertEquals(0.0, s.altitude(P_EXP), 1e-6);
    }

    @Test
    void compensateIsPure() throws IOException {
        Spl06Full s = make();
        Spl06Minimal.Measurement m = s.compensate(-93001, 154664, 16, 1);
        assertEquals(P_EXP, m.pressurePa(), 1e-6);
        assertEquals(T_EXP, m.temperatureC(), 1e-6);
        m = s.compensate(-93001, 154664, 8, 2);
        assertEquals(80852.2826419888, m.pressurePa(), 1e-6);
        assertEquals(76.3351593018, m.temperatureC(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> s.compensate(0, 0, 3, 1));
    }

    @Test
    void configurationSetsRegistersAndShiftBits() throws IOException {
        Spl06Full s = make();
        s.setPressureConfig(128, 8);
        assertEquals(0x37, last(0x06));
        assertNotEquals(0, last(0x09) & 0x04);
        s.setPressureConfig(8, 1);
        assertEquals(0x03, last(0x06));
        assertEquals(0, last(0x09) & 0x04);
        s.setTemperatureConfig(32, 4);
        assertEquals(0xA5, last(0x07));
        assertNotEquals(0, last(0x09) & 0x08);
        s.setTemperatureConfig(2, 1);
        assertEquals(0x81, last(0x07));
        assertEquals(0, last(0x09) & 0x08);
        assertEquals(80852.2826419888, s.read().pressurePa(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> s.setPressureConfig(3, 1));
        assertThrows(IllegalArgumentException.class, () -> s.setPressureConfig(16, 3));
        assertThrows(IllegalArgumentException.class, () -> s.setTemperatureConfig(0, 1));
    }

    @Test
    void backgroundModeReadsWithoutCommand() throws IOException {
        Spl06Full s = make();
        s.startContinuous(true, true);
        assertEquals(0x07, last(0x08));
        s.startContinuous(true, false);
        assertEquals(0x05, last(0x08));
        s.startContinuous(false, true);
        assertEquals(0x06, last(0x08));
        assertThrows(IllegalArgumentException.class, () -> s.startContinuous(false, false));
        s.startContinuous();
        long n = count(0x08);
        assertEquals(P_EXP, s.readContinuous().pressurePa(), 1e-6);
        assertEquals(P_EXP, s.read().pressurePa(), 1e-6);
        assertEquals(n, count(0x08));
        c.setRegister(0x08, 0xD7);
        assertArrayEquals(new boolean[]{true, false}, s.dataReady());
        c.setRegister(0x08, 0xF7);
        s.stop();
        assertEquals(0x00, last(0x08));
    }

    @Test
    void fifoDrainsAndClassifiesEntries() throws IOException {
        Spl06Full s = make();
        s.enableFifo();
        assertNotEquals(0, last(0x09) & 0x02);
        c.fifo = new ArrayDeque<>(List.of(TRAW, PRAW));
        List<Spl06Full.FifoEntry> e = s.readFifo();
        assertEquals(2, e.size());
        assertEquals(Spl06Full.KIND_TEMPERATURE, e.get(0).kind());
        assertEquals(T_EXP, e.get(0).value(), 1e-6);
        assertEquals(Spl06Full.KIND_PRESSURE, e.get(1).kind());
        assertEquals(98264.0472093194, e.get(1).value(), 1e-6);
        assertTrue(s.readFifo().isEmpty());
        c.fifo = null;
        c.setRegister(0x0B, 0x02);
        assertArrayEquals(new boolean[]{false, true}, s.fifoStatus());
        s.flushFifo();
        assertEquals(0x80, last(0x0C));
        s.disableFifo();
        assertEquals(0, last(0x09) & 0x02);
    }

    @Test
    void interruptsMapSourcesAndDispatch() throws IOException {
        Spl06Full s = make();
        s.enableInterrupt(Spl06Full.SOURCE_PRESSURE);
        assertNotEquals(0, last(0x09) & 0x20);
        s.enableInterrupt(Spl06Full.SOURCE_TEMPERATURE | Spl06Full.SOURCE_FIFO_FULL);
        assertEquals(0x74, last(0x09) & 0x74);
        s.disableInterrupt(Spl06Full.SOURCE_PRESSURE | Spl06Full.SOURCE_FIFO_FULL);
        assertEquals(0x10, last(0x09) & 0x70);
        c.setRegister(0x0A, 0xFA);
        assertEquals(0x02, s.pollInterrupt());

        FakePin pin = new FakePin();
        c.pin = pin;
        List<Integer> seen = new ArrayList<>();
        s.onInterrupt(seen::add);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        c.setRegister(0x0A, 0x01);
        pin.handler.onEdge();
        c.setRegister(0x0A, 0x00);
        pin.handler.onEdge();
        assertEquals(List.of(0x01), seen);
        s.offInterrupt();
        assertNull(pin.handler);
    }

    @Test
    void resetRestoresDefaults() throws IOException {
        Spl06Full s = make();
        s.setPressureConfig(64, 2);
        s.reset();
        assertEquals(0x09, last(0x0C));
        assertEquals(0x04, last(0x06));
        assertEquals(0x80, last(0x07));
        assertEquals(0x04, last(0x09));
        assertEquals(P_EXP, s.read().pressurePa(), 1e-6);
    }

    @Test
    void minimalReadsAndRejectsBadChips() throws IOException {
        Mock m = new Mock();
        Spl06Minimal s = new Spl06Minimal(m);
        s.init();
        assertEquals(P_EXP, s.read().pressurePa(), 1e-6);

        Mock bad = new Mock();
        bad.setRegister(0x0D, 0x58);
        assertThrows(IOException.class, () -> new Spl06Minimal(bad).init());

        Mock slow = new Mock();
        slow.setRegister(0x08, 0x40);
        assertThrows(IOException.class, () -> new Spl06Minimal(slow).init());
    }
}
