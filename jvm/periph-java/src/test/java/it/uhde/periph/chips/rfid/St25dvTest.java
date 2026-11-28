package it.uhde.periph.chips.rfid;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class St25dvTest {

    private static MockConnection mock() {
        MockConnection c = new MockConnection();
        c.setAddressWidth(2);
        return c;
    }

    private static MockConnection system(int icRef, int memLo, int memHi) {
        MockConnection s = mock();
        s.setRegister(0x0014, memLo, memHi, 0x03, icRef);
        s.setRegister(0x0018, 0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0);
        s.setRegister(0x0020, 0x12);
        s.setRegister(0x0000, 0x88);
        s.setRegister(0x0002, 0x01);
        return s;
    }

    private static int reg(MockConnection c, int a) {
        Integer v = c.registers().get(a);
        return v == null ? 0 : v;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static final class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;
        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (h == handler) handler = null; }
        @Override public boolean isHigh() { return true; }
        @Override public void close() {}
    }

    @Test
    void minimalReadWrite() throws Exception {
        MockConnection user = mock();
        MockConnection sys = system(0x26, 0xFF, 0x01);
        St25dvMinimal tag = new St25dvMinimal(user, sys);
        assertEquals(1, sys.writes().size());
        assertArrayEquals(bytes(0x00, 0x14), sys.writes().get(0));
        assertTrue(user.writes().isEmpty());
        assertEquals(2048, tag.size);

        user.setRegister(0x0100, 1, 2, 3);
        assertArrayEquals(bytes(1, 2, 3), tag.read(0x0100, 3));
        assertThrows(IllegalArgumentException.class, () -> tag.read(2047, 2));

        byte[] data = new byte[40];
        for (int i = 0; i < 40; i++) data[i] = (byte) i;
        user.writes().clear();
        tag.write(0x0008, data);
        List<byte[]> frames = new ArrayList<>();
        int polls = 0;
        for (byte[] w : user.writes()) if (w.length > 2) frames.add(w); else polls++;
        assertEquals(3, frames.size());
        assertEquals(3, polls);
        assertEquals(10, frames.get(0).length);
        assertEquals(0x08, frames.get(0)[1]);
        assertEquals(0x10, frames.get(1)[1]);
        assertEquals(8, frames.get(1)[2]);
        assertEquals(0x20, frames.get(2)[1]);
        assertEquals(24, frames.get(2)[2]);
        for (int i = 0; i < 40; i++) assertEquals(i, reg(user, 0x08 + i));
        assertThrows(IllegalArgumentException.class, () -> tag.write(2040, new byte[9]));
        user.failNextWrites(1000, 1);
        assertThrows(IOException.class, () -> tag.write(0, bytes(1)));
        user.failNextWrites(0, 0);

        user.setRegister(0x0040, 0x77);
        user.failNextWrites(2, 0);
        assertArrayEquals(bytes(0x77), tag.read(0x0040, 1));
        user.failNextWrites(3, 0);
        assertThrows(IOException.class, () -> tag.read(0x0040, 1));
        user.failNextWrites(0, 0);
    }

    @Test
    void identificationAndPassword() throws Exception {
        assertEquals(512, new St25dvFull(mock(), system(0x24, 0x7F, 0x00)).memorySize());
        assertEquals(8192, new St25dvFull(mock(), system(0x26, 0xFF, 0x07)).memorySize());
        MockConnection user = mock();
        MockConnection sys = system(0x26, 0xFF, 0x01);
        St25dvFull tag = new St25dvFull(user, sys);
        assertEquals(0x26, tag.icReference());
        assertArrayEquals(bytes(0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11), tag.uid());
        assertEquals(0x12, tag.icRevision());
        assertArrayEquals(bytes(0x26), tag.readSystem(0x0017, 1));
        sys.writes().clear();
        tag.writeSystem(0x0001, 5);
        assertArrayEquals(bytes(0x00, 0x01, 0x05), sys.writes().get(0));
        assertArrayEquals(bytes(0x00, 0x00), sys.writes().get(1));

        byte[] pw = bytes(1, 2, 3, 4, 5, 6, 7, 8);
        byte[] frame = bytes(0x09, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0x09, 1, 2, 3, 4, 5, 6, 7, 8);
        user.setRegister(0x2004, 0x01);
        sys.writes().clear();
        assertTrue(tag.presentPassword(pw));
        assertArrayEquals(frame, sys.writes().get(0));
        assertTrue(tag.sessionOpen());
        user.setRegister(0x2004, 0x00);
        assertFalse(tag.presentPassword(pw));
        assertThrows(IllegalArgumentException.class, () -> tag.presentPassword(bytes(0)));
        assertThrows(IOException.class, () -> tag.writePassword(pw));
        user.setRegister(0x2004, 0x01);
        sys.writes().clear();
        tag.writePassword(pw);
        frame[10] = 0x07;
        assertArrayEquals(frame, sys.writes().get(0));
    }

    @Test
    void configuration() throws Exception {
        MockConnection user = mock();
        MockConnection sys = system(0x26, 0xFF, 0x01);
        St25dvFull tag = new St25dvFull(user, sys);
        sys.writes().clear();
        tag.setAreaEnd(2, 0x1F);
        assertArrayEquals(bytes(0x00, 0x07, 0x1F), sys.writes().get(0));
        assertThrows(IllegalArgumentException.class, () -> tag.setAreaEnd(4, 1));
        sys.setRegister(0x000B, 0x00);
        tag.setAreaProtection(3, 0x05, 0x03);
        assertEquals(0x05, reg(sys, 0x0008));
        assertEquals(0x30, reg(sys, 0x000B));
        tag.setAreaProtection(2, 0x0C, 0x01);
        assertEquals(0x34, reg(sys, 0x000B));
        assertEquals(new St25dvFull.AreaProtection(0x05, 0x03), tag.getAreaProtection(3));
        assertThrows(IllegalArgumentException.class, () -> tag.setAreaProtection(5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> tag.setAreaProtection(1, 0, 4));

        tag.setGpo(St25dvFull.GPO_FIELD_CHANGE | St25dvFull.GPO_RF_WRITE);
        assertEquals(0xC8, reg(sys, 0x0000));
        tag.setGpo(St25dvFull.GPO_FIELD_CHANGE, false);
        assertEquals(0x08, tag.getGpo());
        tag.setGpoRuntime(true);
        assertEquals(0x80, reg(user, 0x2000));
        tag.setGpoRuntime(false);
        assertEquals(0x00, reg(user, 0x2000));
        tag.setInterruptPulse(3);
        assertEquals(3, reg(sys, 0x0001));
        assertThrows(IllegalArgumentException.class, () -> tag.setInterruptPulse(8));

        user.setRegister(0x2002, 0x0C);
        assertTrue(tag.fieldPresent());
        user.setRegister(0x2002, 0x08);
        assertFalse(tag.fieldPresent());
        sys.setRegister(0x0002, 0x01);
        sys.writes().clear();
        tag.setEnergyHarvesting(true);
        assertEquals(1, sys.writes().size());
        assertEquals(0x01, reg(user, 0x2002));
        tag.setEnergyHarvesting(false, true);
        assertEquals(0x00, reg(sys, 0x0002));
        assertEquals(0x00, reg(user, 0x2002));
        tag.setRfState(false, true);
        assertEquals(0x02, reg(user, 0x2003));
        tag.setRfState(true, false);
        assertEquals(0x01, reg(user, 0x2003));

        tag.lockCcfile(true);
        assertEquals(0x03, reg(sys, 0x000C));
        tag.lockCcfile(false);
        assertEquals(0x00, reg(sys, 0x000C));
        tag.setMailboxWatchdog(4);
        assertEquals(4, reg(sys, 0x000E));
        assertThrows(IllegalArgumentException.class, () -> tag.setMailboxWatchdog(8));
    }

    @Test
    void mailbox() throws Exception {
        MockConnection user = mock();
        MockConnection sys = system(0x26, 0xFF, 0x01);
        St25dvFull tag = new St25dvFull(user, sys);
        sys.setRegister(0x000D, 0x00);
        tag.enableMailbox();
        assertEquals(0x01, reg(sys, 0x000D));
        assertEquals(0x01, reg(user, 0x2006));
        sys.writes().clear();
        tag.enableMailbox();
        for (byte[] w : sys.writes()) assertEquals(2, w.length);
        user.setRegister(0x2006, 0x01 | 0x04 | 0x20);
        St25dvFull.MailboxStatus st = tag.mailboxStatus();
        assertTrue(st.enabled() && st.rfPut() && !st.hostPut() && st.rfMiss() && !st.hostMiss());
        assertThrows(IOException.class, () -> tag.mailboxWrite(bytes(1)));
        user.setRegister(0x2006, 0x01);
        byte[] msg = new byte[100];
        for (int i = 0; i < 100; i++) msg[i] = (byte) i;
        user.writes().clear();
        tag.mailboxWrite(msg);
        byte[] w = user.writes().get(user.writes().size() - 1);
        assertEquals(102, w.length);
        assertEquals(0x20, w[0]);
        assertEquals(0x08, w[1]);
        assertEquals(99, w[101]);
        assertThrows(IllegalArgumentException.class, () -> tag.mailboxWrite(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> tag.mailboxWrite(new byte[257]));
        user.setRegister(0x2006, 0x00);
        assertThrows(IOException.class, () -> tag.mailboxWrite(bytes(1)));
        assertEquals(0, tag.mailboxRead().length);
        user.setRegister(0x2006, 0x05);
        user.setRegister(0x2007, 19);
        for (int i = 0; i < 20; i++) user.setRegister(0x2008 + i, 0xA0 + i);
        byte[] r = tag.mailboxRead();
        assertEquals(20, r.length);
        assertEquals((byte) 0xB3, r[19]);
        tag.disableMailbox();
        assertEquals(0x00, reg(user, 0x2006));
    }

    @Test
    void ndef() throws Exception {
        MockConnection user = mock();
        St25dvFull tag = new St25dvFull(user, system(0x26, 0xFF, 0x01));
        byte[] ndef = bytes(0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48);
        tag.writeNdef(ndef);
        assertEquals(0xE1, reg(user, 0));
        assertEquals(0xFF, reg(user, 2));
        assertEquals(0x01, reg(user, 3));
        assertEquals(0x03, reg(user, 4));
        assertEquals(ndef.length, reg(user, 5));
        assertEquals(0xFE, reg(user, 6 + ndef.length));
        assertArrayEquals(ndef, tag.readNdef());
        byte[] longMsg = new byte[300];
        tag.writeNdef(longMsg);
        assertEquals(0xFF, reg(user, 5));
        assertEquals(0x01, reg(user, 6));
        assertEquals(0x2C, reg(user, 7));
        assertArrayEquals(longMsg, tag.readNdef());
        assertThrows(IllegalArgumentException.class, () -> tag.writeNdef(new byte[2048]));
        user.setRegister(0x0000, 0x00);
        assertEquals(0, tag.readNdef().length);

        MockConnection u4 = mock();
        new St25dvFull(u4, system(0x24, 0x7F, 0x00)).writeNdef(bytes(1));
        assertEquals(0x40, reg(u4, 2));
        MockConnection u64 = mock();
        St25dvFull t64 = new St25dvFull(u64, system(0x26, 0xFF, 0x07));
        t64.writeNdef(bytes(1, 2));
        assertEquals(0xE2, reg(u64, 0));
        assertEquals(0x03, reg(u64, 6));
        assertEquals(0xFF, reg(u64, 7));
        assertArrayEquals(bytes(1, 2), t64.readNdef());

        user.failNextWrites(2, 0);
        tag.waitWriteComplete();
        user.failNextWrites(1000, 0);
        assertThrows(IOException.class, tag::waitWriteComplete);
        user.failNextWrites(0, 0);
    }

    @Test
    void interrupts() throws Exception {
        MockConnection user = mock();
        MockConnection sys = system(0x26, 0xFF, 0x01);
        St25dvFull tag = new St25dvFull(user, sys);
        sys.setRegister(0x0000, 0x80);
        tag.enableInterrupt(St25dvFull.SOURCE_FIELD_RISING | St25dvFull.SOURCE_RF_WRITE);
        assertEquals(0xC8, reg(sys, 0x0000));
        assertEquals(0x80, reg(user, 0x2000));
        tag.enableInterrupt(St25dvFull.SOURCE_RF_PUT_MSG | St25dvFull.SOURCE_RF_USER);
        assertEquals(0xD9, reg(sys, 0x0000));
        tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE);
        assertEquals(0x99, reg(sys, 0x0000));
        user.setRegister(0x2005, 0x90);
        assertEquals(0x90, tag.pollInterrupt());

        FakePin pin = new FakePin();
        List<Integer> got = new ArrayList<>();
        tag.onInterrupt(got::add, pin);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        user.setRegister(0x2005, 0x10);
        pin.handler.onEdge();
        user.setRegister(0x2005, 0x00);
        pin.handler.onEdge();
        assertEquals(List.of(0x10), got);
        tag.offInterrupt();
        assertNull(pin.handler);

        St25dvFull jf = new St25dvFull(mock(), system(0x26, 0xFF, 0x01), true);
        FakePin pinJf = new FakePin();
        jf.onInterrupt(s -> {}, pinJf);
        assertEquals(EdgeTrigger.RISING, pinJf.trigger);
        jf.offInterrupt();
    }
}
