package it.uhde.periph.chips.imu

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import groovy.transform.CompileStatic

import java.util.function.IntConsumer

/**
 * ICM-20948 full driver — extends ICM20948Minimal with the complete chip functionality.
 *
 * Adds raw and burst reads, temperature, full-scale / filter / sample-rate configuration,
 * sensor power control, low-power modes, gyro and accelerometer offsets, the AK09916
 * magnetometer (read through the chip's own I²C master — no second connection is needed),
 * single-byte auxiliary-bus access, the 4 KiB FIFO, wake-on-motion, interrupts, self-test and
 * the PLL time-base correction.
 *
 * Methods that touch banks 1–3 select the bank and return to bank 0, so the bank register
 * is 0 between calls.
 */
@CompileStatic
class ICM20948Full extends ICM20948Minimal {

    /** Interrupt source: new raw data ({@code INT_STATUS_1.RAW_DATA_0_RDY_INT}). */
    static final int SOURCE_DATA_READY      = 0x01
    /** Interrupt source: wake on motion ({@code INT_STATUS.WOM_INT}). */
    static final int SOURCE_WAKE_ON_MOTION  = 0x02
    /** Interrupt source: I²C master transfer done. */
    static final int SOURCE_I2C_MASTER      = 0x04
    /** Interrupt source: PLL running. */
    static final int SOURCE_PLL_READY       = 0x08
    /** Interrupt source: FIFO overflow. */
    static final int SOURCE_FIFO_OVERFLOW   = 0x10

    protected static final int AK_ADDR = 0x0C
    protected static final int AK_WIA2 = 0x01
    protected static final int AK_ST1  = 0x10
    protected static final int AK_CNTL2 = 0x31
    protected static final int AK_CNTL3 = 0x32
    protected static final int AK_WIA2_VALUE = 0x09
    protected static final double MAG_SENSITIVITY = 0.15

    /** One coherent acceleration / angular-rate / temperature sample. */
    static final class Sample {
        /** Acceleration {x, y, z} in m/s². */
        final double[] accel
        /** Angular rate {x, y, z} in rad/s. */
        final double[] gyro
        /** Die temperature in °C. */
        final double tempC

        Sample(double[] accel, double[] gyro, double tempC) {
            this.accel = accel
            this.gyro = gyro
            this.tempC = tempC
        }
    }

    private boolean magEnabled = false
    private boolean intActiveLow = false
    private volatile IntConsumer callback
    private InputPin intPin
    private Thread pollThread
    private volatile boolean polling
    private final EdgeHandler edgeHandler = new EdgeHandler() {
        @Override
        void onEdge() {
            handleEdge()
        }
    }

    /**
     * Create the Full driver; same arguments as ICM20948Minimal.
     *
     * @param connection register connection bound to the device (0x68 or 0x69)
     * @throws IOException on bus error or if {@code WHO_AM_I} is not 0xEA
     */
    ICM20948Full(RegisterConnection connection) throws IOException {
        super(connection)
    }

    private void lpSuspend() throws IOException {
        if (lpEn && !lpSuspended) {
            int v = rd1(0, REG_PWR_MGMT_1)
            wr1(0, REG_PWR_MGMT_1, v & ~0x20)
            lpSuspended = true
        }
    }

    private void lpResume() throws IOException {
        if (lpSuspended) {
            int v = rd1(0, REG_PWR_MGMT_1)
            wr1(0, REG_PWR_MGMT_1, v | 0x20)
            lpSuspended = false
        }
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return {x, y, z} signed 16-bit counts
     * @throws IOException on bus error
     */
    int[] accelRaw() throws IOException {
        byte[] b = rd(0, REG_ACCEL_XOUT_H, 6)
        return [be16(b, 0), be16(b, 2), be16(b, 4)] as int[]
    }

    /**
     * Read raw 3-axis gyroscope values.
     *
     * @return {x, y, z} signed 16-bit counts
     * @throws IOException on bus error
     */
    int[] gyroRaw() throws IOException {
        byte[] b = rd(0, REG_GYRO_XOUT_H, 6)
        return [be16(b, 0), be16(b, 2), be16(b, 4)] as int[]
    }

    /**
     * Burst-read acceleration, angular rate and temperature so all three belong to one sample.
     *
     * @return the sample (m/s², rad/s, °C)
     * @throws IOException on bus error
     */
    Sample readAll() throws IOException {
        byte[] b = rd(0, REG_ACCEL_XOUT_H, 14)
        double aSens = ACCEL_SENSITIVITY[accelFs]
        double gSens = GYRO_SENSITIVITY[gyroFs]
        double[] a = new double[3]
        double[] g = new double[3]
        for (int i = 0; i < 3; i++) {
            a[i] = be16(b, 2 * i) / aSens * G
            g[i] = be16(b, 6 + 2 * i) / gSens * DEG_TO_RAD
        }
        return new Sample(a, g, be16(b, 12) / 333.87 + 21.0)
    }

    /**
     * Read the die temperature.
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    double temperature() throws IOException {
        byte[] b = rd(0, REG_TEMP_OUT_H, 2)
        return be16(b, 0) / 333.87 + 21.0
    }

    /**
     * Set the gyroscope full-scale range.
     *
     * @param fullScale 0 = ±250, 1 = ±500, 2 = ±1000, 3 = ±2000 dps
     * @throws IOException on bus error
     */
    void configureGyro(int fullScale) throws IOException {
        lpSuspend()
        gyroFs = fullScale & 0x03
        writeGyroConfig()
        lpResume()
    }

    /**
     * Set the accelerometer full-scale range.
     *
     * @param fullScale 0 = ±2, 1 = ±4, 2 = ±8, 3 = ±16 g
     * @throws IOException on bus error
     */
    void configureAccel(int fullScale) throws IOException {
        lpSuspend()
        accelFs = fullScale & 0x03
        writeAccelConfig()
        lpResume()
    }

    /**
     * Set the digital low-pass filters.
     *
     * @param gyroDlpf    GYRO_DLPFCFG 0–7 (3 = 51.2 Hz)
     * @param accelDlpf   ACCEL_DLPFCFG 0–7 (3 = 50.4 Hz)
     * @param gyroFilter  false bypasses the gyro filter (9 kHz output rate)
     * @param accelFilter false bypasses the accelerometer filter (4.5 kHz)
     * @throws IOException on bus error
     */
    void configureFilters(int gyroDlpf, int accelDlpf, boolean gyroFilter,
                                 boolean accelFilter) throws IOException {
        lpSuspend()
        this.gyroDlpf = gyroDlpf & 0x07
        this.accelDlpf = accelDlpf & 0x07
        this.gyroFilter = gyroFilter
        this.accelFilter = accelFilter
        writeGyroConfig()
        writeAccelConfig()
        lpResume()
    }

    /**
     * Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).
     *
     * @param gyroDiv  GYRO_SMPLRT_DIV 0–255
     * @param accelDiv ACCEL_SMPLRT_DIV 0–4095
     * @throws IOException on bus error
     */
    void configureSampleRate(int gyroDiv, int accelDiv) throws IOException {
        lpSuspend()
        wr1(2, REG_GYRO_SMPLRT_DIV, gyroDiv & 0xFF)
        wr1(2, REG_ACCEL_SMPLRT_DIV_1, (accelDiv >> 8) & 0x0F)
        wr1(2, REG_ACCEL_SMPLRT_DIV_2, accelDiv & 0xFF)
        lpResume()
    }

    /**
     * Switch the accelerometer and gyroscope on or off ({@code PWR_MGMT_2}). Turning a sensor on
     * again needs its start-up time (accel 20 ms, gyro 35 ms).
     *
     * @param accel true to power the accelerometer
     * @param gyro  true to power the gyroscope
     * @throws IOException on bus error
     */
    void setSensors(boolean accel, boolean gyro) throws IOException {
        wr1(0, REG_PWR_MGMT_2, (accel ? 0 : 0x38) | (gyro ? 0 : 0x07))
    }

    /**
     * Set or clear the {@code SLEEP} bit in {@code PWR_MGMT_1}.
     *
     * @param sleep true to enter sleep mode, false to wake
     * @throws IOException on bus error
     */
    void setSleep(boolean sleep) throws IOException {
        int v = rd1(0, REG_PWR_MGMT_1)
        wr1(0, REG_PWR_MGMT_1, sleep ? (v | 0x40) : (v & ~0x40))
    }

    /**
     * Configure duty-cycled low-power operation; {@code LP_EN} is set last because it blocks most
     * register writes.
     *
     * @param accel    duty-cycle the accelerometer
     * @param gyro     duty-cycle the gyroscope
     * @param accelAvg DEC3_CFG averaging code 0–3
     * @param gyroAvg  GYRO_AVGCFG averaging code 0–7
     * @throws IOException on bus error
     */
    void setLowPower(boolean accel, boolean gyro, int accelAvg, int gyroAvg) throws IOException {
        lpSuspended = false
        int v = rd1(0, REG_PWR_MGMT_1) & ~0x20
        wr1(0, REG_PWR_MGMT_1, v)
        wr1(2, REG_ACCEL_CONFIG_2, accelAvg & 0x03)
        wr1(2, REG_GYRO_CONFIG_2, gyroAvg & 0x07)
        wr1(0, REG_LP_CONFIG, 0x40 | (accel ? 0x20 : 0) | (gyro ? 0x10 : 0))
        lpEn = accel || gyro
        if (lpEn) wr1(0, REG_PWR_MGMT_1, v | 0x20)
    }

    /**
     * Write the gyroscope offset-cancellation registers.
     *
     * @param x X offset in rad/s (0.0305 dps/LSB, signed 16-bit)
     * @param y Y offset in rad/s
     * @param z Z offset in rad/s
     * @throws IOException on bus error
     */
    void setGyroOffset(double x, double y, double z) throws IOException {
        byte[] data = new byte[6]
        double[] v = [x, y, z] as double[]
        for (int i = 0; i < 3; i++) {
            long code = Math.round(v[i] / DEG_TO_RAD / 0.0305)
            code = Math.max(-32768, Math.min(32767, code))
            data[2 * i] = (byte) (code >> 8)
            data[2 * i + 1] = (byte) code
        }
        lpSuspend()
        wr(2, REG_XG_OFFS_USRH, data)
        lpResume()
    }

    /**
     * Read the 15-bit accelerometer offset codes (0.98 mg/LSB). The registers hold a per-part
     * factory trim: read, adjust, write back.
     *
     * @return {x, y, z} signed 15-bit offset codes
     * @throws IOException on bus error
     */
    int[] getAccelOffset() throws IOException {
        byte[] raw = rd(1, REG_XA_OFFS_H, 8)
        int[] out = new int[3]
        for (int i = 0; i < 3; i++) {
            int code = ((raw[3 * i] & 0xFF) << 7) | ((raw[3 * i + 1] & 0xFF) >> 1)
            out[i] = Register.toSigned(code, 15)
        }
        return out
    }

    /**
     * Write the 15-bit accelerometer offset codes.
     *
     * @param x X offset code (signed 15-bit)
     * @param y Y offset code
     * @param z Z offset code
     * @throws IOException on bus error
     */
    void setAccelOffset(int x, int y, int z) throws IOException {
        lpSuspend()
        int[] regs = [0x14, 0x17, 0x1A] as int[]
        int[] vals = [x, y, z] as int[]
        for (int i = 0; i < 3; i++) {
            int code = vals[i] & 0x7FFF
            wr(1, regs[i], [(byte) ((code >> 7) & 0xFF), (byte) ((code << 1) & 0xFE)] as byte[])
        }
        lpResume()
    }

    private void slv4Wait() throws IOException {
        for (int i = 0; i < 100; i++) {
            int status = rd1(0, REG_I2C_MST_STATUS)
            if ((status & 0x10) != 0) throw new IOException("ICM20948 auxiliary I2C NACK")
            if ((status & 0x40) != 0) return
            sleepMs(1)
        }
        throw new IOException("ICM20948 auxiliary I2C transfer timed out")
    }

    /**
     * Read one byte from an auxiliary-bus device through I²C-master slave 4.
     *
     * @param address  7-bit device address
     * @param register register inside the device
     * @return the byte read
     * @throws IOException on bus error, NACK or timeout
     */
    int auxReadByte(int address, int register) throws IOException {
        selectBank(3, false)
        connection.write(REG_I2C_SLV4_ADDR, [(byte) (0x80 | (address & 0x7F))] as byte[])
        connection.write(REG_I2C_SLV4_REG, [(byte) register] as byte[])
        connection.write(REG_I2C_SLV4_CTRL, [(byte) 0x80] as byte[])
        selectBank(0, false)
        slv4Wait()
        return rd1(3, REG_I2C_SLV4_DI)
    }

    /**
     * Write one byte to an auxiliary-bus device through I²C-master slave 4.
     *
     * @param address  7-bit device address
     * @param register register inside the device
     * @param value    byte to write
     * @throws IOException on bus error, NACK or timeout
     */
    void auxWriteByte(int address, int register, int value) throws IOException {
        selectBank(3, false)
        connection.write(REG_I2C_SLV4_ADDR, [(byte) (address & 0x7F)] as byte[])
        connection.write(REG_I2C_SLV4_REG, [(byte) register] as byte[])
        connection.write(REG_I2C_SLV4_DO, [(byte) value] as byte[])
        connection.write(REG_I2C_SLV4_CTRL, [(byte) 0x80] as byte[])
        selectBank(0, false)
        slv4Wait()
    }

    /**
     * Enable the AK09916 magnetometer via the I²C master. Resets the magnetometer, verifies
     * {@code WIA2} == 0x09, selects continuous measurement 4 (100 Hz) and configures slave 0 to
     * copy the 9-byte {@code ST1..ST2} block to {@code EXT_SLV_SENS_DATA_00..08} continuously.
     *
     * @throws IOException on bus error, or if {@code WIA2} does not read 0x09
     */
    void enableMag() throws IOException {
        lpSuspend()
        int ctrl = rd1(0, REG_USER_CTRL)
        wr1(0, REG_USER_CTRL, ctrl | 0x20);                 // I2C_MST_EN
        wr1(3, REG_I2C_MST_CTRL, 0x07);                     // 345.6 kHz
        auxWriteByte(AK_ADDR, AK_CNTL3, 0x01);              // soft reset
        sleepMs(2)
        int wia = auxReadByte(AK_ADDR, AK_WIA2)
        if (wia != AK_WIA2_VALUE) {
            throw new IOException("AK09916 WIA2: expected 0x09, got 0x" + Integer.toHexString(wia))
        }
        auxWriteByte(AK_ADDR, AK_CNTL2, 0x08);              // continuous 4
        selectBank(3, false)
        connection.write(REG_I2C_SLV0_ADDR, [(byte) (0x80 | AK_ADDR)] as byte[])
        connection.write(REG_I2C_SLV0_REG, [(byte) AK_ST1] as byte[])
        connection.write(REG_I2C_SLV0_CTRL, [(byte) 0x89] as byte[])
        selectBank(0, false)
        magEnabled = true
        lpResume()
    }

    /**
     * Write the magnetometer {@code CNTL2} mode through slave 4.
     *
     * @param mode 0 power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4
     * @throws IOException on bus error
     */
    void magSetMode(int mode) throws IOException {
        auxWriteByte(AK_ADDR, AK_CNTL2, mode & 0x1F)
    }

    private byte[] magBlock() throws IOException {
        if (!magEnabled) {
            throw new IllegalStateException("Magnetometer not enabled. Call enableMag() first.")
        }
        return rd(0, REG_EXT_SLV_SENS_DATA_00, 9)
    }

    /**
     * Read the magnetic field.
     *
     * @return {x, y, z} in µT, as the chip reports them
     * @throws IllegalStateException if {@code enableMag()} has not been called
     * @throws IOException           on bus error or if the sensor overflowed (HOFL)
     */
    double[] mag() throws IOException {
        byte[] b = magBlock()
        if ((b[8] & 0x08) != 0) throw new IOException("Magnetometer overflow (HOFL)")
        return [le16(b, 1) * MAG_SENSITIVITY, le16(b, 3) * MAG_SENSITIVITY,
                le16(b, 5) * MAG_SENSITIVITY] as double[]
    }

    /**
     * Read the raw magnetometer values.
     *
     * @return {x, y, z} signed 16-bit counts
     * @throws IllegalStateException if {@code enableMag()} has not been called
     * @throws IOException           on bus error
     */
    int[] magRaw() throws IOException {
        byte[] b = magBlock()
        return [le16(b, 1), le16(b, 3), le16(b, 5)] as int[]
    }

    /**
     * Check the magnetometer {@code DRDY} flag from the shadowed block.
     *
     * @return true when {@code ST1.DRDY} is set
     * @throws IllegalStateException if {@code enableMag()} has not been called
     * @throws IOException           on bus error
     */
    boolean magDataReady() throws IOException {
        return (magBlock()[0] & 0x01) != 0
    }

    /**
     * Check whether new raw data is available ({@code INT_STATUS_1.RAW_DATA_0_RDY_INT}).
     *
     * @return true when new data is ready
     * @throws IOException on bus error
     */
    boolean dataReady() throws IOException {
        return (rd1(0, REG_INT_STATUS_1) & 0x01) != 0
    }

    /**
     * Select the FIFO sources, enable the FIFO and reset it.
     *
     * @param accel write accelerometer data (6 bytes)
     * @param gyro  write gyroscope data (6 bytes)
     * @param temp  write temperature data (2 bytes)
     * @throws IOException on bus error
     */
    void enableFifo(boolean accel, boolean gyro, boolean temp) throws IOException {
        lpSuspend()
        wr1(0, REG_FIFO_EN_2, (accel ? 0x10 : 0) | (gyro ? 0x0E : 0) | (temp ? 0x01 : 0))
        int ctrl = rd1(0, REG_USER_CTRL)
        wr1(0, REG_USER_CTRL, ctrl | 0x40)
        resetFifo()
        lpResume()
    }

    /**
     * Select the FIFO mode.
     *
     * @param snapshot true to stop when full, false to overwrite the oldest data
     * @throws IOException on bus error
     */
    void setFifoMode(boolean snapshot) throws IOException {
        lpSuspend()
        wr1(0, REG_FIFO_MODE, snapshot ? 0x1F : 0x00)
        lpResume()
    }

    /**
     * Read the number of bytes in the FIFO.
     *
     * @return byte count (0–4096)
     * @throws IOException on bus error
     */
    int fifoCount() throws IOException {
        byte[] b = rd(0, REG_FIFO_COUNTH, 2)
        return ((b[0] & 0x1F) << 8) | (b[1] & 0xFF)
    }

    /**
     * Read bytes from the FIFO (the frame layout is not documented in the datasheet).
     *
     * @param count maximum bytes to read; negative reads everything available
     * @return the bytes read
     * @throws IOException on bus error
     */
    byte[] readFifo(int count) throws IOException {
        int available = fifoCount()
        int remaining = (count < 0 || count > available) ? available : count
        byte[] out = new byte[remaining]
        int done = 0
        while (done < remaining) {
            int n = Math.min(remaining - done, 32)
            System.arraycopy(rd(0, REG_FIFO_R_W, n), 0, out, done, n)
            done += n
        }
        return out
    }

    /**
     * Reset the FIFO by asserting and de-asserting {@code FIFO_RST}.
     *
     * @throws IOException on bus error
     */
    void resetFifo() throws IOException {
        wr1(0, REG_FIFO_RST, 0x1F)
        wr1(0, REG_FIFO_RST, 0x00)
    }

    /**
     * Configure wake-on-motion (needs the accelerometer on with its filter enabled).
     *
     * @param thresholdMg threshold in mg (4 mg/LSB, 0–1020)
     * @param enabled     true to arm, false to disarm
     * @throws IOException on bus error
     */
    void setWakeOnMotion(double thresholdMg, boolean enabled) throws IOException {
        lpSuspend()
        long thr = Math.max(0, Math.min(255, Math.round(thresholdMg / 4.0)))
        wr1(2, REG_ACCEL_WOM_THR, (int) thr)
        wr1(2, REG_ACCEL_INTEL_CTRL, enabled ? 0x03 : 0x00)
        if (enabled) enableInterrupt(SOURCE_WAKE_ON_MOTION)
        else disableInterrupt(SOURCE_WAKE_ON_MOTION)
        lpResume()
    }

    /**
     * Configure the INT1 pin ({@code INT_PIN_CFG}).
     *
     * @param activeLow     INT1 active low
     * @param openDrain     open-drain output instead of push-pull
     * @param latch         hold the level until the status is cleared
     * @param anyReadClears any read clears {@code INT_STATUS}
     * @throws IOException on bus error
     */
    void setInterruptPin(boolean activeLow, boolean openDrain, boolean latch,
                                boolean anyReadClears) throws IOException {
        intActiveLow = activeLow
        wr1(0, REG_INT_PIN_CFG, (activeLow ? 0x80 : 0) | (openDrain ? 0x40 : 0)
                | (latch ? 0x20 : 0) | (anyReadClears ? 0x10 : 0))
    }

    private void intEnable(int source, boolean on) throws IOException {
        int reg
        int mask
        switch (source) {
            case SOURCE_DATA_READY:     reg = REG_INT_ENABLE_1; mask = 0x01; break
            case SOURCE_WAKE_ON_MOTION: reg = REG_INT_ENABLE;   mask = 0x08; break
            case SOURCE_I2C_MASTER:     reg = REG_INT_ENABLE;   mask = 0x01; break
            case SOURCE_PLL_READY:      reg = REG_INT_ENABLE;   mask = 0x04; break
            case SOURCE_FIFO_OVERFLOW:  reg = REG_INT_ENABLE_2; mask = 0x1F; break
            default:
                throw new IllegalArgumentException("ICM20948: unknown interrupt source 0x"
                        + Integer.toHexString(source))
        }
        int v = rd1(0, reg)
        wr1(0, reg, on ? (v | mask) : (v & ~mask))
    }

    /**
     * Enable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    void enableInterrupt(int source) throws IOException {
        intEnable(source, true)
    }

    /**
     * Disable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    void disableInterrupt(int source) throws IOException {
        intEnable(source, false)
    }

    /**
     * Read (and clear) {@code INT_STATUS}, {@code INT_STATUS_1} and {@code INT_STATUS_2}.
     *
     * @return the combined status using the {@code SOURCE_*} bit layout
     * @throws IOException on bus error
     */
    int pollInterrupt() throws IOException {
        byte[] s = rd(0, REG_INT_STATUS, 3)
        int status = 0
        if ((s[1] & 0x01) != 0) status |= SOURCE_DATA_READY
        if ((s[0] & 0x08) != 0) status |= SOURCE_WAKE_ON_MOTION
        if ((s[0] & 0x01) != 0) status |= SOURCE_I2C_MASTER
        if ((s[0] & 0x04) != 0) status |= SOURCE_PLL_READY
        if ((s[2] & 0x1F) != 0) status |= SOURCE_FIFO_OVERFLOW
        return status
    }

    /**
     * Subscribe to INT1 assertions. The callback receives the {@code pollInterrupt()} status and
     * is only invoked for a non-zero status. Delivery uses the connection's {@code intPin()} when
     * one is wired and falls back to a 5 ms polling thread otherwise.
     *
     * @param callback called with the combined interrupt status
     */
    void onInterrupt(IntConsumer callback) {
        offInterrupt()
        this.callback = callback
        InputPin pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, intActiveLow ? EdgeTrigger.FALLING : EdgeTrigger.RISING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    void offInterrupt() {
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void startPolling() {
        polling = true
        pollThread = new Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, "icm20948-poll")
        pollThread.setDaemon(true)
        pollThread.start()
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    private double[][] averageRaw() throws IOException {
        final int samples = 5
        double[] sa = new double[3]
        double[] sg = new double[3]
        for (int n = 0; n < samples; n++) {
            int[] a = accelRaw()
            int[] g = gyroRaw()
            for (int i = 0; i < 3; i++) {
                sa[i] += a[i]
                sg[i] += g[i]
            }
            sleepMs(11)
        }
        for (int i = 0; i < 3; i++) {
            sa[i] /= samples
            sg[i] /= samples
        }
        return [sa, sg] as double[][]
    }

    /**
     * Run the self-test enable procedure and return the raw response. Configures ±2 g / ±250 dps,
     * averages samples with and without the self-test actuation and restores the configuration.
     * The datasheet gives no pass/fail limits, so none is evaluated.
     *
     * @return {accel {x, y, z}, gyro {x, y, z}} raw response (enabled minus disabled)
     * @throws IOException on bus error
     */
    double[][] selfTest() throws IOException {
        int savedAccel = accelFs
        int savedGyro = gyroFs
        configureAccel(0)
        configureGyro(0)
        sleepMs(50)
        double[][] off = averageRaw()
        lpSuspend()
        wr1(2, REG_GYRO_CONFIG_2, 0x38)
        wr1(2, REG_ACCEL_CONFIG_2, 0x1C)
        sleepMs(50)
        double[][] on = averageRaw()
        wr1(2, REG_GYRO_CONFIG_2, 0x00)
        wr1(2, REG_ACCEL_CONFIG_2, 0x00)
        lpResume()
        configureAccel(savedAccel)
        configureGyro(savedGyro)
        double[][] out = new double[2][3]
        for (int k = 0; k < 2; k++) {
            for (int i = 0; i < 3; i++) out[k][i] = on[k][i] - off[k][i]
        }
        return out
    }

    /**
     * Read the per-part PLL time-base correction.
     *
     * @return the deviation in percent (0.079 %/LSB, signed)
     * @throws IOException on bus error
     */
    double readTimebaseCorrection() throws IOException {
        return Register.toSigned(rd1(1, REG_TIMEBASE_CORRECTION_PLL), 8) * 0.079
    }

    /**
     * Issue {@code DEVICE_RESET} and wait. The chip is left in sleep with default registers
     * create a new driver afterwards to run the initialization again.
     *
     * @throws IOException on bus error
     */
    void reset() throws IOException {
        wr1(0, REG_PWR_MGMT_1, 0x80)
        sleepMs(100)
        bank = 0
        lpEn = false
        lpSuspended = false
        magEnabled = false
    }
}
