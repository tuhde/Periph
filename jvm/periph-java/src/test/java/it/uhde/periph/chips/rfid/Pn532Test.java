package it.uhde.periph.chips.rfid;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Pn532Test {

    static final byte[] ACK = {0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF, 0x00};
    static final byte[] NACK = {0x00, 0x00, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};
    static final byte[] ERROR_FRAME = {0x00, 0x00, (byte) 0xFF, 0x01, (byte) 0xFF, 0x7F, (byte) 0x81, 0x00};

    static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    static byte[] frame(int tfi, byte[] data) {
        byte[] f = new byte[data.length + 8];
        int len = data.length + 1;
        f[2] = (byte) 0xFF;
        f[3] = (byte) len;
        f[4] = (byte) (0x100 - len);
        f[5] = (byte) tfi;
        int sum = tfi;
        for (int i = 0; i < data.length; i++) {
            f[6 + i] = data[i];
            sum += data[i] & 0xFF;
        }
        f[6 + data.length] = (byte) (0x100 - (sum & 0xFF));
        return f;
    }

    static byte[] concat(byte[] a, byte[] c) {
        byte[] r = Arrays.copyOf(a, a.length + c.length);
        System.arraycopy(c, 0, r, a.length, c.length);
        return r;
    }

    record Cmd(int cmd, byte[] params) {
        boolean is(int c, int... p) { return cmd == c && Arrays.equals(params, b(p)); }
    }

    /** Simulates the PN532 I²C frame protocol: ACK, response, NACK resend, ACK abort, status byte. */
    static class Sim extends MockConnection {
        final Deque<byte[]> queue = new ArrayDeque<>();
        byte[] last;
        final List<Cmd> commands = new ArrayList<>();
        final List<byte[]> written = new ArrayList<>();
        final List<Integer> reads = new ArrayList<>();
        boolean card = true, noAck, noResponse, errorFrame, badDcs;
        int ic = 0x32, status = 0, failWrites = 0;
        byte[] uid = b(0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66);
        byte[] listResponse;
        final Map<Integer, Integer> regs = new HashMap<>();
        InputPin pin;

        Cmd last() { return commands.get(commands.size() - 1); }

        byte[] respond(int cmd, byte[] p) {
            switch (cmd) {
                case 0x02: return b(ic, 0x01, 0x06, 0x07);
                case 0x04: return b(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x10, 0x01);
                case 0x4A:
                    if (listResponse != null) return listResponse;
                    if (!card) return b(0);
                    return concat(b(1, 1, 0x00, 0x44, 0x00, uid.length), uid);
                case 0x52: case 0x54: case 0x44: case 0x16: return b(status);
                case 0x40: {
                    if (status != 0) return b(status);
                    if (p[1] == 0x30) {
                        byte[] r = new byte[17];
                        for (int i = 0; i < 16; i++) r[1 + i] = (byte) (p[2] + i);
                        return r;
                    }
                    return b(0);
                }
                case 0x42: {
                    byte[] r = new byte[p.length + 1];
                    for (int i = 0; i < p.length; i++) r[1 + i] = p[p.length - 1 - i];
                    return r;
                }
                case 0x06: return b(regs.getOrDefault(((p[0] & 0xFF) << 8) | (p[1] & 0xFF), 0));
                case 0x08: regs.put(((p[0] & 0xFF) << 8) | (p[1] & 0xFF), p[2] & 0xFF); return new byte[0];
                case 0x00: return p[0] == 0 ? p.clone() : b(0);
                default: return new byte[0];
            }
        }

        @Override
        public void write(byte[] data) throws IOException {
            written.add(data.clone());
            if (failWrites > 0) {
                failWrites--;
                throw new IOException("NACK");
            }
            if (Arrays.equals(data, ACK)) { queue.clear(); return; }
            if (Arrays.equals(data, NACK)) { if (last != null) queue.add(last); return; }
            int ln = data[3] & 0xFF;
            Cmd c = new Cmd(data[6] & 0xFF, Arrays.copyOfRange(data, 7, 5 + ln));
            commands.add(c);
            if (noAck) return;
            queue.clear();
            queue.add(ACK);
            if (noResponse) return;
            if (errorFrame) { queue.add(ERROR_FRAME); return; }
            byte[] f = frame(0xD5, concat(b(c.cmd + 1), respond(c.cmd, c.params)));
            if (badDcs) f[f.length - 2]++;
            queue.add(f);
        }

        @Override
        public byte[] read(int n) {
            reads.add(n);
            byte[] out = new byte[n];
            if (queue.isEmpty()) return out;
            out[0] = 0x01;
            if (n == 1) return out;
            byte[] item = queue.poll();
            if (!Arrays.equals(item, ACK)) last = item;
            for (int i = 1; i < n; i++) out[i] = i - 1 < item.length ? item[i - 1] : (byte) 0xFF;
            return out;
        }

        @Override
        public InputPin intPin() { return pin; }
    }

    @Test
    void frameMatchesDatasheetVector() {
        assertArrayEquals(b(0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00), Pn532Minimal.frame(0x02, new byte[0]));
    }

    @Test
    void minimalInitAndReadUid() throws IOException {
        Sim sim = new Sim();
        Pn532Minimal m = new Pn532Minimal(sim);
        m.init();
        assertEquals(3, sim.commands.size());
        assertTrue(sim.commands.get(1).is(0x14, 0x01, 0x14, 0x01));
        assertTrue(sim.commands.get(2).is(0x32, 0x05, 0xFF, 0x01, 0x02));
        assertArrayEquals(NACK, sim.written.get(1));
        assertEquals(3, sim.reads.stream().filter(r -> r == 7).count());
        assertTrue(sim.reads.contains(14));
        assertArrayEquals(sim.uid, m.readUid());
        assertTrue(sim.commands.get(3).is(0x4A, 0x01, 0x00));
        assertTrue(sim.commands.get(4).is(0x52, 0x01));
        sim.card = false;
        assertNull(m.readUid());
        sim.noResponse = true;
        assertNull(m.readUid(0.01));
        assertArrayEquals(ACK, sim.written.get(sim.written.size() - 1));
        sim.noResponse = false;
        sim.errorFrame = true;
        assertThrows(Pn532Minimal.Pn532Exception.class, m::readUid);
        sim.errorFrame = false;
        sim.card = true;
        sim.badDcs = true;
        assertThrows(Pn532Minimal.Pn532Exception.class, m::readUid);
    }

    @Test
    void minimalErrors() throws IOException {
        Sim sim = new Sim();
        sim.ic = 0x31;
        assertThrows(Pn532Minimal.Pn532Exception.class, () -> new Pn532Minimal(sim).init());
        Sim retry = new Sim();
        retry.failWrites = 3;
        new Pn532Minimal(retry).init();
        assertEquals(3, retry.commands.size());
        Sim noAck = new Sim();
        noAck.noAck = true;
        assertThrows(Pn532Minimal.Pn532Exception.class, () -> new Pn532Minimal(noAck).init());
        assertEquals(3, noAck.commands.size());
    }

    @Test
    void fullInfoAndList() throws IOException {
        Sim sim = new Sim();
        Pn532Full f = new Pn532Full(sim);
        assertEquals(new Pn532Full.FirmwareVersion(0x32, 1, 6, 7, true, true, true), f.firmwareVersion());
        assertEquals(new Pn532Full.GeneralStatus(0, true, 1, 1), f.generalStatus());
        sim.listResponse = b(2, 1, 0x00, 0x04, 0x20, 4, 1, 2, 3, 4, 5, 0x75, 0x77, 0x81, 0x02,
                2, 0x00, 0x44, 0x00, 7, 9, 9, 9, 9, 9, 9, 9);
        List<Pn532Full.Target> t = f.listPassiveTargets(Pn532Full.BAUD_106_A, 2, new byte[0], 1.0);
        assertEquals(2, t.size());
        assertEquals(1, t.get(0).tg());
        assertEquals(0x0004, t.get(0).sensRes());
        assertEquals(0x20, t.get(0).selRes());
        assertArrayEquals(b(1, 2, 3, 4), t.get(0).uid());
        assertArrayEquals(b(5, 0x75, 0x77, 0x81, 0x02), t.get(0).ats());
        assertEquals(0x0044, t.get(1).sensRes());
        assertEquals(7, t.get(1).uid().length);
        assertEquals(0, t.get(1).ats().length);
        assertTrue(sim.last().is(0x4A, 0x02, 0x00));
        sim.listResponse = b(1, 1, 20, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC);
        t = f.listPassiveTargets(Pn532Full.BAUD_212_F, 1, b(0x00, 0xFF, 0xFF, 0x01, 0x00), 1.0);
        assertArrayEquals(b(1, 2, 3, 4, 5, 6, 7, 8), t.get(0).uid());
        assertArrayEquals(b(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x12, 0xFC), t.get(0).ats());
        assertTrue(sim.last().is(0x4A, 0x01, 0x01, 0x00, 0xFF, 0xFF, 0x01, 0x00));
        sim.listResponse = b(1, 1, 0x50, 0xA1, 0xA2, 0xA3, 0xA4, 0, 0, 0, 0, 0x81, 0x81, 0x00, 1, 0x00);
        t = f.listPassiveTargets(Pn532Full.BAUD_106_B, 1, b(0x00), 1.0);
        assertArrayEquals(b(0xA1, 0xA2, 0xA3, 0xA4), t.get(0).uid());
        assertArrayEquals(b(0x00), t.get(0).ats());
        sim.listResponse = b(0);
        assertTrue(f.listPassiveTargets().isEmpty());
    }

    @Test
    void fullTagOperations() throws IOException {
        Sim sim = new Sim();
        Pn532Full f = new Pn532Full(sim);
        byte[] block = f.mifareReadBlock(1, 4);
        assertEquals(4, block[0]);
        assertEquals(19, block[15]);
        assertTrue(sim.last().is(0x40, 0x01, 0x30, 0x04));
        f.mifareAuthenticate(1, 7, b(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), sim.uid, false);
        assertTrue(sim.last().is(0x40, 0x01, 0x60, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x33, 0x44, 0x55, 0x66));
        f.mifareAuthenticate(1, 7, new byte[6], b(0xDE, 0xAD, 0xBE, 0xEF), true);
        assertEquals(0x61, sim.last().params()[1]);
        f.mifareWriteBlock(1, 4, new byte[16]);
        assertTrue(sim.last().is(0x40, 0x01, 0xA0, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(4, f.ntagReadPage(1, 4)[0]);
        f.ntagWritePage(1, 5, b(1, 2, 3, 4));
        assertTrue(sim.last().is(0x40, 0x01, 0xA2, 0x05, 1, 2, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> f.ntagWritePage(1, 5, b(1)));
        assertArrayEquals(b(3, 2, 1), f.communicateThru(b(1, 2, 3)));
        sim.status = 0x14;
        Pn532Minimal.Pn532Exception e = assertThrows(Pn532Minimal.Pn532Exception.class, () -> f.dataExchange(1, b(0x30, 0x00)));
        assertEquals(0x14, e.code());
        e = assertThrows(Pn532Minimal.Pn532Exception.class, () -> f.release(1));
        assertEquals(0x14, e.code());
        sim.status = 0;
        f.release(0);
        assertTrue(sim.last().is(0x52, 0x00));
        f.select(1);
        assertTrue(sim.last().is(0x54, 0x01));
        f.deselect(1);
        assertTrue(sim.last().is(0x44, 0x01));
    }

    @Test
    void fullConfigAndRaw() throws IOException {
        Sim sim = new Sim();
        Pn532Full f = new Pn532Full(sim);
        f.setRfField(false, false);
        assertTrue(sim.last().is(0x32, 0x01, 0x00));
        f.setRfField(true, true);
        assertTrue(sim.last().is(0x32, 0x01, 0x03));
        f.setMaxRetries(0xFF);
        assertTrue(sim.last().is(0x32, 0x05, 0xFF, 0x01, 0xFF));
        f.writeRegister(0x6302, 0x5A);
        assertTrue(sim.last().is(0x08, 0x63, 0x02, 0x5A));
        assertEquals(0x5A, f.readRegister(0x6302));
        assertTrue(sim.last().is(0x06, 0x63, 0x02));
        assertTrue(f.selfTest());
        assertTrue(sim.last().is(0x00, 0x00, 'P', 'E', 'R', 'I', 'P', 'H', '0', '1'));
        assertTrue(f.selfTest(0x01));
        assertArrayEquals(b(0x32, 0x01, 0x06, 0x07), f.sendCommand(0x02, new byte[0]));
        f.powerDown();
        assertTrue(sim.last().is(0x16, 0x20));
        f.startCommand(0x02, new byte[0]);
        assertTrue(f.waitReady(0.01));
        assertTrue(f.pollInterrupt());
        assertArrayEquals(b(0x32, 0x01, 0x06, 0x07), f.readResponse(0x02, 1.0));
        assertFalse(f.pollInterrupt());
        assertFalse(f.waitReady(0.005));
    }

    @Test
    void interrupt() {
        Sim sim = new Sim();
        EdgeHandler[] handler = new EdgeHandler[1];
        sim.pin = new InputPin() {
            @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler[0] = h; }
            @Override public void offEdge(EdgeHandler h) { if (handler[0] == h) handler[0] = null; }
            @Override public boolean isHigh() { return true; }
            @Override public void close() {}
        };
        Pn532Full f = new Pn532Full(sim);
        int[] hits = {0};
        f.onInterrupt(() -> hits[0]++);
        assertNotNull(handler[0]);
        handler[0].onEdge();
        assertEquals(1, hits[0]);
        f.offInterrupt();
        assertNull(handler[0]);
    }
}
