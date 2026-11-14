'use strict';

const _REG_CHIP_ID = 0x00;
const _REG_PAGE_ID = 0x07;
const _REG_ACC_DATA = 0x08;
const _REG_MAG_DATA = 0x0E;
const _REG_GYR_DATA = 0x14;
const _REG_EUL_DATA = 0x1A;
const _REG_QUA_DATA = 0x20;
const _REG_LIA_DATA = 0x28;
const _REG_GRV_DATA = 0x2E;
const _REG_TEMP = 0x34;
const _REG_CALIB_STAT = 0x35;
const _REG_ST_RESULT = 0x36;
const _REG_INT_STA = 0x37;
const _REG_SYS_STATUS = 0x39;
const _REG_UNIT_SEL = 0x3B;
const _REG_OPR_MODE = 0x3D;
const _REG_PWR_MODE = 0x3E;
const _REG_SYS_TRIGGER = 0x3F;
const _REG_TEMP_SOURCE = 0x40;
const _REG_AXIS_MAP_CONFIG = 0x41;
const _REG_AXIS_MAP_SIGN = 0x42;
const _REG_ACC_OFFSET = 0x55;
const _REG_INT_MSK = 0x0F;   // page 1
const _REG_INT_EN = 0x10;    // page 1
const _REG_UNIQUE_ID = 0x50; // page 1

const _CHIP_ID_VALUE = 0xA0;
const _UNIT_SEL_SI = 0x86;
const _MODE_CONFIG = 0x00;
const _MODE_NDOF = 0x0C;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Interrupt source bit masks used by `BNO055Full` (`pollInterrupt()` return value
 * and the argument of `enableInterrupt()` / `disableInterrupt()`).
 */
const BNO055Source = {
    ACC_BSX_DRDY: 0x01,
    MAG_DRDY: 0x02,
    GYRO_AM: 0x04,
    GYR_HIGH_RATE: 0x08,
    GYR_DRDY: 0x10,
    ACC_HIGH_G: 0x20,
    ACC_AM: 0x40,
    ACC_NM: 0x80,
};

/** Operation mode values for `BNO055Full.setMode()`. */
const BNO055Mode = {
    CONFIG: 0x0, ACCONLY: 0x1, MAGONLY: 0x2, GYROONLY: 0x3, ACCMAG: 0x4, ACCGYRO: 0x5,
    MAGGYRO: 0x6, AMG: 0x7, IMU: 0x8, COMPASS: 0x9, M4G: 0xA, NDOF_FMC_OFF: 0xB, NDOF: 0xC,
};

const _s16 = (buf, offset) => buf.readInt16LE(offset);

/**
 * BNO055 minimal interface — fused Euler angles.
 *
 * Waits for the chip to boot (up to 650 ms), checks `CHIP_ID` (0xA0), selects normal power
 * mode and `UNIT_SEL` = 0x86 (Android orientation, radians, rad/s, m/s², °C), then enters
 * NDOF fusion mode (100 Hz absolute orientation). I²C address 0x29 (COM3 high, datasheet
 * default) or 0x28 (COM3 low); the chip stretches the I²C clock.
 *
 * The constructor starts the initialisation asynchronously; every public method waits for
 * it. Call `await imu.init()` right after construction to surface a failure at a
 * predictable point.
 */
class BNO055Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x28 or 0x29).
     */
    constructor(connection) {
        this._conn = connection;
        this._mode = _MODE_NDOF;
        this._ready = this._initialize();
        // Re-raised by every public method; mark handled so it never surfaces on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the initialisation sequence started by the constructor.
     * @returns {Promise<void>}
     * @throws {Error} If `CHIP_ID` does not read 0xA0 within 650 ms.
     */
    async init() {
        await this._ready;
    }

    async _initialize() {
        const deadline = Date.now() + 650;
        let id = -1;
        for (;;) {
            try {
                id = (await this._conn.readReg(_REG_CHIP_ID, 1))[0];
                if (id === _CHIP_ID_VALUE) break;
            } catch (e) {
                // the chip does not ACK while booting
            }
            if (Date.now() >= deadline) {
                throw new Error('BNO055 CHIP_ID: expected 0xA0, got 0x' + (id & 0xFF).toString(16).toUpperCase());
            }
            await _sleep(10);
        }
        await this._conn.writeReg(_REG_PWR_MODE, 0x00);
        await this._conn.writeReg(_REG_PAGE_ID, 0x00);
        await this._conn.writeReg(_REG_UNIT_SEL, _UNIT_SEL_SI);
        await this._conn.writeReg(_REG_OPR_MODE, _MODE_NDOF);
        this._mode = _MODE_NDOF;
        await _sleep(7);
    }

    async _vector(reg, scale) {
        await this._ready;
        const raw = await this._conn.readReg(reg, 6);
        return [_s16(raw, 0) / scale, _s16(raw, 2) / scale, _s16(raw, 4) / scale];
    }

    /**
     * Read the fused Euler angles (one burst of 0x1A–0x1F, raw / 900).
     * @returns {Promise<[number, number, number]>} `[heading, roll, pitch]` in rad.
     */
    async euler() {
        return this._vector(_REG_EUL_DATA, 900);
    }
}

/**
 * BNO055 full interface — all outputs, calibration, modes and interrupts.
 */
class BNO055Full extends BNO055Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x28 or 0x29).
     */
    constructor(connection) {
        super(connection);
        this._clkSel = 0x00;
        this._intCallback = null;
        this._intHandler = null;
        this._intPinUsed = null;
        this._pollTimer = null;
    }

    async _r1(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _enterConfig() {
        const prev = this._mode;
        await this._conn.writeReg(_REG_OPR_MODE, _MODE_CONFIG);
        await _sleep(19);
        return prev;
    }

    async _leaveConfig(mode) {
        if (mode !== _MODE_CONFIG) {
            await this._conn.writeReg(_REG_OPR_MODE, mode);
            await _sleep(7);
        }
    }

    /**
     * Select the operation mode (through CONFIGMODE, 19 ms + 7 ms waits).
     * @param {number} mode - 0 (CONFIGMODE) … 12 (NDOF); see `BNO055Mode`.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        await this._ready;
        if (!(mode >= 0 && mode <= 12)) throw new RangeError('mode must be 0..12');
        await this._conn.writeReg(_REG_OPR_MODE, _MODE_CONFIG);
        await _sleep(19);
        if (mode !== _MODE_CONFIG) {
            await this._conn.writeReg(_REG_OPR_MODE, mode);
            await _sleep(7);
        }
        this._mode = mode;
    }

    /**
     * Read `OPR_MODE`.
     * @returns {Promise<number>} Current operation mode, 0–12.
     */
    async getMode() {
        await this._ready;
        return (await this._r1(_REG_OPR_MODE)) & 0x0F;
    }

    /**
     * Read the fused orientation quaternion (raw / 16384).
     * @returns {Promise<[number, number, number, number]>} `[w, x, y, z]`.
     */
    async quaternion() {
        await this._ready;
        const raw = await this._conn.readReg(_REG_QUA_DATA, 8);
        return [_s16(raw, 0) / 16384, _s16(raw, 2) / 16384, _s16(raw, 4) / 16384, _s16(raw, 6) / 16384];
    }

    /**
     * Read the gravity-free linear acceleration (raw / 100).
     * @returns {Promise<[number, number, number]>} `[x, y, z]` in m/s².
     */
    async linearAcceleration() { return this._vector(_REG_LIA_DATA, 100); }

    /**
     * Read the gravity vector (raw / 100).
     * @returns {Promise<[number, number, number]>} `[x, y, z]` in m/s².
     */
    async gravity() { return this._vector(_REG_GRV_DATA, 100); }

    /**
     * Read the raw accelerometer (raw / 100).
     * @returns {Promise<[number, number, number]>} `[x, y, z]` in m/s².
     */
    async acceleration() { return this._vector(_REG_ACC_DATA, 100); }

    /**
     * Read the raw magnetometer (raw / 16).
     * @returns {Promise<[number, number, number]>} `[x, y, z]` in µT.
     */
    async magneticField() { return this._vector(_REG_MAG_DATA, 16); }

    /**
     * Read the raw gyroscope (raw / 900).
     * @returns {Promise<[number, number, number]>} `[x, y, z]` in rad/s.
     */
    async angularRate() { return this._vector(_REG_GYR_DATA, 900); }

    /**
     * Read the temperature (signed 8 bit, 1 LSB = 1 °C).
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        await this._ready;
        const v = await this._r1(_REG_TEMP);
        return v & 0x80 ? v - 256 : v;
    }

    /**
     * Read `CALIB_STAT`.
     * @returns {Promise<[number, number, number, number]>} `[sys, gyr, acc, mag]`, each 0 (uncalibrated) … 3 (calibrated).
     */
    async calibrationStatus() {
        await this._ready;
        const v = await this._r1(_REG_CALIB_STAT);
        return [(v >> 6) & 3, (v >> 4) & 3, (v >> 2) & 3, v & 3];
    }

    /**
     * Check whether sys, gyr, acc and mag are all fully calibrated.
     * @returns {Promise<boolean>} True if every status value is 3.
     */
    async isCalibrated() {
        await this._ready;
        return (await this._r1(_REG_CALIB_STAT)) === 0xFF;
    }

    /**
     * Read the calibration offsets and radii in CONFIGMODE (previous mode restored).
     * @returns {Promise<Buffer>} 22 bytes from `ACC_OFFSET_X_LSB` (0x55) to `MAG_RADIUS_MSB` (0x6A).
     */
    async getCalibration() {
        await this._ready;
        const prev = await this._enterConfig();
        const data = Buffer.from(await this._conn.readReg(_REG_ACC_OFFSET, 22));
        await this._leaveConfig(prev);
        return data;
    }

    /**
     * Write the calibration offsets and radii in CONFIGMODE (previous mode restored).
     * @param {Buffer|number[]} data - 22 bytes as returned by `getCalibration()`.
     * @returns {Promise<void>}
     */
    async setCalibration(data) {
        await this._ready;
        if (data.length !== 22) throw new RangeError('calibration data must be 22 bytes');
        const prev = await this._enterConfig();
        for (let i = 0; i < 22; i++) await this._conn.writeReg(_REG_ACC_OFFSET + i, data[i]);
        await this._leaveConfig(prev);
    }

    /**
     * Remap the sensor axes (CONFIGMODE, previous mode restored).
     * @param {number} config - `AXIS_MAP_CONFIG` (bits 1:0 X, 3:2 Y, 5:4 Z source axis; default 0x24).
     * @param {number} sign - `AXIS_MAP_SIGN` (bit 2 X, bit 1 Y, bit 0 Z inverted; default 0x00).
     * @returns {Promise<void>}
     */
    async setAxisRemap(config, sign) {
        await this._ready;
        const prev = await this._enterConfig();
        await this._conn.writeReg(_REG_AXIS_MAP_CONFIG, config);
        await this._conn.writeReg(_REG_AXIS_MAP_SIGN, sign);
        await this._leaveConfig(prev);
    }

    /**
     * Select the power mode (CONFIGMODE, previous mode restored).
     * @param {'normal'|'low_power'|'suspend'} mode
     * @returns {Promise<void>}
     */
    async setPowerMode(mode) {
        await this._ready;
        const codes = { normal: 0, low_power: 1, suspend: 2 };
        if (!(mode in codes)) throw new RangeError("mode must be 'normal', 'low_power' or 'suspend'");
        const prev = await this._enterConfig();
        await this._conn.writeReg(_REG_PWR_MODE, codes[mode]);
        await this._leaveConfig(prev);
    }

    /**
     * Select the temperature sensor (CONFIGMODE, previous mode restored).
     * @param {'accelerometer'|'gyroscope'} source
     * @returns {Promise<void>}
     */
    async setTemperatureSource(source) {
        await this._ready;
        const codes = { accelerometer: 0, gyroscope: 1 };
        if (!(source in codes)) throw new RangeError("source must be 'accelerometer' or 'gyroscope'");
        const prev = await this._enterConfig();
        await this._conn.writeReg(_REG_TEMP_SOURCE, codes[source]);
        await this._leaveConfig(prev);
    }

    /**
     * Select the external 32.768 kHz crystal (only if fitted) or the internal oscillator (CONFIGMODE).
     * @param {boolean} on
     * @returns {Promise<void>}
     */
    async useExternalCrystal(on) {
        await this._ready;
        const prev = await this._enterConfig();
        this._clkSel = on ? 0x80 : 0x00;
        await this._conn.writeReg(_REG_SYS_TRIGGER, this._clkSel);
        await this._leaveConfig(prev);
    }

    /**
     * Run the power-on self-test (CONFIGMODE, previous mode restored).
     * @returns {Promise<{acc: boolean, mag: boolean, gyr: boolean, mcu: boolean}>} true = passed.
     */
    async selfTest() {
        await this._ready;
        const prev = await this._enterConfig();
        await this._conn.writeReg(_REG_SYS_TRIGGER, this._clkSel | 0x01);
        await _sleep(500);
        const v = await this._r1(_REG_ST_RESULT);
        await this._leaveConfig(prev);
        return { acc: !!(v & 1), mag: !!(v & 2), gyr: !!(v & 4), mcu: !!(v & 8) };
    }

    /**
     * Read `SYS_STATUS` and `SYS_ERR`.
     * @returns {Promise<[number, number]>} `[status, error]` — status 0–6 (5 = fusion running), error 0–10.
     */
    async systemStatus() {
        await this._ready;
        const raw = await this._conn.readReg(_REG_SYS_STATUS, 2);
        return [raw[0], raw[1]];
    }

    /**
     * Reset the chip (`RST_SYS`), wait 650 ms and re-initialise into NDOF.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._conn.writeReg(_REG_SYS_TRIGGER, this._clkSel | 0x20);
        await _sleep(650);
        this._clkSel = 0x00;
        await this._initialize();
    }

    /**
     * Read the identification and revision registers 0x00–0x06.
     * @returns {Promise<{chipId: number, accId: number, magId: number, gyrId: number, swRevision: number, bootloader: number}>}
     */
    async revisions() {
        await this._ready;
        const raw = await this._conn.readReg(_REG_CHIP_ID, 7);
        return { chipId: raw[0], accId: raw[1], magId: raw[2], gyrId: raw[3],
                 swRevision: raw[4] | (raw[5] << 8), bootloader: raw[6] };
    }

    /**
     * Read the 16-byte unique ID (page 1, page 0 restored).
     * @returns {Promise<Buffer>} 16 bytes from `UNIQUE_ID` (0x50).
     */
    async uniqueId() {
        await this._ready;
        await this._conn.writeReg(_REG_PAGE_ID, 0x01);
        try {
            return Buffer.from(await this._conn.readReg(_REG_UNIQUE_ID, 16));
        } finally {
            await this._conn.writeReg(_REG_PAGE_ID, 0x00);
        }
    }

    async _updateIntReg(source, enable) {
        await this._ready;
        await this._conn.writeReg(_REG_PAGE_ID, 0x01);
        try {
            for (const reg of [_REG_INT_EN, _REG_INT_MSK]) {
                let v = await this._r1(reg);
                v = enable ? (v | source) : (v & ~source & 0xFF);
                await this._conn.writeReg(reg, v);
            }
        } finally {
            await this._conn.writeReg(_REG_PAGE_ID, 0x00);
        }
    }

    /**
     * Enable interrupt source bits in `INT_EN` and `INT_MSK` (page 1, page 0 restored).
     * @param {number} source - One or more `BNO055Source` bits.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) { await this._updateIntReg(source, true); }

    /**
     * Disable interrupt source bits in `INT_EN` and `INT_MSK`.
     * @param {number} source - One or more `BNO055Source` bits.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) { await this._updateIntReg(source, false); }

    /**
     * Read `INT_STA` (cleared on read).
     * @returns {Promise<number>} Status bits using the `BNO055Source` layout.
     */
    async pollInterrupt() {
        await this._ready;
        return this._r1(_REG_INT_STA);
    }

    /**
     * Subscribe to INT assertions (rising edge). Uses `intPin` or the connection's `intPin`;
     * without either, a 100 ms polling timer calls `pollInterrupt()`.
     * @param {function(number): void} callback - Called with the `INT_STA` bits when non-zero.
     * @param {object} [intPin] - Optional input pin overriding `connection.intPin`.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin) {
        await this._ready;
        this._intCallback = callback;
        const pin = intPin || (this._conn ? this._conn.intPin : null);
        if (pin) {
            this._intPinUsed = pin;
            this._intHandler = () => { this._dispatchInt(); };
            await pin.onEdge(this._intHandler, 'rising');
        } else {
            this._pollTimer = setInterval(() => { this._dispatchInt(); }, 100);
        }
    }

    /**
     * Unsubscribe from INT assertions.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        this._intCallback = null;
        if (this._intPinUsed && this._intHandler) await this._intPinUsed.offEdge(this._intHandler);
        this._intPinUsed = null;
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
}

Object.assign(BNO055Full, {
    SOURCE_ACC_BSX_DRDY: BNO055Source.ACC_BSX_DRDY,
    SOURCE_MAG_DRDY: BNO055Source.MAG_DRDY,
    SOURCE_GYRO_AM: BNO055Source.GYRO_AM,
    SOURCE_GYR_HIGH_RATE: BNO055Source.GYR_HIGH_RATE,
    SOURCE_GYR_DRDY: BNO055Source.GYR_DRDY,
    SOURCE_ACC_HIGH_G: BNO055Source.ACC_HIGH_G,
    SOURCE_ACC_AM: BNO055Source.ACC_AM,
    SOURCE_ACC_NM: BNO055Source.ACC_NM,
    MODE: BNO055Mode,
});

module.exports = { BNO055Minimal, BNO055Full, BNO055Source, BNO055Mode };
