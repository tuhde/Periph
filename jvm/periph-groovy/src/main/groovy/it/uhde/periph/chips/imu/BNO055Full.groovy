package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec), full driver extending
 * {@link BNO055Minimal}.
 *
 * <p>Adds the quaternion, linear acceleration, gravity, raw sensor data, calibration read/write,
 * operation and power modes, axis remap, self-test, identification and the page-1 interrupts.
 */
@CompileStatic
class BNO055Full extends BNO055Minimal {

    /** Operation mode: CONFIGMODE. */
    static final int MODE_CONFIG = 0x0;
    /** Operation mode: raw accelerometer only. */
    static final int MODE_ACCONLY = 0x1;
    /** Operation mode: raw magnetometer only. */
    static final int MODE_MAGONLY = 0x2;
    /** Operation mode: raw gyroscope only. */
    static final int MODE_GYROONLY = 0x3;
    /** Operation mode: raw accelerometer and magnetometer. */
    static final int MODE_ACCMAG = 0x4;
    /** Operation mode: raw accelerometer and gyroscope. */
    static final int MODE_ACCGYRO = 0x5;
    /** Operation mode: raw magnetometer and gyroscope. */
    static final int MODE_MAGGYRO = 0x6;
    /** Operation mode: all three raw sensors. */
    static final int MODE_AMG = 0x7;
    /** Operation mode: relative-orientation fusion (100 Hz). */
    static final int MODE_IMU = 0x8;
    /** Operation mode: absolute heading fusion (20 Hz). */
    static final int MODE_COMPASS = 0x9;
    /** Operation mode: relative fusion with magnetometer for gyroscope (50 Hz). */
    static final int MODE_M4G = 0xA;
    /** Operation mode: 9-DOF fusion, fast magnetometer calibration off. */
    static final int MODE_NDOF_FMC_OFF = 0xB;
    /** Operation mode: 9-DOF absolute orientation (100 Hz, default). */
    static final int MODE_NDOF = 0xC;

    /** Interrupt source: accelerometer / fusion data ready. */
    static final int SOURCE_ACC_BSX_DRDY = 0x01;
    /** Interrupt source: magnetometer data ready. */
    static final int SOURCE_MAG_DRDY = 0x02;
    /** Interrupt source: gyroscope any motion. */
    static final int SOURCE_GYRO_AM = 0x04;
    /** Interrupt source: gyroscope high rate. */
    static final int SOURCE_GYR_HIGH_RATE = 0x08;
    /** Interrupt source: gyroscope data ready. */
    static final int SOURCE_GYR_DRDY = 0x10;
    /** Interrupt source: accelerometer high-g. */
    static final int SOURCE_ACC_HIGH_G = 0x20;
    /** Interrupt source: accelerometer any motion. */
    static final int SOURCE_ACC_AM = 0x40;
    /** Interrupt source: accelerometer no/slow motion. */
    static final int SOURCE_ACC_NM = 0x80;

    private int clkSel = 0x00;
    private IntConsumer callback;
    private InputPin intPin;
    private Thread pollThread;
    private volatile boolean polling;
    private final EdgeHandler edgeHandler = new EdgeHandler() {
        @Override
        void onEdge() { handleEdge() }
    }

    /**
     * Create the Full driver; same arguments as {@link BNO055Minimal#BNO055Minimal}.
     *
     * @param connection register connection bound to the device
     * @throws IOException on bus error or a wrong {@code CHIP_ID}
     */
    BNO055Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    private int enterConfig() throws IOException {
        int prev = curMode;
        w(REG_OPR_MODE, MODE_CONFIG_V);
        sleepMs(19);
        return prev;
    }

    private void leaveConfig(int prev) throws IOException {
        if (prev != MODE_CONFIG_V) {
            w(REG_OPR_MODE, prev);
            sleepMs(7);
        }
    }

    /**
     * Select the operation mode through CONFIGMODE (19 ms + 7 ms waits).
     *
     * @param newMode 0 (CONFIGMODE) … 12 (NDOF)
     * @throws IOException on bus error
     */
    void setMode(int newMode) throws IOException {
        if (newMode < 0 || newMode > 12) throw new IllegalArgumentException("mode must be 0..12");
        w(REG_OPR_MODE, MODE_CONFIG_V);
        sleepMs(19);
        if (newMode != MODE_CONFIG_V) {
            w(REG_OPR_MODE, newMode);
            sleepMs(7);
        }
        curMode = newMode;
    }

    /**
     * Read {@code OPR_MODE}.
     *
     * @return current operation mode, 0–12
     * @throws IOException on bus error
     */
    int getMode() throws IOException {
        return r1(REG_OPR_MODE) & 0x0F;
    }

    /**
     * Read the fused orientation quaternion (raw / 16384).
     *
     * @return {w, x, y, z}
     * @throws IOException on bus error
     */
    double[] quaternion() throws IOException {
        byte[] b = connection.read(REG_QUA_DATA, 8);
        return [le16(b, 0) / 16384.0, le16(b, 2) / 16384.0, le16(b, 4) / 16384.0, le16(b, 6) / 16384.0] as double[]
    }

    /**
     * Read the gravity-free linear acceleration (raw / 100).
     *
     * @return {x, y, z} in m/s²
     * @throws IOException on bus error
     */
    double[] linearAcceleration() throws IOException { return vector(REG_LIA_DATA, 100.0); }

    /**
     * Read the gravity vector (raw / 100).
     *
     * @return {x, y, z} in m/s²
     * @throws IOException on bus error
     */
    double[] gravity() throws IOException { return vector(REG_GRV_DATA, 100.0); }

    /**
     * Read the raw accelerometer (raw / 100).
     *
     * @return {x, y, z} in m/s²
     * @throws IOException on bus error
     */
    double[] acceleration() throws IOException { return vector(REG_ACC_DATA, 100.0); }

    /**
     * Read the raw magnetometer (raw / 16).
     *
     * @return {x, y, z} in µT
     * @throws IOException on bus error
     */
    double[] magneticField() throws IOException { return vector(REG_MAG_DATA, 16.0); }

    /**
     * Read the raw gyroscope (raw / 900).
     *
     * @return {x, y, z} in rad/s
     * @throws IOException on bus error
     */
    double[] angularRate() throws IOException { return vector(REG_GYR_DATA, 900.0); }

    /**
     * Read the temperature (signed 8 bit, 1 LSB = 1 °C).
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    int temperature() throws IOException {
        return (byte) r1(REG_TEMP);
    }

    /**
     * Read {@code CALIB_STAT}.
     *
     * @return {sys, gyr, acc, mag}, each 0 (uncalibrated) … 3 (fully calibrated)
     * @throws IOException on bus error
     */
    int[] calibrationStatus() throws IOException {
        int v = r1(REG_CALIB_STAT);
        return [(v >> 6) & 3, (v >> 4) & 3, (v >> 2) & 3, v & 3] as int[]
    }

    /**
     * Check whether sys, gyr, acc and mag are all fully calibrated.
     *
     * @return true if every status value is 3
     * @throws IOException on bus error
     */
    boolean isCalibrated() throws IOException {
        return r1(REG_CALIB_STAT) == 0xFF;
    }

    /**
     * Read the 22 offset/radius bytes 0x55–0x6A in CONFIGMODE (previous mode restored).
     *
     * @return 22 calibration bytes
     * @throws IOException on bus error
     */
    byte[] getCalibration() throws IOException {
        int prev = enterConfig();
        try {
            return connection.read(REG_ACC_OFFSET, 22);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Write the 22 offset/radius bytes in CONFIGMODE (previous mode restored).
     *
     * @param data 22 bytes as returned by {@link #getCalibration()}
     * @throws IOException on bus error
     */
    void setCalibration(byte[] data) throws IOException {
        if (data.length != 22) throw new IllegalArgumentException("calibration data must be 22 bytes");
        int prev = enterConfig();
        try {
            for (int i = 0; i < 22; i++) w(REG_ACC_OFFSET + i, data[i] & 0xFF);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Remap the sensor axes (CONFIGMODE, previous mode restored).
     *
     * @param config {@code AXIS_MAP_CONFIG} (default 0x24)
     * @param sign   {@code AXIS_MAP_SIGN} (default 0x00)
     * @throws IOException on bus error
     */
    void setAxisRemap(int config, int sign) throws IOException {
        int prev = enterConfig();
        try {
            w(REG_AXIS_MAP_CONFIG, config);
            w(REG_AXIS_MAP_SIGN, sign);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Select the power mode (CONFIGMODE, previous mode restored).
     *
     * @param powerMode "normal", "low_power" or "suspend"
     * @throws IOException on bus error
     */
    void setPowerMode(String powerMode) throws IOException {
        int code;
        switch (powerMode) {
            case "normal": code = 0; break;
            case "low_power": code = 1; break;
            case "suspend": code = 2; break;
            default: throw new IllegalArgumentException("power mode must be 'normal', 'low_power' or 'suspend'");
        }
        int prev = enterConfig();
        try {
            w(REG_PWR_MODE, code);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Select the temperature sensor (CONFIGMODE, previous mode restored).
     *
     * @param source "accelerometer" or "gyroscope"
     * @throws IOException on bus error
     */
    void setTemperatureSource(String source) throws IOException {
        int code;
        switch (source) {
            case "accelerometer": code = 0; break;
            case "gyroscope": code = 1; break;
            default: throw new IllegalArgumentException("source must be 'accelerometer' or 'gyroscope'");
        }
        int prev = enterConfig();
        try {
            w(REG_TEMP_SOURCE, code);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Select the external 32.768 kHz crystal (only if fitted) or the internal oscillator.
     *
     * @param on true for the external crystal
     * @throws IOException on bus error
     */
    void useExternalCrystal(boolean on) throws IOException {
        int prev = enterConfig();
        try {
            clkSel = on ? 0x80 : 0x00;
            w(REG_SYS_TRIGGER, clkSel);
        } finally {
            leaveConfig(prev);
        }
    }

    /**
     * Run the power-on self-test (CONFIGMODE, previous mode restored; waits 500 ms).
     *
     * @return {acc, mag, gyr, mcu}, true = passed
     * @throws IOException on bus error
     */
    boolean[] selfTest() throws IOException {
        int prev = enterConfig();
        int v;
        try {
            w(REG_SYS_TRIGGER, clkSel | 0x01);
            sleepMs(500);
            v = r1(REG_ST_RESULT);
        } finally {
            leaveConfig(prev);
        }
        return [(v & 1) != 0, (v & 2) != 0, (v & 4) != 0, (v & 8) != 0] as boolean[]
    }

    /**
     * Read {@code SYS_STATUS} and {@code SYS_ERR}.
     *
     * @return {status, error}; status 5 = fusion running, error 0 = none
     * @throws IOException on bus error
     */
    int[] systemStatus() throws IOException {
        byte[] b = connection.read(REG_SYS_STATUS, 2);
        return [b[0] & 0xFF, b[1] & 0xFF] as int[]
    }

    /**
     * Reset the chip ({@code RST_SYS}), wait 650 ms and re-initialise into NDOF.
     *
     * @throws IOException on bus error
     */
    void reset() throws IOException {
        w(REG_SYS_TRIGGER, clkSel | 0x20);
        sleepMs(650);
        clkSel = 0;
        initialize();
    }

    /**
     * Read the identification registers 0x00–0x06.
     *
     * @return {chipId, accId, magId, gyrId, swRevision (16 bit), bootloader}
     * @throws IOException on bus error
     */
    int[] revisions() throws IOException {
        byte[] b = connection.read(REG_CHIP_ID, 7);
        return [b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF,
                (b[4] & 0xFF) | ((b[5] & 0xFF) << 8), b[6] & 0xFF] as int[]
    }

    /**
     * Read the 16-byte unique ID from page 1 (page 0 restored).
     *
     * @return 16 bytes
     * @throws IOException on bus error
     */
    byte[] uniqueId() throws IOException {
        w(REG_PAGE_ID, 0x01);
        try {
            return connection.read(REG_UNIQUE_ID, 16);
        } finally {
            w(REG_PAGE_ID, 0x00);
        }
    }

    private void updateIntReg(int source, boolean enable) throws IOException {
        w(REG_PAGE_ID, 0x01);
        try {
            for (int reg : [REG_INT_EN, REG_INT_MSK] as int[]) {
                int v = r1(reg);
                v = enable ? (v | source) : (v & ~source & 0xFF);
                w(reg, v);
            }
        } finally {
            w(REG_PAGE_ID, 0x00);
        }
    }

    /**
     * Enable interrupt source bits in {@code INT_EN} and {@code INT_MSK} (page 1).
     *
     * @param source one or more {@code SOURCE_*} bits
     * @throws IOException on bus error
     */
    void enableInterrupt(int source) throws IOException { updateIntReg(source, true); }

    /**
     * Disable interrupt source bits in {@code INT_EN} and {@code INT_MSK}.
     *
     * @param source one or more {@code SOURCE_*} bits
     * @throws IOException on bus error
     */
    void disableInterrupt(int source) throws IOException { updateIntReg(source, false); }

    /**
     * Read {@code INT_STA} (cleared on read).
     *
     * @return {@code SOURCE_*} bits
     * @throws IOException on bus error
     */
    int pollInterrupt() throws IOException {
        return r1(REG_INT_STA);
    }

    /**
     * Subscribe to rising edges on the connection's INT pin (a 100 ms polling thread when none is attached).
     *
     * @param callback called with the {@link #pollInterrupt()} status when non-zero
     */
    void onInterrupt(IntConsumer callback) {
        offInterrupt();
        this.callback = callback;
        InputPin pin = connection.intPin();
        if (pin != null) {
            intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.RISING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "bno055-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    void offInterrupt() {
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

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick
        }
    }
}
