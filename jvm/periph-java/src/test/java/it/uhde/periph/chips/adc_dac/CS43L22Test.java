package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import it.uhde.periph.connection.OutputPin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CS43L22Test {

    private static final class Pin implements OutputPin {
        final List<Boolean> levels = new ArrayList<>();
        @Override public void set(boolean high) { levels.add(high); }
        @Override public void close() { }
    }

    private static int reg(MockConnection m, int r) {
        Integer v = m.registers().get(r);
        return v == null ? 0 : v;
    }

    private static MockConnection mock() {
        MockConnection m = new MockConnection();
        m.setRegister(0x01, 0xE1);
        m.setRegister(0x32, 0x3B);
        return m;
    }

    private static CS43L22Full chip(MockConnection m) throws IOException {
        CS43L22Full c = new CS43L22Full(m);
        c.init();
        return c;
    }

    @Test
    void initSequence() throws Exception {
        MockConnection m = mock();
        Pin pin = new Pin();
        CS43L22Full c = new CS43L22Full(m, pin);
        assertEquals(0, m.writes().size());
        c.init();
        assertEquals(List.of(false, true), pin.levels);
        assertArrayEquals(new byte[]{0x02, 0x01}, m.writes().get(1));
        assertEquals(0xAF, reg(m, 0x04));
        assertEquals(0xA0, reg(m, 0x05));
        assertEquals(0x04, reg(m, 0x06));
        assertEquals(0x60, reg(m, 0x0D));
        assertEquals(0x01, reg(m, 0x02));
        List<String> req = new ArrayList<>();
        for (byte[] w : m.writes()) {
            if (w.length == 2 && (w[0] == 0x00 || w[0] == 0x47 || w[0] == 0x32)) {
                req.add((w[0] & 0xFF) + ":" + (w[1] & 0xFF));
            }
        }
        assertEquals(List.of("0:153", "71:128", "50:187", "50:59", "0:0"), req);
        assertThrows(java.io.IOException.class, () -> new CS43L22Minimal(new MockConnection()).init());
    }

    @Test
    void startStopAndVolume() throws Exception {
        MockConnection m = mock();
        CS43L22Full c = chip(m);
        c.start();
        assertEquals(0x9E, reg(m, 0x02));
        m.setRegister(0x0E, 0x03);
        m.setRegister(0x0A, 0x0F);
        c.stop();
        assertEquals(0x9F, reg(m, 0x02));
        assertEquals(0xF0, reg(m, 0x0F) & 0xF0);
        assertEquals(0, reg(m, 0x0E) & 3);
        assertEquals(0, reg(m, 0x0A) & 0x0F);
        c.setVolume(12);
        assertEquals(0x18, reg(m, 0x20));
        c.setVolume(-102);
        assertEquals(0x34, reg(m, 0x20));
        c.setVolume(-500);
        assertEquals(0x34, reg(m, 0x21));
        c.setVolume(-0.5);
        assertEquals(0xFF, reg(m, 0x20));
        assertEquals(-0.5, c.getVolume());
        c.setVolumeChannels(-10, 3);
        assertEquals(0xEC, reg(m, 0x20));
        assertEquals(0x06, reg(m, 0x21));
    }

    @Test
    void channelVolumesAndMutes() throws Exception {
        MockConnection m = mock();
        CS43L22Full c = chip(m);
        c.setHeadphoneVolume(-20);
        assertEquals(0xD8, reg(m, 0x22));
        assertEquals(0xD8, reg(m, 0x23));
        c.setHeadphoneVolume(-96, -100);
        assertEquals(0x40, reg(m, 0x22));
        assertEquals(0x01, reg(m, 0x23));
        c.setSpeakerVolume(5, -1);
        assertEquals(0x00, reg(m, 0x24));
        assertEquals(0xFE, reg(m, 0x25));
        c.setPcmVolume(-51.5, 12);
        assertEquals(0x19, reg(m, 0x1A));
        assertEquals(0x18, reg(m, 0x1B));
        c.mutePcm(true, false);
        assertEquals(0x99, reg(m, 0x1A));
        c.setPcmVolume(0);
        assertEquals(0x80, reg(m, 0x1A));
        c.setPassthroughVolume(-60, 12);
        assertEquals(0x88, reg(m, 0x14));
        assertEquals(0x18, reg(m, 0x15));
        m.setRegister(0x0F, 0);
        c.mute(true);
        assertEquals(0xC0, reg(m, 0x0F));
        c.mute(false);
        assertEquals(0x00, reg(m, 0x0F));
        c.muteOutputs(null, true);
        assertEquals(0x30, reg(m, 0x0F));
        c.muteOutputs(true, false);
        assertEquals(0xC0, reg(m, 0x0F));
        c.setSpeakerMode(true, false, true);
        assertEquals(3, reg(m, 0x0F) & 7);
    }

    @Test
    void outputsGainClockingInterface() throws Exception {
        MockConnection m = mock();
        CS43L22Full c = chip(m);
        c.setOutputs(CS43L22Full.OUT_ON, CS43L22Full.OUT_OFF);
        assertEquals(0xAF, reg(m, 0x04));
        c.setOutputs(0, 1);
        assertEquals(0x05, reg(m, 0x04));
        assertThrows(IllegalArgumentException.class, () -> c.setOutputs(4, 0));
        c.setHeadphoneGain(CS43L22Full.GAIN_1_000);
        assertEquals(0xC0, reg(m, 0x0D));
        assertEquals(1.0, c.headphoneGain());
        assertThrows(IllegalArgumentException.class, () -> c.setHeadphoneGain(8));
        c.setClocking(new CS43L22Full.Clocking(true, 1, false, false, 0, false));
        assertEquals(0xA0, reg(m, 0x05));
        c.setClocking(new CS43L22Full.Clocking(false, 3, true, true, 3, true));
        assertEquals(0x7F, reg(m, 0x05));
        assertThrows(IllegalArgumentException.class, () -> c.setClocking(new CS43L22Full.Clocking(true, 4, false, false, 0, false)));
        c.setInterface(false, CS43L22Full.FMT_I2S, 0, false, false, false, false);
        assertEquals(0x04, reg(m, 0x06));
        assertEquals(0x00, reg(m, 0x07));
        c.setInterface(true, CS43L22Full.FMT_RJ, 2, false, true, true, true);
        assertEquals(0xCA, reg(m, 0x06));
        assertEquals(0x48, reg(m, 0x07));
        c.setInterface(false, CS43L22Full.FMT_LJ, 1, true, false, false, false);
        assertEquals(0x11, reg(m, 0x06));
        assertThrows(IllegalArgumentException.class,
                () -> c.setInterface(false, CS43L22Full.FMT_I2S, 0, true, false, false, false));
    }

    @Test
    void clockingTable() {
        CS43L22Full.Clocking a = CS43L22Full.clockingFor(12288000, 48000);
        assertFalse(a.auto);
        assertEquals(1, a.speed);
        assertEquals(0, a.ratio);
        CS43L22Full.Clocking b = CS43L22Full.clockingFor(12288000, 8000);
        assertEquals(3, b.speed);
        assertTrue(b.group32k);
        assertEquals(0, CS43L22Full.clockingFor(11289600, 44100).ratio);
        assertEquals(1, CS43L22Full.clockingFor(12000000, 48000).ratio);
        CS43L22Full.Clocking d = CS43L22Full.clockingFor(24000000, 44117.647);
        assertEquals(3, d.ratio);
        assertTrue(d.mclkDiv2);
        CS43L22Full.Clocking e = CS43L22Full.clockingFor(27000000, 16000);
        assertTrue(e.videoClk && e.group32k);
        assertEquals(2, e.speed);
        assertEquals(2, CS43L22Full.clockingFor(16934400, 8018.18).ratio);
        assertThrows(IllegalArgumentException.class, () -> CS43L22Full.clockingFor(12288000, 44100));
    }

    @Test
    void toneBeepLimiter() throws Exception {
        MockConnection m = mock();
        CS43L22Full c = chip(m);
        c.setTone(12, -10.5, 1, 2, true);
        assertEquals(0x0F, reg(m, 0x1F));
        assertEquals(0x0D, reg(m, 0x1E) & 0x1F);
        c.setTone(0, 0, 0, 0, false);
        assertEquals(0x88, reg(m, 0x1F));
        assertEquals(0, reg(m, 0x1E) & 1);
        c.setBeep(0xC, 2, 3, 6, CS43L22Full.BEEP_SINGLE, true);
        assertEquals(0xC2, reg(m, 0x1C));
        assertEquals(0x66, reg(m, 0x1D));
        assertEquals(1, reg(m, 0x1E) >> 6);
        assertThrows(IllegalStateException.class, () -> c.setBeep(1, 1, 1, 0, 1, true));
        c.stopBeep();
        assertEquals(0, reg(m, 0x1E) >> 6);
        c.setBeep(1, 1, 0, -56, 3, false);
        assertEquals(0x07, reg(m, 0x1D) & 0x1F);
        assertEquals(0xE0, reg(m, 0x1E) & 0xE0);
        c.setBeep(1, 1, 0, 0, CS43L22Full.BEEP_OFF, true);
        assertEquals(0, reg(m, 0x1E) >> 6);
        c.setBeep(1, 1, 0, 0, 2, true);
        assertEquals(0x03, reg(m, 0x1D) & 0x1F);
        c.stopBeep();
        assertThrows(IllegalArgumentException.class, () -> c.setBeep(16, 0, 0, 0, 1, true));
        c.setLimiter(true, -6, -12, 0x20, 5, false, true, true);
        assertEquals(0x53, reg(m, 0x27));
        assertEquals(0xA0, reg(m, 0x28));
        assertEquals(5, reg(m, 0x29));
        c.setLimiter(false, -29, 0);
        assertEquals(7, reg(m, 0x27) >> 5);
        assertEquals(0x7F, reg(m, 0x28));
        assertThrows(IllegalArgumentException.class, () -> c.setLimiter(true, 0, 0, 64, 0, true, false, false));
    }

    @Test
    void dspPassthroughStatusRaw() throws Exception {
        MockConnection m = mock();
        CS43L22Full c = chip(m);
        m.setRegister(0x0E, 0);
        m.setRegister(0x0A, 0xA5);
        c.setSoftRamp(true, true, false);
        assertEquals(0x02, reg(m, 0x0E));
        assertEquals(0xA7, reg(m, 0x0A));
        c.setZeroCross(true, false, true);
        assertEquals(0x03, reg(m, 0x0E));
        assertEquals(0xA6, reg(m, 0x0A));
        c.setDeemphasis(true);
        c.freeze(true);
        assertEquals(0x0C, reg(m, 0x0E) & 0x0C);
        c.freeze(false);
        assertEquals(0, reg(m, 0x0E) & 0x08);
        c.setPcmSwap(CS43L22Full.SRC_SWAPPED, CS43L22Full.SRC_MIX);
        assertEquals(0xD0, reg(m, 0x26));
        c.invertPcm(true, false);
        assertEquals(0x04, reg(m, 0x0D) & 0x0C);
        c.setPassthrough(true, 2, 4);
        assertEquals(0x82, reg(m, 0x08));
        assertEquals(0x88, reg(m, 0x09));
        assertEquals(0xC0, reg(m, 0x0E) & 0xC0);
        assertThrows(IllegalArgumentException.class, () -> c.setPassthrough(true, 0, 1));
        c.mutePassthrough(true, false);
        assertEquals(0x10, reg(m, 0x0E) & 0x30);
        c.setBatteryCompensation(true, 9, true);
        assertEquals(0xC9, reg(m, 0x2F));
        m.setRegister(0x30, 0x40);
        assertEquals(64 * 2.5 / 63.3, c.readVpVoltage(2.5), 1e-9);
        m.setRegister(0x2E, 0x4C);
        CS43L22Full.Status st = c.readStatus();
        assertTrue(st.clockError && st.pcmAOverflow && st.pcmBOverflow);
        assertFalse(st.dspAOverflow || st.dspBOverflow);
        m.setRegister(0x31, 0x28);
        CS43L22Full.SpeakerStatus sp = c.readSpeakerStatus();
        assertTrue(sp.speakerAShort && sp.spkrHpPin);
        assertFalse(sp.speakerBShort);
        m.setRegister(0x34, 0x5F);
        c.setChargePumpFrequency(7);
        assertEquals(0x7F, reg(m, 0x34));
        assertThrows(IllegalArgumentException.class, () -> c.setChargePumpFrequency(16));
        assertArrayEquals(new int[]{0x1C, 1}, c.readId());
        assertEquals(0xE1, c.readRegister(0x01));
        assertThrows(IllegalArgumentException.class, () -> c.readRegister(0x03));
        c.writeRegister(0x20, 0x10);
        assertEquals(0x10, reg(m, 0x20));
        assertThrows(IllegalArgumentException.class, () -> c.writeRegister(0x2E, 0));
        assertThrows(IllegalArgumentException.class, () -> c.writeRegister(0x33, 0));
        assertThrows(IllegalStateException.class, c::reset);
    }

    @Test
    void resetReinitializes() throws Exception {
        MockConnection m = mock();
        Pin pin = new Pin();
        CS43L22Full c = new CS43L22Full(m, pin);
        c.init();
        c.start();
        c.reset();
        assertEquals(0x01, reg(m, 0x02));
        assertEquals(4, pin.levels.size());
    }
}
