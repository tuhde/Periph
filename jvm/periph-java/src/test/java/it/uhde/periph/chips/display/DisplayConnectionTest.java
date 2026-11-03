package it.uhde.periph.chips.display;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockDisplayConnection;
import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.SoftSpi;
import it.uhde.periph.connection.WireMode;
import org.junit.jupiter.api.Test;

class DisplayConnectionTest {

    private static final class LogPin implements OutputPin {
        final String name;
        final List<String> log;
        LogPin(String name, List<String> log) { this.name = name; this.log = log; }
        @Override public void set(boolean high) { log.add(name + "=" + (high ? 1 : 0)); }
        @Override public void close() {}
    }

    private static class LevelPin implements InputPin {
        volatile boolean high;
        @Override public void onEdge(EdgeHandler handler, EdgeTrigger trigger) {}
        @Override public void offEdge(EdgeHandler handler) {}
        @Override public boolean isHigh() { return high; }
        @Override public void close() {}
    }

    private static final class FakeTransport implements DisplayConnection.Transport {
        final List<byte[]> writes = new ArrayList<>();
        final List<Integer> readQ = new ArrayList<>();
        @Override public void write(byte[] data) { writes.add(data.clone()); }
        @Override public byte[] read(int n) {
            byte[] out = new byte[n];
            for (int i = 0; i < n && !readQ.isEmpty(); i++) out[i] = (byte) (int) readQ.remove(0);
            return out;
        }
        @Override public void close() {}
    }

    @Test
    void commandAndParametersAreSeparateTransfers() throws IOException {
        List<String> log = new ArrayList<>();
        FakeTransport spi = new FakeTransport();
        DisplayConnection c = new DisplayConnection(spi, new LogPin("dc", log), new LogPin("res", log),
            null, null, WireMode.SIMPLEX);
        log.clear();
        c.sendCommand(0x10, new byte[] {1, 2, 3});
        assertEquals(2, spi.writes.size());
        assertArrayEquals(new byte[] {0x10}, spi.writes.get(0));
        assertArrayEquals(new byte[] {1, 2, 3}, spi.writes.get(1));
        assertEquals(List.of("dc=0", "dc=1"), log);
        c.sendCommand(0x04, new byte[0]);
        assertEquals(3, spi.writes.size());
        assertThrows(UnsupportedOperationException.class, () -> c.readData(1));
    }

    @Test
    void resetPulsesResLowThenHigh() throws IOException {
        List<String> log = new ArrayList<>();
        DisplayConnection c = new DisplayConnection(new FakeTransport(), new LogPin("dc", log),
            new LogPin("res", log), null, null, WireMode.SIMPLEX).withResetTiming(1, 1);
        log.clear();
        c.reset();
        assertEquals(List.of("res=0", "res=1"), log);
    }

    @Test
    void waitBusyHonoursPolarityAndTimeout() throws Exception {
        LevelPin busy = new LevelPin();
        busy.high = true;
        DisplayConnection c = new DisplayConnection(new FakeTransport(), new LogPin("dc", new ArrayList<>()),
            null, busy, null, WireMode.SIMPLEX);
        assertTrue(c.busyLevel());
        assertTrue(c.waitBusy(false, 50));   // active-low, level high -> ready
        assertFalse(c.waitBusy(true, 20));   // active-high stuck -> timeout
        new Thread(() -> {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            busy.high = false;
        }).start();
        assertTrue(c.waitBusy(true, 1000));
        DisplayConnection none = new DisplayConnection(new FakeTransport(), new LogPin("dc", new ArrayList<>()),
            null, null, null, WireMode.SIMPLEX);
        assertTrue(none.waitBusy(true, 1));
        assertFalse(none.busyLevel());
    }

    @Test
    void halfDuplexPinReadsOneWindowPerByte() throws IOException {
        FakeTransport spi = new FakeTransport();
        spi.readQ.addAll(Arrays.asList(0xA1, 0xB2));
        DisplayConnection c = new DisplayConnection(spi, new LogPin("dc", new ArrayList<>()),
            null, null, null, WireMode.HALF_DUPLEX_PIN);
        assertArrayEquals(new byte[] {(byte) 0xA1, (byte) 0xB2}, c.readData(2));
    }

    @Test
    void halfDuplexHwIsRejectedAndDisabledGateBlocks() throws IOException {
        assertThrows(UnsupportedOperationException.class, () -> new DisplayConnection(
            new FakeTransport(), new LogPin("dc", new ArrayList<>()), null, null, null, WireMode.HALF_DUPLEX_HW));
        FakeTransport spi = new FakeTransport();
        DisplayConnection c = new DisplayConnection(spi, new LogPin("dc", new ArrayList<>()),
            null, null, null, WireMode.SIMPLEX);
        c.disable();
        c.sendCommand(1, new byte[] {2});
        assertTrue(spi.writes.isEmpty());
        assertFalse(c.isEnabled());
    }

    @Test
    void softSpiBitOrderAndRead() throws IOException {
        List<Integer> bits = new ArrayList<>();
        boolean[] mosiLevel = {false};
        OutputPin sck = new OutputPin() {
            @Override public void set(boolean high) { if (high) bits.add(mosiLevel[0] ? 1 : 0); }
            @Override public void close() {}
        };
        OutputPin mosi = new OutputPin() {
            @Override public void set(boolean high) { mosiLevel[0] = high; }
            @Override public void close() {}
        };
        SoftSpi writeOnly = new SoftSpi(sck, mosi, null);
        writeOnly.write(new byte[] {(byte) 0xA5});
        assertEquals(List.of(1, 0, 1, 0, 0, 1, 0, 1), bits);
        assertThrows(IllegalStateException.class, () -> writeOnly.read(1, 0));

        boolean[] pattern = {false, false, true, true, true, true, false, false}; // 0x3C
        int[] k = {0};
        InputPin miso = new LevelPin() {
            @Override public boolean isHigh() { return pattern[k[0]++ % 8]; }
        };
        SoftSpi rw = new SoftSpi(sck, mosi, miso);
        assertEquals(0x3C, rw.read(1, 0)[0] & 0xFF);
    }

    @Test
    void mockRecordsCalls() throws IOException {
        MockDisplayConnection m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN);
        m.queueRead((byte) 7, (byte) 8);
        m.reset();
        m.sendCommand(0x12, new byte[] {1});
        m.waitBusy(false, 100);
        assertEquals(List.of(0x12), m.commands());
        assertArrayEquals(new byte[] {7, 8}, m.readData(2));
        assertThrows(UnsupportedOperationException.class, () -> new MockDisplayConnection().readData(1));
    }
}
