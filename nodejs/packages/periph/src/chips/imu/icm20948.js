'use strict';

const _REG_WHO_AM_I       = 0x00;
const _REG_USER_CTRL      = 0x03;
const _REG_LP_CONFIG      = 0x05;
const _REG_PWR_MGMT_1     = 0x06;
const _REG_PWR_MGMT_2     = 0x07;
const _REG_INT_PIN_CFG    = 0x0F;
const _REG_INT_ENABLE     = 0x10;
const _REG_INT_ENABLE_1   = 0x11;
const _REG_INT_ENABLE_2   = 0x12;
const _REG_I2C_MST_STATUS = 0x17;
const _REG_INT_STATUS     = 0x19;
const _REG_INT_STATUS_1   = 0x1A;
const _REG_ACCEL_XOUT_H   = 0x2D;
const _REG_GYRO_XOUT_H    = 0x33;
const _REG_TEMP_OUT_H     = 0x39;
const _REG_EXT_SLV_SENS_DATA_00 = 0x3B;
const _REG_FIFO_EN_2      = 0x67;
const _REG_FIFO_RST       = 0x68;
const _REG_FIFO_MODE      = 0x69;
const _REG_FIFO_COUNTH    = 0x70;
const _REG_FIFO_R_W       = 0x72;
const _REG_BANK_SEL       = 0x7F;

// Bank 1
const _REG_XA_OFFS_H = 0x14;
const _REG_TIMEBASE_CORRECTION_PLL = 0x28;

// Bank 2
const _REG_GYRO_SMPLRT_DIV    = 0x00;
const _REG_GYRO_CONFIG_1      = 0x01;
const _REG_GYRO_CONFIG_2      = 0x02;
const _REG_XG_OFFS_USRH       = 0x03;
const _REG_ACCEL_SMPLRT_DIV_1 = 0x10;
const _REG_ACCEL_SMPLRT_DIV_2 = 0x11;
const _REG_ACCEL_INTEL_CTRL   = 0x12;
const _REG_ACCEL_WOM_THR      = 0x13;
const _REG_ACCEL_CONFIG       = 0x14;
const _REG_ACCEL_CONFIG_2     = 0x15;

// Bank 3
const _REG_I2C_MST_CTRL  = 0x01;
const _REG_I2C_SLV0_ADDR = 0x03;
const _REG_I2C_SLV0_REG  = 0x04;
const _REG_I2C_SLV0_CTRL = 0x05;
const _REG_I2C_SLV4_ADDR = 0x13;
const _REG_I2C_SLV4_REG  = 0x14;
const _REG_I2C_SLV4_CTRL = 0x15;
const _REG_I2C_SLV4_DO   = 0x16;
const _REG_I2C_SLV4_DI   = 0x17;

const _WHO_AM_I_VALUE = 0xEA;

const _AK_ADDR = 0x0C;
const _AK_WIA2 = 0x01;
const _AK_ST1 = 0x10;
const _AK_CNTL2 = 0x31;
const _AK_CNTL3 = 0x32;
const _AK_WIA2_VALUE = 0x09;
const _MAG_SENSITIVITY = 0.15;

const _ACCEL_SENSITIVITY = [16384.0, 8192.0, 4096.0, 2048.0];
const _GYRO_SENSITIVITY  = [131.0, 65.5, 32.8, 16.4];
const _G = 9.80665;
const _DEG_TO_RAD = Math.PI / 180.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Interrupt source bit flags returned by `pollInterrupt()` and accepted by `enableInterrupt()`. */
const ICM20948Source = {
    SOURCE_DATA_READY: 0x01,
    SOURCE_WAKE_ON_MOTION: 0x02,
    SOURCE_I2C_MASTER: 0x04,
    SOURCE_PLL_READY: 0x08,
    SOURCE_FIFO_OVERFLOW: 0x10,
};

// source -> [enable register, mask]
const _INT_ENABLE_MAP = {
    0x01: [_REG_INT_ENABLE_1, 0x01],
    0x02: [_REG_INT_ENABLE, 0x08],
    0x04: [_REG_INT_ENABLE, 0x01],
    0x08: [_REG_INT_ENABLE, 0x04],
    0x10: [_REG_INT_ENABLE_2, 0x1F],
};

/**
 * ICM-20948 9-axis MotionTracking device (TDK InvenSense) — minimal interface.
 *
 * Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. The registers are organised in four user banks
 * selected through `REG_BANK_SEL` (0x7F); the driver caches the selected bank
 * and returns to bank 0 after every bank 1–3 access. VDDIO is 1.71–1.95 V
 * only. SPI and the DMP are out of scope.
 *
 * Default configuration (written at construction):
 * - Gyroscope ±250 dps, low-pass filter 51.2 Hz (`GYRO_DLPFCFG` = 3)
 * - Accelerometer ±2 g, low-pass filter 50.4 Hz (`ACCEL_DLPFCFG` = 3)
 * - Both output data rates 102.3 Hz (divider 10)
 * - Clock auto (`CLKSEL` = 1), all six axes and the temperature sensor on
 * - FIFO, DMP, I²C master and all interrupts off
 *
 * Constructor caveat: JS constructors cannot be async, so the datasheet
 * Initialization Sequence — including the `WHO_AM_I` identity check — starts
 * in the constructor and every public method awaits it first. A wrong or
 * absent device therefore rejects the first call made on the driver; call
 * `await imu.init()` right after construction to surface it at a predictable
 * point.
 */
class ICM20948Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C connection (0x68 with AD0 = GND, 0x69 with AD0 = VDDIO).
     */
    constructor(connection) {
        this._conn = connection;
        this._bank = 0;
        this._accelFs = 0;
        this._gyroFs = 0;
        this._accelDlpf = 3;
        this._gyroDlpf = 3;
        this._accelFilter = true;
        this._gyroFilter = true;
        this._lpEn = false;
        this._lpSuspended = false;
        this._ready = this._runInitSequence();
        // The rejection is re-raised by every public method; mark it handled
        // here so it never surfaces as an unhandled rejection on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the Initialization Sequence started by the constructor.
     * @returns {Promise<void>}
     * @throws {Error} If `WHO_AM_I` is not 0xEA (wrong chip, wrong address, or wiring problem).
     */
    async init() {
        await this._ready;
    }

    async _runInitSequence() {
        await this._selectBank(0, true);
        const who = (await this._conn.readReg(_REG_WHO_AM_I, 1))[0];
        if (who !== _WHO_AM_I_VALUE) {
            throw new Error('ICM20948 WHO_AM_I: expected 0x' + _WHO_AM_I_VALUE.toString(16) +
                ', got 0x' + who.toString(16));
        }
        await this._conn.writeReg(_REG_PWR_MGMT_1, 0x80);   // DEVICE_RESET
        await _sleep(100);
        this._bank = 0;                                       // reset returns to bank 0
        await this._conn.writeReg(_REG_PWR_MGMT_1, 0x01);   // SLEEP = 0, CLKSEL = 1
        await this._conn.writeReg(_REG_PWR_MGMT_2, 0x00);   // all axes on
        await this._writeGyroConfig();
        await this._writeAccelConfig();
        await this._wr(2, _REG_GYRO_SMPLRT_DIV, 0x0A);
        await this._wr(2, _REG_ACCEL_SMPLRT_DIV_1, 0x00);
        await this._wr(2, _REG_ACCEL_SMPLRT_DIV_2, 0x0A);
        await _sleep(35);
    }

    async _selectBank(bank, force = false) {
        if (force || bank !== this._bank) {
            await this._conn.writeReg(_REG_BANK_SEL, bank << 4);
            this._bank = bank;
        }
    }

    async _rd(bank, reg, length) {
        await this._selectBank(bank);
        const data = await this._conn.readReg(reg, length);
        if (bank !== 0) await this._selectBank(0);
        return data;
    }

    async _wr(bank, reg, data) {
        await this._selectBank(bank);
        await this._conn.writeReg(reg, data);
        if (bank !== 0) await this._selectBank(0);
    }

    async _writeGyroConfig() {
        await this._wr(2, _REG_GYRO_CONFIG_1,
            (this._gyroDlpf << 3) | (this._gyroFs << 1) | (this._gyroFilter ? 1 : 0));
    }

    async _writeAccelConfig() {
        await this._wr(2, _REG_ACCEL_CONFIG,
            (this._accelDlpf << 3) | (this._accelFs << 1) | (this._accelFilter ? 1 : 0));
    }

    /**
     * Read 3-axis linear acceleration.
     * @returns {Promise<number[]>} [x, y, z] acceleration in m/s².
     */
    async accel() {
        await this._ready;
        const buf = await this._rd(0, _REG_ACCEL_XOUT_H, 6);
        const sens = _ACCEL_SENSITIVITY[this._accelFs];
        return [buf.readInt16BE(0) / sens * _G, buf.readInt16BE(2) / sens * _G,
                buf.readInt16BE(4) / sens * _G];
    }

    /**
     * Read 3-axis angular rate.
     * @returns {Promise<number[]>} [x, y, z] angular rate in rad/s.
     */
    async gyro() {
        await this._ready;
        const buf = await this._rd(0, _REG_GYRO_XOUT_H, 6);
        const sens = _GYRO_SENSITIVITY[this._gyroFs];
        return [buf.readInt16BE(0) / sens * _DEG_TO_RAD, buf.readInt16BE(2) / sens * _DEG_TO_RAD,
                buf.readInt16BE(4) / sens * _DEG_TO_RAD];
    }
}

/**
 * ICM-20948 full interface — extends `ICM20948Minimal`.
 *
 * Adds raw and burst reads, temperature, full-scale / filter / sample-rate
 * configuration, sensor power control, low-power modes, gyro and
 * accelerometer offsets, the AK09916 magnetometer (read through the chip's
 * own I²C master — no second connection is needed), single-byte
 * auxiliary-bus access, the 4 KiB FIFO, wake-on-motion, interrupts,
 * self-test and the PLL time-base correction.
 *
 * Methods that touch banks 1–3 select the bank and return to bank 0, so the
 * bank register is 0 between calls.
 */
class ICM20948Full extends ICM20948Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C connection to the ICM-20948.
     */
    constructor(connection) {
        super(connection);
        this._magEnabled = false;
        this._intCallback = null;
        this._intHandler = null;
        this._pollTimer = null;
        this._intActiveLow = false;
    }

    async _lpSuspend() {
        if (this._lpEn && !this._lpSuspended) {
            const v = (await this._conn.readReg(_REG_PWR_MGMT_1, 1))[0];
            await this._conn.writeReg(_REG_PWR_MGMT_1, v & ~0x20);
            this._lpSuspended = true;
        }
    }

    async _lpResume() {
        if (this._lpSuspended) {
            const v = (await this._conn.readReg(_REG_PWR_MGMT_1, 1))[0];
            await this._conn.writeReg(_REG_PWR_MGMT_1, v | 0x20);
            this._lpSuspended = false;
        }
    }

    /**
     * Read raw 3-axis accelerometer values.
     * @returns {Promise<number[]>} [x, y, z] signed 16-bit counts.
     */
    async accelRaw() {
        await this._ready;
        const b = await this._rd(0, _REG_ACCEL_XOUT_H, 6);
        return [b.readInt16BE(0), b.readInt16BE(2), b.readInt16BE(4)];
    }

    /**
     * Read raw 3-axis gyroscope values.
     * @returns {Promise<number[]>} [x, y, z] signed 16-bit counts.
     */
    async gyroRaw() {
        await this._ready;
        const b = await this._rd(0, _REG_GYRO_XOUT_H, 6);
        return [b.readInt16BE(0), b.readInt16BE(2), b.readInt16BE(4)];
    }

    /**
     * Burst-read acceleration, angular rate and temperature in one sample.
     * @returns {Promise<Array>} `[[ax, ay, az], [gx, gy, gz], tempC]` — m/s², rad/s, °C.
     */
    async readAll() {
        await this._ready;
        const b = await this._rd(0, _REG_ACCEL_XOUT_H, 14);
        const aSens = _ACCEL_SENSITIVITY[this._accelFs];
        const gSens = _GYRO_SENSITIVITY[this._gyroFs];
        const accel = [0, 2, 4].map((o) => b.readInt16BE(o) / aSens * _G);
        const gyro = [6, 8, 10].map((o) => b.readInt16BE(o) / gSens * _DEG_TO_RAD);
        return [accel, gyro, b.readInt16BE(12) / 333.87 + 21.0];
    }

    /**
     * Read die temperature.
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        await this._ready;
        const b = await this._rd(0, _REG_TEMP_OUT_H, 2);
        return b.readInt16BE(0) / 333.87 + 21.0;
    }

    /**
     * Set gyroscope full-scale range.
     * @param {number} [fullScale=0] - 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     * @returns {Promise<void>}
     */
    async configureGyro(fullScale = 0) {
        await this._ready;
        await this._lpSuspend();
        this._gyroFs = fullScale & 0x03;
        await this._writeGyroConfig();
        await this._lpResume();
    }

    /**
     * Set accelerometer full-scale range.
     * @param {number} [fullScale=0] - 0–3 (0=±2, 1=±4, 2=±8, 3=±16 g).
     * @returns {Promise<void>}
     */
    async configureAccel(fullScale = 0) {
        await this._ready;
        await this._lpSuspend();
        this._accelFs = fullScale & 0x03;
        await this._writeAccelConfig();
        await this._lpResume();
    }

    /**
     * Set the digital low-pass filters.
     * @param {number} [gyroDlpf=3] - `GYRO_DLPFCFG` 0–7 (3 = 51.2 Hz).
     * @param {number} [accelDlpf=3] - `ACCEL_DLPFCFG` 0–7 (3 = 50.4 Hz).
     * @param {boolean} [gyroFilter=true] - false bypasses the gyro filter (9 kHz output rate).
     * @param {boolean} [accelFilter=true] - false bypasses the accelerometer filter (4.5 kHz).
     * @returns {Promise<void>}
     */
    async configureFilters(gyroDlpf = 3, accelDlpf = 3, gyroFilter = true, accelFilter = true) {
        await this._ready;
        await this._lpSuspend();
        this._gyroDlpf = gyroDlpf & 0x07;
        this._accelDlpf = accelDlpf & 0x07;
        this._gyroFilter = !!gyroFilter;
        this._accelFilter = !!accelFilter;
        await this._writeGyroConfig();
        await this._writeAccelConfig();
        await this._lpResume();
    }

    /**
     * Set the sample-rate dividers; ODR = 1125 Hz / (1 + div).
     * @param {number} [gyroDiv=10] - `GYRO_SMPLRT_DIV` 0–255.
     * @param {number} [accelDiv=10] - `ACCEL_SMPLRT_DIV` 0–4095.
     * @returns {Promise<void>}
     */
    async configureSampleRate(gyroDiv = 10, accelDiv = 10) {
        await this._ready;
        await this._lpSuspend();
        await this._wr(2, _REG_GYRO_SMPLRT_DIV, gyroDiv & 0xFF);
        await this._wr(2, _REG_ACCEL_SMPLRT_DIV_1, (accelDiv >> 8) & 0x0F);
        await this._wr(2, _REG_ACCEL_SMPLRT_DIV_2, accelDiv & 0xFF);
        await this._lpResume();
    }

    /**
     * Switch the accelerometer and gyroscope on or off (`PWR_MGMT_2`).
     * Turning a sensor on again needs its start-up time (accel 20 ms, gyro 35 ms).
     * @param {boolean} [accel=true]
     * @param {boolean} [gyro=true]
     * @returns {Promise<void>}
     */
    async setSensors(accel = true, gyro = true) {
        await this._ready;
        await this._conn.writeReg(_REG_PWR_MGMT_2, (accel ? 0 : 0x38) | (gyro ? 0 : 0x07));
    }

    /**
     * Set or clear the `SLEEP` bit in `PWR_MGMT_1`.
     * @param {boolean} [sleep=true]
     * @returns {Promise<void>}
     */
    async setSleep(sleep = true) {
        await this._ready;
        let v = (await this._conn.readReg(_REG_PWR_MGMT_1, 1))[0];
        v = sleep ? (v | 0x40) : (v & ~0x40);
        await this._conn.writeReg(_REG_PWR_MGMT_1, v);
    }

    /**
     * Configure duty-cycled low-power operation; `LP_EN` is set last because it blocks most register writes.
     * @param {boolean} [accel=false] - Duty-cycle the accelerometer.
     * @param {boolean} [gyro=false] - Duty-cycle the gyroscope.
     * @param {number} [accelAvg=0] - `DEC3_CFG` averaging code 0–3.
     * @param {number} [gyroAvg=0] - `GYRO_AVGCFG` averaging code 0–7.
     * @returns {Promise<void>}
     */
    async setLowPower(accel = false, gyro = false, accelAvg = 0, gyroAvg = 0) {
        await this._ready;
        this._lpSuspended = false;
        const v = (await this._conn.readReg(_REG_PWR_MGMT_1, 1))[0] & ~0x20;
        await this._conn.writeReg(_REG_PWR_MGMT_1, v);
        await this._wr(2, _REG_ACCEL_CONFIG_2, accelAvg & 0x03);
        await this._wr(2, _REG_GYRO_CONFIG_2, gyroAvg & 0x07);
        await this._conn.writeReg(_REG_LP_CONFIG, 0x40 | (accel ? 0x20 : 0) | (gyro ? 0x10 : 0));
        this._lpEn = !!(accel || gyro);
        if (this._lpEn) await this._conn.writeReg(_REG_PWR_MGMT_1, v | 0x20);
    }

    /**
     * Write the gyroscope offset-cancellation registers.
     * @param {number} x - X offset in rad/s (0.0305 dps/LSB, signed 16-bit).
     * @param {number} y - Y offset in rad/s.
     * @param {number} z - Z offset in rad/s.
     * @returns {Promise<void>}
     */
    async setGyroOffset(x, y, z) {
        await this._ready;
        const buf = Buffer.alloc(6);
        [x, y, z].forEach((v, i) => {
            const code = Math.max(-32768, Math.min(32767, Math.round(v / _DEG_TO_RAD / 0.0305)));
            buf.writeInt16BE(code, 2 * i);
        });
        await this._lpSuspend();
        await this._wr(2, _REG_XG_OFFS_USRH, buf);
        await this._lpResume();
    }

    /**
     * Read the 15-bit accelerometer offset codes (0.98 mg/LSB). The registers
     * hold a per-part factory trim: read, adjust, write back.
     * @returns {Promise<number[]>} [x, y, z] signed 15-bit offset codes.
     */
    async getAccelOffset() {
        await this._ready;
        const raw = await this._rd(1, _REG_XA_OFFS_H, 8);
        return [0, 3, 6].map((i) => {
            let code = (raw[i] << 7) | (raw[i + 1] >> 1);
            if (code & 0x4000) code -= 0x8000;
            return code;
        });
    }

    /**
     * Write the 15-bit accelerometer offset codes.
     * @param {number} x - X offset code (signed 15-bit).
     * @param {number} y - Y offset code.
     * @param {number} z - Z offset code.
     * @returns {Promise<void>}
     */
    async setAccelOffset(x, y, z) {
        await this._ready;
        await this._lpSuspend();
        const regs = [0x14, 0x17, 0x1A];
        const vals = [x, y, z];
        for (let i = 0; i < 3; i++) {
            const code = vals[i] & 0x7FFF;
            await this._wr(1, regs[i], Buffer.from([(code >> 7) & 0xFF, (code << 1) & 0xFE]));
        }
        await this._lpResume();
    }

    async _slv4Wait() {
        for (let i = 0; i < 100; i++) {
            const status = (await this._conn.readReg(_REG_I2C_MST_STATUS, 1))[0];
            if (status & 0x10) throw new Error('ICM20948 auxiliary I2C NACK');
            if (status & 0x40) return;
            await _sleep(1);
        }
        throw new Error('ICM20948 auxiliary I2C transfer timed out');
    }

    /**
     * Read one byte from an auxiliary-bus device through I²C-master slave 4.
     * @param {number} address - 7-bit device address.
     * @param {number} register - Register inside the device.
     * @returns {Promise<number>} The byte read.
     * @throws {Error} On NACK or timeout.
     */
    async auxReadByte(address, register) {
        await this._ready;
        await this._selectBank(3);
        await this._conn.writeReg(_REG_I2C_SLV4_ADDR, 0x80 | (address & 0x7F));
        await this._conn.writeReg(_REG_I2C_SLV4_REG, register & 0xFF);
        await this._conn.writeReg(_REG_I2C_SLV4_CTRL, 0x80);
        await this._selectBank(0);
        await this._slv4Wait();
        return (await this._rd(3, _REG_I2C_SLV4_DI, 1))[0];
    }

    /**
     * Write one byte to an auxiliary-bus device through I²C-master slave 4.
     * @param {number} address - 7-bit device address.
     * @param {number} register - Register inside the device.
     * @param {number} value - Byte to write.
     * @returns {Promise<void>}
     * @throws {Error} On NACK or timeout.
     */
    async auxWriteByte(address, register, value) {
        await this._ready;
        await this._selectBank(3);
        await this._conn.writeReg(_REG_I2C_SLV4_ADDR, address & 0x7F);
        await this._conn.writeReg(_REG_I2C_SLV4_REG, register & 0xFF);
        await this._conn.writeReg(_REG_I2C_SLV4_DO, value & 0xFF);
        await this._conn.writeReg(_REG_I2C_SLV4_CTRL, 0x80);
        await this._selectBank(0);
        await this._slv4Wait();
    }

    /**
     * Enable the AK09916 magnetometer via the I²C master. Resets the
     * magnetometer, verifies `WIA2` == 0x09, selects continuous measurement 4
     * (100 Hz) and configures slave 0 to copy the 9-byte `ST1..ST2` block to
     * `EXT_SLV_SENS_DATA_00..08` continuously.
     * @returns {Promise<void>}
     * @throws {Error} If `WIA2` does not read 0x09 or an auxiliary transfer fails.
     */
    async enableMag() {
        await this._ready;
        await this._lpSuspend();
        const ctrl = (await this._conn.readReg(_REG_USER_CTRL, 1))[0];
        await this._conn.writeReg(_REG_USER_CTRL, ctrl | 0x20);   // I2C_MST_EN
        await this._wr(3, _REG_I2C_MST_CTRL, 0x07);                // 345.6 kHz
        await this.auxWriteByte(_AK_ADDR, _AK_CNTL3, 0x01);        // soft reset
        await _sleep(2);
        const wia = await this.auxReadByte(_AK_ADDR, _AK_WIA2);
        if (wia !== _AK_WIA2_VALUE) {
            throw new Error('AK09916 WIA2: expected 0x09, got 0x' + wia.toString(16));
        }
        await this.auxWriteByte(_AK_ADDR, _AK_CNTL2, 0x08);        // continuous 4
        await this._selectBank(3);
        await this._conn.writeReg(_REG_I2C_SLV0_ADDR, 0x80 | _AK_ADDR);
        await this._conn.writeReg(_REG_I2C_SLV0_REG, _AK_ST1);
        await this._conn.writeReg(_REG_I2C_SLV0_CTRL, 0x89);
        await this._selectBank(0);
        this._magEnabled = true;
        await this._lpResume();
    }

    /**
     * Write the magnetometer `CNTL2` mode through slave 4.
     * @param {number} mode - 0 power-down, 1 single, 2/4/6 continuous 1–3, 8 continuous 4.
     * @returns {Promise<void>}
     */
    async magSetMode(mode) {
        await this.auxWriteByte(_AK_ADDR, _AK_CNTL2, mode & 0x1F);
    }

    async _magBlock() {
        await this._ready;
        if (!this._magEnabled) {
            throw new Error('Magnetometer not enabled. Call enableMag() first.');
        }
        return this._rd(0, _REG_EXT_SLV_SENS_DATA_00, 9);
    }

    /**
     * Read the magnetic field.
     * @returns {Promise<number[]>} [x, y, z] in µT, as the chip reports them.
     * @throws {Error} If `enableMag()` has not been called, or the sensor overflowed (`HOFL`).
     */
    async mag() {
        const b = await this._magBlock();
        if (b[8] & 0x08) throw new Error('Magnetometer overflow (HOFL)');
        return [b.readInt16LE(1) * _MAG_SENSITIVITY, b.readInt16LE(3) * _MAG_SENSITIVITY,
                b.readInt16LE(5) * _MAG_SENSITIVITY];
    }

    /**
     * Read the raw magnetometer values.
     * @returns {Promise<number[]>} [x, y, z] signed 16-bit counts.
     * @throws {Error} If `enableMag()` has not been called.
     */
    async magRaw() {
        const b = await this._magBlock();
        return [b.readInt16LE(1), b.readInt16LE(3), b.readInt16LE(5)];
    }

    /**
     * Check the magnetometer `DRDY` flag from the shadowed block.
     * @returns {Promise<boolean>}
     * @throws {Error} If `enableMag()` has not been called.
     */
    async magDataReady() {
        const b = await this._magBlock();
        return (b[0] & 0x01) !== 0;
    }

    /**
     * Check whether new raw data is available (`INT_STATUS_1.RAW_DATA_0_RDY_INT`).
     * @returns {Promise<boolean>}
     */
    async dataReady() {
        await this._ready;
        return ((await this._rd(0, _REG_INT_STATUS_1, 1))[0] & 0x01) !== 0;
    }

    /**
     * Select FIFO sources, enable the FIFO and reset it.
     * @param {boolean} [accel=true] - Write accelerometer data (6 bytes).
     * @param {boolean} [gyro=true] - Write gyroscope data (6 bytes).
     * @param {boolean} [temp=false] - Write temperature data (2 bytes).
     * @returns {Promise<void>}
     */
    async enableFifo(accel = true, gyro = true, temp = false) {
        await this._ready;
        await this._lpSuspend();
        await this._conn.writeReg(_REG_FIFO_EN_2, (accel ? 0x10 : 0) | (gyro ? 0x0E : 0) | (temp ? 0x01 : 0));
        const ctrl = (await this._conn.readReg(_REG_USER_CTRL, 1))[0];
        await this._conn.writeReg(_REG_USER_CTRL, ctrl | 0x40);
        await this.resetFifo();
        await this._lpResume();
    }

    /**
     * Select FIFO mode.
     * @param {boolean} [snapshot=false] - true stops when full, false overwrites the oldest data.
     * @returns {Promise<void>}
     */
    async setFifoMode(snapshot = false) {
        await this._ready;
        await this._lpSuspend();
        await this._conn.writeReg(_REG_FIFO_MODE, snapshot ? 0x1F : 0x00);
        await this._lpResume();
    }

    /**
     * Read the number of bytes in the FIFO.
     * @returns {Promise<number>} Byte count (0–4096).
     */
    async fifoCount() {
        await this._ready;
        const b = await this._rd(0, _REG_FIFO_COUNTH, 2);
        return ((b[0] & 0x1F) << 8) | b[1];
    }

    /**
     * Read bytes from the FIFO (the frame layout is not documented in the datasheet).
     * @param {number} [count] - Maximum bytes to read (default: all available).
     * @returns {Promise<Buffer>} FIFO data.
     */
    async readFifo(count) {
        const available = await this.fifoCount();
        let remaining = (count === undefined || count > available) ? available : count;
        const chunks = [];
        while (remaining > 0) {
            const n = Math.min(remaining, 32);
            chunks.push(await this._rd(0, _REG_FIFO_R_W, n));
            remaining -= n;
        }
        return Buffer.concat(chunks);
    }

    /**
     * Reset the FIFO by asserting and de-asserting `FIFO_RST`.
     * @returns {Promise<void>}
     */
    async resetFifo() {
        await this._ready;
        await this._conn.writeReg(_REG_FIFO_RST, 0x1F);
        await this._conn.writeReg(_REG_FIFO_RST, 0x00);
    }

    /**
     * Configure wake-on-motion (needs the accelerometer on with its filter enabled).
     * @param {number} thresholdMg - Threshold in mg (4 mg/LSB, 0–1020).
     * @param {boolean} [enabled=true] - true to arm, false to disarm.
     * @returns {Promise<void>}
     */
    async setWakeOnMotion(thresholdMg, enabled = true) {
        await this._ready;
        await this._lpSuspend();
        const thr = Math.max(0, Math.min(255, Math.round(thresholdMg / 4)));
        await this._wr(2, _REG_ACCEL_WOM_THR, thr);
        await this._wr(2, _REG_ACCEL_INTEL_CTRL, enabled ? 0x03 : 0x00);
        if (enabled) await this.enableInterrupt(ICM20948Source.SOURCE_WAKE_ON_MOTION);
        else await this.disableInterrupt(ICM20948Source.SOURCE_WAKE_ON_MOTION);
        await this._lpResume();
    }

    /**
     * Configure the INT1 pin (`INT_PIN_CFG`).
     * @param {boolean} [activeLow=false]
     * @param {boolean} [openDrain=false]
     * @param {boolean} [latch=false] - Hold the level until the status is cleared.
     * @param {boolean} [anyReadClears=false] - Any read clears `INT_STATUS`.
     * @returns {Promise<void>}
     */
    async setInterruptPin(activeLow = false, openDrain = false, latch = false, anyReadClears = false) {
        await this._ready;
        this._intActiveLow = !!activeLow;
        await this._conn.writeReg(_REG_INT_PIN_CFG,
            (activeLow ? 0x80 : 0) | (openDrain ? 0x40 : 0) | (latch ? 0x20 : 0) | (anyReadClears ? 0x10 : 0));
    }

    async _intEnable(source, on) {
        await this._ready;
        const entry = _INT_ENABLE_MAP[source];
        if (!entry) throw new Error('unknown interrupt source: ' + source);
        const [reg, mask] = entry;
        const v = (await this._conn.readReg(reg, 1))[0];
        await this._conn.writeReg(reg, on ? (v | mask) : (v & ~mask));
    }

    /**
     * Enable one interrupt source.
     * @param {number} source - One of the `SOURCE_*` constants.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        await this._intEnable(source, true);
    }

    /**
     * Disable one interrupt source.
     * @param {number} source - One of the `SOURCE_*` constants.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._intEnable(source, false);
    }

    /**
     * Read (and clear) `INT_STATUS`, `INT_STATUS_1` and `INT_STATUS_2`.
     * @returns {Promise<number>} Combined status using the `SOURCE_*` bit layout.
     */
    async pollInterrupt() {
        await this._ready;
        const s = await this._rd(0, _REG_INT_STATUS, 3);
        let status = 0;
        if (s[1] & 0x01) status |= ICM20948Source.SOURCE_DATA_READY;
        if (s[0] & 0x08) status |= ICM20948Source.SOURCE_WAKE_ON_MOTION;
        if (s[0] & 0x01) status |= ICM20948Source.SOURCE_I2C_MASTER;
        if (s[0] & 0x04) status |= ICM20948Source.SOURCE_PLL_READY;
        if (s[2] & 0x1F) status |= ICM20948Source.SOURCE_FIFO_OVERFLOW;
        return status;
    }

    /**
     * Subscribe to INT1 assertions. Uses the connection's `intPin` when
     * present, otherwise falls back to a 5 ms polling timer.
     * @param {function(number): void} callback - Called with the `pollInterrupt()` status.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        this._intCallback = callback;
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin) {
            this._intHandler = () => { this._dispatchInt(); };
            await intPin.onEdge(this._intHandler, this._intActiveLow ? 'falling' : 'rising');
        } else {
            this._pollTimer = setInterval(() => { this._dispatchInt(); }, 5);
        }
    }

    /**
     * Unsubscribe from INT1 assertions.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        this._intCallback = null;
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin && this._intHandler) {
            await intPin.offEdge(this._intHandler);
        }
        this._intHandler = null;
        if (this._pollTimer !== null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    async _dispatchInt() {
        const cb = this._intCallback;
        if (!cb) return;
        try {
            const status = await this.pollInterrupt();
            if (status) cb(status);
        } catch (err) {
            // ignore: the next edge or poll retries
        }
    }

    async _averageRaw(samples = 5) {
        const sa = [0, 0, 0];
        const sg = [0, 0, 0];
        for (let n = 0; n < samples; n++) {
            const a = await this.accelRaw();
            const g = await this.gyroRaw();
            for (let i = 0; i < 3; i++) { sa[i] += a[i]; sg[i] += g[i]; }
            await _sleep(11);
        }
        return [sa.map((v) => v / samples), sg.map((v) => v / samples)];
    }

    /**
     * Run the self-test enable procedure and return the raw response.
     * Configures ±2 g / ±250 dps, averages samples with and without the
     * self-test actuation and restores the configuration. The datasheet gives
     * no pass/fail limits, so none is evaluated.
     * @returns {Promise<Array>} `[[ax, ay, az], [gx, gy, gz]]` — raw response (enabled minus disabled) per axis.
     */
    async selfTest() {
        await this._ready;
        const saved = [this._accelFs, this._gyroFs];
        await this.configureAccel(0);
        await this.configureGyro(0);
        await _sleep(50);
        const [a0, g0] = await this._averageRaw();
        await this._lpSuspend();
        await this._wr(2, _REG_GYRO_CONFIG_2, 0x38);
        await this._wr(2, _REG_ACCEL_CONFIG_2, 0x1C);
        await _sleep(50);
        const [a1, g1] = await this._averageRaw();
        await this._wr(2, _REG_GYRO_CONFIG_2, 0x00);
        await this._wr(2, _REG_ACCEL_CONFIG_2, 0x00);
        await this._lpResume();
        await this.configureAccel(saved[0]);
        await this.configureGyro(saved[1]);
        return [a1.map((v, i) => v - a0[i]), g1.map((v, i) => v - g0[i])];
    }

    /**
     * Read the per-part PLL time-base correction.
     * @returns {Promise<number>} Deviation in percent (0.079 %/LSB, signed).
     */
    async readTimebaseCorrection() {
        await this._ready;
        const raw = (await this._rd(1, _REG_TIMEBASE_CORRECTION_PLL, 1))[0];
        return (raw > 127 ? raw - 256 : raw) * 0.079;
    }

    /**
     * Issue `DEVICE_RESET` and wait. The chip is left in sleep with default
     * registers; create a new driver afterwards to run the initialization again.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._conn.writeReg(_REG_PWR_MGMT_1, 0x80);
        await _sleep(100);
        this._bank = 0;
        this._lpEn = false;
        this._lpSuspended = false;
        this._magEnabled = false;
    }
}

Object.assign(ICM20948Full, ICM20948Source);

module.exports = { ICM20948Minimal, ICM20948Full, ICM20948Source };
