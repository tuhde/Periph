package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MMA8451QTest {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(MMA8451QMinimal.REG_WHO_AM_I, 0x1A);
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00);
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x00);
        c.setRegister(MMA8451QMinimal.REG_XYZ_DATA_CFG, 0x00);
        c.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00);
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00);
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x00);
        c.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x00);
        // 14-bit data: X = 0x4000 >> 2 = 4096 counts = 1 g; Y = -0x1000 >> 2 = -1024
        // counts = -0.25 g; Z = 0.
        c.setRegister(MMA8451QMinimal.REG_OUT_X_MSB, 0x40, 0x00);
        c.setRegister(MMA8451QMinimal.REG_OUT_Y_MSB, 0xF0, 0x00);
        c.setRegister(MMA8451QMinimal.REG_OUT_Z_MSB, 0x00, 0x00);
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

    private static int writeCountTo(MockConnection connection, int reg) {
        int n = 0;
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg) n++;
        }
        return n;
    }

    @Test
    void initSequenceWrites() throws IOException {
        MockConnection connection = newConnection();
        new MMA8451QMinimal(connection);
        // Software reset (CTRL_REG2 = 0x40), then XYZ_DATA_CFG = 0x00,
        // CTRL_REG2 = 0x00, CTRL_REG1 = 0x19.
        boolean resetWritten = false;
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == MMA8451QMinimal.REG_CTRL_REG2
                    && (w[1] & 0xFF) == 0x40) {
                resetWritten = true;
            }
        }
        assertTrue(resetWritten, "the software reset must write 0x40 to CTRL_REG2");
        assertEquals(0x00, lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG));
        assertEquals(0x00, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2));
        assertEquals(0x19, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1));
        // WHO_AM_I was read.
        boolean whoRead = false;
        for (byte[] w : connection.writes()) {
            if (w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_WHO_AM_I) whoRead = true;
        }
        assertTrue(whoRead, "WHO_AM_I must be read during init");
    }

    @Test
    void badWhoAmIThrows() {
        MockConnection connection = newConnection();
        connection.setRegister(MMA8451QMinimal.REG_WHO_AM_I, 0x00);
        assertThrows(IOException.class, () -> new MMA8451QMinimal(connection));
    }

    @Test
    void readConverts14BitCounts() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QMinimal accel = new MMA8451QMinimal(connection);
        double[] xyz = accel.read();
        // 4096 counts / 4096 counts-per-g = 1 g; -1024 counts = -0.25 g.
        assertEquals(1.0, xyz[0], 1e-9);
        assertEquals(-0.25, xyz[1], 1e-9);
        assertEquals(0.0, xyz[2], 1e-9);
    }

    @Test
    void readRawCounts() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        int[] raw = full.readRaw();
        assertEquals(4096, raw[0]);
        assertEquals(-1024, raw[1]);
        assertEquals(0, raw[2]);
    }

    @Test
    void setRangeUpdatesSensitivity() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setRange(4);
        // XYZ_DATA_CFG bits 1:0 = 0b01 for ±4 g.
        assertEquals(0x01, lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03);
        // 4096 counts at ±4 g = 2048 counts/g = 2 g.
        double[] xyz = full.read();
        assertEquals(2.0, xyz[0], 1e-9);

        connection.setRegister(MMA8451QMinimal.REG_XYZ_DATA_CFG, 0x01);
        full.setRange(8);
        assertEquals(0x02, lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03);
    }

    @Test
    void setRangeRejectsInvalid() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        assertThrows(IllegalArgumentException.class, () -> full.setRange(3));
    }

    @Test
    void setDataRate() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setDataRate(50);
        // CTRL_REG1 bits 5:3 = 0b100 for 50 Hz.
        assertEquals(0x04, (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x38) >> 3);
        assertThrows(IllegalArgumentException.class, () -> full.setDataRate(123));
    }

    @Test
    void setOversampling() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00);
        full.setOversampling(2);
        // MODS bits 1:0 = 0b10, SMODS bits 4:3 = 0b10 << 3 = 0x10.
        assertEquals(0x12, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x1B);
        full.setOversampling(3, 1);
        // SMODS = 0b01 << 3 = 0x08, MODS = 0b11.
        assertEquals(0x0B, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x1B);
        assertThrows(IllegalArgumentException.class, () -> full.setOversampling(4));
    }

    @Test
    void lowNoiseAndRangeInteraction() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setLowNoise(true);
        assertEquals(0x04, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x04);
        assertThrows(IllegalArgumentException.class, () -> full.setRange(8));
    }

    @Test
    void setResolution8Bit() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setResolution(8);
        assertEquals(0x02, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x02);
        // 8-bit mode reads only the MSB registers: X = 0x40 = 64 counts.
        int[] raw = full.readRaw();
        assertEquals(64, raw[0]);
        assertEquals(-16, raw[1]);
        assertThrows(IllegalArgumentException.class, () -> full.setResolution(12));
    }

    @Test
    void resolutionChangeBlockedWhileFifoEnabled() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        full.setFifo(1, 8);
        assertThrows(IllegalArgumentException.class, () -> full.setResolution(8));
    }

    @Test
    void standbyAndActivate() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.standby();
        assertEquals(0, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x01);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x18);
        full.activate();
        assertEquals(0x01, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0x01);
    }

    @Test
    void resetClearsCachedState() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        full.reset();
        assertEquals(0x40, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x40);
    }

    @Test
    void offsetsUseTwoMilliGramLsb() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setOffset(0.010, -0.020, 0.000);
        assertEquals(5, lastWriteTo(connection, MMA8451QMinimal.REG_OFF_X));
        assertEquals(0xF6, lastWriteTo(connection, MMA8451QMinimal.REG_OFF_Y));
        assertEquals(0, lastWriteTo(connection, MMA8451QMinimal.REG_OFF_Z));

        connection.setRegister(MMA8451QMinimal.REG_OFF_X, 5);
        connection.setRegister(MMA8451QMinimal.REG_OFF_Y, 0xF6);
        connection.setRegister(MMA8451QMinimal.REG_OFF_Z, 0);
        double[] off = full.getOffset();
        assertEquals(0.010, off[0], 1e-9);
        assertEquals(-0.020, off[1], 1e-9);
        assertEquals(0.0, off[2], 1e-9);
    }

    @Test
    void statusAndSystemMode() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x08);
        assertTrue(full.newDataAvailable());
        assertEquals(0x08, full.readStatus());
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x00);
        assertFalse(full.newDataAvailable());

        connection.setRegister(MMA8451QMinimal.REG_SYSMOD, 0x01);
        assertEquals(1, full.readSystemMode());
        connection.setRegister(MMA8451QMinimal.REG_SYSMOD, 0x02);
        assertEquals(2, full.readSystemMode());
    }

    @Test
    void fifoConfigurationAndStatus() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setFifo(1, 16);
        // F_MODE = 0b01 in bits 7:6, watermark 16 in bits 5:0.
        assertEquals(0x50, lastWriteTo(connection, MMA8451QMinimal.REG_F_SETUP));

        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0xC2);
        MMA8451QFull.FifoStatus st = full.readFifoStatus();
        assertTrue(st.overflow());
        assertTrue(st.watermark());
        assertEquals(2, st.count());
        assertEquals(2, full.fifoLevel());
    }

    @Test
    void fifoReadDrainsSamples() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_STATUS, 0x02);
        double[][] samples = full.readFifo();
        assertEquals(2, samples.length);
        assertEquals(1.0, samples[0][0], 1e-9);
        assertEquals(-0.25, samples[0][1], 1e-9);
    }

    @Test
    void fifoTriggerSources() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setFifoTrigger(true, false, true, false);
        // TRIG_CFG bits 5..2: transient = 0x20, pulse = 0x08.
        assertEquals(0x28, lastWriteTo(connection, MMA8451QMinimal.REG_TRIG_CFG));
    }

    @Test
    void freefallMotionThresholdUsesSixtyThreeMilliGramLsb() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setFreefallMotion(false, 7, 0.2, 60, true, true);
        // FF_MT_CFG: ELE = 0x80, axes 0x38, freefall (OAE = 0).
        assertEquals(0xB8, lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_CFG));
        // FF_MT_THS: DBCNTM = 0x80, THS = round(0.2 / 0.063) = 3.
        assertEquals(0x83, lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_THS));
        // FF_MT_COUNT: 60 ms / 10 ms step at 100 Hz = 6.
        assertEquals(6, lastWriteTo(connection, MMA8451QMinimal.REG_FF_MT_COUNT));
    }

    @Test
    void transientConfiguration() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setTransient(7, 0.5, 0, false, true);
        // TRANSIENT_CFG: ELE = 0x10, axes 0x0E.
        assertEquals(0x1E, lastWriteTo(connection, MMA8451QMinimal.REG_TRANSIENT_CFG));
        // THS = round(0.5 / 0.063) = 8.
        assertEquals(8, lastWriteTo(connection, MMA8451QMinimal.REG_TRANSIENT_THS));
    }

    @Test
    void pulseConfiguration() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        connection.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00);
        full.setPulse(0, 0x04, 0.5, 0.5, 1.0, 40, 100, 300, true, false, true, true);
        // PULSE_CFG: DPA=0, ELE=0x40, ZDPEFE=0x20.
        assertEquals(0x60, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_CFG));
        // PULSE_THSX = round(0.5 / 0.063) = 8; PULSE_THSZ = round(1.0 / 0.063) = 16.
        assertEquals(8, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_THSX));
        assertEquals(16, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_THSZ));
        // PULSE_TMLT: 40 ms / 10 ms step (LPF on, 100 Hz) = 4.
        assertEquals(4, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_TMLT));
        // PULSE_LTCY: 100 ms / 20 ms step = 5.
        assertEquals(5, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_LTCY));
        // PULSE_WIND: 300 ms / 20 ms step = 15.
        assertEquals(15, lastWriteTo(connection, MMA8451QMinimal.REG_PULSE_WIND));
        // HP_FILTER_CUTOFF: Pulse_HPF_BYP = 0, Pulse_LPF_EN = 1.
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_HP_FILTER));
    }

    @Test
    void orientationConfigurationAndTable() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        full.setOrientation(true, 100, 45, 4, 4, 1);
        // PL_CFG: DBCNTM = 0x80, PL_EN = 0x40.
        assertEquals(0xC0, lastWriteTo(connection, MMA8451QMinimal.REG_PL_CFG));
        // PL_COUNT: 100 ms / 10 ms step = 10.
        assertEquals(10, lastWriteTo(connection, MMA8451QMinimal.REG_PL_COUNT));
        // P_L_THS_REG: 45 deg -> 0x10 << 3 = 0x80, HYS = 4.
        assertEquals(0x84, lastWriteTo(connection, MMA8451QMinimal.REG_P_L_THS));
        // PL_BF_ZCOMP: BKFR = 1 -> 0x40, ZLOCK = 4 -> 0x04.
        assertEquals(0x44, lastWriteTo(connection, MMA8451QMinimal.REG_PL_BF_ZCOMP));

        // An unsupported trip angle is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> full.setOrientation(true, 0, 47, 4, 4, 1));
        // THS + HYS outside 1..31 is rejected (75 deg -> 0x19, HYS = 7 -> 0x1C).
        assertThrows(IllegalArgumentException.class,
                () -> full.setOrientation(true, 0, 75, 7, 4, 1));
    }

    @Test
    void readOrientationDecodesStatus() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        // NEWLP | LO | LAPO = 0b10 | BAFRO
        connection.setRegister(MMA8451QMinimal.REG_PL_STATUS, 0xC5);
        MMA8451QFull.Orientation o = full.readOrientation();
        assertTrue(o.changed());
        assertTrue(o.lockout());
        assertEquals(2, o.portraitLandscape());
        assertTrue(o.back());
    }

    @Test
    void autoSleepConfiguration() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG2, 0x00);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00);
        full.setAutoSleep(true, 12.5, 10, MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT);
        // CTRL_REG1 bits 7:6 = 0b01 for 12.5 Hz.
        assertEquals(0x01, (lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1) & 0xC0) >> 6);
        // CTRL_REG2 SLPE = 0x04.
        assertEquals(0x04, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG2) & 0x04);
        // CTRL_REG3 WAKE_PULSE = 0x10, WAKE_FF_MT = 0x08.
        assertEquals(0x18, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x78);
        // ASLP_COUNT: 10 s / 0.32 s = 31.
        assertEquals(31, lastWriteTo(connection, MMA8451QMinimal.REG_ASLP_COUNT));
        assertThrows(IllegalArgumentException.class, () -> full.setAutoSleep(true, 99));
    }

    @Test
    void fifoGateAndInterruptPins() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00);
        full.setFifoGate(true);
        assertEquals(0x80, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x80);

        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x00);
        full.setInterruptPins(true, true);
        // IPOL = 0x02, PP_OD = 0x01.
        assertEquals(0x03, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x03);

        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG3, 0x03);
        full.setInterruptPins(false, false);
        assertEquals(0, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG3) & 0x03);
    }

    @Test
    void enableAndDisableInterrupt() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x00);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x00);
        full.enableInterrupt(MMA8451QFull.SOURCE_ORIENTATION, 1);
        // CTRL_REG4 INT_EN_LNDPRT = 0x10.
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x10);
        // CTRL_REG5 INT_CFG_LNDPRT = 0x10 routes to INT1.
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG5) & 0x10);

        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x10);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG5, 0x10);
        full.enableInterrupt(MMA8451QFull.SOURCE_PULSE, 2);
        // CTRL_REG4 now has LNDPRT | PULSE.
        assertEquals(0x18, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x18);
        // CTRL_REG5: PULSE routed to INT2 -> bit cleared.
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG5) & 0x18);

        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG4, 0x18);
        full.disableInterrupt(MMA8451QFull.SOURCE_PULSE);
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG4) & 0x18);

        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(0x02, 1));
        assertThrows(IllegalArgumentException.class, () -> full.enableInterrupt(0x10, 3));
    }

    @Test
    void pollInterruptClearsPerSourceFlags() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        // FIFO | PULSE | DRDY set.
        connection.setRegister(MMA8451QMinimal.REG_INT_SOURCE,
                MMA8451QFull.SOURCE_FIFO | MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_DATA_READY);
        int status = full.pollInterrupt();
        assertEquals(MMA8451QFull.SOURCE_FIFO | MMA8451QFull.SOURCE_PULSE
                | MMA8451QFull.SOURCE_DATA_READY, status);
        // F_STATUS (0x00) and PULSE_SRC (0x22) must have been read.
        boolean readStatus = false;
        boolean readPulseSrc = false;
        for (byte[] w : connection.writes()) {
            if (w.length != 1) continue;
            int reg = w[0] & 0xFF;
            if (reg == MMA8451QMinimal.REG_STATUS) readStatus = true;
            if (reg == MMA8451QMinimal.REG_PULSE_SRC) readPulseSrc = true;
        }
        assertTrue(readStatus, "F_STATUS must be read to clear the FIFO source");
        assertTrue(readPulseSrc, "PULSE_SRC must be read to clear the pulse source");
    }

    @Test
    void pollInterruptDoesNotClearDataReady() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_INT_SOURCE, MMA8451QFull.SOURCE_DATA_READY);
        assertEquals(MMA8451QFull.SOURCE_DATA_READY, full.pollInterrupt());
        // The XYZ data registers must not have been touched.
        for (byte[] w : connection.writes()) {
            if (w.length == 1 && (w[0] & 0xFF) == MMA8451QMinimal.REG_OUT_X_MSB) {
                throw new AssertionError("data-ready must not read the data registers");
            }
        }
    }

    @Test
    void onAndOffInterruptAreAccepted() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        int[] seen = new int[1];
        full.onInterrupt(s -> seen[0] = s);
        full.offInterrupt();
        assertEquals(0, seen[0]);
    }

    @Test
    void selfTestRestoresConfiguration() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        MMA8451QFull.SelfTestResult r = full.selfTest();
        // The mock returns the same sample for every read, so the deltas are 0
        // and the heuristic reports "not ok".
        assertFalse(r.ok());
        assertEquals(0, r.dx());
        assertEquals(0, r.dy());
        assertEquals(0, r.dz());
        // The self-test forces ST on CTRL_REG2 at least once.
        boolean stSet = false;
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == MMA8451QMinimal.REG_CTRL_REG2
                    && (w[1] & 0x80) != 0) {
                stSet = true;
            }
        }
        assertTrue(stSet, "CTRL_REG2.ST must be set during the self-test");
        // The configuration is restored at the end (±2 g -> FS bits 0b00).
        assertEquals(0x00, lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x03);
    }

    @Test
    void highPassOutput() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        connection.setRegister(MMA8451QMinimal.REG_HP_FILTER, 0x00);
        full.setHighPassOutput(true, 2);
        // XYZ_DATA_CFG HPF_OUT = 0x10.
        assertEquals(0x10, lastWriteTo(connection, MMA8451QMinimal.REG_XYZ_DATA_CFG) & 0x10);
        // HP_FILTER_CUTOFF SEL = 2.
        assertEquals(0x02, lastWriteTo(connection, MMA8451QMinimal.REG_HP_FILTER) & 0x03);
        assertThrows(IllegalArgumentException.class, () -> full.setHighPassOutput(true, 4));
    }

    @Test
    void standbyWriteWrapsConfigurationRegisters() throws IOException {
        MockConnection connection = newConnection();
        MMA8451QFull full = new MMA8451QFull(connection);
        connection.setRegister(MMA8451QMinimal.REG_CTRL_REG1, 0x19);
        int before = writeCountTo(connection, MMA8451QMinimal.REG_CTRL_REG1);
        full.setRange(4);
        int after = writeCountTo(connection, MMA8451QMinimal.REG_CTRL_REG1);
        // STANDBY (clear ACTIVE) + restore ACTIVE = 2 extra writes.
        assertEquals(before + 2, after);
        // The last CTRL_REG1 write restores the original value.
        assertEquals(0x19, lastWriteTo(connection, MMA8451QMinimal.REG_CTRL_REG1));
    }
}
