'use strict';

const _REG_SMPLRT_DIV   = 0x19;
const _REG_CONFIG       = 0x1A;
const _REG_GYRO_CONFIG  = 0x1B;
const _REG_ACCEL_CONFIG = 0x1C;
const _REG_FIFO_EN      = 0x23;
const _REG_INT_PIN_CFG  = 0x37;
const _REG_INT_ENABLE   = 0x38;
const _REG_INT_STATUS   = 0x3A;
const _REG_ACCEL_XOUT_H = 0x3B;
const _REG_TEMP_OUT_H   = 0x41;
const _REG_GYRO_XOUT_H  = 0x43;
const _REG_USER_CTRL    = 0x6A;
const _REG_PWR_MGMT_1   = 0x6B;
const _REG_PWR_MGMT_2   = 0x6C;
const _REG_FIFO_COUNTH  = 0x72;
const _REG_FIFO_COUNTL  = 0x73;
const _REG_FIFO_R_W     = 0x74;
const _REG_WHO_AM_I     = 0x75;

const _WHO_AM_I_VALUE = 0x68;

const _ACCEL_SENSITIVITY = [16384.0, 8192.0, 4096.0, 2048.0];
const _GYRO_SENSITIVITY  = [131.0, 65.5, 32.8, 16.4];

/**
 * MPU-6050 6-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the transport. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization.
 *
 * Default configuration (written at construction):
 * - Gyroscope full-scale: ±250 dps (FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (AFS_SEL=0)
 * - DLPF: 44 Hz bandwidth (CONFIG DLPF_CFG=3, 1 kHz gyro rate)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: PLL with gyro X reference (CLKSEL=1)
 */
class MPU6050Minimal {
    /**
     * @param {object} transport - Configured I²C transport (writeRead, write).
     */
    constructor(transport) {
        this._transport = transport;
        this._accelFs = 0;
        this._gyroFs = 0;
        this._writeReg(_REG_PWR_MGMT_1, 0x80);
        const end1 = Date.now() + 100;
        while (Date.now() < end1) {}
        this._writeReg(_REG_PWR_MGMT_1, 0x01);
        const who = this._readReg(_REG_WHO_AM_I);
        if (who !== _WHO_AM_I_VALUE) {
            throw new Error('MPU6050 WHO_AM_I: expected 0x' + _WHO_AM_I_VALUE.toString(16) + ', got 0x' + who.toString(16));
        }
        this._writeReg(_REG_GYRO_CONFIG, 0x00);
        this._writeReg(_REG_ACCEL_CONFIG, 0x00);
        this._writeReg(_REG_CONFIG, 0x03);
        this._writeReg(_REG_SMPLRT_DIV, 0x04);
        const end2 = Date.now() + 35;
        while (Date.now() < end2) {}
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value]));
    }

    _readReg(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 1)[0];
    }

    _readReg16Signed(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 2).readInt16BE(0);
    }

    _readBurst(reg, len) {
        return this._transport.writeRead(Buffer.from([reg]), len);
    }

    /**
     * Read 3-axis linear acceleration.
     * @returns {number[]} [x, y, z] acceleration in m/s².
     */
    accel() {
        const buf = this._readBurst(_REG_ACCEL_XOUT_H, 6);
        const ax = buf.readInt16BE(0);
        const ay = buf.readInt16BE(2);
        const az = buf.readInt16BE(4);
        const sens = _ACCEL_SENSITIVITY[this._accelFs];
        return [ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665];
    }

    /**
     * Read 3-axis angular rate.
     * @returns {number[]} [x, y, z] angular rate in rad/s.
     */
    gyro() {
        const buf = this._readBurst(_REG_GYRO_XOUT_H, 6);
        const gx = buf.readInt16BE(0);
        const gy = buf.readInt16BE(2);
        const gz = buf.readInt16BE(4);
        const sens = _GYRO_SENSITIVITY[this._gyroFs];
        return [gx / sens * Math.PI / 180.0,
                gy / sens * Math.PI / 180.0,
                gz / sens * Math.PI / 180.0];
    }
}

/**
 * MPU-6050 full interface — extends MPU6050Minimal with configuration and FIFO support.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, raw data access, data-ready polling,
 * sleep/standby control, and FIFO management.
 */
class MPU6050Full extends MPU6050Minimal {
    /**
     * @param {object} transport - Configured I²C transport.
     */
    constructor(transport) {
        super(transport);
    }

    /**
     * Set gyroscope full-scale range.
     * @param {number} [fullScale=0] - Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    configureGyro(fullScale = 0) {
        this._gyroFs = fullScale & 0x03;
        this._writeReg(_REG_GYRO_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set accelerometer full-scale range.
     * @param {number} [fullScale=0] - Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    configureAccel(fullScale = 0) {
        this._accelFs = fullScale & 0x03;
        this._writeReg(_REG_ACCEL_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set digital low-pass filter bandwidth.
     * @param {number} [dlpf=3] - Filter setting 0–6 (0=260/256 Hz, 1=184/188 Hz, 2=94/98 Hz,
     *                            3=44/42 Hz, 4=21/20 Hz, 5=10/10 Hz, 6=5/5 Hz; gyro/accel BW).
     */
    configureDlpf(dlpf = 3) {
        this._writeReg(_REG_CONFIG, dlpf & 0x07);
    }

    /**
     * Set sample rate divider.
     * @param {number} [divider=4] - SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider)
     *                               when DLPF is active.
     */
    configureSampleRate(divider = 4) {
        this._writeReg(_REG_SMPLRT_DIV, divider & 0xFF);
    }

    /**
     * Read die temperature.
     * @returns {number} Temperature in °C.
     */
    temperature() {
        const raw = this._readReg16Signed(_REG_TEMP_OUT_H);
        return raw / 340.0 + 36.53;
    }

    /**
     * Read raw 3-axis accelerometer values.
     * @returns {number[]} [x, y, z] raw 16-bit signed values.
     */
    accelRaw() {
        const buf = this._readBurst(_REG_ACCEL_XOUT_H, 6);
        return [buf.readInt16BE(0), buf.readInt16BE(2), buf.readInt16BE(4)];
    }

    /**
     * Read raw 3-axis gyroscope values.
     * @returns {number[]} [x, y, z] raw 16-bit signed values.
     */
    gyroRaw() {
        const buf = this._readBurst(_REG_GYRO_XOUT_H, 6);
        return [buf.readInt16BE(0), buf.readInt16BE(2), buf.readInt16BE(4)];
    }

    /**
     * Check if new sensor data is available.
     * @returns {boolean} True when DATA_RDY_INT is set in INT_STATUS.
     */
    dataReady() {
        return !!(this._readReg(_REG_INT_STATUS) & 0x01);
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     * @param {boolean} [sleep=true] - True to enter sleep mode, false to wake.
     */
    setSleep(sleep = true) {
        let val = this._readReg(_REG_PWR_MGMT_1);
        if (sleep) {
            val |= 0x40;
        } else {
            val &= ~0x40;
        }
        this._writeReg(_REG_PWR_MGMT_1, val);
    }

    /**
     * Put individual axes into standby mode.
     * @param {boolean} [xa=false] - X accelerometer standby.
     * @param {boolean} [ya=false] - Y accelerometer standby.
     * @param {boolean} [za=false] - Z accelerometer standby.
     * @param {boolean} [xg=false] - X gyroscope standby.
     * @param {boolean} [yg=false] - Y gyroscope standby.
     * @param {boolean} [zg=false] - Z gyroscope standby.
     */
    setStandby(xa = false, ya = false, za = false, xg = false, yg = false, zg = false) {
        const val = ((xa ? 1 : 0) << 5) | ((ya ? 1 : 0) << 4) | ((za ? 1 : 0) << 3) |
                    ((xg ? 1 : 0) << 2) | ((yg ? 1 : 0) << 1) | (zg ? 1 : 0);
        this._writeReg(_REG_PWR_MGMT_2, val);
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     * @returns {number} FIFO byte count (0–1024).
     */
    fifoCount() {
        const buf = this._readBurst(_REG_FIFO_COUNTH, 2);
        return ((buf[0] & 0x1F) << 8) | buf[1];
    }

    /**
     * Read all available data from the FIFO buffer.
     * @returns {Buffer} FIFO data.
     */
    readFifo() {
        const count = this.fifoCount();
        if (count === 0) return Buffer.alloc(0);
        return this._readBurst(_REG_FIFO_R_W, count);
    }

    /**
     * Configure and enable FIFO sources.
     * @param {boolean} [gyro=true] - Enable gyroscope data in FIFO.
     * @param {boolean} [accel=true] - Enable accelerometer data in FIFO.
     * @param {boolean} [temp=false] - Enable temperature data in FIFO.
     */
    enableFifo(gyro = true, accel = true, temp = false) {
        const fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
        this._writeReg(_REG_FIFO_EN, fifoEn);
        const userCtrl = this._readReg(_REG_USER_CTRL);
        this._writeReg(_REG_USER_CTRL, userCtrl | 0x40);
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    resetFifo() {
        const userCtrl = this._readReg(_REG_USER_CTRL);
        this._writeReg(_REG_USER_CTRL, userCtrl | 0x04);
    }
}

module.exports = { MPU6050Minimal, MPU6050Full };
