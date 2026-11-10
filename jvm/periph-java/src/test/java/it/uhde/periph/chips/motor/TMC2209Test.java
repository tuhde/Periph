package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.OutputPin;
import it.uhde.periph.connection.UartConnectionMock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TMC2209Test {

    private static class Pin implements OutputPin {
        final List<Boolean> levels = new ArrayList<>();
        @Override public void set(boolean high) { levels.add(high); }
        @Override public void close() {}
        long highs() { return levels.stream().filter(b -> b).count(); }
    }

    @Test
    void crcVectors() {
        assertEquals(0x7F, TMC2209Minimal.crc8(new byte[] {5, 0, (byte) 0x80, 0, 0, 1, (byte) 0xC1, 0}, 7));
        assertEquals(0x6F, TMC2209Minimal.crc8(new byte[] {5, 0, 6, 0}, 3));
    }

    @Test
    void initWritesDefaults() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        new TMC2209Minimal(mock);
        assertEquals(0x1C1, mock.lastWrite(0x00));
        assertEquals(0x07, mock.lastWrite(0x01));
        assertEquals(20, mock.lastWrite(0x11));
        assertEquals(0xC10D0024L, mock.lastWrite(0x70));
        assertTrue(mock.datagrams().stream().anyMatch(d -> Arrays.equals(d, new byte[] {5, 0, (byte) 0x80, 0, 0, 1, (byte) 0xC1, 0x7F})));
        assertTrue(mock.datagrams().stream().anyMatch(d -> Arrays.equals(d, new byte[] {5, 0, 6, 0x6F})));
        long chop = mock.lastWrite(0x6C);
        assertEquals(3, chop & 0xF);
        assertEquals(2, (chop >> 15) & 3);
        assertEquals(5, (chop >> 4) & 7);
        assertEquals(4, (chop >> 24) & 0xF);
        assertEquals(1, (chop >> 28) & 1);
        long ih = mock.lastWrite(0x10);
        assertEquals(8, (ih >> 8) & 0x1F);
        assertEquals(2, ih & 0x1F);
        assertEquals(6, (ih >> 16) & 0xF);
    }

    @Test
    void currentAndMotion() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        TMC2209Minimal m = new TMC2209Minimal(mock);
        m.setCurrent(1.0);
        assertEquals(17, (mock.lastWrite(0x10) >> 8) & 0x1F);
        m.setCurrent(5.0);
        assertEquals(31, (mock.lastWrite(0x10) >> 8) & 0x1F);
        m.run(200);
        assertEquals(4474, mock.lastWrite(0x22));
        m.run(-200);
        assertEquals((-4474L) & 0xFFFFFFL, mock.lastWrite(0x22));
        m.stop();
        assertEquals(0, mock.lastWrite(0x22));
        m.disable();
        assertEquals(0, mock.lastWrite(0x6C) & 0xF);
        m.enable();
        assertEquals(3, mock.lastWrite(0x6C) & 0xF);
    }

    @Test
    void statusAndFaults() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        TMC2209Minimal m = new TMC2209Minimal(mock);
        mock.regs().put(0x6F, (1L << 31) | 0x01 | 0x08 | 0x20);
        assertTrue(m.isStandstill());
        assertEquals(TMC2209Minimal.FAULT_OVERTEMP_WARNING | TMC2209Minimal.FAULT_SHORT_GND | TMC2209Minimal.FAULT_SHORT_SUPPLY, m.readFault());
    }

    @Test
    void enPinNoEchoAndErrors() throws Exception {
        Pin pin = new Pin();
        TMC2209Minimal m = new TMC2209Minimal(new UartConnectionMock(), 0.11, 0, pin, true);
        assertEquals(List.of(true), pin.levels);
        m.enable();
        assertFalse(pin.levels.get(pin.levels.size() - 1));

        UartConnectionMock ne = new UartConnectionMock(false);
        new TMC2209Minimal(ne, 0.11, 0, null, false);
        assertEquals(0x1C1, ne.lastWrite(0x00));

        UartConnectionMock bad = new UartConnectionMock();
        bad.regs().put(0x06, 0L);
        assertThrows(IOException.class, () -> new TMC2209Minimal(bad));

        UartConnectionMock flaky = new UartConnectionMock();
        flaky.corruptNextReply();
        new TMC2209Minimal(flaky);
        assertEquals(0x1C1, flaky.lastWrite(0x00));
    }

    @Test
    void fullConfiguration() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        TMC2209Full f = new TMC2209Full(mock);
        f.setMicrosteps(256);
        assertEquals(0, (mock.lastWrite(0x6C) >> 24) & 0xF);
        f.setMicrosteps(1);
        assertEquals(8, (mock.lastWrite(0x6C) >> 24) & 0xF);
        assertThrows(IllegalArgumentException.class, () -> f.setMicrosteps(3));
        f.setMicrosteps(32);
        f.run(100);
        assertEquals(4474, mock.lastWrite(0x22));
        f.setInterpolation(false);
        assertEquals(0, (mock.lastWrite(0x6C) >> 28) & 1);
        f.setChopperMode(true);
        f.setDirectionInverted(true);
        f.setIndexOutput(1);
        long g = mock.lastWrite(0x00);
        assertEquals(0x4, g & 0x4);
        assertEquals(0x8, g & 0x8);
        assertEquals(0x10, g & 0x30);
        f.setDoubleEdge(true);
        f.setDriverProtection(false, true);
        long c = mock.lastWrite(0x6C);
        assertEquals(1, (c >> 29) & 1);
        assertEquals(1, (c >> 31) & 1);
        assertEquals(0, (c >> 30) & 1);
        f.setVsense(true);
        assertEquals(1, (mock.lastWrite(0x6C) >> 17) & 1);
        f.setHoldCurrent(0.2);
        assertEquals(6, mock.lastWrite(0x10) & 0x1F);
        f.setCurrentDelay(2, 100);
        assertEquals(2, (mock.lastWrite(0x10) >> 16) & 0xF);
        assertEquals(100, mock.lastWrite(0x11));
        f.setStealthchopThreshold(300);
        assertEquals(300, mock.lastWrite(0x13));
        f.configureChopper(4, 1, 3, 2);
        c = mock.lastWrite(0x6C);
        assertEquals(4, c & 0xF);
        assertEquals(1, (c >> 15) & 3);
        assertEquals(3, (c >> 4) & 7);
        assertEquals(2, (c >> 7) & 0xF);
        f.configureStealthchop(true, false, 40, 5, 1, 4, 12);
        long p = mock.lastWrite(0x70);
        assertEquals(40, p & 0xFF);
        assertEquals(5, (p >> 8) & 0xFF);
        assertEquals(1, (p >> 16) & 3);
        assertEquals(1, (p >> 18) & 1);
        assertEquals(0, (p >> 19) & 1);
        assertEquals(4, (p >> 24) & 0xF);
        assertEquals(12, p >> 28);
        f.setFreewheel(1);
        assertEquals(1, (mock.lastWrite(0x70) >> 20) & 3);
        f.setSendDelay(4);
        assertEquals(4 << 8, mock.lastWrite(0x03));
        assertThrows(IllegalArgumentException.class, () -> f.setSendDelay(16));
        f.setInternalRsense(true);
        assertThrows(IllegalStateException.class, () -> f.setCurrent(0.5));
        f.setInternalRsense(false);
    }

    @Test
    void fullReadbacks() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        TMC2209Full f = new TMC2209Full(mock);
        mock.regs().put(0x6A, 513L);
        mock.regs().put(0x6B, (0x1FFL << 16) | 0x100);
        mock.regs().put(0x12, 1234L);
        mock.regs().put(0x6F, (1L << 31) | (1L << 30) | (17L << 16) | (0x5L << 8) | 0x02);
        mock.regs().put(0x71, (0x1FEL << 16) | 77);
        mock.regs().put(0x72, (9L << 16) | 33);
        mock.regs().put(0x01, 0x03L);
        mock.regs().put(0x05, 0x123456L);
        assertEquals(513, f.microstepPosition());
        assertEquals(new TMC2209Full.CoilCurrents(-256, -1), f.readCoilCurrents());
        assertEquals(1234, f.readTstep());
        assertEquals(17, f.readCurrentScale());
        assertEquals(5, f.readTemperatureFlags());
        TMC2209Full.Status st = f.readStatus();
        assertTrue(st.stst() && st.stealth() && st.ot() && st.t120() && st.t150() && !st.t143());
        assertEquals(17, st.csActual());
        assertEquals(new TMC2209Full.GlobalStatus(true, true, false), f.readGlobalStatus(false));
        f.readGlobalStatus(true);
        assertEquals(0x07, mock.lastWrite(0x01));
        assertEquals(new TMC2209Full.PwmAuto(77, -2, 33, 9), f.readPwmAuto());
        mock.regs().put(0x06, 0x21000000L | 0x04 | 0x10 | 0x200);
        TMC2209Full.Ioin io = f.readIoin();
        assertTrue(io.ms1() && io.diag() && io.dir());
        assertFalse(io.ms2());
        assertEquals(0x21, io.version());
        assertEquals(0x123456, f.readOtp());
        assertEquals(mock.regs().get(0x02).intValue(), f.readIfcnt());
        assertEquals(513, f.readRegister(0x6A));
        assertThrows(IllegalArgumentException.class, () -> f.readRegister(0x10));
        f.writeRegister(0x13, 5);
        assertEquals(5, mock.lastWrite(0x13));
        assertThrows(IllegalArgumentException.class, () -> f.writeRegister(0x04, 1));
    }

    @Test
    void stepPulsesAndIdentity() throws Exception {
        Pin step = new Pin();
        Pin dir = new Pin();
        TMC2209Full f = new TMC2209Full(new UartConnectionMock(), 0.11, 0, null, true, step, dir, null, false, 12_000_000L);
        f.step(3, 0);
        assertEquals(3, step.highs());
        assertTrue(dir.levels.get(dir.levels.size() - 1));
        f.step(-2, 0);
        assertFalse(dir.levels.get(dir.levels.size() - 1));
        TMC2209Full nopin = new TMC2209Full(new UartConnectionMock());
        assertThrows(IllegalStateException.class, () -> nopin.step(1, 0));
    }

    @Test
    void rejectsTmc2208AndChecksAddress() throws Exception {
        UartConnectionMock old = new UartConnectionMock();
        old.regs().put(0x06, 0x20000100L);
        assertThrows(IOException.class, () -> new TMC2209Minimal(old));

        UartConnectionMock am = new UartConnectionMock();
        new TMC2209Minimal(am, 0.11, 2, null, true);
        assertTrue(am.datagrams().stream().allMatch(d -> d[1] == 2));
        byte[] gconf = am.datagrams().get(2);
        assertEquals(TMC2209Minimal.crc8(gconf, 7), gconf[7] & 0xFF);
        assertThrows(IllegalArgumentException.class, () -> new TMC2209Minimal(new UartConnectionMock(), 0.11, 4, null, true));
        assertThrows(IllegalArgumentException.class, () -> new TMC2209Minimal(new UartConnectionMock(), 0.11, -1, null, true));
    }

    @Test
    void multiNodeWritesSenddelayFirst() throws Exception {
        UartConnectionMock mn = new UartConnectionMock();
        new TMC2209Full(mn, 0.11, 0, null, true, null, null, null, true, 12_000_000L);
        assertEquals(0x03, mn.regWrites().get(0)[0]);
        assertEquals(2L << 8, mn.regWrites().get(0)[1]);
        assertEquals(7L, mn.regs().get(0x02));
    }

    private static class DiagPin implements InputPin {
        final boolean level;
        DiagPin(boolean level) { this.level = level; }
        @Override public boolean isHigh() { return level; }
        @Override public void onEdge(EdgeHandler handler, EdgeTrigger trigger) {}
        @Override public void offEdge(EdgeHandler handler) {}
        @Override public void close() {}
    }

    @Test
    void stallGuardAndCoolStep() throws Exception {
        UartConnectionMock mock = new UartConnectionMock();
        TMC2209Full f = new TMC2209Full(mock, 0.11, 0, null, true, null, null, new DiagPin(true), false, 12_000_000L);
        f.setStallThreshold(60);
        assertEquals(60, mock.lastWrite(0x40));
        assertThrows(IllegalArgumentException.class, () -> f.setStallThreshold(256));
        f.setCoolstepThreshold(500);
        assertEquals(500, mock.lastWrite(0x14));
        assertThrows(IllegalArgumentException.class, () -> f.setCoolstepThreshold(1 << 20));
        f.configureCoolstep(5, 2, 1, 1, 0);
        assertEquals(5 | (1 << 5) | (2 << 8) | (1 << 13), mock.lastWrite(0x42));
        f.configureCoolstep(0, 0, 0, 0, 1);
        assertEquals(1 << 15, mock.lastWrite(0x42));
        assertThrows(IllegalArgumentException.class, () -> f.configureCoolstep(16, 0, 0, 0, 0));
        mock.regs().put(0x41, 0x1FEL);
        assertEquals(510, f.readStallguard());
        assertTrue(f.isStalled());
        TMC2209Full nopin = new TMC2209Full(new UartConnectionMock());
        assertThrows(IllegalStateException.class, nopin::isStalled);
    }
}
