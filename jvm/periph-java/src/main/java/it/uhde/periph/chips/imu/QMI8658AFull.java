package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * QMI8658A — 6-axis inertial measurement unit (QST), full driver extending {@link QMI8658AMinimal}.
 *
 * <p>Adds raw and burst reads, temperature, sample counter, full-scale / ODR / filter
 * configuration, power control, SyncSample data locking, the FIFO, the motion engines (any / no /
 * significant motion, tap, pedometer, wake-on-motion), offsets, calibration-on-demand, self-test,
 * device info, pull-up control and interrupts.
 */
public class QMI8658AFull extends QMI8658AMinimal {

    /** Interrupt source: new sample (only while the FIFO is in bypass; INT2). */
    public static final int SOURCE_DATA_READY = 0x0001;
    /** Interrupt source: FIFO watermark. */
    public static final int SOURCE_FIFO_WATERMARK = 0x0002;
    /** Interrupt source: FIFO full. */
    public static final int SOURCE_FIFO_FULL = 0x0004;
    /** Interrupt source: any-motion event. */
    public static final int SOURCE_ANY_MOTION = 0x0008;
    /** Interrupt source: no-motion event. */
    public static final int SOURCE_NO_MOTION = 0x0010;
    /** Interrupt source: significant-motion event. */
    public static final int SOURCE_SIGNIFICANT_MOTION = 0x0020;
    /** Interrupt source: pedometer step event. */
    public static final int SOURCE_STEP = 0x0040;
    /** Interrupt source: tap event. */
    public static final int SOURCE_TAP = 0x0080;
    /** Interrupt source: wake-on-motion event. */
    public static final int SOURCE_WAKE_ON_MOTION = 0x0100;

    private static final int CMD_ACK = 0x00;
    private static final int CMD_RST_FIFO = 0x04;
    private static final int CMD_REQ_FIFO = 0x05;
    private static final int CMD_WRITE_WOM = 0x08;
    private static final int CMD_ACCEL_OFFSET = 0x09;
    private static final int CMD_GYRO_OFFSET = 0x0A;
    private static final int CMD_CONFIG_TAP = 0x0C;
    private static final int CMD_CONFIG_PEDOMETER = 0x0D;
    private static final int CMD_CONFIG_MOTION = 0x0E;
    private static final int CMD_RESET_PEDOMETER = 0x0F;
    private static final int CMD_COPY_USID = 0x10;
    private static final int CMD_SET_RPU = 0x11;
    private static final int CMD_AHB_CLOCK_GATING = 0x12;
    private static final int CMD_COD = 0xA2;
    private static final int CMD_APPLY_GYRO_GAINS = 0xAA;

    /** One coherent sample from {@link #readAll()}. */
    public static final class Sample {
        /** Acceleration {x, y, z} in m/s². */
        public final double[] accel;
        /** Angular rate {x, y, z} in rad/s. */
        public final double[] gyro;
        /** Die temperature in °C. */
        public final double tempC;

        Sample(double[] accel, double[] gyro, double tempC) {
            this.accel = accel;
            this.gyro = gyro;
            this.tempC = tempC;
        }
    }

    /** Last tap event from {@link #tapStatus()}. */
    public static final class TapStatus {
        /** 0 none, 1 single tap, 2 double tap. */
        public final int count;
        /** 0 none, 1 X, 2 Y, 3 Z. */
        public final int axis;
        /** True for the negative direction of the tap axis. */
        public final boolean negative;

        TapStatus(int count, int axis, boolean negative) {
            this.count = count;
            this.axis = axis;
            this.negative = negative;
        }
    }

    /** Result of a self-test. */
    public static final class SelfTestResult {
        /** True when every axis exceeded the datasheet limit. */
        public final boolean passed;
        /** Response {x, y, z}: m/s² (accelerometer) or rad/s (gyroscope). */
        public final double[] values;

        SelfTestResult(boolean passed, double[] values) {
            this.passed = passed;
            this.values = values;
        }
    }

    /** Result of {@link #calibrateGyro()}. */
    public static final class Calibration {
        /** True when {@code COD_STATUS} is 0x00. */
        public final boolean success;
        /** Raw {@code COD_STATUS} register. */
        public final int codStatus;

        Calibration(boolean success, int codStatus) {
            this.success = success;
            this.codStatus = codStatus;
        }
    }

    /** Revision, firmware version and USID from {@link #deviceInfo()}. */
    public static final class DeviceInfo {
        /** {@code REVISION_ID} register. */
        public final int revision;
        /** Firmware version, 3 bytes. */
        public final byte[] firmware;
        /** Unique serial ID, 6 bytes. */
        public final byte[] usid;

        DeviceInfo(int revision, byte[] firmware, byte[] usid) {
            this.revision = revision;
            this.firmware = firmware;
            this.usid = usid;
        }
    }

    private double anyThr = 0.25, noThr = 0.125;
    private int anyWin = 3, noWin = 30, anyAxes = 0x07, noAxes = 0x07;
    private boolean anyAnd = false, noAnd = true;
    private int sigWait = 150, sigConfirm = 600;
    private int intSources = 0;
    private int fifoPin = 2, activityPin = 2, womPin = 1;
    private IntConsumer callback;
    private InputPin intPin;
    private Thread pollThread;
    private volatile boolean polling;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Create the Full driver; same arguments as {@link QMI8658AMinimal#QMI8658AMinimal}.
     *
     * @param connection register connection bound to the device
     * @throws IOException on bus error or a wrong {@code WHO_AM_I}
     */
    public QMI8658AFull(RegisterConnection connection) throws IOException {
        super(connection);
    }

    private void setCtrl1(int v) throws IOException { ctrl1 = v & 0xFF; w(REG_CTRL1, ctrl1); }
    private void setCtrl7(int v) throws IOException { ctrl7 = v & 0xFF; w(REG_CTRL7, ctrl7); }
    private void setCtrl8(int v) throws IOException { ctrl8 = v & 0xFF; w(REG_CTRL8, ctrl8); }

    private void waitStatus(int mask, boolean want, long timeoutMs, String what) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int st = r1(REG_STATUSINT);
            if (((st & mask) != 0) == want) return;
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("QMI8658A timeout waiting for " + what);
            }
            sleepMs(1);
        }
    }

    private void ctrl9(int cmd, long timeoutMs) throws IOException {
        w(REG_CTRL9, cmd);
        IOException failure = null;
        try {
            waitStatus(0x80, true, timeoutMs, "CTRL9 CmdDone");
        } catch (IOException e) {
            failure = e;
        }
        w(REG_CTRL9, CMD_ACK);
        if (failure != null) throw failure;
        waitStatus(0x80, false, timeoutMs, "CTRL9 ack");
    }

    private void calCmd(int cmd, int[] params, long timeoutMs) throws IOException {
        for (int i = 0; i < 8; i++) w(REG_CAL1_L + i, i < params.length ? params[i] : 0);
        ctrl9(cmd, timeoutMs);
    }

    private void twoSets(int cmd, int[] set1, int[] set2) throws IOException {
        int[] p = new int[8];
        System.arraycopy(set1, 0, p, 0, set1.length);
        p[7] = 0x10;
        calCmd(cmd, p, 100);
        p = new int[8];
        System.arraycopy(set2, 0, p, 0, set2.length);
        p[7] = 0x20;
        calCmd(cmd, p, 100);
    }

    private int sensorsOff() throws IOException {
        int prev = ctrl7 & 0x03;
        if (prev != 0) {
            setCtrl7(ctrl7 & ~0x03);
            sleepMs(5);
        }
        return prev;
    }

    private void sensorsRestore(int prev) throws IOException {
        if (prev != 0) {
            setCtrl7(ctrl7 | prev);
            sleepMs((prev & 0x02) != 0 ? 165 : 10);
        }
    }

    @Override
    protected byte[] readData(int reg, int length) throws IOException {
        if (sync) {
            waitStatus(0x01, true, 100, "SyncSample Avail");
            waitStatus(0x02, true, 100, "SyncSample Locked");
        }
        return connection.read(reg, length);
    }

    /**
     * Read raw acceleration counts.
     *
     * @return signed 16-bit {x, y, z}
     * @throws IOException on bus error
     */
    public int[] accelRaw() throws IOException {
        byte[] b = readData(REG_AX_L, 6);
        return new int[]{le16(b, 0), le16(b, 2), le16(b, 4)};
    }

    /**
     * Read raw angular-rate counts.
     *
     * @return signed 16-bit {x, y, z}
     * @throws IOException on bus error
     */
    public int[] gyroRaw() throws IOException {
        byte[] b = readData(REG_GX_L, 6);
        return new int[]{le16(b, 0), le16(b, 2), le16(b, 4)};
    }

    /**
     * Burst-read temperature, acceleration and angular rate so all three belong to one sample.
     *
     * @return the sample (m/s², rad/s, °C)
     * @throws IOException on bus error
     */
    public Sample readAll() throws IOException {
        byte[] b = readData(REG_TEMP_L, 14);
        double[] a = new double[3];
        double[] g = new double[3];
        for (int i = 0; i < 3; i++) {
            a[i] = le16(b, 2 + 2 * i) / accelSens * G;
            g[i] = le16(b, 8 + 2 * i) / gyroSens * DEG_TO_RAD;
        }
        return new Sample(a, g, le16(b, 0) / 256.0);
    }

    /**
     * Read the die temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double temperature() throws IOException {
        return le16(readData(REG_TEMP_L, 2), 0) / 256.0;
    }

    /**
     * Read the 24-bit sample counter.
     *
     * @return counter value, wraps at 0xFFFFFF
     * @throws IOException on bus error
     */
    public int timestamp() throws IOException {
        byte[] b = connection.read(REG_TIMESTAMP, 3);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16);
    }

    /**
     * Check for new sensor data ({@code STATUS0} aDA / gDA; {@code STATUSINT.Avail} while SyncSample is on).
     *
     * @return true when an enabled sensor has new data
     * @throws IOException on bus error
     */
    public boolean dataReady() throws IOException {
        if (sync) return (r1(REG_STATUSINT) & 0x01) != 0;
        return (r1(REG_STATUS0) & 0x03) != 0;
    }

    /**
     * Configure the accelerometer (CTRL2).
     *
     * @param fullScale 0 = ±2 g, 1 = ±4 g, 2 = ±8 g, 3 = ±16 g
     * @param odr ODR code 0–8 or 12–15 (12–15 only with the gyroscope off)
     * @throws IOException on bus error
     * @throws IllegalArgumentException on an out-of-range argument
     */
    public void configureAccel(int fullScale, int odr) throws IOException {
        if (fullScale < 0 || fullScale > 3) throw new IllegalArgumentException("fullScale must be 0-3");
        if (!((odr >= 0 && odr <= 8) || (odr >= 12 && odr <= 15))) {
            throw new IllegalArgumentException("odr must be 0-8 or 12-15");
        }
        accelFs = fullScale;
        accelOdr = odr;
        updateSens();
        w(REG_CTRL2, (fullScale << 4) | odr);
    }

    /**
     * Configure the gyroscope (CTRL3).
     *
     * @param fullScale 0 = ±16 dps … 7 = ±2048 dps (doubling)
     * @param odr ODR code 0–8
     * @throws IOException on bus error
     * @throws IllegalArgumentException on an out-of-range argument
     */
    public void configureGyro(int fullScale, int odr) throws IOException {
        if (fullScale < 0 || fullScale > 7) throw new IllegalArgumentException("fullScale must be 0-7");
        if (odr < 0 || odr > 8) throw new IllegalArgumentException("odr must be 0-8");
        gyroFs = fullScale;
        gyroOdr = odr;
        updateSens();
        w(REG_CTRL3, (fullScale << 4) | odr);
    }

    /**
     * Configure the low-pass filters (CTRL5).
     *
     * @param accelFilter enable the accelerometer filter
     * @param accelMode 0–3 = 2.66 / 3.63 / 5.39 / 13.37 % of ODR
     * @param gyroFilter enable the gyroscope filter
     * @param gyroMode 0–3, same bandwidths
     * @throws IOException on bus error
     */
    public void configureFilters(boolean accelFilter, int accelMode, boolean gyroFilter, int gyroMode)
            throws IOException {
        if (accelMode < 0 || accelMode > 3 || gyroMode < 0 || gyroMode > 3) {
            throw new IllegalArgumentException("filter mode must be 0-3");
        }
        w(REG_CTRL5, (gyroMode << 5) | (gyroFilter ? 0x10 : 0) | (accelMode << 1) | (accelFilter ? 1 : 0));
    }

    /**
     * Enable or disable the sensors (CTRL7 aEN / gEN / gSN). The gyroscope needs 150 ms + 3/ODR,
     * the accelerometer 3 ms + 3/ODR before data is valid.
     *
     * @param accel enable the accelerometer
     * @param gyro enable the gyroscope
     * @param gyroSnooze gyroscope snooze (drive only, no data)
     * @throws IOException on bus error
     */
    public void setSensors(boolean accel, boolean gyro, boolean gyroSnooze) throws IOException {
        setCtrl7((ctrl7 & ~0x13) | (accel ? 0x01 : 0) | (gyro ? 0x02 : 0) | (gyroSnooze ? 0x10 : 0));
    }

    /**
     * Power the chip down (CTRL1.SensorDisable, sensors disabled first) or wake it.
     *
     * @param down true to power down, false to wake
     * @throws IOException on bus error
     */
    public void setPowerDown(boolean down) throws IOException {
        if (down) {
            setCtrl7(ctrl7 & ~0x03);
            setCtrl1(ctrl1 | 0x01);
        } else {
            setCtrl1(ctrl1 & ~0x01);
            sleepMs(15);
        }
    }

    /**
     * Enable or disable SyncSample (data-locking) mode; incompatible with the FIFO and motion engines.
     *
     * @param enabled true to enable
     * @throws IOException on bus error or timeout
     */
    public void setSyncSample(boolean enabled) throws IOException {
        if (enabled) {
            calCmd(CMD_AHB_CLOCK_GATING, new int[]{1}, 100);
            setCtrl7(ctrl7 | 0x80);
        } else {
            setCtrl7(ctrl7 & ~0x80);
            calCmd(CMD_AHB_CLOCK_GATING, new int[]{0}, 100);
        }
        sync = enabled;
    }

    /**
     * Configure the FIFO.
     *
     * @param mode 0 = bypass, 1 = FIFO (stops when full), 2 = stream
     * @param size 0–3 = 16 / 32 / 64 / 128 samples
     * @param watermark watermark in samples (0 disables)
     * @throws IOException on bus error
     */
    public void configureFifo(int mode, int size, int watermark) throws IOException {
        if (mode < 0 || mode > 2 || size < 0 || size > 3) {
            throw new IllegalArgumentException("mode must be 0-2 and size 0-3");
        }
        fifoWtm = watermark & 0xFF;
        w(REG_FIFO_WTM_TH, fifoWtm);
        fifoCtrl = (size << 2) | mode;
        w(REG_FIFO_CTRL, fifoCtrl);
        if (mode == 0) setCtrl7(ctrl7 & ~0x20);   // DRDY back on
    }

    /**
     * Read the raw {@code FIFO_STATUS} byte.
     *
     * @return full (bit 7), watermark (6), overflow (5), not-empty (4) flags
     * @throws IOException on bus error
     */
    public int fifoStatus() throws IOException {
        return r1(REG_FIFO_STATUS);
    }

    /**
     * Number of bytes in the FIFO.
     *
     * @return 2 × the sample-word count
     * @throws IOException on bus error
     */
    public int fifoCount() throws IOException {
        int st = r1(REG_FIFO_STATUS);
        int lo = r1(REG_FIFO_SMPL_CNT);
        return 2 * (((st & 0x03) << 8) | lo);
    }

    /**
     * Drain the FIFO with the read-mode procedure (little-endian 6-byte frames, accelerometer first
     * when both sensors are on).
     *
     * @param count maximum number of bytes, or a negative value for all available
     * @return the FIFO contents
     * @throws IOException on bus error or timeout
     */
    public byte[] readFifo(int count) throws IOException {
        int total = fifoCount();
        int n = count < 0 ? total : Math.min(count, total);
        if (n <= 0) return new byte[0];
        ctrl9(CMD_REQ_FIFO, 100);
        w(REG_CTRL1, ctrl1 & ~0x40);              // ADDR_AI off: FIFO_DATA repeats
        byte[] out = new byte[n];
        int got = 0;
        IOException failure = null;
        try {
            while (got < n) {
                int chunk = Math.min(32, n - got);
                System.arraycopy(connection.read(REG_FIFO_DATA, chunk), 0, out, got, chunk);
                got += chunk;
            }
        } catch (IOException e) {
            failure = e;
        }
        w(REG_CTRL1, ctrl1);
        w(REG_FIFO_CTRL, fifoCtrl & 0x7F);
        if (failure != null) throw failure;
        return out;
    }

    /**
     * Reset the FIFO (CTRL9 RST_FIFO).
     *
     * @throws IOException on bus error or timeout
     */
    public void resetFifo() throws IOException {
        ctrl9(CMD_RST_FIFO, 100);
    }

    private void sendMotion() throws IOException {
        int prev = sensorsOff();
        try {
            int a = clampU8(Math.round(anyThr * 32));
            int n = clampU8(Math.round(noThr * 32));
            int mode = (noAnd ? 0x80 : 0) | ((noAxes & 7) << 4) | (anyAnd ? 0x08 : 0) | (anyAxes & 7);
            twoSets(CMD_CONFIG_MOTION,
                    new int[]{a, a, a, n, n, n, mode},
                    new int[]{anyWin & 0xFF, noWin & 0xFF, sigWait & 0xFF, (sigWait >> 8) & 0xFF,
                            sigConfirm & 0xFF, (sigConfirm >> 8) & 0xFF});
        } finally {
            sensorsRestore(prev);
        }
    }

    private static int clampU8(long v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    /**
     * Configure the any-motion engine.
     *
     * @param thresholdG slope threshold in g (1/32 g steps, 0–7.97)
     * @param window window in samples
     * @param axes axis mask (bit 0 X, 1 Y, 2 Z)
     * @param logicAnd AND instead of OR over the enabled axes
     * @throws IOException on bus error or timeout
     */
    public void configureAnyMotion(double thresholdG, int window, int axes, boolean logicAnd) throws IOException {
        anyThr = thresholdG;
        anyWin = window;
        anyAxes = axes;
        anyAnd = logicAnd;
        sendMotion();
    }

    /**
     * Configure the no-motion engine (arguments as for {@link #configureAnyMotion}).
     *
     * @param thresholdG slope threshold in g
     * @param window window in samples
     * @param axes axis mask
     * @param logicAnd AND instead of OR over the enabled axes
     * @throws IOException on bus error or timeout
     */
    public void configureNoMotion(double thresholdG, int window, int axes, boolean logicAnd) throws IOException {
        noThr = thresholdG;
        noWin = window;
        noAxes = axes;
        noAnd = logicAnd;
        sendMotion();
    }

    /**
     * Configure the significant-motion engine; any- and no-motion must be configured and enabled.
     *
     * @param waitWindow wait window in samples (16-bit)
     * @param confirmWindow confirmation window in samples (16-bit)
     * @throws IOException on bus error or timeout
     */
    public void configureSignificantMotion(int waitWindow, int confirmWindow) throws IOException {
        sigWait = waitWindow;
        sigConfirm = confirmWindow;
        sendMotion();
    }

    /**
     * Enable the motion engines (CTRL8 bits 1–3).
     *
     * @param anyMotion enable any-motion
     * @param noMotion enable no-motion
     * @param significant enable significant-motion
     * @throws IOException on bus error
     */
    public void enableMotion(boolean anyMotion, boolean noMotion, boolean significant) throws IOException {
        setCtrl8((ctrl8 & ~0x0E) | (anyMotion ? 0x02 : 0) | (noMotion ? 0x04 : 0) | (significant ? 0x08 : 0));
    }

    /**
     * Configure the tap engine (two CTRL9 CONFIGURE_TAP commands).
     *
     * @param priority axis priority 0–5
     * @param peakWindow peak window in samples
     * @param tapWindow quiet window in samples
     * @param doubleTapWindow double-tap window in samples
     * @param alpha acceleration averaging weight (1/128 steps)
     * @param gamma magnitude averaging weight (1/128 steps)
     * @param peakThreshold peak threshold in g² (1/1024 steps)
     * @param quietThreshold undefined-motion threshold in g² (1/1024 steps)
     * @throws IOException on bus error or timeout
     */
    public void configureTap(int priority, int peakWindow, int tapWindow, int doubleTapWindow, double alpha,
                             double gamma, double peakThreshold, double quietThreshold) throws IOException {
        int peak = (int) Math.round(peakThreshold * 1024);
        int quiet = (int) Math.round(quietThreshold * 1024);
        int prev = sensorsOff();
        try {
            twoSets(CMD_CONFIG_TAP,
                    new int[]{peakWindow & 0xFF, priority & 0xFF, tapWindow & 0xFF, (tapWindow >> 8) & 0xFF,
                            doubleTapWindow & 0xFF, (doubleTapWindow >> 8) & 0xFF},
                    new int[]{(int) Math.round(alpha * 128) & 0x7F, (int) Math.round(gamma * 128) & 0x7F,
                            peak & 0xFF, (peak >> 8) & 0xFF, quiet & 0xFF, (quiet >> 8) & 0xFF});
        } finally {
            sensorsRestore(prev);
        }
    }

    /**
     * Enable or disable the tap engine (CTRL8.Tap_EN). Use an accelerometer ODR above 200 Hz.
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     */
    public void enableTap(boolean enabled) throws IOException {
        setCtrl8(enabled ? (ctrl8 | 0x01) : (ctrl8 & ~0x01));
    }

    /**
     * Read the last tap event.
     *
     * @return count, axis and polarity
     * @throws IOException on bus error
     */
    public TapStatus tapStatus() throws IOException {
        int v = r1(REG_TAP_STATUS);
        return new TapStatus(v & 0x03, (v >> 4) & 0x03, (v & 0x80) != 0);
    }

    /**
     * Configure the pedometer (two CTRL9 CONFIGURE_PEDOMETER commands; defaults are for a 50 Hz ODR).
     *
     * @param sampleCount samples per step window
     * @param peakToPeakMg peak-to-peak threshold in mg
     * @param peakMg peak threshold in mg
     * @param timeUp maximum step interval in samples
     * @param timeLow minimum step interval in samples
     * @param entryCount steps needed to enter counting
     * @param precision precision setting
     * @param reportEvery steps per pedometer event / count update
     * @throws IOException on bus error or timeout
     */
    public void configurePedometer(int sampleCount, double peakToPeakMg, double peakMg, int timeUp, int timeLow,
                                   int entryCount, int precision, int reportEvery) throws IOException {
        int p2p = (int) Math.round(peakToPeakMg / 1000.0 * 1024);
        int pk = (int) Math.round(peakMg / 1000.0 * 1024);
        int prev = sensorsOff();
        try {
            twoSets(CMD_CONFIG_PEDOMETER,
                    new int[]{sampleCount & 0xFF, (sampleCount >> 8) & 0xFF, p2p & 0xFF, (p2p >> 8) & 0xFF,
                            pk & 0xFF, (pk >> 8) & 0xFF},
                    new int[]{timeUp & 0xFF, (timeUp >> 8) & 0xFF, timeLow & 0xFF, entryCount & 0xFF,
                            precision & 0xFF, reportEvery & 0xFF});
        } finally {
            sensorsRestore(prev);
        }
    }

    /**
     * Enable or disable the pedometer (CTRL8.Pedo_EN); a 0→1 transition resets the count.
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     */
    public void enablePedometer(boolean enabled) throws IOException {
        setCtrl8(enabled ? (ctrl8 | 0x10) : (ctrl8 & ~0x10));
    }

    /**
     * Read the 24-bit step count.
     *
     * @return steps (updated every {@code reportEvery} steps)
     * @throws IOException on bus error
     */
    public int stepCount() throws IOException {
        byte[] b = connection.read(REG_STEP_CNT, 3);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16);
    }

    /**
     * Clear the step count (CTRL9 RESET_PEDOMETER).
     *
     * @throws IOException on bus error or timeout
     */
    public void resetStepCount() throws IOException {
        ctrl9(CMD_RESET_PEDOMETER, 100);
    }

    /**
     * Enter or leave wake-on-motion mode. Uses accelerometer ±2 g at ODR code 12 (128 Hz).
     *
     * @param thresholdMg 1–255 mg enables, 0 exits WoM mode
     * @param interruptPin 1 or 2
     * @param initialLevel initial pin level, 0 or 1
     * @param blankingSamples samples ignored after enabling (0–63)
     * @throws IOException on bus error or timeout
     */
    public void setWakeOnMotion(int thresholdMg, int interruptPin, int initialLevel, int blankingSamples)
            throws IOException {
        sensorsOff();
        if (thresholdMg == 0) {
            calCmd(CMD_WRITE_WOM, new int[]{0, 0}, 100);
            intSources &= ~SOURCE_WAKE_ON_MOTION;
            updateIntPins();
            return;
        }
        accelFs = 0;
        accelOdr = 12;
        updateSens();
        w(REG_CTRL2, 0x0C);
        womPin = interruptPin == 2 ? 2 : 1;
        int sel = (womPin == 2 ? 0x40 : 0) | (initialLevel != 0 ? 0x80 : 0);
        calCmd(CMD_WRITE_WOM, new int[]{Math.min(255, thresholdMg), sel | (blankingSamples & 0x3F)}, 100);
        intSources |= SOURCE_WAKE_ON_MOTION;
        updateIntPins();
        setCtrl7((ctrl7 & ~0x03) | 0x01);
        sleepMs(10);
    }

    private void offsetCmd(int cmd, int[] codes) throws IOException {
        int prev = sensorsOff();
        try {
            int[] p = new int[6];
            for (int i = 0; i < 3; i++) {
                p[2 * i] = codes[i] & 0xFF;
                p[2 * i + 1] = (codes[i] >> 8) & 0xFF;
            }
            calCmd(cmd, p, 100);
        } finally {
            sensorsRestore(prev);
        }
    }

    /**
     * Apply an accelerometer offset (signed 4.12 g; volatile).
     *
     * @param x X offset in m/s²
     * @param y Y offset in m/s²
     * @param z Z offset in m/s²
     * @throws IOException on bus error or timeout
     */
    public void setAccelOffset(double x, double y, double z) throws IOException {
        offsetCmd(CMD_ACCEL_OFFSET, new int[]{code(x / G * 4096), code(y / G * 4096), code(z / G * 4096)});
    }

    /**
     * Apply a gyroscope offset (signed 11.5 dps; volatile).
     *
     * @param x X offset in rad/s
     * @param y Y offset in rad/s
     * @param z Z offset in rad/s
     * @throws IOException on bus error or timeout
     */
    public void setGyroOffset(double x, double y, double z) throws IOException {
        offsetCmd(CMD_GYRO_OFFSET,
                new int[]{code(x / DEG_TO_RAD * 32), code(y / DEG_TO_RAD * 32), code(z / DEG_TO_RAD * 32)});
    }

    private static int code(double v) {
        return (int) Math.round(v) & 0xFFFF;
    }

    /**
     * Run gyroscope calibration-on-demand (about 1.5 s; keep the device still).
     *
     * @return success flag and the {@code COD_STATUS} register
     * @throws IOException on bus error or timeout
     */
    public Calibration calibrateGyro() throws IOException {
        int prev = sensorsOff();
        int status;
        try {
            ctrl9(CMD_COD, 3000);
            status = r1(REG_COD_STATUS);
        } finally {
            sensorsRestore(prev);
        }
        return new Calibration(status == 0, status);
    }

    /**
     * Read the raw X/Y/Z gyroscope gains, valid right after {@link #calibrateGyro()}.
     *
     * @return raw 16-bit {x, y, z} gains
     * @throws IOException on bus error
     */
    public int[] getGyroGains() throws IOException {
        byte[] b = connection.read(REG_DVX_L, 6);
        return new int[]{(b[0] & 0xFF) | ((b[1] & 0xFF) << 8), (b[2] & 0xFF) | ((b[3] & 0xFF) << 8),
                (b[4] & 0xFF) | ((b[5] & 0xFF) << 8)};
    }

    /**
     * Restore previously saved gyroscope gains.
     *
     * @param x raw X gain
     * @param y raw Y gain
     * @param z raw Z gain
     * @throws IOException on bus error or timeout
     */
    public void setGyroGains(int x, int y, int z) throws IOException {
        int prev = sensorsOff();
        try {
            calCmd(CMD_APPLY_GYRO_GAINS,
                    new int[]{x & 0xFF, (x >> 8) & 0xFF, y & 0xFF, (y >> 8) & 0xFF, z & 0xFF, (z >> 8) & 0xFF}, 100);
        } finally {
            sensorsRestore(prev);
        }
    }

    private double[] selfTest(int ctrlReg, int saved, double scale) throws IOException {
        int prev = sensorsOff();
        byte[] b;
        try {
            w(ctrlReg, saved | 0x80);
            waitStatus(0x01, true, 3000, "self-test Avail");
            b = connection.read(REG_DVX_L, 6);
            w(ctrlReg, saved);
            waitStatus(0x01, false, 3000, "self-test end");
        } finally {
            sensorsRestore(prev);
        }
        return new double[]{le16(b, 0) / scale, le16(b, 2) / scale, le16(b, 4) / scale};
    }

    /**
     * Run the accelerometer self-test (device at rest).
     *
     * @return pass flag (every axis above 200 mg) and the response in m/s²
     * @throws IOException on bus error or timeout
     */
    public SelfTestResult selfTestAccel() throws IOException {
        double[] g = selfTest(REG_CTRL2, (accelFs << 4) | accelOdr, 2048.0);
        boolean pass = true;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            if (Math.abs(g[i]) <= 0.2) pass = false;
            out[i] = g[i] * G;
        }
        return new SelfTestResult(pass, out);
    }

    /**
     * Run the gyroscope self-test.
     *
     * @return pass flag (every axis above 300 dps) and the response in rad/s
     * @throws IOException on bus error or timeout
     */
    public SelfTestResult selfTestGyro() throws IOException {
        double[] d = selfTest(REG_CTRL3, (gyroFs << 4) | gyroOdr, 16.0);
        boolean pass = true;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) <= 300.0) pass = false;
            out[i] = d[i] * DEG_TO_RAD;
        }
        return new SelfTestResult(pass, out);
    }

    /**
     * Read revision, firmware version and USID (CTRL9 COPY_USID).
     *
     * @return the device information
     * @throws IOException on bus error or timeout
     */
    public DeviceInfo deviceInfo() throws IOException {
        ctrl9(CMD_COPY_USID, 100);
        int revision = r1(REG_REVISION_ID);
        byte[] fw = new byte[3];
        System.arraycopy(connection.read(REG_FW_VERSION, 2), 0, fw, 0, 2);
        fw[2] = (byte) r1(REG_FW_VERSION + 2);
        return new DeviceInfo(revision, fw, connection.read(REG_DVX_L, 6));
    }

    /**
     * Control the internal 200 kΩ pull-ups (CTRL9 SET_RPU); {@code false} disables a group.
     * Disabling {@code i2c} on a bus without external pull-ups breaks the bus.
     *
     * @param aux SDx / SCx / RESV pull-ups
     * @param sdx SDx pull-up
     * @param cs CS pull-up
     * @param i2c SCL / SDA pull-ups
     * @throws IOException on bus error or timeout
     */
    public void setPullups(boolean aux, boolean sdx, boolean cs, boolean i2c) throws IOException {
        calCmd(CMD_SET_RPU, new int[]{(aux ? 0 : 1) | (sdx ? 0 : 2) | (cs ? 0 : 4) | (i2c ? 0 : 8)}, 100);
    }

    /**
     * Soft reset and wait 15 ms; the sensors are off afterwards.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        w(REG_RESET, RESET_CMD);
        sleepMs(15);
        ctrl1 = 0x40;
        ctrl7 = 0x00;
        ctrl8 = 0x80;
        w(REG_CTRL1, ctrl1);
        w(REG_CTRL8, ctrl8);
        sync = false;
        intSources = 0;
    }

    private int sourcePin(int source) {
        if (source == SOURCE_DATA_READY) return 2;
        if (source == SOURCE_FIFO_WATERMARK || source == SOURCE_FIFO_FULL) return fifoPin;
        if (source == SOURCE_WAKE_ON_MOTION) return womPin;
        return activityPin;
    }

    private void updateIntPins() throws IOException {
        int pins = 0;
        for (int bit = 0; bit < 9; bit++) {
            int s = 1 << bit;
            if ((intSources & s) != 0) pins |= 1 << (sourcePin(s) - 1);
        }
        int v = ctrl1 & ~0x18;
        v |= ((pins & 1) != 0 ? 0x08 : 0) | ((pins & 2) != 0 ? 0x10 : 0);
        v = fifoPin == 1 ? (v | 0x04) : (v & ~0x04);
        if (v != ctrl1) setCtrl1(v);
    }

    private static int engineBit(int source) {
        switch (source) {
            case SOURCE_ANY_MOTION: return 0x02;
            case SOURCE_NO_MOTION: return 0x04;
            case SOURCE_SIGNIFICANT_MOTION: return 0x08;
            case SOURCE_STEP: return 0x10;
            case SOURCE_TAP: return 0x01;
            default: return 0;
        }
    }

    /**
     * Route the FIFO interrupt (CTRL1.FIFO_INT_SEL) and the motion-engine group (CTRL8.ACTIVITY_INT_SEL).
     *
     * @param fifoPin 1 or 2
     * @param activityPin 1 or 2
     * @throws IOException on bus error
     */
    public void setInterruptPins(int fifoPin, int activityPin) throws IOException {
        this.fifoPin = fifoPin == 1 ? 1 : 2;
        this.activityPin = activityPin == 1 ? 1 : 2;
        setCtrl8(this.activityPin == 1 ? (ctrl8 | 0x40) : (ctrl8 & ~0x40));
        updateIntPins();
    }

    /**
     * Enable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_DATA_READY) setCtrl7(ctrl7 & ~0x20);
        else if (source == SOURCE_FIFO_WATERMARK) w(REG_FIFO_WTM_TH, fifoWtm);
        else if (engineBit(source) != 0) setCtrl8(ctrl8 | engineBit(source));
        intSources |= source;
        updateIntPins();
    }

    /**
     * Disable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public void disableInterrupt(int source) throws IOException {
        if (source == SOURCE_DATA_READY) setCtrl7(ctrl7 | 0x20);
        else if (source == SOURCE_FIFO_WATERMARK) w(REG_FIFO_WTM_TH, 0);
        else if (engineBit(source) != 0) setCtrl8(ctrl8 & ~engineBit(source));
        intSources &= ~source;
        updateIntPins();
    }

    /**
     * Read STATUS0, STATUS1 and FIFO_STATUS (clearing what clears on read).
     *
     * @return combined status using the {@code SOURCE_*} bit layout
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        byte[] s = connection.read(REG_STATUS0, 2);
        int fs = r1(REG_FIFO_STATUS);
        int s0 = s[0] & 0xFF;
        int s1 = s[1] & 0xFF;
        int st = 0;
        if ((s0 & 0x03) != 0) st |= SOURCE_DATA_READY;
        if ((fs & 0x40) != 0) st |= SOURCE_FIFO_WATERMARK;
        if ((fs & 0x80) != 0) st |= SOURCE_FIFO_FULL;
        if ((s1 & 0x20) != 0) st |= SOURCE_ANY_MOTION;
        if ((s1 & 0x40) != 0) st |= SOURCE_NO_MOTION;
        if ((s1 & 0x80) != 0) st |= SOURCE_SIGNIFICANT_MOTION;
        if ((s1 & 0x10) != 0) st |= SOURCE_STEP;
        if ((s1 & 0x02) != 0) st |= SOURCE_TAP;
        if ((s1 & 0x04) != 0) st |= SOURCE_WAKE_ON_MOTION;
        return st;
    }

    /**
     * Subscribe to rising edges on the connection's interrupt pin (a 5 ms polling thread when none
     * is attached). The callback receives the {@link #pollInterrupt()} status for a non-zero status.
     *
     * @param callback called with the combined interrupt status
     */
    public void onInterrupt(IntConsumer callback) {
        offInterrupt();
        this.callback = callback;
        InputPin pin = connection.intPin();
        if (pin != null) {
            intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.RISING);
        } else {
            startPolling();
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    public void offInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                handleEdge();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "qmi8658a-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
