package it.uhde.periph.chips.comms;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rfm9xTest {

    private static final int REG_VERSION = 0x42;
    private static final int EXPECTED_VERSION = 0x12;

    private static class FakeOutputPin implements OutputPin {
        final List<Boolean> calls = new ArrayList<>();
        @Override public void set(boolean high) { calls.add(high); }
        @Override public void close() {}
    }

    private static class FakeInputPin implements InputPin {
        final List<EdgeHandler> handlers = new CopyOnWriteArrayList<>();
        @Override public void onEdge(EdgeHandler handler, EdgeTrigger trigger) { handlers.add(handler); }
        @Override public void offEdge(EdgeHandler handler) { handlers.remove(handler); }
        @Override public void close() {}
        void fire() { for (EdgeHandler h : handlers) h.onEdge(); }
    }

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(REG_VERSION, EXPECTED_VERSION);
        return c;
    }

    @Test
    void initAndSend() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Minimal sensor = new Rfm95Minimal(connection, 915_000_000L);
        assertTrue(hasWrite(connection, 0x86, 0xE4));
        assertTrue(hasWrite(connection, 0x87, 0xC0));
        assertTrue(hasWrite(connection, 0x88, 0x00));
        assertTrue(hasWrite(connection, 0x9D, 0x72));
        assertTrue(hasWrite(connection, 0x9E, 0x77));
        assertTrue(hasWrite(connection, 0x89, 0x8F));

        MockConnection badVersionConn = new MockConnection();
        badVersionConn.setRegister(REG_VERSION, 0x99);
        assertThrows(IOException.class, () -> new Rfm95Minimal(badVersionConn, 915_000_000L));

        assertThrows(IllegalArgumentException.class, () -> new Rfm95Minimal(connection, 433_000_000L));

        MockConnection lfConnection = newConnection();
        new Rfm96Minimal(lfConnection, 433_000_000L);

        // send([0xDE, 0xAD, 0xBE]): standby first (writes[0]), then FIFO/TX sequence.
        connection.setRegister(0x12, 0x08); // IRQ_FLAGS: TX_DONE set, poll succeeds immediately
        int base = connection.writes().size();
        sensor.send(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE});
        List<byte[]> w = connection.writes();
        assertArrayEquals(new byte[]{(byte) 0x8D, (byte) 0x80}, w.get(base + 1));
        assertArrayEquals(new byte[]{(byte) 0x80, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE}, w.get(base + 2));
        assertArrayEquals(new byte[]{(byte) 0xA2, 0x03}, w.get(base + 3));
        assertArrayEquals(new byte[]{(byte) 0xC0, 0x40}, w.get(base + 4));
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x83}, w.get(base + 5));
        assertTrue(hasWrite(connection, 0x92, 0x08));
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x81}, w.get(w.size() - 1));

        // NOTE: unlike Python (ValueError), send() here silently truncates an
        // over-255-byte payload to 255 bytes instead of rejecting it -- same
        // no-exceptions-style pattern as this file's other clamp-not-throw
        // behaviors (configure()'s sf/bandwidth handling).
        connection.setRegister(0x12, 0x08);
        sensor.send(new byte[256]);
        assertTrue(hasWrite(connection, 0xA2, 255));
    }

    @Test
    void receive() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Minimal sensor = new Rfm95Minimal(connection, 915_000_000L);

        connection.setRegister(0x12, 0x40);
        connection.setRegister(0x10, 0x00);
        connection.setRegister(0x13, 0x03);
        connection.setRegister(0x00, 0xAA, 0xBB, 0xCC);
        int base = connection.writes().size();
        byte[] payload = sensor.receive(100);
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x86}, connection.writes().get(base + 2));
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, payload);
        assertTrue(hasWrite(connection, 0x92, 0x40));

        connection.setRegister(0x12, 0x00);
        assertNull(sensor.receive(10));
    }

    @Test
    void configureSetFrequencySetTxPower() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Full full = new Rfm95Full(connection, 915_000_000L);

        full.configure(9, 125.0f, 5, true);
        assertTrue(hasWrite(connection, 0xB1, 0x03));
        assertTrue(hasWrite(connection, 0x9D, 0x72));
        assertTrue(hasWrite(connection, 0x9E, 0x97));

        // RFM97's maxSf is 9 -- configure() clamps out-of-range sf rather
        // than throwing (same no-exceptions-style pattern as cpp/nodejs/rust).
        MockConnection rfm97Connection = newConnection();
        Rfm97Full rfm97 = new Rfm97Full(rfm97Connection, 915_000_000L);
        rfm97.configure(12, 125.0f, 5, true);
        assertTrue(hasWrite(rfm97Connection, 0x9E, 0x97));  // sf clamped 12 -> 9

        full.setFrequency(868_000_000L);
        assertTrue(hasWrite(connection, 0x86, 0xD9));
        assertTrue(hasWrite(connection, 0x87, 0x00));

        full.setTxPower(20, true);
        assertTrue(hasWrite(connection, 0xCD, 0x87));
        assertTrue(hasWrite(connection, 0x8B, 0x3B));
        assertTrue(hasWrite(connection, 0x89, 0x8F));

        full.setTxPower(10, false);
        assertTrue(hasWrite(connection, 0x89, 0x7A));
    }

    @Test
    void telemetryAndPowerControl() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Full full = new Rfm95Full(connection, 915_000_000L);

        full.standby();
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x81}, connection.writes().get(connection.writes().size() - 1));
        full.sleep();
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x80}, connection.writes().get(connection.writes().size() - 1));

        assertEquals(0x12, full.version());
        connection.setRegister(0x1B, 100);
        assertEquals(-137 + 100, full.rssi());
        connection.setRegister(0x1A, 90);
        assertEquals(-137 + 90, full.lastPacketRssi());
        connection.setRegister(0x19, 20);
        assertTrue(Math.abs(full.lastPacketSnr() - 5.0f) < 1e-6f);
        connection.setRegister(0x19, 0xF4);
        assertTrue(Math.abs(full.lastPacketSnr() - (-3.0f)) < 1e-6f);
    }

    @Test
    void receiveContinuousAndReadPacket() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Full full = new Rfm95Full(connection, 915_000_000L);

        full.receiveContinuous();
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x85}, connection.writes().get(connection.writes().size() - 1));

        connection.setRegister(0x12, 0x40);
        connection.setRegister(0x10, 0x00);
        connection.setRegister(0x13, 0x02);
        connection.setRegister(0x00, 0x11, 0x22);
        assertArrayEquals(new byte[]{0x11, 0x22}, full.readPacket());

        connection.setRegister(0x12, 0x00);
        assertNull(full.readPacket());

        full.stopReceive();
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x81}, connection.writes().get(connection.writes().size() - 1));
    }

    @Test
    void receiveInterrupt() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Full full = new Rfm95Full(connection, 915_000_000L);
        assertThrows(IllegalStateException.class, () -> full.receive(2000, true));

        MockConnection dio0Connection = newConnection();
        dio0Connection.setRegister(0x12, 0x40);
        dio0Connection.setRegister(0x10, 0x00);
        dio0Connection.setRegister(0x13, 0x01);
        dio0Connection.setRegister(0x00, 0x99);
        FakeInputPin dio0 = new FakeInputPin();
        Rfm95Full fullWithDio0 = new Rfm95Full(dio0Connection, 915_000_000L, null, dio0);

        Thread firer = new Thread(() -> {
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            dio0.fire();
        });
        firer.start();
        byte[] payload = fullWithDio0.receive(2000, true);
        firer.join();
        assertArrayEquals(new byte[]{(byte) 0x99}, payload);
    }

    @Test
    void reset() throws Exception {
        MockConnection connection = newConnection();
        Rfm95Full full = new Rfm95Full(connection, 915_000_000L);
        // No resetPin: reset() falls back to a POR wait, not an error, unlike
        // AD7705's reset() -- this driver documents that as the fallback.
        full.reset();

        FakeOutputPin resetPin = new FakeOutputPin();
        MockConnection connection2 = newConnection();
        Rfm95Full withReset = new Rfm95Full(connection2, 915_000_000L, resetPin, null);
        assertEquals(List.of(false, true), resetPin.calls);
        resetPin.calls.clear();
        withReset.reset();
        assertEquals(List.of(false, true), resetPin.calls);
    }

    private static boolean hasWrite(MockConnection connection, int... bytes) {
        byte[] want = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) want[i] = (byte) bytes[i];
        for (byte[] w : connection.writes()) {
            if (java.util.Arrays.equals(w, want)) return true;
        }
        return false;
    }
}
