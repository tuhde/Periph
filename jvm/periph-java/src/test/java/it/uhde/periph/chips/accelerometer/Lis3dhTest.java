package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Lis3dhTest {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33);
        // Sub-address for the I²C multi-byte auto-increment is reg | 0x80:
        // X = +1.000 g, Y = 0, Z = -0.500 g at 12-bit / ±2 g (1 mg/LSB).
        c.setRegister(Lis3dhMinimal.REG_OUT_X_L | 0x80,
                0x80, 0x3E,
                0x00, 0x00,
                0xC0, 0xE0);
        return c;
    }

    private static int lastWriteTo(MockConnection connection, int reg) {
        List<byte[]> writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    private static boolean readSubAddressUsed(MockConnection connection, int sub) {
        for (byte[] w : connection.writes()) {
            if (w.length == 1 && (w[0] & 0xFF) == sub) return true;
        }
        return false;
    }

    private static boolean writeWithBitSeen(MockConnection connection, int reg, int bit) {
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg && (w[1] & bit) != 0) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Initialization sequence
    // -------------------------------------------------------------------------

    @Test
    void initSequenceWritesTheControlRegisters() throws IOException {
        MockConnection connection = newConnection();
        new Lis3dhMinimal(connection);

        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG));
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
    }

    @Test
    void initSequenceOrderIsDatasheetOrder() throws IOException {
        MockConnection connection = newConnection();
        new Lis3dhMinimal(connection);

        int[] expected = {
                Lis3dhMinimal.REG_CTRL_REG2,
                Lis3dhMinimal.REG_CTRL_REG3,
                Lis3dhMinimal.REG_CTRL_REG5,
                Lis3dhMinimal.REG_CTRL_REG6,
                Lis3dhMinimal.REG_FIFO_CTRL,
                Lis3dhMinimal.REG_TEMP_CFG,
                Lis3dhMinimal.REG_CTRL_REG4,
                Lis3dhMinimal.REG_CTRL_REG1};
        List<byte[]> writes = connection.writes();
        int index = 0;
        for (byte[] w : writes) {
            if (w.length != 2) continue;
            int reg = w[0] & 0xFF;
            if (index < expected.length && reg == expected[index]) index++;
        }
        assertEquals(expected.length, index);
    }

    @Test
    void initDoesNotTouchTheReservedRegisters() throws IOException {
        MockConnection connection = newConnection();
        new Lis3dhMinimal(connection);
        for (byte[] w : connection.writes()) {
            if (w.length != 2) continue;
            int reg = w[0] & 0xFF;
            assertFalse(reg <= 0x06, "reserved register written");
            assertFalse(reg == 0x0E, "reserved register written");
            assertFalse(reg >= 0x10 && reg <= 0x1D, "reserved register written");
            assertFalse(reg == Lis3dhMinimal.REG_CTRL_REG0, "CTRL_REG0 must stay untouched");
        }
    }

    @Test
    void badWhoAmIThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x44);
        assertThrows(IOException.class, () -> new Lis3dhMinimal(connection));
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @Test
    void readUsesTheAutoIncrementSubAddress() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhMinimal chip = new Lis3dhMinimal(connection);
        chip.read();
        assertTrue(readSubAddressUsed(connection, Lis3dhMinimal.REG_OUT_X_L | 0x80));
    }

    @Test
    void readScalesToGrams() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhMinimal chip = new Lis3dhMinimal(connection);
        double[] xyz = chip.read();
        assertEquals(1.0, xyz[0], 1e-9);
        assertEquals(0.0, xyz[1], 1e-9);
        assertEquals(-0.5, xyz[2], 1e-9);
    }

    @Test
    void readRawReturnsSignedCounts() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        int[] raw = chip.readRaw();
        assertEquals(1000, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(-500, raw[2]);
    }

    @Test
    void statusAndNewData() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x0F);
        assertTrue(chip.newDataAvailable());
        assertEquals(0x0F, chip.readStatus());
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @Test
    void setRangeUpdatesTheFsField() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setRange(8);
        // (0x88 & ~0x30) | (2 << 4) = 0xA8
        assertEquals(0xA8, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));
    }

    @Test
    void setRangeRejectsUnknownRanges() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertThrows(IllegalArgumentException.class, () -> chip.setRange(3));
    }

    @Test
    void setDataRateUpdatesTheOdrField() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setDataRate(50);
        // (0x57 & 0x0F) | 0x40 = 0x47
        assertEquals(0x47, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
    }

    @Test
    void setDataRateValidatesTheModeCombination() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        // 1620 Hz and 5376 Hz are low-power mode only.
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(1620));
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(5376));
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(999));
    }

    @Test
    void setModeUpdatesLpenAndHr() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);

        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER);
        assertEquals(0x5F, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));

        chip.setMode(Lis3dhMinimal.MODE_NORMAL);
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));

        chip.setMode(Lis3dhMinimal.MODE_HIGH_RESOLUTION);
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));
    }

    @Test
    void setModeRejectsUnknownModes() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertThrows(IllegalArgumentException.class, () -> chip.setMode(7));
    }

    @Test
    void lowPowerModeShiftsEightBits() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER);
        // 8-bit: 0x1000 >> 8 = 16 counts; ±2 g low power is 16 mg/LSB.
        connection.setRegister(Lis3dhMinimal.REG_OUT_X_L | 0x80,
                0x00, 0x10,
                0x00, 0x00,
                0x00, 0x00);
        double[] xyz = chip.read();
        assertEquals(16 * 16 / 1000.0, xyz[0], 1e-9);
    }

    @Test
    void setAxesUpdatesTheAxisBits() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setAxes(true, false, true);
        // (0x57 & 0xF8) | 0x05 = 0x55
        assertEquals(0x55, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
    }

    @Test
    void powerDownClearsTheOdrField() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.powerDown();
        assertEquals(0x07, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
    }

    // -------------------------------------------------------------------------
    // Temperature and auxiliary ADC
    // -------------------------------------------------------------------------

    @Test
    void temperatureEnablesTheAdcAndReturnsARelativeDelta() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_OUT_ADC3_H, 0xF0);
        assertEquals(-16, chip.readTemperatureDelta());
        // ADC_EN + TEMP_EN
        assertEquals(0xC0, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG));
    }

    @Test
    void adcReadsTheAuxiliaryChannels() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        // Channel 1: raw16 = 0x0400 = 1024 -> 16 counts at 10-bit.
        connection.setRegister(0x08 | 0x80, 0x00, 0x04);
        assertEquals(1.200 + 16 * (0.400 / 512.0), chip.readAdc(1), 1e-9);
    }

    @Test
    void adcChannel3IsRejectedWhileTheTemperatureSensorIsOn() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_TEMP_CFG, 0x40);
        assertThrows(IllegalArgumentException.class, () -> chip.readAdc(3));
        assertThrows(IllegalArgumentException.class, () -> chip.readAdc(4));
    }

    @Test
    void enableAdcTogglesTheAdcEnableBit() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.enableAdc(true);
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG));
        chip.enableAdc(false);
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG));
    }

    // -------------------------------------------------------------------------
    // High-pass filter
    // -------------------------------------------------------------------------

    @Test
    void highPassConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setHighPass(3, 2, true, true, true, true);
        // (3 << 6) | (2 << 4) | FDS | HPCLICK | HP_IA2 | HP_IA1 = 0xEF
        assertEquals(0xEF, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2));
    }

    @Test
    void highPassRejectsInvalidCodes() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertThrows(IllegalArgumentException.class, () -> chip.setHighPass(4, 0, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> chip.setHighPass(0, 4, false, false, false, false));
    }

    @Test
    void referenceRegister() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_REFERENCE, 0x42);
        assertEquals(0x42, chip.resetHighPass());
        chip.setReference(0x7F);
        assertEquals(0x7F, lastWriteTo(connection, Lis3dhMinimal.REG_REFERENCE));
        assertThrows(IllegalArgumentException.class, () -> chip.setReference(256));
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    @Test
    void fifoConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setFifo(2, 16, false);
        // (2 << 6) | 16 = 0x90
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL));
        assertEquals(0x40, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));

        chip.setFifo(0, 0, true);
        assertEquals(0x20, lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));
    }

    @Test
    void fifoLevelAndStatus() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xE3);
        assertEquals(3, chip.fifoLevel());
        Lis3dhFull.FifoStatus status = chip.readFifoStatus();
        assertTrue(status.watermark);
        assertTrue(status.overrun);
        assertTrue(status.empty);
        assertEquals(3, status.level);
    }

    @Test
    void readFifoDrainsTheSampleSets() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x03);
        List<double[]> samples = chip.readFifo();
        assertEquals(3, samples.size());
        assertEquals(1.0, samples.get(0)[0], 1e-9);
        assertEquals(-0.5, samples.get(2)[2], 1e-9);
    }

    @Test
    void readFifoNeverReadsMoreThanTheFifoHolds() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x02);
        assertEquals(2, chip.readFifo(10).size());
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators
    // -------------------------------------------------------------------------

    @Test
    void motionInterruptConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setMotionInterrupt(1, 0x35, 0.5, 10, true);
        // AOI + 0x35
        assertEquals(0xB5, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG));
        // round(0.5 g * 1000 / 16 mg) = 31
        assertEquals(31, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS));
        // round(10 ms * 100 Hz / 1000) = 1
        assertEquals(1, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR));
    }

    @Test
    void motionInterruptUsesGenerator2Registers() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setMotionInterrupt(2, 0x01, 0.16, 0, false);
        assertEquals(0x01, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG));
        // round(0.16 g * 1000 / 16 mg) = 10
        assertEquals(10, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_THS));
        assertThrows(IllegalArgumentException.class, () -> chip.setMotionInterrupt(3, 0x01, 0.16, 0, false));
    }

    @Test
    void freeFallConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setFreeFall(1, 0.35, 60);
        assertEquals(0x95, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG));
        // round(0.35 g * 1000 / 16 mg) = 22
        assertEquals(22, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS));
        // round(60 ms * 100 Hz / 1000) = 6
        assertEquals(6, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR));
    }

    @Test
    void orientationConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setOrientation(1, false, false);
        // 6D movement recognition with all six axis zones.
        assertEquals(0x7F, lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG));
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));

        chip.setOrientation(2, true, true);
        // 6D position recognition with the four 4D zones.
        assertEquals(0xCF, lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG));
        // D4D_INT2
        assertEquals(0x01, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));
    }

    @Test
    void latchConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setLatch(1, true);
        assertEquals(0x08, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));
        chip.setLatch(2, true);
        assertEquals(0x0A, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5));
    }

    @Test
    void readInterruptSourceReturnsTheSourceByte() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x60);
        connection.setRegister(Lis3dhMinimal.REG_INT2_SRC, 0x40);
        assertEquals(0x60, chip.readInterruptSource(1));
        assertEquals(0x40, chip.readInterruptSource(2));
        assertThrows(IllegalArgumentException.class, () -> chip.readInterruptSource(3));
    }

    // -------------------------------------------------------------------------
    // Click detection
    // -------------------------------------------------------------------------

    @Test
    void clickConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setClick(0, 0x04, 0.8, 20, 40, 200, true);
        // Double click on Z: ZD is bit 5.
        assertEquals(0x20, lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_CFG));
        // round(0.8 g * 1000 / 16 mg) = 50, latched -> 0x80
        assertEquals(0xB2, lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_THS));
        assertEquals(2, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LIMIT));
        assertEquals(4, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LATENCY));
        assertEquals(20, lastWriteTo(connection, Lis3dhMinimal.REG_TIME_WINDOW));
    }

    @Test
    void clickSourceIsReadBack() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x48);
        assertEquals(0x48, chip.readClickSource());
    }

    // -------------------------------------------------------------------------
    // Activity function
    // -------------------------------------------------------------------------

    @Test
    void activityConfiguration() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setActivity(0.5, 100);
        // round(0.5 g * 1000 / 16 mg) = 31
        assertEquals(31, lastWriteTo(connection, Lis3dhMinimal.REG_ACT_THS));
        // round((100 ms * 100 Hz / 1000 - 1) / 8) = 1
        assertEquals(1, lastWriteTo(connection, Lis3dhMinimal.REG_ACT_DUR));
    }

    @Test
    void activityRejectsAZeroThreshold() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertThrows(IllegalArgumentException.class, () -> chip.setActivity(0.001, 100));
    }

    // -------------------------------------------------------------------------
    // Interrupt routing and polling
    // -------------------------------------------------------------------------

    @Test
    void interruptPolarity() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setInterruptPolarity(true);
        assertEquals(0x02, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6));
        chip.setInterruptPolarity(false);
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6));
    }

    @Test
    void enableInterruptRoutesToTheRightRegister() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);

        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3));

        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 2);
        assertEquals(0x80, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6));

        // INT1-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 2);
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3));

        // INT2-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_ACTIVITY, 1);
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6));
    }

    @Test
    void disableInterruptClearsTheRoutingBit() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);
        chip.disableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);
        assertEquals(0x00, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3));
    }

    @Test
    void enableInterruptRejectsUnknownSourcesAndPins() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertThrows(IllegalArgumentException.class, () -> chip.enableInterrupt(0x8000, 1));
        assertThrows(IllegalArgumentException.class,
                () -> chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 3));
    }

    @Test
    void pollInterruptCombinesTheEnabledSources() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);

        // Nothing enabled -> nothing reported.
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x08);
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x40);
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x40);
        assertEquals(0, chip.pollInterrupt());

        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 1);
        chip.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1);
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1);
        int status = chip.pollInterrupt();
        assertEquals(Lis3dhSource.SOURCE_DATA_READY
                | Lis3dhSource.SOURCE_IA1
                | Lis3dhSource.SOURCE_CLICK, status);
    }

    @Test
    void pollInterruptReportsTheFifoFlags() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.enableInterrupt(Lis3dhSource.SOURCE_WATERMARK, 1);
        chip.enableInterrupt(Lis3dhSource.SOURCE_OVERRUN, 1);
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xC0);
        assertEquals(Lis3dhSource.SOURCE_WATERMARK | Lis3dhSource.SOURCE_OVERRUN,
                chip.pollInterrupt());
    }

    // -------------------------------------------------------------------------
    // System functions
    // -------------------------------------------------------------------------

    @Test
    void sdoPullUp() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.setSdoPullup(false);
        assertEquals(0x90, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0));
        chip.setSdoPullup(true);
        assertEquals(0x10, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0));
    }

    @Test
    void rebootSetsBootAndRerunsTheInitSequence() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.reboot();
        assertTrue(writeWithBitSeen(connection, Lis3dhMinimal.REG_CTRL_REG5, 0x80));
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));
    }

    @Test
    void selfTestDetectsTheExpectedDelta() throws IOException {
        SelfTestConnection connection = new SelfTestConnection();
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33);
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertTrue(chip.selfTest(1));
    }

    @Test
    void selfTestFailsWithoutADelta() throws IOException {
        MockConnection connection = newConnection();
        Lis3dhFull chip = new Lis3dhFull(connection);
        assertFalse(chip.selfTest(1));
        assertThrows(IllegalArgumentException.class, () -> chip.selfTest(3));
    }

    @Test
    void selfTestRestoresTheConfiguration() throws IOException {
        SelfTestConnection connection = new SelfTestConnection();
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33);
        Lis3dhFull chip = new Lis3dhFull(connection);
        chip.selfTest(1);
        assertEquals(0x57, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1));
        assertEquals(0x88, lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4));
    }

    /**
     * Mock that reports a self-test step: the acceleration jumps by 100
     * counts as soon as {@code CTRL_REG4.ST} is set, so the self-test delta
     * lands inside the datasheet's 17..360 LSb window.
     */
    private static class SelfTestConnection extends MockConnection {

        @Override
        public byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = data[0] & 0xFF;
            if (n == 6 && (reg & 0x7F) == Lis3dhMinimal.REG_OUT_X_L) {
                int ctrl4 = registers().getOrDefault(Lis3dhMinimal.REG_CTRL_REG4, 0);
                byte[] out = new byte[6];
                if ((ctrl4 & 0x06) != 0) {
                    // 100 counts at 10-bit: raw16 = 100 << 6 = 0x1900.
                    out[1] = 0x19;
                    out[3] = 0x19;
                    out[5] = 0x19;
                }
                writes().add(data.clone());
                return out;
            }
            return super.writeRead(data, n);
        }
    }
}
