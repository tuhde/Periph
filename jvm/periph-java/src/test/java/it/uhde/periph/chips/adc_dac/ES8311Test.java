package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ES8311Test {

    private static int reg(MockConnection m, int r) {
        Integer v = m.registers().get(r);
        return v == null ? 0 : v;
    }

    @Test
    void initSequence() throws Exception {
        MockConnection m = new MockConnection();
        new ES8311Minimal(m);
        assertArrayEquals(new byte[]{0x00, 0x1F}, m.writes().get(0));
        assertArrayEquals(new byte[]{0x00, 0x00}, m.writes().get(1));
        assertArrayEquals(new byte[]{0x00, (byte) 0x80}, m.writes().get(2));
        assertEquals(0x3F, reg(m, 0x01));
        assertEquals(0x00, reg(m, 0x02));
        assertEquals(0x10, reg(m, 0x03));
        assertEquals(0x10, reg(m, 0x04));
        assertEquals(0x00, reg(m, 0x05));
        assertEquals(0x03, reg(m, 0x06));
        assertEquals(0xFF, reg(m, 0x08));
        assertEquals(0x0C, reg(m, 0x09));
        assertEquals(0x0C, reg(m, 0x0A));
        assertEquals(0x01, reg(m, 0x0D));
        assertEquals(0x02, reg(m, 0x0E));
        assertEquals(0x10, reg(m, 0x13));
        assertEquals(0x6A, reg(m, 0x1C));
        assertEquals(0x08, reg(m, 0x37));
        assertEquals(0x18, reg(m, 0x14));
        assertEquals(0xBF, reg(m, 0x17));
        assertEquals(0x00, reg(m, 0x32));
        assertEquals(0x04, reg(m, 0x16));
    }

    @Test
    void minimalApi() throws Exception {
        MockConnection m = new MockConnection();
        ES8311Minimal chip = new ES8311Minimal(m);
        chip.setVolume(0);
        assertEquals(0xBF, reg(m, 0x32));
        chip.setVolume(-95.5);
        assertEquals(0x00, reg(m, 0x32));
        chip.setVolume(32);
        assertEquals(0xFF, reg(m, 0x32));
        chip.setVolume(100);
        assertEquals(0xFF, reg(m, 0x32));
        chip.setVolume(-10.2);
        assertEquals(171, reg(m, 0x32));
        m.setRegister(0x31, 0x81);
        chip.setMute(true);
        assertEquals(0xE1, reg(m, 0x31));
        chip.setMute(false);
        assertEquals(0x81, reg(m, 0x31));
        chip.setMicGain(30);
        assertEquals(0x1A, reg(m, 0x14));
        chip.setMicGain(7.4);
        assertEquals(0x12, reg(m, 0x14));
        chip.setMicGain(99);
        assertEquals(10, reg(m, 0x14) & 0x0F);
    }

    @Test
    void clockSolver() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ES8311Minimal(new MockConnection(), 1000, 48000));
        MockConnection m = new MockConnection();
        new ES8311Minimal(m, 24576000, 48000);
        assertEquals(0x20, reg(m, 0x02));
        m = new MockConnection();
        new ES8311Minimal(m, 12288000, 16000);
        int r02 = reg(m, 0x02);
        long[] mults = {1, 2, 4, 8};
        long ratio = 12288000L * mults[(r02 >> 3) & 3] / (((r02 >> 5) & 7) + 1) / ((reg(m, 0x05) & 0x0F) + 1) / 16000;
        assertTrue(ratio == 256 || ratio == 384 || ratio == 512 || ratio == 768);
        assertEquals(reg(m, 0x03), reg(m, 0x04));
        assertTrue(reg(m, 0x03) >= 16 && reg(m, 0x03) <= 63);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection m = new MockConnection();
        ES8311Full chip = new ES8311Full(m);
        chip.setVolume(-20);
        assertEquals(-20.0, chip.getVolume());
        chip.setMicGain(21);
        assertEquals(21.0, chip.getMicGain());

        int n = m.writes().size();
        chip.reset();
        assertEquals(n + 3, m.writes().size());
        assertArrayEquals(new byte[]{0, 0x1F}, m.writes().get(n));
        assertArrayEquals(new byte[]{0, (byte) 0x80}, m.writes().get(n + 2));

        chip.powerDown();
        assertEquals(0x1F, reg(m, 0x00));
        assertEquals(0x60, reg(m, 0x31) & 0x60);
        assertEquals(0xFC, reg(m, 0x0D));
        assertEquals(0x70, reg(m, 0x0E) & 0x70);
        chip.powerUp();
        assertEquals(0x80, reg(m, 0x00));
        assertEquals(0x01, reg(m, 0x0D));
        chip.setLowPower(true, false, false, false, false, false, false, true);
        assertEquals(0x81, reg(m, 0x0F));
        chip.setLowPower(false, true, false, true, true, false, true, false);
        assertEquals(0x40 | 0x10 | 0x08 | 0x02, reg(m, 0x0F));

        chip.configureClock(12288000, 48000, true, true);
        assertEquals(0xFF, reg(m, 0x01));
        chip.configureClock(12288000, 48000);
        assertEquals(0x3F, reg(m, 0x01));
        assertThrows(IllegalArgumentException.class, () -> chip.configureClock(1000, 48000));

        chip.configureSerial("left_justified", 24);
        assertEquals(0x01, reg(m, 0x09));
        assertEquals(0x01, reg(m, 0x0A));
        chip.configureSerial("dsp_b", 32, false, false, false, "right", null, null, true);
        assertEquals(0x80 | 0x20 | (4 << 2) | 3, reg(m, 0x09));
        assertEquals(0x20 | (4 << 2) | 3, reg(m, 0x0A));
        chip.configureSerial("dsp_a", 16, false, false, true, "left", null, null, true);
        assertEquals(0x20 | (3 << 2) | 3, reg(m, 0x0A));
        assertEquals(0x04, reg(m, 0x00) & 0x44);
        chip.setSdpMute(true, false);
        assertTrue((reg(m, 0x09) & 0x40) != 0 && (reg(m, 0x0A) & 0x40) == 0);
        chip.configureSerial("i2s", 16);
        assertTrue((reg(m, 0x09) & 0x40) != 0);
        chip.configureSerial("i2s", 16, true, false, false, "left", 4, 256, true);
        assertEquals(0x40, reg(m, 0x00) & 0x44);
        assertEquals(0x03, reg(m, 0x06));
        assertEquals(0x00, reg(m, 0x07) & 0x0F);
        assertEquals(0xFF, reg(m, 0x08));
        chip.configureSerial("i2s", 16, true, true, false, "left", 32, 64, false);
        assertEquals(0x40 | 0x20 | 24, reg(m, 0x06));
        assertThrows(IllegalArgumentException.class, () -> chip.configureSerial("x", 16));
        assertThrows(IllegalArgumentException.class, () -> chip.configureSerial("i2s", 8));

        chip.setTristate(true, false);
        assertEquals(0x20, reg(m, 0x07) & 0x30);
        chip.setTristate(false, true);
        assertEquals(0x10, reg(m, 0x07) & 0x30);

        chip.setInput("digital");
        assertTrue((reg(m, 0x14) & 0x70) == 0x40 && (reg(m, 0x0E) & 0x40) != 0);
        chip.setInput("analog");
        assertTrue((reg(m, 0x14) & 0x70) == 0x10 && (reg(m, 0x0E) & 0x40) == 0);
        chip.setInput("none");
        assertTrue((reg(m, 0x14) & 0x70) == 0x00 && (reg(m, 0x0E) & 0x40) != 0);
        assertThrows(IllegalArgumentException.class, () -> chip.setInput("x"));
        chip.configureDmic(true);
        assertEquals(1, reg(m, 0x15) & 1);
        chip.configureDmic(false);
        assertEquals(0, reg(m, 0x15) & 1);

        chip.setAdcVolume(-95.5);
        assertEquals(0, reg(m, 0x17));
        chip.configureAdc(42, 128, false, false, 0x05, 0x0A);
        assertEquals(7, reg(m, 0x16) & 7);
        assertEquals(32, reg(m, 0x03));
        assertEquals(0x0A, reg(m, 0x1C) & 0x3F);
        assertEquals(0x05, reg(m, 0x1B) & 0x1F);
        chip.configureAdc(24, 64, true, true, 0x0C, 0x0C);
        assertEquals(0x40 | 32, reg(m, 0x03));
        assertTrue((reg(m, 0x1C) & 0x20) != 0);
        assertThrows(IllegalArgumentException.class, () -> chip.configureAdc(24, 8, false, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> chip.configureAdc(24, 20, true, true, 0, 0));
        chip.setAdcRamp(7);
        assertEquals(7, reg(m, 0x15) >> 4);
        chip.setAdcRamp(99);
        assertEquals(15, reg(m, 0x15) >> 4);

        chip.configureAlc(true, -12.0, -18.1, 3, true, 2, -60);
        assertEquals(0xC3, reg(m, 0x18));
        assertEquals((7 << 4) | 3, reg(m, 0x19));
        assertEquals((2 << 4) | 6, reg(m, 0x1A));
        chip.configureAlc(false);
        assertEquals(0, reg(m, 0x18));
        assertEquals(0xF0, reg(m, 0x19));
        assertEquals(0, reg(m, 0x1A));

        chip.setAdcEq(new long[]{1, 2, 3, 4, 0x3FFFFFFFL});
        assertEquals(0, reg(m, 0x1C) & 0x40);
        assertEquals(1, reg(m, 0x20));
        assertEquals(0x3F, reg(m, 0x2D));
        assertEquals(0xFF, reg(m, 0x30));
        assertTrue(m.writes().stream().anyMatch(w -> w.length == 21 && w[0] == 0x1D));
        chip.bypassAdcEq();
        assertEquals(0x40, reg(m, 0x1C) & 0x40);
        assertThrows(IllegalArgumentException.class, () -> chip.setAdcEq(new long[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> chip.setAdcEq(new long[]{1L << 30, 0, 0, 0, 0}));
        chip.setDacEq(new long[]{0x100, 0x200, 0x300});
        assertEquals(0, reg(m, 0x37) & 0x08);
        assertEquals(1, reg(m, 0x3A));
        assertEquals(2, reg(m, 0x3E));
        assertEquals(3, reg(m, 0x42));
        chip.bypassDacEq();
        assertEquals(0x08, reg(m, 0x37) & 0x08);

        chip.setDacRamp(8);
        assertTrue((reg(m, 0x37) >> 4) == 8 && (reg(m, 0x37) & 0x08) != 0);
        chip.configureDrc(true, -12.0, -24.1, 5);
        assertEquals(0x85, reg(m, 0x34));
        assertEquals((7 << 4) | 1, reg(m, 0x35));
        chip.setDacMuteOptions(true, false, true);
        assertEquals(0xC0, reg(m, 0x31) & 0xE0);
        chip.setDacMuteOptions(true, true, false);
        assertEquals(0x60, reg(m, 0x31) & 0xE0);

        chip.setLoopback(true);
        assertTrue((reg(m, 0x44) & 0x80) != 0);
        chip.setAdcdatMode("dac_adc");
        assertEquals(0xC0, reg(m, 0x44));
        chip.setAdcdatMode("dac_dac");
        assertEquals(6, (reg(m, 0x44) >> 4) & 7);
        chip.setLoopback(false);
        assertEquals(0x60, reg(m, 0x44));
        assertThrows(IllegalArgumentException.class, () -> chip.setAdcdatMode("x"));

        chip.writeRegister(0x40, 0x34);
        assertEquals(0x34, reg(m, 0x40));
        assertEquals(0x34, chip.readRegister(0x40));
    }
}
