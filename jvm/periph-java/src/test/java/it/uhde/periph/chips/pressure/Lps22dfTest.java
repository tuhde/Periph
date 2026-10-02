package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Lps22dfTest {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(Lps22dfMinimal.REG_WHO_AM_I, Lps22dfMinimal.CHIP_ID);
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
    void constructionSequence() throws IOException {
        MockConnection connection = newConnection();
        new Lps22dfMinimal(connection);

        var writes = connection.writes();
        assertEquals(4, writes.size());
        assertEquals(Lps22dfMinimal.REG_WHO_AM_I, writes.get(0)[0] & 0xFF);
        assertEquals(0x04, writes.get(1)[1] & 0xFF); // SWRESET
        assertEquals((3 << 3), writes.get(2)[1] & 0xFF); // CTRL_REG1 default
        assertEquals(0x08, writes.get(3)[1] & 0xFF); // BDU
    }

    @Test
    void constructionBadChipIdThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(Lps22dfMinimal.REG_WHO_AM_I, 0x00);
        assertThrows(IOException.class, () -> new Lps22dfMinimal(connection));
    }

    @Test
    void pressureAndTemperature() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfMinimal chip = new Lps22dfMinimal(connection);

        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01 | 0x02);
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0x80, 0x0C); // raw=819200 -> 20000 Pa
        assertEquals(20000.0, chip.pressure(), 1e-3);

        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x2E, 0x09); // raw=2350 -> 23.5 degC
        assertEquals(23.5, chip.temperature(), 1e-3);

        // Negative values.
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0xC0, 0xF9); // raw=-409600 -> -10000 Pa
        assertEquals(-10000.0, chip.pressure(), 1e-3);

        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x0C, 0xFE); // raw=-500 -> -5.0 degC
        assertEquals(-5.0, chip.temperature(), 1e-3);
    }

    // Regression: temperature() must poll STATUS.T_DA before reading TEMP_OUT,
    // exactly like pressure() polls STATUS.P_DA -- it previously read
    // TEMP_OUT_L/H unconditionally with no STATUS check at all.
    @Test
    void temperaturePollsStatusFirst() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfMinimal chip = new Lps22dfMinimal(connection);

        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x02);
        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x2E, 0x09);
        chip.temperature();

        var writes = connection.writes();
        int n = writes.size();
        assertEquals(Lps22dfMinimal.REG_STATUS, writes.get(n - 2)[0] & 0xFF);
        assertEquals(Lps22dfMinimal.REG_TEMP_OUT_L, writes.get(n - 1)[0] & 0xFF);
    }

    @Test
    void configureWritesRegisters() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        full.configure(Lps22dfFull.ODR_50_HZ, Lps22dfFull.AVG_16, true, 1, true);
        assertEquals((Lps22dfFull.ODR_50_HZ << 3) | Lps22dfFull.AVG_16, lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG1));
        assertEquals(0x10 | 0x20 | 0x08, lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG2));
    }

    @Test
    void oneshotWritesSequenceAndWaits() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01);
        full.oneshot();

        var writes = connection.writes();
        int n = writes.size();
        assertEquals(Lps22dfMinimal.REG_CTRL_REG1, writes.get(n - 3)[0] & 0xFF);
        assertEquals(0x00, writes.get(n - 3)[1] & 0xFF);
        assertEquals(Lps22dfMinimal.REG_CTRL_REG2, writes.get(n - 2)[0] & 0xFF);
        assertEquals(0x09, writes.get(n - 2)[1] & 0xFF);
        assertEquals(Lps22dfMinimal.REG_STATUS, writes.get(n - 1)[0] & 0xFF);
    }

    @Test
    void altitudeAtZeroPressure() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01);
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0x00, 0x00); // raw=0 -> 0 Pa
        assertEquals(44330.0, full.altitude(101325.0), 1.0);
    }

    @Test
    void softwareResetWritesCtrlReg2() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        full.softwareReset();
        assertEquals(0x04, lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG2));
    }

    @Test
    void setPressureOffset() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        // -12.34 hPa -> raw = round(-12.34*4096) = -50545 -> wraps to 14991 (0x3A8F).
        full.setPressureOffset(-1234.0); // Pa
        assertEquals(0x8F, lastWriteTo(connection, Lps22dfMinimal.REG_RPDS_L));
        assertEquals(0x3A, lastWriteTo(connection, Lps22dfMinimal.REG_RPDS_H));
    }

    @Test
    void setPressureThreshold() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        // 900.0 hPa -> raw = round(900*16) & 0x7FFF = 14400 = 0x3840.
        full.setPressureThreshold(90000.0); // Pa
        assertEquals(0x40, lastWriteTo(connection, Lps22dfMinimal.REG_THS_P_L));
        assertEquals(0x38, lastWriteTo(connection, Lps22dfMinimal.REG_THS_P_H));
    }

    @Test
    void configureInterrupt() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        full.configureInterrupt(true, true, true, true, true, true, true, true);
        assertEquals(0x08 | 0x02 | 0x01, lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG3));
        assertEquals(0x40 | 0x20 | 0x10 | 0x04 | 0x02 | 0x01, lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG4));
    }

    @Test
    void configurePressureEvent() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        full.configurePressureEvent(true, true, true);
        assertEquals(0x01 | 0x02 | 0x04, lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG));
    }

    @Test
    void autozeroAutorefpResetReference() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);

        full.autozero();
        assertEquals(0x20, lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG));

        full.autorefp();
        assertEquals(0x80, lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG));

        full.resetReference();
        assertEquals(0x50, lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG));
    }

    @Test
    void referencePressure() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_REF_P_L, 0x00, 0x10); // raw=4096 -> 100.0 Pa
        assertEquals(100.0, full.referencePressure(), 1e-3);
    }

    @Test
    void fifoModeAndWatermark() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);

        full.setFifoMode(Lps22dfFull.FIFO_CONT_TO_FIFO);
        assertEquals((1 << 2) | 3, lastWriteTo(connection, Lps22dfMinimal.REG_FIFO_CTRL));

        full.setFifoWatermark(100);
        assertEquals(100, lastWriteTo(connection, Lps22dfMinimal.REG_FIFO_WTM));
    }

    @Test
    void readFifo() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_FIFO_STATUS1, 2);
        connection.setRegister(Lps22dfMinimal.REG_FIFO_PRESS_XL,
                0x00, 0x80, 0x0C,  // sample 0: raw=819200 -> 20000 Pa
                0x00, 0xC0, 0xF9); // sample 1: raw=-409600 -> -10000 Pa

        double[] out = new double[4];
        int n = full.readFifo(out);
        assertEquals(2, n);
        assertEquals(20000.0, out[0], 1e-3);
        assertEquals(-10000.0, out[1], 1e-3);
    }

    @Test
    void readFifoTruncatesToOutBufferLength() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_FIFO_STATUS1, 5);
        connection.setRegister(Lps22dfMinimal.REG_FIFO_PRESS_XL,
                0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C,
                0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C);

        double[] out = new double[2];
        assertEquals(2, full.readFifo(out));
    }

    @Test
    void interruptSource() throws IOException {
        MockConnection connection = newConnection();
        Lps22dfFull full = new Lps22dfFull(connection);
        connection.setRegister(Lps22dfMinimal.REG_INT_SOURCE, 0x85); // BOOT_ON | IA | PH
        assertEquals(0x85, full.interruptSource());
    }
}
