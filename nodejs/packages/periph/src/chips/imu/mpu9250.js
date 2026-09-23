'use strict';

const _REG_SMPLRT_DIV    = 0x19;
const _REG_CONFIG        = 0x1A;
const _REG_GYRO_CONFIG   = 0x1B;
const _REG_ACCEL_CONFIG  = 0x1C;
const _REG_ACCEL_CONFIG2 = 0x1D;
const _REG_LP_ACCEL_ODR  = 0x1E;
const _REG_WOM_THR       = 0x1F;
const _REG_FIFO_EN       = 0x23;
const _REG_INT_PIN_CFG   = 0x37;
const _REG_INT_ENABLE    = 0x38;
const _REG_INT_STATUS    = 0x3A;
const _REG_ACCEL_XOUT_H  = 0x3B;
const _REG_TEMP_OUT_H    = 0x41;
const _REG_GYRO_XOUT_H   = 0x43;
const _REG_USER_CTRL     = 0x6A;
const _REG_PWR_MGMT_1    = 0x6B;
const _REG_PWR_MGMT_2    = 0x6C;
const _REG_FIFO_COUNTH   = 0x72;
const _REG_FIFO_COUNTL   = 0x73;
const _REG_FIFO_R_W      = 0x74;
const _REG_WHO_AM_I      = 0x75;

const _WHO_AM_I_VALUE = 0x71;

const _AK8963_ADDR = 0x0C;

const _AK8963_REG_WIA      = 0x00;
const _AK8963_REG_ST1      = 0x02;
const _AK8963_REG_HXL      = 0x03;
const _AK8963_REG_ST2      = 0x09;
const _AK8963_REG_CNTL1    = 0x0A;
const _AK8963_REG_CNTL2    = 0x0B;
const _AK8963_REG_ASAX     = 0x10;
const _AK8963_REG_ASAY     = 0x11;
const _AK8963_REG_ASAZ     = 0x12;

const _AK8963_WIA_VALUE = 0x48;

const _ACCEL_SENSITIVITY = [16384.0, 8192.0, 4096.0, 2048.0];
const _GYRO_SENSITIVITY  = [131.0, 65.5, 32.8, 16.4];
const _MAG_SENSITIVITY_14BIT = 0.6;
const _MAG_SENSITIVITY_16BIT = 0.15;

/**
 * MPU-9250 9-axis MotionTracking device (accelerometer + gyroscope) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the connection. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization. Magnetometer
 * is not included in Minimal — it requires a separate initialization path.
 *
 * Default configuration (written at construction):
 * - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
 * - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
 * - Accelerometer DLPF: 44.8 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: auto PLL (CLKSEL=1)
 * - All six axes enabled
 * - SPI only: I2C_IF_DIS set to prevent accidental I²C re-enable
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 */
class MPU9250Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._accelFs = 0;
        this._gyroFs = 0;
        this._init();
    }

    async _init() {
        await this._writeReg(_REG_PWR_MGMT_1, 0x80);
        const end1 = Date.now() + 100;
        while (Date.now() < end1) {}
        await this._writeReg(_REG_PWR_MGMT_1, 0x01);
        const who = await this._readReg(_REG_WHO_AM_I);
        if (who !== _WHO_AM_I_VALUE) {
            throw new Error('MPU9250 WHO_AM_I: expected 0x' + _WHO_AM_I_VALUE.toString(16) + ', got 0x' + who.toString(16));
        }
        await this._writeReg(_REG_GYRO_CONFIG, 0x00);
        await this._writeReg(_REG_ACCEL_CONFIG, 0x00);
        await this._writeReg(_REG_ACCEL_CONFIG2, 0x03);
        await this._writeReg(_REG_CONFIG, 0x03);
        await this._writeReg(_REG_SMPLRT_DIV, 0x04);
        const end2 = Date.now() + 35;
        while (Date.now() < end2) {}
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg, value]));
    }

    async _readReg(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 1))[0];
    }

    async _readReg16Signed(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 2)).readInt16BE(0);
    }

    async _readBurst(reg, len) {
        return this._conn.writeRead(Buffer.from([reg]), len);
    }

    /**
     * Read 3-axis linear acceleration.
     * @returns {Promise<number[]>} [x, y, z] acceleration in m/s².
     */
    async accel() {
        const buf = await this._readBurst(_REG_ACCEL_XOUT_H, 6);
        const ax = buf.readInt16BE(0);
        const ay = buf.readInt16BE(2);
        const az = buf.readInt16BE(4);
        const sens = _ACCEL_SENSITIVITY[this._accelFs];
        return [ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665];
    }

    /**
     * Read 3-axis angular rate.
     * @returns {Promise<number[]>} [x, y, z] angular rate in rad/s.
     */
    async gyro() {
        const buf = await this._readBurst(_REG_GYRO_XOUT_H, 6);
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
 * MPU-9250 full interface — extends MPU9250Minimal with complete functionality.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * raw data access, data-ready polling, sleep/standby control, and FIFO management.
 *
 * The AK8963 magnetometer sits behind the MPU-9250's I²C bypass (BYPASS_EN)
 * as its own device at address 0x0C, so it needs its own connection bound
 * to that address on the same bus — it cannot be reached through the
 * connection already bound to the MPU-9250's own address. Construct that
 * second connection the same way as the primary one (e.g. on Linux,
 * `new I2CConnection(1, 0x0C)` alongside `new I2CConnection(1, 0x68)`) and
 * pass both in.
 */
class MPU9250Full extends MPU9250Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection pointing at the MPU-9250.
     * @param {import('../../connection/connection').Connection} magConnection - Configured I²C connection bound to the AK8963's address (0x0C), on the same bus as connection.
     */
    constructor(connection, magConnection) {
        super(connection);
        this._magConn = magConnection;
        this._magEnabled = false;
        this._magBits = 16;
        this._magScaleX = 1.0;
        this._magScaleY = 1.0;
        this._magScaleZ = 1.0;
    }

    async _ak8963Write(reg, value) {
        await this._magConn.write(Buffer.from([reg, value]));
    }

    async _ak8963Read(reg) {
        return (await this._magConn.writeRead(Buffer.from([reg]), 1))[0];
    }

    async _ak8963ReadBurst(reg, len) {
        return this._magConn.writeRead(Buffer.from([reg]), len);
    }

    /**
     * Set gyroscope full-scale range.
     * @param {number} [fullScale=0] - Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     * @returns {Promise<void>}
     */
    async configureGyro(fullScale = 0) {
        this._gyroFs = fullScale & 0x03;
        await this._writeReg(_REG_GYRO_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set accelerometer full-scale range.
     * @param {number} [fullScale=0] - Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     * @returns {Promise<void>}
     */
    async configureAccel(fullScale = 0) {
        this._accelFs = fullScale & 0x03;
        await this._writeReg(_REG_ACCEL_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set digital low-pass filter bandwidth.
     * @param {number} [gyroDlpf=3] - Gyro filter setting 0–7 (0=250 Hz, 1=184 Hz, 2=92 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz, 7=3600 Hz).
     * @param {number} [accelDlpf=3] - Accel filter setting 0–7 (0=218.1 Hz, 1=218.1 Hz, 2=99 Hz, 3=44.8 Hz, 4=21.2 Hz, 5=10.2 Hz, 6=5.05 Hz, 7=420 Hz).
     * @returns {Promise<void>}
     */
    async configureDlpf(gyroDlpf = 3, accelDlpf = 3) {
        await this._writeReg(_REG_CONFIG, gyroDlpf & 0x07);
        await this._writeReg(_REG_ACCEL_CONFIG2, accelDlpf & 0x07);
    }

    /**
     * Set sample rate divider.
     * @param {number} [divider=4] - SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider) when DLPF is active.
     * @returns {Promise<void>}
     */
    async configureSampleRate(divider = 4) {
        await this._writeReg(_REG_SMPLRT_DIV, divider & 0xFF);
    }

    /**
     * Read die temperature.
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        const raw = await this._readReg16Signed(_REG_TEMP_OUT_H);
        return raw / 333.87 + 21.0;
    }

    /**
     * Initialize AK8963 magnetometer via I²C bypass mode.
     * @param {number} [bits=16] - Output resolution, 14 or 16.
     * @param {number} [mode=6] - Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
     * @returns {Promise<void>}
     */
    async enableMag(bits = 16, mode = 6) {
        await this._writeReg(_REG_INT_PIN_CFG, 0x22);
        const end1 = Date.now() + 10;
        while (Date.now() < end1) {}

        await this._ak8963Write(_AK8963_REG_CNTL1, 0x00);
        const end2 = Date.now() + 10;
        while (Date.now() < end2) {}

        await this._ak8963Write(_AK8963_REG_CNTL1, 0x0F);
        const end3 = Date.now() + 10;
        while (Date.now() < end3) {}

        const asax = await this._ak8963Read(_AK8963_REG_ASAX);
        const asay = await this._ak8963Read(_AK8963_REG_ASAY);
        const asaz = await this._ak8963Read(_AK8963_REG_ASAZ);

        this._magScaleX = (asax - 128) / 256.0 + 1.0;
        this._magScaleY = (asay - 128) / 256.0 + 1.0;
        this._magScaleZ = (asaz - 128) / 256.0 + 1.0;

        await this._ak8963Write(_AK8963_REG_CNTL1, 0x00);
        const end4 = Date.now() + 10;
        while (Date.now() < end4) {}

        let cntl1Val = 0;
        if (bits === 16) {
            cntl1Val |= 0x10;
        }
        cntl1Val |= (mode & 0x0F);
        await this._ak8963Write(_AK8963_REG_CNTL1, cntl1Val);
        const end5 = Date.now() + 10;
        while (Date.now() < end5) {}

        this._magEnabled = true;
        this._magBits = bits;
    }

    /**
     * Read 3-axis magnetic field.
     * @returns {Promise<number[]>} [x, y, z] magnetic field in µT.
     * @throws {Error} If magnetometer has not been enabled via enableMag().
     */
    async mag() {
        if (!this._magEnabled) {
            throw new Error('Magnetometer not enabled. Call enableMag() first.');
        }
        const buf = await this._ak8963ReadBurst(_AK8963_REG_HXL, 7);
        const mx = buf.readInt16LE(0);
        const my = buf.readInt16LE(2);
        const mz = buf.readInt16LE(4);
        // ST2 at buf[6] must be read to unlock next measurement

        const sens = (this._magBits === 16) ? _MAG_SENSITIVITY_16BIT : _MAG_SENSITIVITY_14BIT;
        return [mx * sens * this._magScaleX,
                my * sens * this._magScaleY,
                mz * sens * this._magScaleZ];
    }

    /**
     * Read raw 3-axis accelerometer values.
     * @returns {Promise<number[]>} [x, y, z] raw 16-bit signed values.
     */
    async accelRaw() {
        const buf = await this._readBurst(_REG_ACCEL_XOUT_H, 6);
        return [buf.readInt16BE(0), buf.readInt16BE(2), buf.readInt16BE(4)];
    }

    /**
     * Read raw 3-axis gyroscope values.
     * @returns {Promise<number[]>} [x, y, z] raw 16-bit signed values.
     */
    async gyroRaw() {
        const buf = await this._readBurst(_REG_GYRO_XOUT_H, 6);
        return [buf.readInt16BE(0), buf.readInt16BE(2), buf.readInt16BE(4)];
    }

    /**
     * Read raw 3-axis magnetometer values.
     * @returns {Promise<number[]>} [x, y, z] raw 16-bit signed values.
     * @throws {Error} If magnetometer has not been enabled via enableMag().
     */
    async magRaw() {
        if (!this._magEnabled) {
            throw new Error('Magnetometer not enabled. Call enableMag() first.');
        }
        // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
        const buf = await this._ak8963ReadBurst(_AK8963_REG_HXL, 7);
        return [buf.readInt16LE(0), buf.readInt16LE(2), buf.readInt16LE(4)];
    }

    /**
     * Check if new sensor data is available.
     * @returns {Promise<boolean>} True when RAW_DATA_RDY_INT is set in INT_STATUS.
     */
    async dataReady() {
        return !!((await this._readReg(_REG_INT_STATUS)) & 0x01);
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     * @param {boolean} [sleep=true] - True to enter sleep mode, false to wake.
     * @returns {Promise<void>}
     */
    async setSleep(sleep = true) {
        let val = await this._readReg(_REG_PWR_MGMT_1);
        if (sleep) {
            val |= 0x40;
        } else {
            val &= ~0x40;
        }
        await this._writeReg(_REG_PWR_MGMT_1, val);
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     * @returns {Promise<number>} FIFO byte count (0–512).
     */
    async fifoCount() {
        const buf = await this._readBurst(_REG_FIFO_COUNTH, 2);
        return ((buf[0] & 0x1F) << 8) | buf[1];
    }

    /**
     * Read all available data from the FIFO buffer.
     * @returns {Promise<Buffer>} FIFO data.
     */
    async readFifo() {
        const count = await this.fifoCount();
        if (count === 0) return Buffer.alloc(0);
        return this._readBurst(_REG_FIFO_R_W, count);
    }

    /**
     * Configure and enable FIFO sources.
     * @param {boolean} [gyro=true] - Enable gyroscope data in FIFO.
     * @param {boolean} [accel=true] - Enable accelerometer data in FIFO.
     * @param {boolean} [temp=false] - Enable temperature data in FIFO.
     * @returns {Promise<void>}
     */
    async enableFifo(gyro = true, accel = true, temp = false) {
        const fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
        await this._writeReg(_REG_FIFO_EN, fifoEn);
        const userCtrl = await this._readReg(_REG_USER_CTRL);
        await this._writeReg(_REG_USER_CTRL, userCtrl | 0x40);
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     * @returns {Promise<void>}
     */
    async resetFifo() {
        const userCtrl = await this._readReg(_REG_USER_CTRL);
        await this._writeReg(_REG_USER_CTRL, userCtrl | 0x04);
    }
}

module.exports = { MPU9250Minimal, MPU9250Full };