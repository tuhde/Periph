package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LSM303Test {

    /** Fake INT pin: records the armed handler and trigger, and lets the test fire an edge. */
    static final class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;

        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return false; }
        @Override public void close() { }
    }

    /** Accelerometer mock; the burst address is reg|0x80 (the mock does not auto-increment). */
    private static MockConnection accelMock() {
        return new MockConnection();
    }

    /** Magnetometer mock with a valid identity. */
    private static MockConnection magMock() {
        MockConnection m = new MockConnection();
        m.setRegister(0x0A, 0x48, 0x34, 0x33);
        return m;
    }

    private static int lastWriteTo(MockConnection connection, int reg) {
        var writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    private static LSM303Full newFull(MockConnection accel, MockConnection mag) throws IOException {
        return new LSM303Full(accel, mag);
    }

    @Test
    void constructionWritesInitSequence() throws Exception {
        MockConnection accel = accelMock();
        MockConnection mag = magMock();
        new LSM303Minimal(accel, mag);

        assertEquals(0x88, lastWriteTo(accel, 0x23));  // CTRL_REG4_A
        assertEquals(0x57, lastWriteTo(accel, 0x20));  // CTRL_REG1_A
        assertEquals(0x00, lastWriteTo(accel, 0x24));  // CTRL_REG5_A
        assertEquals(0x10, lastWriteTo(mag, 0x00));    // CRA_REG_M
        assertEquals(0x20, lastWriteTo(mag, 0x01));    // CRB_REG_M
        assertEquals(0x00, lastWriteTo(mag, 0x02));    // MR_REG_M: out of sleep
    }

    @Test
    void magnetometerIdentityMismatchThrows() {
        MockConnection mag = new MockConnection();
        mag.setRegister(0x0A, 0x48, 0x34, 0x00);
        assertThrows(IOException.class, () -> new LSM303Minimal(accelMock(), mag));
    }

    @Test
    void readAccelerationUsesBurstAndLeftJustifiedData() throws Exception {
        MockConnection accel = accelMock();
        LSM303Minimal chip = new LSM303Minimal(accel, magMock());
        // Burst address 0x28|0x80 = 0xA8; LSB first. 0x4000 -> 1024 counts (1 mg/LSB, HR).
        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0xF0, 0x00, 0x10);
        double[] g = chip.readAcceleration();
        assertEquals(1.024, g[0], 1e-9);
        assertEquals(-0.256, g[1], 1e-9);
        assertEquals(0.256, g[2], 1e-9);
    }

    @Test
    void readMagneticReordersAndUsesZGain() throws Exception {
        MockConnection mag = magMock();
        LSM303Minimal chip = new LSM303Minimal(accelMock(), mag);
        // Wire order X, Z, Y, high byte first: X=1100, Z=980, Y=-2200.
        mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xF7, 0x68);
        double[] ut = chip.readMagnetic();
        assertEquals(100.0, ut[0], 1e-9);   // 1100 / 1100 gain * 100
        assertEquals(-200.0, ut[1], 1e-9);  // -2200 / 1100 * 100
        assertEquals(100.0, ut[2], 1e-9);   // 980 / 980 (Z gain) * 100
    }

    @Test
    void readFullScaleDependsOnRange() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setRange(4);
        assertEquals(0x98, lastWriteTo(accel, 0x23));  // FS=01 with HR and BDU kept
        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00);
        assertEquals(2.048, chip.readAcceleration()[0], 1e-9);  // HR ±4 g = 2 mg/LSB
        assertThrows(IllegalArgumentException.class, () -> chip.setRange(3));
    }

    @Test
    void setModeAndDataRateRules() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(1620));  // HR mode
        chip.setMode(0);
        assertEquals(0x5F, lastWriteTo(accel, 0x20));  // LPen=1 in CTRL_REG1
        chip.setDataRate(1620);
        assertEquals(0x8F, lastWriteTo(accel, 0x20));  // ODR=1000
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(1344));
        chip.setMode(1);
        chip.setDataRate(1344);
        assertEquals(0x90, lastWriteTo(accel, 0x20) & 0xF0);  // ODR=1001 in normal mode
        assertThrows(IllegalArgumentException.class, () -> chip.setMode(3));
    }

    @Test
    void powerDownAndAxes() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setAxes(true, false, true);
        assertEquals(0x55, lastWriteTo(accel, 0x20));
        chip.powerDown();
        assertEquals(0x05, lastWriteTo(accel, 0x20));
    }

    @Test
    void statusAndFifo() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());

        accel.setRegister(0x27, 0x08);
        assertTrue(chip.newDataAvailable());
        assertEquals(0x08, chip.readStatus());

        chip.setFifo(1, 16, false);
        assertEquals(0x50, lastWriteTo(accel, 0x2E));  // FM=01, WTM=16
        assertEquals(0x40, lastWriteTo(accel, 0x24));  // FIFO_EN set

        accel.setRegister(0x2F, 0xC5);                 // WTM=1, OVRN=1, EMPTY=0, FSS=5
        assertArrayEquals(new int[]{1, 1, 0, 5}, chip.readFifoStatus());
        assertEquals(5, chip.fifoLevel());

        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00);
        assertEquals(2, chip.readFifo(2).length);       // capped by FSS=5 → 2 requested
        assertEquals(5, chip.readFifo().length);        // all unread sets
    }

    @Test
    void highPassWritesCtrlReg2() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setHighPass(1, 2, true, true, false, true);
        assertEquals(0x6D, lastWriteTo(accel, 0x21));
        accel.setRegister(0x26, 0x5A);
        assertEquals(0x5A, chip.resetHighPass());
    }

    @Test
    void motionInterruptThresholdAndDuration() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setMotionInterrupt(1, 0x3F, 1.0, 100, true);
        assertEquals(0xBF, lastWriteTo(accel, 0x30));  // AOI | all six events
        assertEquals(64, lastWriteTo(accel, 0x32));    // 1 g * 128 / 2 g
        assertEquals(10, lastWriteTo(accel, 0x33));    // 100 ms * 100 Hz
    }

    @Test
    void freeFallUsesAndModeXyzLow() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setFreeFall(1, 0.35, 30);
        assertEquals(0x95, lastWriteTo(accel, 0x30));
        assertEquals(22, lastWriteTo(accel, 0x32));    // round(0.35 * 128 / 2)
        assertEquals(3, lastWriteTo(accel, 0x33));     // round(30 * 100 / 1000)
    }

    @Test
    void orientationAndLatch() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setOrientation(2, true, true);
        assertEquals(0xFF, lastWriteTo(accel, 0x34));  // position, 6D, all events
        assertEquals(0x01, lastWriteTo(accel, 0x24));  // D4D_INT2
        chip.setLatch(1, true);
        assertEquals(0x09, lastWriteTo(accel, 0x24));  // LIR_INT1 | D4D_INT2
        accel.setRegister(0x31, 0x40);
        assertEquals(0x40, chip.readInterruptSource(1));
    }

    @Test
    void clickEncodesAxisMasks() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.setClick(4, 4, 0.8, 20, 40, 200);
        assertEquals(0x30, lastWriteTo(accel, 0x38));  // single Z = bit4, double Z = bit5
        assertEquals(51, lastWriteTo(accel, 0x3A));
        assertEquals(2, lastWriteTo(accel, 0x3B));
        assertEquals(4, lastWriteTo(accel, 0x3C));
        assertEquals(20, lastWriteTo(accel, 0x3D));

        chip.setClick(1, 2, 0.5, 10, 20, 50);
        assertEquals(0x09, lastWriteTo(accel, 0x38));  // single X = bit0, double Y = bit3
    }

    @Test
    void routingSourcesToInt1AndInt2() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());

        chip.enableInterrupt(LSM303Full.SOURCE_IA1, 2);
        assertEquals(0x40, lastWriteTo(accel, 0x25));  // I2_INT1 on INT2
        chip.disableInterrupt(LSM303Full.SOURCE_IA1, 2);
        assertEquals(0x00, lastWriteTo(accel, 0x25));

        chip.enableInterrupt(LSM303Full.SOURCE_DATA_READY);
        assertEquals(0x10, lastWriteTo(accel, 0x22));  // I1_DRDY1 (INT1 only; pin ignored)

        assertThrows(IllegalArgumentException.class,
                () -> chip.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY));
        assertThrows(IllegalArgumentException.class, () -> chip.enableInterrupt(LSM303Full.SOURCE_IA1, 3));
    }

    @Test
    void pollReportsEnabledSourcesAndMagDataReady() throws Exception {
        MockConnection accel = accelMock();
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accel, mag);
        chip.enableInterrupt(LSM303Full.SOURCE_DATA_READY);
        chip.enableInterrupt(LSM303Full.SOURCE_IA1);
        accel.setRegister(0x27, 0x08);
        accel.setRegister(0x31, 0x40);
        assertEquals(0x0009, chip.pollInterrupt());

        mag.setRegister(0x09, 0x01);
        assertEquals(0x0089, chip.pollInterrupt());  // magnetometer DRDY always checked
    }

    @Test
    void magnetometerConfiguration() throws Exception {
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accelMock(), mag);

        chip.setMagRange(810);
        assertEquals(0xE0, lastWriteTo(mag, 0x01));    // GN=111
        mag.setRegister(0x03, 0x00, 0xE6, 0x00, 0xCD, 0x00, 0x00);  // X=230, Z=205
        assertEquals(100.0, chip.readMagnetic()[0], 1e-9);
        assertEquals(100.0, chip.readMagnetic()[2], 1e-9);

        chip.setMagDataRate(220);
        assertEquals(0x1C, lastWriteTo(mag, 0x00));    // DO=111, TEMP_EN still off
        assertThrows(IllegalArgumentException.class, () -> chip.setMagDataRate(3.7));
        assertThrows(IllegalArgumentException.class, () -> chip.setMagRange(300));

        chip.setMagMode(2);
        assertEquals(0x02, lastWriteTo(mag, 0x02));
    }

    @Test
    void magnetometerStatusAndIdentity() throws Exception {
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accelMock(), mag);
        mag.setRegister(0x09, 0x03);
        assertTrue(chip.magDataReady());
        assertArrayEquals(new int[]{1, 1}, chip.readMagStatus());
        assertArrayEquals(new byte[]{0x48, 0x34, 0x33}, chip.readMagIdentity());
    }

    @Test
    void temperatureDeltaEnablesSensorOnFirstUse() throws Exception {
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accelMock(), mag);
        mag.setRegister(0x31, 0x01, 0x00);  // +256 raw → 16 LSB → 2 °C
        assertEquals(2.0, chip.readTemperatureDelta(), 1e-9);
        assertEquals(0x90, lastWriteTo(mag, 0x00));  // TEMP_EN bit set, DO=100 kept

        mag.setRegister(0x31, 0xFC, 0x00);  // -1024 raw → -64 LSB → -8 °C
        assertEquals(-8.0, chip.readTemperatureDelta(), 1e-9);
    }

    @Test
    void headingFlatAndWrap() throws Exception {
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accelMock(), mag);

        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x4C);  // Y=+1100
        assertEquals(90.0, chip.heading(false), 1e-6);

        mag.setRegister(0x03, 0xFB, 0xB4, 0x00, 0x00, 0x00, 0x00);  // X=-1100
        assertEquals(180.0, chip.heading(false), 1e-6);

        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0xFB, 0xB4);  // Y=-1100
        assertEquals(270.0, chip.heading(false), 1e-6);
    }

    @Test
    void headingTiltCompensatedLevelMatchesFlat() throws Exception {
        MockConnection accel = accelMock();
        MockConnection mag = magMock();
        LSM303Full chip = newFull(accel, mag);
        accel.setRegister(0xA8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40);  // gravity on +Z
        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x4C);    // Y=+1100
        assertEquals(90.0, chip.heading(), 1e-6);
    }

    @Test
    void reboot() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.reboot();
        assertEquals(0x80, lastWriteTo(accel, 0x24) & 0x80);
    }

    @Test
    void onInterruptFiresOnPinEdge() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.enableInterrupt(LSM303Full.SOURCE_IA1);
        accel.setRegister(0x31, 0x40);
        FakePin pin = new FakePin();
        AtomicInteger got = new AtomicInteger(-1);

        chip.onInterrupt(got::set, 1, pin);
        assertEquals(EdgeTrigger.RISING, pin.trigger);
        pin.handler.onEdge();
        assertEquals(LSM303Full.SOURCE_IA1, got.get());

        chip.offInterrupt(1);
        assertNull(pin.handler);

        chip.setInterruptPolarity(true);
        chip.onInterrupt(got::set, 1, pin);
        assertEquals(EdgeTrigger.FALLING, pin.trigger);
        chip.offInterrupt();
    }

    @Test
    void onInterruptPollingFallbackDelivers() throws Exception {
        MockConnection accel = accelMock();
        LSM303Full chip = newFull(accel, magMock());
        chip.enableInterrupt(LSM303Full.SOURCE_IA2, 2);
        accel.setRegister(0x35, 0x40);
        AtomicInteger got = new AtomicInteger(-1);
        chip.onInterrupt(got::set, 2);  // no pin for INT2: 5 ms polling thread
        long deadline = System.currentTimeMillis() + 2000;
        while (got.get() == -1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        chip.offInterrupt(2);
        assertEquals(LSM303Full.SOURCE_IA2, got.get());
    }

    @Test
    void invalidPinsAndGeneratorsRejected() throws Exception {
        LSM303Full chip = newFull(accelMock(), magMock());
        assertThrows(IllegalArgumentException.class, () -> chip.readInterruptSource(3));
        assertThrows(IllegalArgumentException.class, () -> chip.onInterrupt(v -> { }, 0));
        assertFalse(chip.magDataReady());
    }
}
