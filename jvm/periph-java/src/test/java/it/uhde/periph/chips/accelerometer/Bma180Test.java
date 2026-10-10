package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bma180Test {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(Bma180Minimal.REG_CHIP_ID, 0x03);
        c.setRegister(Bma180Minimal.REG_CTRL_REG0, 0x00);
        c.setRegister(Bma180Minimal.REG_OFFSET_LSB1, 0x00);
        c.setRegister(Bma180Minimal.REG_BW_TCS, 0x00);
        // raw_x = +0x200, raw_y = -0x200, raw_z = 0.
        c.setRegister(Bma180Minimal.REG_ACC_X_LSB, 0x00, 0x08);
        c.setRegister(Bma180Minimal.REG_ACC_Y_LSB, 0x00, 0xF8);
        c.setRegister(Bma180Minimal.REG_ACC_Z_LSB, 0x00, 0x00);
        return c;
    }

    private static int lastWriteTo(MockConnection connection, int reg) {
        var writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    @Test
    void constructionAndRead() throws IOException {
        var chip = new Bma180Minimal(newConnection());
        // Read goes from the mock with raw_x = +512, raw_y = -512, raw_z = 0.
        float[] xyz = chip.read();
        assertEquals(0.125f, xyz[0], 1e-6f);
        assertEquals(-0.125f, xyz[1], 1e-6f);
        assertEquals(0.0f, xyz[2], 1e-6f);
    }

    @Test
    void constructionBadChipId() {
        MockConnection c = new MockConnection();
        c.setRegister(Bma180Minimal.REG_CHIP_ID, 0xFF);
        assertThrows(IOException.class, () -> new Bma180Minimal(c));
    }

    @Test
    void fullRangeAndBandwidth() throws IOException {
        MockConnection c = newConnection();
        var full = new Bma180Full(c);
        full.setRange(8);
        // (0x04 & 0xF1) | 0x0A = 0x0A.
        assertEquals(0x0A, lastWriteTo(c, Bma180Minimal.REG_OFFSET_LSB1));
        full.setBandwidth(40);
        // (0x40 & 0x0F) | 0x20 = 0x20.
        assertEquals(0x20, lastWriteTo(c, Bma180Minimal.REG_BW_TCS));
    }

    @Test
    void fullTemperature() throws IOException {
        var c = newConnection();
        var full = new Bma180Full(c);
        c.setRegister(Bma180Minimal.REG_TEMP, 0x02);
        assertEquals(25.0f, full.readTemperature(), 1e-6f);
        c.setRegister(Bma180Minimal.REG_TEMP, 0x82);
        assertEquals(-39.0f, full.readTemperature(), 1e-6f);
    }

    @Test
    void fullReadRaw() throws IOException {
        var c = newConnection();
        c.setRegister(Bma180Minimal.REG_ACC_X_LSB, 0x00, 0x08);
        c.setRegister(Bma180Minimal.REG_ACC_Y_LSB, 0x00, 0x00);
        c.setRegister(Bma180Minimal.REG_ACC_Z_LSB, 0x00, 0x04);
        var full = new Bma180Full(c);
        int[] raw = full.readRaw();
        assertEquals(512, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(256, raw[2]);
    }

    @Test
    void fullLowGPreservesBit0() throws IOException {
        var c = newConnection();
        c.setRegister(Bma180Minimal.REG_HIGH_LOW_INFO, 0x00);
        c.setRegister(Bma180Minimal.REG_LOW_DUR, 0x01);  // bit 0 (tco_range) pre-set
        var full = new Bma180Full(c);
        full.setLowG(0.3f, 40, 0.05f, 0x07, 0, true);
        // range=2 -> code = round(0.3/2*255) = 38.
        assertEquals(38, lastWriteTo(c, Bma180Minimal.REG_LOW_TH));
        // bit 0 preserved.
        assertEquals(0x01, lastWriteTo(c, Bma180Minimal.REG_LOW_DUR) & 0x01);
        // axes bits 3:1 set; low_filt bit 0 set.
        assertEquals(0x0E, lastWriteTo(c, Bma180Minimal.REG_HIGH_LOW_INFO) & 0x0F);
    }

    @Test
    void fullSleepWake() throws IOException, InterruptedException {
        var c = newConnection();
        var full = new Bma180Full(c);
        full.sleep();
        assertEquals(0x02, lastWriteTo(c, Bma180Minimal.REG_CTRL_REG0) & 0x02);
        full.wake();
        assertEquals(0x00, lastWriteTo(c, Bma180Minimal.REG_CTRL_REG0) & 0x02);
    }

    @Test
    void fullSoftResetWritesB6() throws IOException, InterruptedException {
        var c = newConnection();
        c.setRegister(Bma180Minimal.REG_CHIP_ID, 0x03);
        var full = new Bma180Full(c);
        full.softReset();
        assertEquals(0xB6, lastWriteTo(c, Bma180Minimal.REG_RESET));
    }

    @Test
    void fullVersionAndCustomer() throws IOException {
        var c = newConnection();
        var full = new Bma180Full(c);
        c.setRegister(Bma180Minimal.REG_VERSION, 0xAB);
        int[] v = full.readVersion();
        assertEquals(0xA, v[0]);
        assertEquals(0xB, v[1]);
        c.setRegister(Bma180Minimal.REG_CD1, 0xA5);
        assertEquals(0xA5, full.readCustomer(0));
        full.writeCustomer(1, 0x5A);
        assertEquals(0x5A, lastWriteTo(c, Bma180Minimal.REG_CD2));
    }
}