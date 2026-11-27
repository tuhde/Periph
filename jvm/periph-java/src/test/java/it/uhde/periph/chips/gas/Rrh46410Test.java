package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Rrh46410Test {

    static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    static int cs(byte[] d, int len) {
        int s = 0;
        for (int i = 0; i < len; i++) s += d[i] & 0xFF;
        return ~s & 0xFF;
    }

    /** Simulates the RRH46410 request/response protocol with checksums. */
    static class Sim extends MockConnection {
        final List<byte[]> written = new ArrayList<>();
        byte[] response;
        int mode, counter, gpio = 0x0C, gpioDir, humidity = -1, pid = 0x2310, failWrites;
        int statusOverride = -1;
        boolean hasResult, badChecksum, requestsOk = true;
        InputPin pin;

        synchronized void sample(int n) {
            hasResult = true;
            counter = (counter + n) & 0xFF;
        }

        @Override
        public synchronized void write(byte[] data) throws IOException {
            written.add(data.clone());
            if (failWrites > 0) {
                failWrites--;
                throw new IOException("nack");
            }
            if (cs(data, data.length - 1) != (data[data.length - 1] & 0xFF)) requestsOk = false;
            int cmd = data[0] & 0xFF;
            byte[] p = Arrays.copyOfRange(data, 1, data.length - 1);
            int status = 0;
            byte[] out = new byte[0];
            switch (cmd) {
                case 0x00 -> out = b(pid >> 8, pid & 0xFF, 1, 2, 3);
                case 0x01 -> out = b(1, 2, 3, 4, 5, 6);
                case 0x10 -> out = b(mode);
                case 0x11 -> { mode = p[0] & 0xFF; counter = 0; hasResult = false; }
                case 0x12 -> humidity = p[0] & 0xFF;
                case 0x18 -> {
                    if (!hasResult) status = 0x10;
                    out = mode == 0x05 ? b(counter, 0x01, 0xF4, 0x0B, 0xB8)
                            : b(counter, 25, 0x00, 0x32, 0x01, 0x2C, 0x02, 0x58, 3);
                }
                case 0x20 -> gpioDir = p[0] & 0xFF;
                case 0x21 -> out = b(gpio);
                case 0x22 -> gpio |= p[0];
                case 0x23 -> gpio &= ~p[0];
                case 0x8F -> { mode = 0; response = null; return; }
                default -> status = 0x80;
            }
            if (statusOverride >= 0) status = statusOverride;
            byte[] r = new byte[out.length + 2];
            r[0] = (byte) status;
            System.arraycopy(out, 0, r, 1, out.length);
            int c = cs(r, out.length + 1);
            if (badChecksum) c ^= 1;
            r[out.length + 1] = (byte) c;
            response = r;
        }

        @Override
        public synchronized byte[] read(int n) {
            byte[] out = new byte[n];
            Arrays.fill(out, (byte) 0xFF);
            if (response != null) System.arraycopy(response, 0, out, 0, Math.min(n, response.length));
            return out;
        }

        @Override
        public InputPin intPin() { return pin; }
    }

    static class Pin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;
        boolean high;
        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return high; }
        @Override public void close() {}
    }

    @Test
    void initAndRead() throws IOException {
        Sim sim = new Sim();
        Rrh46410Minimal m = new Rrh46410Minimal(sim);
        m.init();
        assertArrayEquals(b(0x00, 0xFF), sim.written.get(0));
        assertArrayEquals(b(0x11, 0x01, 0xED), sim.written.get(1));
        assertTrue(sim.requestsOk);
        assertEquals(1, sim.mode);
        sim.sample(5);
        Rrh46410Minimal.Measurement r = m.read();
        assertEquals(2.5, r.iaq(), 1e-9);
        assertEquals(0.5, r.tvocMgM3(), 1e-9);
        assertEquals(3.0, r.etohPpm(), 1e-9);
        assertEquals(600, r.eco2Ppm());
        assertEquals(30, r.relativeIaq());
        assertEquals(5, r.sample());
        assertFalse(r.warmedUp());
        assertArrayEquals(b(0x18, 0xE7), sim.written.get(sim.written.size() - 1));
        sim.sample(95);
        r = m.read();
        assertTrue(r.warmedUp() && r.sample() == 100);
        sim.sample(200);
        r = m.read();
        assertTrue(r.warmedUp() && r.sample() == 44);
    }

    @Test
    void errors() throws IOException {
        Sim sim = new Sim();
        sim.pid = 0x1234;
        assertThrows(Rrh46410Minimal.Rrh46410Exception.class, () -> new Rrh46410Minimal(sim).init());
        Sim retry = new Sim();
        retry.failWrites = 3;
        new Rrh46410Minimal(retry).init();
        assertEquals(1, retry.mode);
        Sim s = new Sim();
        Rrh46410Minimal m = new Rrh46410Minimal(s);
        m.init();
        s.sample(1);
        s.badChecksum = true;
        assertThrows(Rrh46410Minimal.Rrh46410Exception.class, m::read);
        s.badChecksum = false;
        s.statusOverride = 0x0A;
        Rrh46410Minimal.Rrh46410Exception e = assertThrows(Rrh46410Minimal.Rrh46410Exception.class, m::read);
        assertEquals(0x0A, e.status());
    }

    @Test
    void fullApi() throws IOException {
        Sim sim = new Sim();
        Rrh46410Full f = new Rrh46410Full(sim);
        f.pollMs = 1;
        f.init();
        assertEquals(new Rrh46410Full.ProductId(0x2310, 1, 2, 3), f.productId());
        assertArrayEquals(b(1, 2, 3, 4, 5, 6), f.trackingNumber());
        assertEquals(Rrh46410Full.MODE_IAQ_2ND_GEN, f.getMode());
        assertNull(f.readResults());
        assertTrue(f.status().notComplete());
        sim.sample(3);
        assertEquals(3, f.readResults().sample());
        assertEquals(0, f.status().raw());
        assertFalse(f.waitForSample(0.005));
        sim.sample(1);
        assertTrue(f.pollInterrupt());
        assertTrue(f.waitForSample(0.1));
        assertEquals(4, f.lastResult().sample());
        assertFalse(f.pollInterrupt());
        f.setHumidity(50);
        assertEquals(128, sim.humidity);
        f.setHumidity(100);
        assertEquals(255, sim.humidity);
        assertThrows(IllegalArgumentException.class, () -> f.setHumidity(101));
        f.setMode(Rrh46410Full.MODE_PBAQ);
        assertEquals(5, sim.mode);
        assertEquals(5.0, f.samplePeriod());
        assertEquals(60, f.warmupSamples());
        assertEquals(90.0, f.samplePeriod(Rrh46410Full.MODE_ULP));
        assertEquals(10, f.warmupSamples(Rrh46410Full.MODE_ULP));
        assertEquals(3.0, f.samplePeriod(Rrh46410Full.MODE_IAQ_2ND_GEN));
        assertEquals(100, f.warmupSamples(Rrh46410Full.MODE_IAQ_2ND_GEN));
        sim.sample(2);
        Rrh46410Minimal.Measurement p = f.readResults();
        assertTrue(p.pbaq());
        assertEquals(0.5, p.tvocMgM3(), 1e-9);
        assertEquals(3.0, p.etohPpm(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> f.setMode(0x04));
        assertEquals("very good", Rrh46410Full.airQualityLabel(1.5));
        assertEquals("good", Rrh46410Full.airQualityLabel(2.0));
        assertEquals("medium", Rrh46410Full.airQualityLabel(3.9));
        assertEquals("poor", Rrh46410Full.airQualityLabel(4.0));
        assertEquals("bad", Rrh46410Full.airQualityLabel(5.0));
        f.configGpio(3);
        assertEquals(3, sim.gpioDir);
        f.setGpio(1);
        assertEquals(1, f.getGpio() & 1);
        f.clearGpio(1);
        assertEquals(0, f.getGpio() & 1);
        assertThrows(IllegalArgumentException.class, () -> f.setGpio(4));
        f.suspend();
        assertEquals(0, sim.mode);
        assertThrows(IllegalArgumentException.class, () -> f.cleanSensor(false));
        assertArrayEquals(b(sim.gpio), f.sendCommand(0x21, new byte[0], 1));
        f.sendCommand(0x55, new byte[0], 0);
        assertTrue(f.status().invalidCommand());
        f.setMode(Rrh46410Full.MODE_IAQ_2ND_GEN);
        f.reset();
        assertTrue(sim.written.stream().anyMatch(w -> Arrays.equals(w, b(0x8F, 0x70))));
        assertEquals(0.0, f.samplePeriod());
        assertEquals(0, sim.mode);
    }

    @Test
    void interrupt() throws Exception {
        Sim sim = new Sim();
        Pin pin = new Pin();
        Rrh46410Full f = new Rrh46410Full(sim);
        f.init();
        List<Rrh46410Minimal.Measurement> hits = new ArrayList<>();
        f.onInterrupt(hits::add, pin);
        assertEquals(EdgeTrigger.RISING, pin.trigger);
        sim.sample(1);
        pin.handler.onEdge();
        pin.handler.onEdge();
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).sample());
        f.offInterrupt();
        assertNull(pin.handler);

        f.pollMs = 2;
        List<Rrh46410Minimal.Measurement> polled = java.util.Collections.synchronizedList(new ArrayList<>());
        f.onInterrupt(polled::add, null);
        sim.sample(1);
        for (int i = 0; i < 200 && polled.isEmpty(); i++) Thread.sleep(5);
        f.offInterrupt();
        assertEquals(2, polled.get(0).sample());

        sim.pin = pin;
        assertFalse(f.pollInterrupt());
        pin.high = true;
        assertTrue(f.pollInterrupt());
    }
}
