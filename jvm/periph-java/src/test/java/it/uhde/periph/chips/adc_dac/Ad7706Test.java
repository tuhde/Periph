package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ad7706Test {

    @Test
    void initAndMinimalReads() throws Exception {
        MockConnection connection = new MockConnection();
        Ad7706Minimal sensor = new Ad7706Minimal(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ);
        assertArrayEquals(new byte[]{0x20, 0x0C}, connection.writes().get(0));
        assertArrayEquals(new byte[]{0x10, 0x40}, connection.writes().get(1));
        assertArrayEquals(new byte[]{0x08}, connection.writes().get(2));

        connection.setRegister(0x38, 0xC0, 0x00);
        assertEquals(0xC000, sensor.readRaw());
        assertTrue(Math.abs(sensor.readVoltage() - 1.25f) < 1e-6f);
    }

    @Test
    void threeChannelsIndependentState() throws Exception {
        // Regression tests for driver bugs found while writing this test:
        // configure() only updated the shared gain/bipolar/buffered fields for
        // channel 1, and configureClock() was hardcoded to always write
        // Channel 1's Clock Register. AD7706 has three channels, so this also
        // checks channel 3 (comm select bits 11, not just channel 2's 01).
        MockConnection connection = new MockConnection();
        Ad7706Full full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ);

        // configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
        full.configure(2, 4, false, true, 250);
        int n = connection.writes().size();
        assertArrayEquals(new byte[]{0x21, 0x0E}, connection.writes().get(n - 2));
        assertArrayEquals(new byte[]{0x11, 0x16}, connection.writes().get(n - 1));

        // configure(3, gain=8, bipolar=true, buffered=false, 500 Hz):
        full.configure(3, 8, true, false, 500);
        n = connection.writes().size();
        assertArrayEquals(new byte[]{0x23, 0x0F}, connection.writes().get(n - 2));
        assertArrayEquals(new byte[]{0x13, 0x18}, connection.writes().get(n - 1));

        // Data Register reads: CH2 comm=0x39, CH3 comm=0x3B.
        connection.setRegister(0x39, 0x80, 0x00);  // code=0x8000, gain=4, unipolar -> 0.3125 V
        assertTrue(Math.abs(full.readVoltage(2) - 0.3125f) < 1e-6f);

        connection.setRegister(0x3B, 0xE0, 0x00);  // code=0xE000, gain=8, bipolar -> 0.234375 V
        assertTrue(Math.abs(full.readVoltage(3) - 0.234375f) < 1e-6f);

        // Channel 1 was never configured -> still the ctor default.
        connection.setRegister(0x38, 0xC0, 0x00);
        assertTrue(Math.abs(full.readVoltage(1) - 1.25f) < 1e-6f);

        assertThrows(IllegalArgumentException.class, () -> full.configure(4, 1, true, false, 50));
    }

    @Test
    void calibrationUsesConfiguredChannelState() throws Exception {
        MockConnection connection = new MockConnection();
        Ad7706Full full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ);
        full.configure(3, 8, true, false, 500);

        // selfCalibrate(3): setup = MODE_SELF_CAL(0x40)|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x58
        full.selfCalibrate(3);
        int n = connection.writes().size();
        assertArrayEquals(new byte[]{0x13, 0x58}, connection.writes().get(n - 2));
    }

    @Test
    void calibrationRegistersAndPowerControl() throws Exception {
        MockConnection connection = new MockConnection();
        Ad7706Full full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ);

        // Zero-Scale reg CH3 read comm = REG_OFFSET|RW_READ|CH3(0x03) = 0x6B.
        connection.setRegister(0x6B, 0x12, 0x34, 0x56);
        assertEquals(0x123456, full.getOffsetCalibration(3));

        full.setOffsetCalibration(0xABCDEF, 3);
        assertArrayEquals(new byte[]{0x63, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF},
                connection.writes().get(connection.writes().size() - 1));

        full.standby();
        assertArrayEquals(new byte[]{0x04}, connection.writes().get(connection.writes().size() - 1));

        full.wakeup();
        int n = connection.writes().size();
        assertArrayEquals(new byte[]{0x00}, connection.writes().get(n - 2));
        assertArrayEquals(new byte[]{0x08}, connection.writes().get(n - 1));
    }

    @Test
    void reset() throws Exception {
        MockConnection connection = new MockConnection();
        Ad7706Full full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ);
        assertThrows(IllegalStateException.class, full::reset);

        java.util.List<Boolean> calls = new java.util.ArrayList<>();
        Ad7706Full.OutputPin pin = high -> calls.add(high);
        MockConnection connection2 = new MockConnection();
        Ad7706Full withReset = new Ad7706Full(connection2, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ, pin);

        withReset.reset();
        assertEquals(java.util.List.of(false, true), calls);
    }
}
