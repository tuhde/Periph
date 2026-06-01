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

module.exports = { MPU6050Minimal };
