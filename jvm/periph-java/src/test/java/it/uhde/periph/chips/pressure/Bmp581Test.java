package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Bmp581Test {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50);
        connection.setRegister(Bmp581Minimal.REG_STATUS, Bmp581Minimal.STATUS_NVM_RDY);
        connection.setRegister(Bmp581Minimal.REG_TEMP_XLSB, 0x00, 0x10, 0x00);  // raw_t = 0x1000 -> 0.0625 °C
        connection.setRegister(Bmp581Minimal.REG_PRESS_XLSB, 0x04, 0x00, 0x00); // raw_p = 0x04 -> 0.0625 Pa

        Bmp581Full sensor = new Bmp581Full(connection);

        assertEquals(0x71, (int) connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG));
        assertEquals(0x40, (int) connection.registers().get(Bmp581Minimal.REG_OSR_CONFIG));

        assertEquals(0.0625, sensor.temperature(), 1e-6);
        assertEquals(0.0625, sensor.pressure(), 1e-6);
        double[] both = sensor.both();
        assertEquals(0.0625, both[0], 1e-6);
        assertEquals(0.0625, both[1], 1e-6);

        connection.setRegister(Bmp581Minimal.REG_CHIP_ID, 0x50);
        assertEquals(0x50, sensor.chipId());

        connection.setRegister(Bmp581Full.REG_REV_ID, 0x32);
        assertEquals(0x32, sensor.revId());

        connection.setRegister(Bmp581Minimal.REG_STATUS, 0x09);
        assertEquals(0x09, sensor.status());

        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x11);
        assertEquals(0x11, sensor.interruptStatus());

        connection.setRegister(Bmp581Minimal.REG_INT_STATUS, 0x01);
        assertTrue(sensor.dataReady());

        sensor.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true);
        assertEquals((0x17 << 2) | 0x01, (int) connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG));
        assertEquals(0x40 | (4 << 3) | 2, (int) connection.registers().get(Bmp581Minimal.REG_OSR_CONFIG));

        sensor.setMode(Bmp581Full.MODE_STANDBY);
        assertEquals((0x17 << 2) | 0x00, (int) connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG));

        sensor.setMode(Bmp581Full.MODE_CONTINUOUS);
        assertEquals((0x17 << 2) | 0x03, (int) connection.registers().get(Bmp581Minimal.REG_ODR_CONFIG));

        connection.setRegister(Bmp581Full.REG_DSP_CONFIG, 0x00);
        sensor.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS);
        assertEquals(0x28, (int) connection.registers().get(Bmp581Full.REG_DSP_CONFIG));
        assertEquals((Bmp581Full.IIR_COEFF_3 << 3) | Bmp581Full.IIR_BYPASS,
                (int) connection.registers().get(Bmp581Full.REG_DSP_IIR));

        sensor.enableDrdyInterrupt(true);
        assertEquals(Bmp581Full.INT_SOURCE_DRDY, (int) connection.registers().get(Bmp581Full.REG_INT_SOURCE));

        sensor.enableFifoInterrupt(true, false);
        assertEquals(Bmp581Full.INT_SOURCE_DRDY | Bmp581Full.INT_SOURCE_FIFO_THS,
                (int) connection.registers().get(Bmp581Full.REG_INT_SOURCE));

        sensor.enableOorInterrupt(true);
        assertEquals(Bmp581Full.INT_SOURCE_DRDY | Bmp581Full.INT_SOURCE_FIFO_THS | Bmp581Full.INT_SOURCE_OOR_P,
                (int) connection.registers().get(Bmp581Full.REG_INT_SOURCE));

        sensor.configureInterrupt(1, 1, true, true);
        assertEquals(0x0F, (int) connection.registers().get(Bmp581Full.REG_INT_CONFIG));

        sensor.setOorThreshold(110000.0, 200.0, 2);
        int thr17 = (int) (110000.0 * 64.0) >> 7;
        assertEquals(thr17 & 0xFF, (int) connection.registers().get(Bmp581Full.REG_OOR_THR_P_LSB));
        assertEquals((thr17 >> 8) & 0xFF, (int) connection.registers().get(Bmp581Full.REG_OOR_THR_P_MSB));
        int range8 = ((int) (200.0 * 64.0) >> 7) & 0xFF;
        assertEquals(range8, (int) connection.registers().get(Bmp581Full.REG_OOR_RANGE));
        assertEquals((2 << 6) | ((thr17 >> 16) & 0x01),
                (int) connection.registers().get(Bmp581Full.REG_OOR_CONFIG));

        sensor.configureFifo(Bmp581Full.FIFO_BOTH, Bmp581Full.FIFO_STREAM, 8);
        assertEquals(0x03, (int) connection.registers().get(Bmp581Full.REG_FIFO_SEL));
        assertEquals(0x08, (int) connection.registers().get(Bmp581Full.REG_FIFO_CONFIG));

        connection.setRegister(Bmp581Full.REG_FIFO_COUNT, 4);
        assertEquals(4, sensor.fifoCount());

        connection.setRegister(Bmp581Full.REG_OSR_EFF, 0xA0);
        int[] eff = sensor.effectiveOsr();
        assertEquals(4, eff[0]);
        assertEquals(0, eff[1]);
        assertTrue(sensor.odrIsValid());

        sensor.softwareReset();
        assertTrue(true); // reached without throwing
    }
}