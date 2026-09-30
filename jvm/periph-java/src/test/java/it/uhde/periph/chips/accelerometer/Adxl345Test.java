package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Adxl345Test {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(Adxl345Minimal.REG_DEVID, 0xE5);
        c.setRegister(Adxl345Minimal.REG_DATAX0, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00); // x=1,y=2,z=3
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
        MockConnection connection = newConnection();
        Adxl345Minimal accel = new Adxl345Minimal(connection);

        assertEquals(0x08, lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT));
        assertEquals(0x0A, lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE));
        assertEquals(0x08, lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL));

        double[] xyz = accel.read();
        assertEquals(0.0039, xyz[0], 1e-9);
        assertEquals(0.0078, xyz[1], 1e-9);
        assertEquals(0.0117, xyz[2], 1e-9);
    }

    @Test
    void constructionBadDevIdThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(Adxl345Minimal.REG_DEVID, 0x00);
        assertThrows(IOException.class, () -> new Adxl345Minimal(connection));
    }

    @Test
    void rangeDataRateLowPowerOffset() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);

        full.setRange(4);
        assertEquals(0x08 | 0x01, lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT));

        connection.setRegister(Adxl345Minimal.REG_BW_RATE, 0x0A);
        full.setDataRate(100);
        assertEquals(0x0A, lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE));

        full.setLowPower(true);
        assertEquals(0x10, lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) & 0x10);

        full.setOffset(0.5, -0.5, 0.0);
        assertEquals(32, lastWriteTo(connection, Adxl345Minimal.REG_OFSX));
        assertEquals(0xE0, lastWriteTo(connection, Adxl345Minimal.REG_OFSY));
        assertEquals(0, lastWriteTo(connection, Adxl345Minimal.REG_OFSZ));
    }

    // Regression: RATE_CODES was declared as an int[][], truncating 12.5 Hz
    // and 6.25 Hz to 12 and 6 -- close enough to tip the "nearest rate" vote
    // for request rates near that boundary. At 9.0 Hz the true nearest is
    // 6.25 Hz (diff 2.75) over 12.5 Hz (diff 3.5), but with truncated ints
    // both differences round to 3, so the tie (kept-first) wrongly picked
    // 12.5 Hz's code (0x07) instead of 6.25 Hz's (0x06).
    @Test
    void dataRateNearestSelectionUsesFractionalRates() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);
        connection.setRegister(Adxl345Minimal.REG_BW_RATE, 0x0A);

        full.setDataRate(9.0);
        assertEquals(0x06, lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) & 0x0F);
    }

    @Test
    void tapAndFreeFallRoundToNearestLsb() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00);
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00);

        full.setTapDetection(0.5, 10.0, 0x07, false);
        assertEquals(8, lastWriteTo(connection, Adxl345Minimal.REG_THRESH_TAP));
        assertTrue((lastWriteTo(connection, Adxl345Minimal.REG_INT_ENABLE) & Adxl345Full.INT_SINGLE_TAP) != 0);

        // 0.3g / 62.5mg = 4.8 -> rounds to 5.
        full.setFreeFall(0.3, 100);
        assertEquals(5, lastWriteTo(connection, Adxl345Minimal.REG_THRESH_FF));
        assertEquals(20, lastWriteTo(connection, Adxl345Minimal.REG_TIME_FF));
    }

    @Test
    void activityAndInactivity() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);
        connection.setRegister(Adxl345Minimal.REG_ACT_INACT_CTL, 0x00);
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00);
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00);

        full.setActivity(0.5, 0x70, true);
        assertEquals(0xF0, lastWriteTo(connection, Adxl345Minimal.REG_ACT_INACT_CTL));

        // time_sec=2.7 -> rounds to 3.
        full.setInactivity(0.5, 2.7, 0x07, false);
        assertEquals(3, lastWriteTo(connection, Adxl345Minimal.REG_TIME_INACT));
        assertEquals(0xF7, lastWriteTo(connection, Adxl345Minimal.REG_ACT_INACT_CTL));
    }

    @Test
    void interruptRoutingAndSource() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00);
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00);

        full.setInterrupt(Adxl345Full.INT_WATERMARK, true, 2);
        assertTrue((lastWriteTo(connection, Adxl345Minimal.REG_INT_MAP) & Adxl345Full.INT_WATERMARK) != 0);

        connection.setRegister(Adxl345Minimal.REG_INT_SOURCE, 0x44);
        assertEquals(0x44, full.readInterruptSource());
    }

    @Test
    void fifo() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);

        full.setFifoMode(Adxl345Full.FIFO_STREAM, 16);
        assertEquals(Adxl345Full.FIFO_STREAM | 16, lastWriteTo(connection, Adxl345Minimal.REG_FIFO_CTL));

        connection.setRegister(Adxl345Minimal.REG_FIFO_STATUS, 3);
        assertEquals(3, full.fifoCount());

        double[][] samples = full.readFifo(4);
        assertEquals(3, samples.length);
        assertEquals(0.0039, samples[0][0], 1e-9);

        double[][] truncated = full.readFifo(2);
        assertEquals(2, truncated.length);
    }

    @Test
    void sleepLinkAutoSleepSelfTest() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Full full = new Adxl345Full(connection);

        full.setSleep(true, 8);
        assertEquals(0x04, lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x04);
        full.setSleep(false, 8);
        assertEquals(0x00, lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x04);

        full.setLinkMode(true);
        assertEquals(0x40, lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x40);
        full.setAutoSleep(true);
        assertEquals(0x20, lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x20);

        full.selfTest(true);
        assertEquals(0x80, lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) & 0x80);
        full.selfTest(false);
        assertEquals(0x00, lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) & 0x80);
    }

    // SPI command-byte framing (R/W|MB|A5..A0) lives in SPIConnection.read/write
    // now; the driver must address registers through RegisterConnection and
    // fetch the six data bytes as one burst so MB gets set on SPI.
    @Test
    void registerBurstRead() throws IOException {
        MockConnection connection = newConnection();
        Adxl345Minimal accel = new Adxl345Minimal(connection);

        int before = connection.writes().size();
        accel.read();
        var reads = connection.writes().subList(before, connection.writes().size());
        assertEquals(1, reads.size(), "read() should issue exactly one register burst");
        assertEquals(1, reads.get(0).length);
        assertEquals(Adxl345Minimal.REG_DATAX0, reads.get(0)[0] & 0xFF);
    }
}
