'use strict';

/**
 * FXOS8700CQ 6-axis e-compass driver — 14-bit accelerometer + 16-bit
 * magnetometer (NXP).
 *
 * The FXOS8700CQ combines a three-axis MEMS accelerometer (±2 / ±4 / ±8 g,
 * 0.244 / 0.488 / 0.976 mg/LSB) and a three-axis magnetometer (fixed ±1200 µT
 * range, 0.1 µT/LSB) behind **one I²C address** and **one shared ADC**. In
 * *hybrid mode* both sensors are sampled in an interleaved fashion at half the
 * programmed output data rate (ODR) and all twelve data bytes can be read in a
 * single burst. The accelerometer side adds a 32-sample FIFO, a high-pass
 * filtered output, embedded freefall / motion, transient, tap, orientation and
 * Auto-SLEEP / WAKE detectors; the magnetometer side adds hard-iron offsets,
 * an autonomous min / max tracker with hardware auto-calibration and a
 * relative die-temperature sensor.
 *
 * I²C framing is the plain register-pointer protocol (write the register
 * address, repeated START, read). There is no auto-increment bit; multi-byte
 * transfers auto-increment within one transaction.
 *
 * The vector-magnitude detectors (0x5F-0x72), the magnetic threshold detector
 * (0x52-0x5A, `M_INT_SRC`) and the per-axis freefall / motion thresholds
 * (0x73-0x78) are not wrapped by the API: use `readRegister()` /
 * `writeRegister()`.
 *
 * The constructor starts the initialization sequence asynchronously; await
 * `init()` (every public method also waits for it).
 */

/** I²C address for SA1 = 0, SA0 = 0 (default). */
const I2C_ADDRESS = 0x1E;
/** All four selectable I²C addresses. */
const I2C_ADDRESSES = [0x1C, 0x1D, 0x1E, 0x1F];

// Registers.
const REG_STATUS = 0x00;
const REG_OUT_X_MSB = 0x01;
const REG_F_SETUP = 0x09;
const REG_TRIG_CFG = 0x0A;
const REG_SYSMOD = 0x0B;
const REG_INT_SOURCE = 0x0C;
const REG_WHO_AM_I = 0x0D;
const REG_XYZ_DATA_CFG = 0x0E;
const REG_HP_FILTER_CUTOFF = 0x0F;
const REG_PL_STATUS = 0x10;
const REG_PL_CFG = 0x11;
const REG_PL_COUNT = 0x12;
const REG_PL_BF_ZCOMP = 0x13;
const REG_PL_THS = 0x14;
const REG_A_FFMT_CFG = 0x15;
const REG_A_FFMT_SRC = 0x16;
const REG_A_FFMT_THS = 0x17;
const REG_A_FFMT_COUNT = 0x18;
const REG_TRANSIENT_CFG = 0x1D;
const REG_TRANSIENT_SRC = 0x1E;
const REG_TRANSIENT_THS = 0x1F;
const REG_TRANSIENT_COUNT = 0x20;
const REG_PULSE_CFG = 0x21;
const REG_PULSE_SRC = 0x22;
const REG_PULSE_THSX = 0x23;
const REG_PULSE_TMLT = 0x26;
const REG_PULSE_LTCY = 0x27;
const REG_PULSE_WIND = 0x28;
const REG_ASLP_COUNT = 0x29;
const REG_CTRL1 = 0x2A;
const REG_CTRL2 = 0x2B;
const REG_CTRL3 = 0x2C;
const REG_CTRL4 = 0x2D;
const REG_CTRL5 = 0x2E;
const REG_OFF_X = 0x2F;
const REG_M_DR_STATUS = 0x32;
const REG_M_OUT_X_MSB = 0x33;
const REG_M_OFF_X_MSB = 0x3F;
const REG_MAX_X_MSB = 0x45;
const REG_MIN_X_MSB = 0x4B;
const REG_TEMP = 0x51;
const REG_M_CTRL1 = 0x5B;
const REG_M_CTRL2 = 0x5C;
const REG_M_CTRL3 = 0x5D;

// Bit masks.
const CTRL1_ACTIVE = 0x01;
const CTRL1_F_READ = 0x02;
const CTRL1_LNOISE = 0x04;
const CTRL1_DR_MASK = 0x38;
const CTRL1_ASLP_MASK = 0xC0;
const CTRL2_ST = 0x80;
const CTRL2_RST = 0x40;
const CTRL2_SMODS_MASK = 0x18;
const CTRL2_SLPE = 0x04;
const CTRL2_MODS_MASK = 0x03;
const STATUS_ZYXDR = 0x08;
const M_CTRL1_ACAL = 0x80;
const M_CTRL1_RST = 0x40;
const M_CTRL1_OST = 0x20;
const M_CTRL1_OS_MASK = 0x1C;
const M_CTRL1_HMS_MASK = 0x03;
const M_CTRL2_HYB_AUTOINC = 0x20;

const WHO_AM_I_VALUES = [0xC7, 0xC4];

// System output data rates selected by CTRL_REG1.dr (Hz).
const ODR_HZ = [800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.5625];

// Debounce time steps in ms (also PULSE_TMLT with the pulse LPF on), indexed
// [mods][row]; `row` is the CTRL_REG1.dr code, shifted by one in hybrid mode.
const STEP_MS = [
    [1.25, 2.5, 5.0, 10.0, 20.0, 20.0, 20.0, 20.0],
    [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 80.0, 80.0],
    [1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5],
    [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 160.0, 160.0],
];

// PULSE_TMLT steps with the pulse low-pass filter off (LTCY / WIND are twice).
const PULSE_STEP_NO_LPF_MS = [
    [0.625, 0.625, 1.25, 2.5, 5.0, 5.0, 5.0, 5.0],
    [0.625, 0.625, 1.25, 2.5, 5.0, 20.0, 20.0, 20.0],
    [0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625],
    [0.625, 1.25, 2.5, 5.0, 10.0, 40.0, 40.0, 40.0],
];

// Orientation trip angle (degrees) -> P_L_THS<4:0>.
const PL_THRESHOLDS = {
    15: 0x07, 20: 0x09, 30: 0x0C, 35: 0x0D, 40: 0x0F,
    45: 0x10, 55: 0x13, 60: 0x14, 70: 0x17, 75: 0x19,
};

// Auto-SLEEP rates (Hz) -> CTRL_REG1.aslp_rate.
const ASLP_RATES = [[50.0, 0], [12.5, 1], [6.25, 2], [1.56, 3]];

const COUNTS_PER_G = { 2: 4096.0, 4: 2048.0, 8: 1024.0 };
const MAG_UT_PER_LSB = 0.1;

/**
 * Interrupt source constants for the FXOS8700.
 *
 * The values equal the bit positions of `INT_SOURCE`, `CTRL_REG4` and
 * `CTRL_REG5`, so they are also the return mask of `pollInterrupt()`.
 */
const FXOS8700Source = {
    /** New sample (accelerometer in accel-only mode, magnetometer otherwise). */
    SOURCE_DATA_READY: 0x01,
    /** Accelerometer vector-magnitude change (configured via raw registers). */
    SOURCE_ACCEL_VECM: 0x02,
    /** Freefall / motion (`A_FFMT_SRC.ea`). */
    SOURCE_FF_MT: 0x04,
    /** Single / double tap (`PULSE_SRC.ea`). */
    SOURCE_PULSE: 0x08,
    /** Orientation change (`PL_STATUS.newlp`). */
    SOURCE_ORIENTATION: 0x10,
    /** Transient (`TRANSIENT_SRC.ea`). */
    SOURCE_TRANSIENT: 0x20,
    /** FIFO overflow or watermark. */
    SOURCE_FIFO: 0x40,
    /** WAKE <-> SLEEP transition. */
    SOURCE_AUTO_SLEEP: 0x80,
};

/**
 * Sleep `ms` milliseconds.
 *
 * @param {number} ms - Delay in milliseconds.
 * @returns {Promise<void>}
 */
function _delayMs(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Clamp `value` into `[low, high]`.
 *
 * @param {number} value - Value.
 * @param {number} low - Lower bound.
 * @param {number} high - Upper bound.
 * @returns {number} Clamped value.
 */
function _clamp(value, low, high) {
    return value < low ? low : (value > high ? high : value);
}

/**
 * Sign-extend an unsigned 16-bit value.
 *
 * @param {number} value - Raw 16-bit value.
 * @returns {number} Signed value.
 */
function _toSigned16(value) {
    return value & 0x8000 ? value - 0x10000 : value;
}

/**
 * Sign-extend an unsigned 8-bit value.
 *
 * @param {number} value - Raw 8-bit value.
 * @returns {number} Signed value.
 */
function _toSigned8(value) {
    return value & 0x80 ? value - 0x100 : value;
}

/**
 * FXOS8700 minimal interface — acceleration and magnetic field.
 *
 * The constructor starts the datasheet initialization sequence: it verifies
 * `WHO_AM_I` (0xC7, or 0xC4 on pre-production parts), performs a software
 * reset and starts hybrid mode.
 *
 * Default configuration: hybrid mode, system ODR 200 Hz (100 Hz per sensor),
 * ±2 g (4096 counts/g), 14-bit output, normal accelerometer oversampling,
 * magnetometer OSR 7, low-noise off, `hyb_autoinc_mode` on, no FIFO, no
 * detectors, no interrupt routing, interrupts active-low push-pull.
 */
class FXOS8700Minimal {
    /**
     * @param {object} connection - Register connection (I²C) bound to 0x1C-0x1F.
     */
    constructor(connection) {
        this._conn = connection;
        this._mode = 2;          // 0 accel only, 1 mag only, 2 hybrid
        this._dr = 2;            // CTRL_REG1.dr code
        this._fsG = 2;
        this._mods = 0;          // accelerometer oversampling (wake)
        this._lnoise = false;
        this._fRead = false;     // true = 8-bit fast read
        this._fifoMode = 0;
        this._hyb = true;        // hyb_autoinc_mode
        this._ready = this._runInit();
        // The rejection is re-raised by every public method.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the initialization sequence started by the constructor.
     *
     * @returns {Promise<void>}
     * @throws {Error} If `WHO_AM_I` is neither 0xC7 nor 0xC4.
     */
    async init() {
        await this._ready;
    }

    /**
     * Run the initialization sequence: verify `WHO_AM_I`, software reset (the
     * `rst` write is not acknowledged and the NACK is ignored) and start
     * hybrid mode at 200 Hz system ODR, ±2 g, magnetometer OSR 7.
     *
     * @returns {Promise<void>}
     */
    async _runInit() {
        await _delayMs(1);
        if (!WHO_AM_I_VALUES.includes(await this._whoAmI())) {
            throw new Error('FXOS8700 not found: unexpected WHO_AM_I');
        }
        await this._softReset();
        if (!WHO_AM_I_VALUES.includes(await this._whoAmI())) {
            throw new Error('FXOS8700 not found after reset');
        }
        await this._conn.writeReg(REG_CTRL1, 0x00);
        await this._conn.writeReg(REG_M_CTRL1, 0x1F);
        await this._conn.writeReg(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC);
        await this._conn.writeReg(REG_XYZ_DATA_CFG, 0x00);
        await this._conn.writeReg(REG_CTRL2, 0x00);
        await this._conn.writeReg(REG_CTRL1, 0x11);
        this._mode = 2;
        this._dr = 2;
        this._fsG = 2;
        this._mods = 0;
        this._lnoise = false;
        this._fRead = false;
        this._fifoMode = 0;
        this._hyb = true;
        await _delayMs(this._turnOnMs());
    }

    /**
     * Read `WHO_AM_I`, retrying on NACK for up to 10 ms.
     *
     * @returns {Promise<number>} The identity byte.
     */
    async _whoAmI() {
        for (let attempt = 0; attempt < 10; attempt++) {
            try {
                return (await this._conn.readReg(REG_WHO_AM_I, 1))[0];
            } catch (e) {
                await _delayMs(1);
            }
        }
        return (await this._conn.readReg(REG_WHO_AM_I, 1))[0];
    }

    /**
     * Write `CTRL_REG2.rst` tolerating the missing ACK, then wait 2 ms.
     *
     * @returns {Promise<void>}
     */
    async _softReset() {
        try {
            await this._conn.writeReg(REG_CTRL2, CTRL2_RST);
        } catch (e) {
            // The chip resets its I²C interface and does not ACK.
        }
        await _delayMs(2);
    }

    /**
     * Read the acceleration.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Acceleration in g.
     */
    async readAccel() {
        await this._ready;
        const raw = await this._readAccelCounts();
        const scale = this._accelScale();
        return { x: raw.x / scale, y: raw.y / scale, z: raw.z / scale };
    }

    /**
     * Read the magnetic flux density.
     *
     * Reading `M_OUT_X_MSB` first latches the X / Y / Z set, so the six bytes
     * are always read as one burst.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Field in µT.
     */
    async readMag() {
        await this._ready;
        const raw = await this._readMagCounts();
        return { x: raw.x * MAG_UT_PER_LSB, y: raw.y * MAG_UT_PER_LSB, z: raw.z * MAG_UT_PER_LSB };
    }

    // --- Helpers shared with FXOS8700Full --------------------------------

    /**
     * Counts per g at the current range and resolution.
     *
     * @returns {number} Counts per g.
     */
    _accelScale() {
        const cpg = COUNTS_PER_G[this._fsG];
        return this._fRead ? cpg / 64.0 : cpg;
    }

    /**
     * Decode three accelerometer axes from a buffer.
     *
     * @param {Buffer} data - Raw bytes.
     * @param {number} [offset=0] - Start index.
     * @returns {{x:number, y:number, z:number}} Signed counts.
     */
    _decodeAccel(data, offset = 0) {
        if (this._fRead) {
            return {
                x: _toSigned8(data[offset]),
                y: _toSigned8(data[offset + 1]),
                z: _toSigned8(data[offset + 2]),
            };
        }
        const axis = (i) => _toSigned16((data[offset + 2 * i] << 8) | data[offset + 2 * i + 1]) >> 2;
        return { x: axis(0), y: axis(1), z: axis(2) };
    }

    /**
     * Read the signed accelerometer counts (14-bit, or 8-bit in fast read).
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Counts.
     */
    async _readAccelCounts() {
        const n = this._fRead ? 3 : 6;
        return this._decodeAccel(await this._conn.readReg(REG_OUT_X_MSB, n));
    }

    /**
     * Decode three magnetometer axes (16-bit, MSB first).
     *
     * @param {Buffer} data - Raw bytes.
     * @param {number} [offset=0] - Start index.
     * @returns {{x:number, y:number, z:number}} Signed counts.
     */
    _decodeMag(data, offset = 0) {
        const axis = (i) => _toSigned16((data[offset + 2 * i] << 8) | data[offset + 2 * i + 1]);
        return { x: axis(0), y: axis(1), z: axis(2) };
    }

    /**
     * Read the signed magnetometer counts.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Counts.
     */
    async _readMagCounts() {
        return this._decodeMag(await this._conn.readReg(REG_M_OUT_X_MSB, 6));
    }

    /**
     * System output data rate in Hz.
     *
     * @returns {number} ODR.
     */
    _odrHz() {
        return ODR_HZ[this._dr];
    }

    /**
     * STANDBY -> ACTIVE turn-on time, 2 / ODR + 2 ms (rounded up).
     *
     * @returns {number} Milliseconds.
     */
    _turnOnMs() {
        return Math.floor(2000.0 / this._odrHz()) + 3;
    }
}

const _FXOS8700FullMixin = (Base) => class extends Base {
    /**
     * @param {object} connection - Register connection (I²C) bound to 0x1C-0x1F.
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._lastStatus = 0;
    }

    // --- Mode / power ----------------------------------------------------

    /**
     * Enter STANDBY (`active` = 0) and wait until `SYSMOD` reads 0 (up to
     * 300 ms when leaving mag-only / hybrid mode).
     *
     * @returns {Promise<void>}
     */
    async standby() {
        await this._ready;
        const c1 = (await this._conn.readReg(REG_CTRL1, 1))[0];
        await this._conn.writeReg(REG_CTRL1, c1 & ~CTRL1_ACTIVE & 0xFF);
        await this._waitStandby();
    }

    /**
     * Enter ACTIVE (`active` = 1) and wait the turn-on time.
     *
     * @returns {Promise<void>}
     */
    async activate() {
        await this._ready;
        const c1 = (await this._conn.readReg(REG_CTRL1, 1))[0];
        await this._conn.writeReg(REG_CTRL1, c1 | CTRL1_ACTIVE);
        await _delayMs(this._turnOnMs());
    }

    /**
     * Poll `SYSMOD` until it reads 00 (up to 350 ms).
     *
     * @returns {Promise<void>}
     * @throws {Error} If the chip does not reach STANDBY.
     */
    async _waitStandby() {
        for (let i = 0; i < 350; i++) {
            if (((await this._conn.readReg(REG_SYSMOD, 1))[0] & 0x03) === 0) {
                return;
            }
            await _delayMs(1);
        }
        throw new Error('FXOS8700 did not enter STANDBY');
    }

    /**
     * Enter STANDBY if ACTIVE.
     *
     * @returns {Promise<boolean>} Whether the chip was ACTIVE.
     */
    async _begin() {
        await this._ready;
        const c1 = (await this._conn.readReg(REG_CTRL1, 1))[0];
        if (c1 & CTRL1_ACTIVE) {
            await this._conn.writeReg(REG_CTRL1, c1 & ~CTRL1_ACTIVE & 0xFF);
            await this._waitStandby();
            return true;
        }
        return false;
    }

    /**
     * Restore ACTIVE if `wasActive`.
     *
     * @param {boolean} wasActive - Value returned by `_begin()`.
     * @returns {Promise<void>}
     */
    async _end(wasActive) {
        if (wasActive) {
            await this.activate();
        }
    }

    /**
     * Read-modify-write a register: clear `mask`, OR in `value`.
     *
     * @param {number} reg - Register.
     * @param {number} mask - Bits to replace.
     * @param {number} value - New bits.
     * @returns {Promise<void>}
     */
    async _modify(reg, mask, value) {
        const cur = (await this._conn.readReg(reg, 1))[0];
        await this._conn.writeReg(reg, (cur & ~mask & 0xFF) | (value & mask));
    }

    /**
     * Software reset (`CTRL_REG2.rst`). The chip does not acknowledge the
     * write (tolerated) and returns to STANDBY with every register at its
     * reset value. Create a new driver (or call `_runInit()`) afterwards.
     *
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._softReset();
        this._mode = 0;
        this._dr = 0;
        this._fsG = 2;
        this._mods = 0;
        this._lnoise = false;
        this._fRead = false;
        this._fifoMode = 0;
        this._hyb = false;
    }

    /**
     * Re-run the initialization sequence (use after `reset()`).
     *
     * @returns {Promise<void>}
     */
    async reinit() {
        this._ready = this._runInit();
        this._ready.catch(() => {});
        await this._ready;
    }

    /**
     * Select accelerometer-only, magnetometer-only or hybrid operation.
     *
     * @param {number} mode - 0 accelerometer only, 1 magnetometer only, 2 hybrid.
     * @returns {Promise<void>}
     * @throws {Error} If `mode` is not 0, 1 or 2.
     */
    async setMode(mode) {
        if (![0, 1, 2].includes(mode)) {
            throw new Error('mode must be 0, 1 or 2');
        }
        const was = await this._begin();
        await this._modify(REG_M_CTRL1, M_CTRL1_HMS_MASK, [0, 1, 3][mode]);
        if (mode === 2) {
            await this._modify(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC, M_CTRL2_HYB_AUTOINC);
            this._hyb = true;
        }
        this._mode = mode;
        await this._end(was);
    }

    /**
     * Select the accelerometer full-scale range.
     *
     * @param {number} rangeG - 2, 4 or 8 (g).
     * @returns {Promise<void>}
     * @throws {Error} For any other value, or 8 while low-noise mode is on.
     */
    async setRange(rangeG) {
        await this._ready;
        if (![2, 4, 8].includes(rangeG)) {
            throw new Error('rangeG must be 2, 4 or 8');
        }
        if (rangeG === 8 && this._lnoise) {
            throw new Error('±8 g is not available in low-noise mode');
        }
        await this._modify(REG_XYZ_DATA_CFG, 0x03, { 2: 0, 4: 1, 8: 2 }[rangeG]);
        this._fsG = rangeG;
    }

    /**
     * Select the system output data rate (each sensor runs at half this rate
     * in hybrid mode).
     *
     * @param {number} rateHz - 800, 400, 200, 100, 50, 12.5, 6.25 or 1.5625 (1.56 accepted).
     * @returns {Promise<void>}
     * @throws {Error} If the rate is not supported.
     */
    async setDataRate(rateHz) {
        let code = -1;
        ODR_HZ.forEach((hz, i) => {
            if (Math.abs(hz - rateHz) <= 0.01 * hz) {
                code = i;
            }
        });
        if (code < 0) {
            throw new Error('unsupported data rate');
        }
        const was = await this._begin();
        await this._modify(REG_CTRL1, CTRL1_DR_MASK, code << 3);
        this._dr = code;
        await this._end(was);
    }

    /**
     * Select the accelerometer oversampling mode.
     *
     * @param {number} mode - 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power.
     * @param {?number} [sleepMode=null] - Same encoding for SLEEP mode; unchanged if null.
     * @returns {Promise<void>}
     * @throws {Error} If a mode is outside 0-3.
     */
    async setAccelOversampling(mode, sleepMode = null) {
        if (![0, 1, 2, 3].includes(mode) || (sleepMode !== null && ![0, 1, 2, 3].includes(sleepMode))) {
            throw new Error('oversampling mode must be 0-3');
        }
        const was = await this._begin();
        await this._modify(REG_CTRL2, CTRL2_MODS_MASK, mode);
        if (sleepMode !== null) {
            await this._modify(REG_CTRL2, CTRL2_SMODS_MASK, sleepMode << 3);
        }
        this._mods = mode;
        await this._end(was);
    }

    /**
     * Select the magnetometer oversampling ratio code (`m_os`, datasheet
     * Table 203; e.g. at 200 Hz 0-5 give 2x, 6 gives 4x and 7 gives 8x).
     *
     * @param {number} ratio - 0-7 for active mode.
     * @param {?number} [sleepRatio=null] - 0-7 for Auto-SLEEP; unchanged if null.
     * @returns {Promise<void>}
     * @throws {Error} If a value is outside 0-7.
     */
    async setMagOversampling(ratio, sleepRatio = null) {
        await this._ready;
        const ok = (v) => Number.isInteger(v) && v >= 0 && v <= 7;
        if (!ok(ratio) || (sleepRatio !== null && !ok(sleepRatio))) {
            throw new Error('oversampling ratio must be 0-7');
        }
        await this._modify(REG_M_CTRL1, M_CTRL1_OS_MASK, ratio << 2);
        if (sleepRatio !== null) {
            await this._modify(REG_M_CTRL3, 0x70, sleepRatio << 4);
        }
    }

    /**
     * Enable or disable the reduced-noise mode (`lnoise`).
     *
     * @param {boolean} enabled - True to enable; limits the range to ±4 g.
     * @returns {Promise<void>}
     * @throws {Error} If enabling while the range is ±8 g.
     */
    async setLowNoise(enabled) {
        await this._ready;
        if (enabled && this._fsG === 8) {
            throw new Error('low-noise mode is not available at ±8 g');
        }
        const was = await this._begin();
        await this._modify(REG_CTRL1, CTRL1_LNOISE, enabled ? CTRL1_LNOISE : 0);
        this._lnoise = !!enabled;
        await this._end(was);
    }

    /**
     * Select 14-bit or 8-bit (fast read) accelerometer output.
     *
     * @param {number} bits - 14 or 8.
     * @returns {Promise<void>}
     * @throws {Error} If `bits` is not 14 or 8, or the FIFO is on.
     */
    async setResolution(bits) {
        await this._ready;
        if (bits !== 14 && bits !== 8) {
            throw new Error('bits must be 14 or 8');
        }
        if (this._fifoMode !== 0) {
            throw new Error('resolution cannot change while the FIFO is on');
        }
        const was = await this._begin();
        await this._modify(REG_CTRL1, CTRL1_F_READ, bits === 8 ? CTRL1_F_READ : 0);
        this._fRead = bits === 8;
        await this._end(was);
    }

    // --- Data ----------------------------------------------------------------

    /**
     * Read the raw accelerometer counts.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Signed counts (14-bit right-aligned, or 8-bit).
     */
    async readRawAccel() {
        await this._ready;
        return this._readAccelCounts();
    }

    /**
     * Read the raw magnetometer counts.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Signed 16-bit counts (0.1 µT/LSB).
     */
    async readRawMag() {
        await this._ready;
        return this._readMagCounts();
    }

    /**
     * Read accelerometer and magnetometer with one burst from 0x01.
     *
     * @returns {Promise<{accel:{x:number,y:number,z:number}, mag:{x:number,y:number,z:number}}>}
     *     Acceleration in g and magnetic field in µT.
     * @throws {Error} If the chip is not in hybrid mode.
     */
    async readHybrid() {
        await this._ready;
        if (this._mode !== 2 || !this._hyb) {
            throw new Error('readHybrid needs hybrid mode with hyb_autoinc_mode');
        }
        const nAccel = this._fRead ? 3 : 6;
        const data = await this._conn.readReg(REG_OUT_X_MSB, nAccel + 6);
        const acc = this._decodeAccel(data, 0);
        const mag = this._decodeMag(data, nAccel);
        const scale = this._accelScale();
        return {
            accel: { x: acc.x / scale, y: acc.y / scale, z: acc.z / scale },
            mag: { x: mag.x * MAG_UT_PER_LSB, y: mag.y * MAG_UT_PER_LSB, z: mag.z * MAG_UT_PER_LSB },
        };
    }

    /**
     * Read the die temperature (`int8(TEMP) * 0.96`; relative, uncalibrated).
     *
     * @returns {Promise<number>} Temperature in °C.
     * @throws {Error} In accelerometer-only mode.
     */
    async readTemperature() {
        await this._ready;
        if (this._mode === 0) {
            throw new Error('temperature needs the magnetometer to be active');
        }
        return _toSigned8((await this._conn.readReg(REG_TEMP, 1))[0]) * 0.96;
    }

    /**
     * Whether a new accelerometer sample is ready (`STATUS.zyxdr`; not valid
     * while the FIFO is on).
     *
     * @returns {Promise<boolean>} True if new data is available.
     */
    async newAccelData() {
        await this._ready;
        return ((await this._conn.readReg(REG_STATUS, 1))[0] & STATUS_ZYXDR) !== 0;
    }

    /**
     * Whether a new magnetometer sample is ready (`M_DR_STATUS.zyxdr`).
     *
     * @returns {Promise<boolean>} True if new data is available.
     */
    async newMagData() {
        await this._ready;
        return ((await this._conn.readReg(REG_M_DR_STATUS, 1))[0] & STATUS_ZYXDR) !== 0;
    }

    /**
     * Read the `STATUS` byte.
     *
     * @returns {Promise<number>} Status byte.
     */
    async readStatus() {
        await this._ready;
        return (await this._conn.readReg(REG_STATUS, 1))[0];
    }

    /**
     * Read the `M_DR_STATUS` byte.
     *
     * @returns {Promise<number>} Status byte.
     */
    async readMagStatus() {
        await this._ready;
        return (await this._conn.readReg(REG_M_DR_STATUS, 1))[0];
    }

    /**
     * Read `SYSMOD` bits 1:0.
     *
     * @returns {Promise<number>} 0 standby, 1 wake, 2 sleep.
     */
    async readSystemMode() {
        await this._ready;
        return (await this._conn.readReg(REG_SYSMOD, 1))[0] & 0x03;
    }

    /**
     * Select high-pass-filtered output data. The cutoff in Hz depends on ODR
     * and oversampling mode (datasheet Table 64; effective ODR halved in
     * hybrid mode).
     *
     * @param {boolean} enabled - True to output high-pass-filtered data (`hpf_out`).
     * @param {number} [cutoff=0] - `sel` code 0-3.
     * @returns {Promise<void>}
     * @throws {Error} If `cutoff` is outside 0-3.
     */
    async setHighPassOutput(enabled, cutoff = 0) {
        await this._ready;
        if (![0, 1, 2, 3].includes(cutoff)) {
            throw new Error('cutoff must be 0-3');
        }
        const was = await this._begin();
        await this._modify(REG_XYZ_DATA_CFG, 0x10, enabled ? 0x10 : 0);
        await this._modify(REG_HP_FILTER_CUTOFF, 0x03, cutoff);
        await this._end(was);
    }

    /**
     * Write the per-axis accelerometer offset registers (added to the
     * measurement; 2 mg/LSB, ±0.256 g).
     *
     * @param {number} xG - X offset in g.
     * @param {number} yG - Y offset in g.
     * @param {number} zG - Z offset in g.
     * @returns {Promise<void>}
     */
    async setAccelOffset(xG, yG, zG) {
        const data = Buffer.from([xG, yG, zG].map((v) => _clamp(Math.round(v / 0.002), -128, 127) & 0xFF));
        const was = await this._begin();
        await this._conn.writeReg(REG_OFF_X, data);
        await this._end(was);
    }

    /**
     * Read the accelerometer offsets.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Offsets in g.
     */
    async getAccelOffset() {
        await this._ready;
        const d = await this._conn.readReg(REG_OFF_X, 3);
        return { x: _toSigned8(d[0]) * 0.002, y: _toSigned8(d[1]) * 0.002, z: _toSigned8(d[2]) * 0.002 };
    }

    /**
     * Write the hard-iron offset registers (15-bit value in bits 15:1,
     * subtracted from each measurement, 0.1 µT/LSB).
     *
     * @param {number} xUt - X offset to subtract, in µT.
     * @param {number} yUt - Y offset to subtract, in µT.
     * @param {number} zUt - Z offset to subtract, in µT.
     * @returns {Promise<void>}
     */
    async setMagOffset(xUt, yUt, zUt) {
        await this._ready;
        const out = [];
        for (const v of [xUt, yUt, zUt]) {
            const word = (_clamp(Math.round(v / 0.1), -16384, 16383) << 1) & 0xFFFF;
            out.push(word >> 8, word & 0xFF);
        }
        await this._conn.writeReg(REG_M_OFF_X_MSB, Buffer.from(out));
    }

    /**
     * Read the hard-iron offsets.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Offsets in µT.
     */
    async getMagOffset() {
        await this._ready;
        const d = await this._conn.readReg(REG_M_OFF_X_MSB, 6);
        const axis = (i) => (_toSigned16((d[2 * i] << 8) | d[2 * i + 1]) >> 1) * 0.1;
        return { x: axis(0), y: axis(1), z: axis(2) };
    }

    /**
     * Select raw magnetometer output (`m_raw`).
     *
     * @param {boolean} raw - True = hard-iron offsets are not applied to the output.
     * @returns {Promise<void>}
     */
    async setMagRaw(raw) {
        await this._ready;
        await this._modify(REG_M_CTRL3, 0x80, raw ? 0x80 : 0);
    }

    /**
     * Enable or disable hardware hard-iron auto-calibration (`m_acal`).
     * Enabling also clears `m_raw` and enables min/max tracking. Rotate the
     * board through all orientations while on; the offsets stay in `M_OFF_*`
     * afterwards (NXP AN4399).
     *
     * @param {boolean} enabled - True to enable.
     * @returns {Promise<void>}
     */
    async setMagAutoCalibration(enabled) {
        await this._ready;
        if (enabled) {
            await this._modify(REG_M_CTRL3, 0x80, 0);
            const was = await this._begin();
            await this._modify(REG_M_CTRL2, 0x10, 0);
            await this._end(was);
        }
        await this._modify(REG_M_CTRL1, M_CTRL1_ACAL, enabled ? M_CTRL1_ACAL : 0);
    }

    /**
     * Read the tracked magnetic minimum and maximum.
     *
     * @returns {Promise<{min:{x:number,y:number,z:number}, max:{x:number,y:number,z:number}}>} Values in µT.
     */
    async readMagMinMax() {
        await this._ready;
        const mn = this._decodeMag(await this._conn.readReg(REG_MIN_X_MSB, 6));
        const mx = this._decodeMag(await this._conn.readReg(REG_MAX_X_MSB, 6));
        const k = MAG_UT_PER_LSB;
        return {
            min: { x: mn.x * k, y: mn.y * k, z: mn.z * k },
            max: { x: mx.x * k, y: mx.y * k, z: mx.z * k },
        };
    }

    /**
     * Reset the min/max registers to 0x7FFF / 0x8000 (`m_maxmin_rst`).
     *
     * @returns {Promise<void>}
     */
    async resetMagMinMax() {
        await this._ready;
        await this._modify(REG_M_CTRL2, 0x04, 0x04);
    }

    /**
     * Select the automatic magnetic reset (degauss) period.
     *
     * @param {number} mode - 0 every ODR cycle, 1 every 16, 2 every 512, 3 disabled.
     * @returns {Promise<void>}
     * @throws {Error} If `mode` is outside 0-3.
     */
    async setMagReset(mode) {
        await this._ready;
        if (![0, 1, 2, 3].includes(mode)) {
            throw new Error('mode must be 0-3');
        }
        const was = await this._begin();
        await this._modify(REG_M_CTRL2, 0x03, mode);
        await this._end(was);
    }

    /**
     * Trigger a one-shot magnetic reset (`m_rst`).
     *
     * @returns {Promise<void>}
     */
    async triggerMagReset() {
        await this._ready;
        await this._modify(REG_M_CTRL1, M_CTRL1_RST, M_CTRL1_RST);
    }

    /**
     * Take one magnetic measurement in STANDBY (`m_ost`); ACTIVE is restored.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Field in µT.
     * @throws {Error} In accelerometer-only mode or if the measurement times out (1 s).
     */
    async measureMagOnce() {
        await this._ready;
        if (this._mode === 0) {
            throw new Error('one-shot measurement needs the magnetometer');
        }
        const was = await this._begin();
        await this._modify(REG_M_CTRL1, M_CTRL1_OST, M_CTRL1_OST);
        let done = false;
        for (let i = 0; i < 1000; i++) {
            if (((await this._conn.readReg(REG_M_CTRL1, 1))[0] & M_CTRL1_OST) === 0) {
                done = true;
                break;
            }
            await _delayMs(1);
        }
        if (!done) {
            await this._end(was);
            throw new Error('magnetic one-shot measurement timed out');
        }
        const value = await this.readMag();
        await this._end(was);
        return value;
    }

    /**
     * Compute the tilt-compensated compass heading from one accelerometer and
     * one magnetometer sample (chip frame: X forward, Y right, Z down; verify
     * the axis signs on hardware).
     *
     * @param {number} [declinationDeg=0] - Magnetic declination added to the result.
     * @returns {Promise<number>} Heading in degrees, [0, 360).
     */
    async heading(declinationDeg = 0.0) {
        await this._ready;
        let a;
        let m;
        if (this._mode === 2 && this._hyb) {
            ({ accel: a, mag: m } = await this.readHybrid());
        } else {
            a = await this.readAccel();
            m = await this.readMag();
        }
        const roll = Math.atan2(a.y, a.z);
        const pitch = Math.atan2(-a.x, a.y * Math.sin(roll) + a.z * Math.cos(roll));
        const mx2 = m.x * Math.cos(pitch) + m.z * Math.sin(pitch);
        const my2 = m.x * Math.sin(roll) * Math.sin(pitch) + m.y * Math.cos(roll)
            - m.z * Math.sin(roll) * Math.cos(pitch);
        const deg = Math.atan2(-my2, mx2) * 180 / Math.PI + declinationDeg;
        return ((deg % 360) + 360) % 360;
    }

    // --- FIFO ----------------------------------------------------------------

    /**
     * Configure the 32-sample accelerometer FIFO. Changing between two enabled
     * modes goes through disabled. The FIFO holds accelerometer data only.
     *
     * @param {number} mode - 0 disabled, 1 circular, 2 fill, 3 trigger.
     * @param {number} [watermark=0] - 0-63 (0 disables the flag).
     * @returns {Promise<void>}
     * @throws {Error} If an argument is out of range.
     */
    async setFifo(mode, watermark = 0) {
        await this._ready;
        if (![0, 1, 2, 3].includes(mode) || !Number.isInteger(watermark) || watermark < 0 || watermark > 63) {
            throw new Error('invalid FIFO mode or watermark');
        }
        const was = await this._begin();
        if (this._fifoMode !== 0 && mode !== 0 && mode !== this._fifoMode) {
            await this._conn.writeReg(REG_F_SETUP, 0x00);
        }
        await this._conn.writeReg(REG_F_SETUP, (mode << 6) | watermark);
        this._fifoMode = mode;
        await this._end(was);
    }

    /**
     * Select the FIFO trigger sources (`TRIG_CFG`).
     *
     * @param {{transient?:boolean, orientation?:boolean, pulse?:boolean, motion?:boolean}} [sources] - Source enables.
     * @returns {Promise<void>}
     */
    async setFifoTrigger({ transient = false, orientation = false, pulse = false, motion = false } = {}) {
        const value = (transient ? 0x20 : 0) | (orientation ? 0x10 : 0) | (pulse ? 0x08 : 0) | (motion ? 0x04 : 0);
        const was = await this._begin();
        await this._conn.writeReg(REG_TRIG_CFG, value);
        await this._end(was);
    }

    /**
     * Number of samples in the FIFO (0-32).
     *
     * @returns {Promise<number>} Sample count.
     */
    async fifoLevel() {
        await this._ready;
        return (await this._conn.readReg(REG_STATUS, 1))[0] & 0x3F;
    }

    /**
     * Decode `F_STATUS` (clears `SOURCE_FIFO`).
     *
     * @returns {Promise<{overflow:boolean, watermark:boolean, count:number}>} Decoded status.
     */
    async readFifoStatus() {
        await this._ready;
        const v = (await this._conn.readReg(REG_STATUS, 1))[0];
        return { overflow: (v & 0x80) !== 0, watermark: (v & 0x40) !== 0, count: v & 0x3F };
    }

    /**
     * Drain accelerometer samples from the FIFO. Each sample is read with its
     * own 6-byte (3 in 8-bit mode) transaction from 0x01, so the hybrid
     * auto-increment jump to the magnetometer never matters.
     *
     * @param {?number} [count=null] - Maximum number of samples; default all.
     * @returns {Promise<Array<{x:number, y:number, z:number}>>} Samples in g.
     */
    async readFifo(count = null) {
        let n = await this.fifoLevel();
        if (count !== null && count < n) {
            n = count;
        }
        const scale = this._accelScale();
        const out = [];
        for (let i = 0; i < n; i++) {
            const c = await this._readAccelCounts();
            out.push({ x: c.x / scale, y: c.y / scale, z: c.z / scale });
        }
        return out;
    }

    /**
     * Enable or disable the FIFO gate (`fifo_gate`).
     *
     * @param {boolean} enabled - True to block FIFO input around WAKE/SLEEP transitions.
     * @returns {Promise<void>}
     */
    async setFifoGate(enabled) {
        const was = await this._begin();
        await this._modify(REG_CTRL3, 0x80, enabled ? 0x80 : 0);
        await this._end(was);
    }

    // --- Detectors -----------------------------------------------------------

    /**
     * Debounce time step in ms at the effective ODR and oversampling.
     *
     * @returns {number} Step in ms.
     */
    _stepMs() {
        const row = Math.min(this._dr + (this._mode === 2 ? 1 : 0), 7);
        return STEP_MS[this._mods][row];
    }

    /**
     * PULSE_TMLT time step in ms.
     *
     * @param {boolean} lpf - Pulse low-pass filter enabled.
     * @returns {number} Step in ms.
     */
    _pulseStepMs(lpf) {
        const row = Math.min(this._dr + (this._mode === 2 ? 1 : 0), 7);
        return lpf ? STEP_MS[this._mods][row] : PULSE_STEP_NO_LPF_MS[this._mods][row];
    }

    /**
     * Encode a 7-bit threshold (0.063 g/LSB).
     *
     * @param {number} thresholdG - Threshold in g.
     * @returns {number} Register value.
     */
    _threshold(thresholdG) {
        return _clamp(Math.round(thresholdG / 0.063), 0, 127);
    }

    /**
     * Configure the freefall / motion detector (`A_FFMT_*`).
     *
     * @param {boolean} motion - True = motion (any enabled axis above), false = freefall (all below).
     * @param {{axes?:number, thresholdG?:number, debounceMs?:number, latch?:boolean, debounceClear?:boolean}} [opts]
     *     axes: mask X=1, Y=2, Z=4 (default 7); thresholdG: 0.063 g/LSB (default 0.3);
     *     debounceMs; latch until `A_FFMT_SRC` is read; debounceClear clears the counter when the condition is lost.
     * @returns {Promise<void>}
     */
    async setFreefallMotion(motion, { axes = 7, thresholdG = 0.3, debounceMs = 0, latch = false, debounceClear = false } = {}) {
        const cfg = (latch ? 0x80 : 0) | (motion ? 0x40 : 0) | ((axes & 7) << 3);
        const ths = (debounceClear ? 0x80 : 0) | this._threshold(thresholdG);
        const count = _clamp(Math.round(debounceMs / this._stepMs()), 0, 255);
        const was = await this._begin();
        await this._conn.writeReg(REG_A_FFMT_CFG, cfg);
        await this._conn.writeReg(REG_A_FFMT_THS, ths);
        await this._conn.writeReg(REG_A_FFMT_COUNT, count);
        await this._end(was);
    }

    /**
     * Read `A_FFMT_SRC` (clears the flags when latched).
     *
     * @returns {Promise<number>} Source byte.
     */
    async readMotionSource() {
        await this._ready;
        return (await this._conn.readReg(REG_A_FFMT_SRC, 1))[0];
    }

    /**
     * Configure the transient detector (`TRANSIENT_*`).
     *
     * @param {{axes?:number, thresholdG?:number, debounceMs?:number, bypassHpf?:boolean, latch?:boolean}} [opts]
     *     axes: mask X=1, Y=2, Z=4 (default 7); thresholdG (default 0.3); debounceMs;
     *     bypassHpf: motion-like behaviour; latch until `TRANSIENT_SRC` is read.
     * @returns {Promise<void>}
     */
    async setTransient({ axes = 7, thresholdG = 0.3, debounceMs = 0, bypassHpf = false, latch = false } = {}) {
        const cfg = (latch ? 0x10 : 0) | ((axes & 7) << 1) | (bypassHpf ? 1 : 0);
        const count = _clamp(Math.round(debounceMs / this._stepMs()), 0, 255);
        const was = await this._begin();
        await this._conn.writeReg(REG_TRANSIENT_CFG, cfg);
        await this._conn.writeReg(REG_TRANSIENT_THS, this._threshold(thresholdG));
        await this._conn.writeReg(REG_TRANSIENT_COUNT, count);
        await this._end(was);
    }

    /**
     * Read `TRANSIENT_SRC` — **clears** the interrupt.
     *
     * @returns {Promise<number>} Source byte.
     */
    async readTransientSource() {
        await this._ready;
        return (await this._conn.readReg(REG_TRANSIENT_SRC, 1))[0];
    }

    /**
     * Configure tap (pulse) detection (`PULSE_*`).
     *
     * @param {{single?:number, double?:number, thresholdG?:(number|number[]), timeLimitMs?:number,
     *     latencyMs?:number, windowMs?:number, latch?:boolean, doubleAbort?:boolean, lpf?:boolean,
     *     hpf?:boolean}} [opts] single/double: axis masks (X=1, Y=2, Z=4); thresholdG: [x, y, z]
     *     or one number (default 0.5); timeLimitMs 30; latencyMs 100; windowMs 300; latch until
     *     `PULSE_SRC` is read; doubleAbort; lpf / hpf: pulse filters (default on).
     * @returns {Promise<void>}
     */
    async setPulse({ single = 0, double = 0, thresholdG = [0.5, 0.5, 0.5], timeLimitMs = 30, latencyMs = 100,
        windowMs = 300, latch = false, doubleAbort = false, lpf = true, hpf = true } = {}) {
        const ths = typeof thresholdG === 'number' ? [thresholdG, thresholdG, thresholdG] : thresholdG;
        let cfg = (doubleAbort ? 0x80 : 0) | (latch ? 0x40 : 0);
        for (let i = 0; i < 3; i++) {
            if ((single >> i) & 1) {
                cfg |= 1 << (2 * i);
            }
            if ((double >> i) & 1) {
                cfg |= 2 << (2 * i);
            }
        }
        const step = this._pulseStepMs(lpf);
        const tmlt = _clamp(Math.round(timeLimitMs / step), 0, 255);
        const ltcy = _clamp(Math.round(latencyMs / (2 * step)), 0, 255);
        const wind = _clamp(Math.round(windowMs / (2 * step)), 0, 255);
        const was = await this._begin();
        await this._conn.writeReg(REG_PULSE_CFG, cfg);
        await this._conn.writeReg(REG_PULSE_THSX, Buffer.from(ths.map((t) => this._threshold(t))));
        await this._conn.writeReg(REG_PULSE_TMLT, tmlt);
        await this._conn.writeReg(REG_PULSE_LTCY, ltcy);
        await this._conn.writeReg(REG_PULSE_WIND, wind);
        await this._modify(REG_HP_FILTER_CUTOFF, 0x30, (hpf ? 0 : 0x20) | (lpf ? 0x10 : 0));
        await this._end(was);
    }

    /**
     * Read `PULSE_SRC` — **clears** the interrupt.
     *
     * @returns {Promise<number>} Source byte.
     */
    async readPulseSource() {
        await this._ready;
        return (await this._conn.readReg(REG_PULSE_SRC, 1))[0];
    }

    /**
     * Configure orientation (portrait / landscape) detection.
     *
     * @param {{enabled?:boolean, debounceMs?:number, thresholdDeg?:number, hysteresis?:number,
     *     zLock?:number, backFront?:number}} [opts] thresholdDeg: 15, 20, 30, 35, 40, 45 (default),
     *     55, 60, 70 or 75; hysteresis `hys` code 0-7 (default 4); zLock code 0-7 (default 4);
     *     backFront code 0-3 (default 2).
     * @returns {Promise<void>}
     * @throws {Error} For an invalid argument, or if `pl_ths + hys` is outside 1-31.
     */
    async setOrientation({ enabled = true, debounceMs = 0, thresholdDeg = 45, hysteresis = 4, zLock = 4,
        backFront = 2 } = {}) {
        if (!(thresholdDeg in PL_THRESHOLDS)) {
            throw new Error('unsupported thresholdDeg');
        }
        if (!(hysteresis >= 0 && hysteresis <= 7) || !(zLock >= 0 && zLock <= 7)
            || !(backFront >= 0 && backFront <= 3)) {
            throw new Error('hysteresis/zLock/backFront out of range');
        }
        const ths = PL_THRESHOLDS[thresholdDeg];
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw new Error('pl_ths + hys must be within 1-31');
        }
        const count = _clamp(Math.round(debounceMs / this._stepMs()), 0, 255);
        const was = await this._begin();
        await this._conn.writeReg(REG_PL_CFG, 0x80 | (enabled ? 0x40 : 0));
        await this._conn.writeReg(REG_PL_COUNT, count);
        await this._conn.writeReg(REG_PL_THS, (ths << 3) | hysteresis);
        await this._conn.writeReg(REG_PL_BF_ZCOMP, (backFront << 6) | zLock);
        await this._end(was);
    }

    /**
     * Decode `PL_STATUS` — **clears** `newlp`.
     *
     * @returns {Promise<{changed:boolean, lockout:boolean, portraitLandscape:number, back:boolean}>}
     *     portraitLandscape: 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left.
     */
    async readOrientation() {
        await this._ready;
        const v = (await this._conn.readReg(REG_PL_STATUS, 1))[0];
        return {
            changed: (v & 0x80) !== 0,
            lockout: (v & 0x40) !== 0,
            portraitLandscape: (v >> 1) & 0x03,
            back: (v & 0x01) !== 0,
        };
    }

    /**
     * Configure Auto-SLEEP / WAKE.
     *
     * @param {boolean} enabled - Enable Auto-SLEEP (`slpe`).
     * @param {{sleepRateHz?:number, timeoutS?:number, wakeSources?:number}} [opts] sleepRateHz: 50
     *     (default), 12.5, 6.25 or 1.56 (halved in hybrid mode); timeoutS: inactivity time (default 10);
     *     wakeSources: mask of `SOURCE_TRANSIENT`, `SOURCE_ORIENTATION`, `SOURCE_PULSE`, `SOURCE_FF_MT`
     *     and `SOURCE_ACCEL_VECM`.
     * @returns {Promise<void>}
     * @throws {Error} For an unsupported rate or source.
     */
    async setAutoSleep(enabled, { sleepRateHz = 50, timeoutS = 10, wakeSources = 0 } = {}) {
        let code = -1;
        for (const [hz, c] of ASLP_RATES) {
            if (Math.abs(hz - sleepRateHz) <= 0.01 * hz) {
                code = c;
            }
        }
        if (code < 0) {
            throw new Error('unsupported sleep rate');
        }
        if (wakeSources & ~0x3E) {
            throw new Error('unsupported wake source');
        }
        const step = this._dr === 7 ? 0.64 : 0.32;
        const count = _clamp(Math.round(timeoutS / step), 0, 255);
        const was = await this._begin();
        await this._modify(REG_CTRL1, CTRL1_ASLP_MASK, code << 6);
        await this._conn.writeReg(REG_ASLP_COUNT, count);
        await this._modify(REG_CTRL2, CTRL2_SLPE, enabled ? CTRL2_SLPE : 0);
        await this._modify(REG_CTRL3, 0x7C, (wakeSources & 0x3E) << 1);
        await this._end(was);
    }

    // --- Interrupts ----------------------------------------------------------

    /**
     * Configure INT1 / INT2 polarity and drive (`ipol`, `pp_od`).
     *
     * @param {boolean} [activeHigh=false] - True for active-high.
     * @param {boolean} [openDrain=false] - True for open-drain (needs an external pull-up).
     * @returns {Promise<void>}
     */
    async setInterruptPins(activeHigh = false, openDrain = false) {
        const was = await this._begin();
        await this._modify(REG_CTRL3, 0x03, (activeHigh ? 0x02 : 0) | (openDrain ? 0x01 : 0));
        await this._end(was);
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2. Only routes the
     * source; the detector behind it is configured with the `set*` methods.
     *
     * @param {number} source - A `FXOS8700Source.SOURCE_*` mask.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     * @throws {Error} If `pin` is not 1 or 2.
     */
    async enableInterrupt(source, pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        const was = await this._begin();
        await this._modify(REG_CTRL4, source, source);
        await this._modify(REG_CTRL5, source, pin === 1 ? source : 0);
        await this._end(was);
    }

    /**
     * Disable an interrupt source (clears its `int_en_*` bit).
     *
     * @param {number} source - A `FXOS8700Source.SOURCE_*` mask.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        const was = await this._begin();
        await this._modify(REG_CTRL4, source, 0);
        await this._end(was);
    }

    /**
     * Read `INT_SOURCE` and clear the pending sources. For every set bit
     * except `SOURCE_DATA_READY` and `SOURCE_ACCEL_VECM` the matching source
     * register is read so the flag (and the pin) clears.
     *
     * @returns {Promise<number>} A mask of `FXOS8700Source.SOURCE_*` constants.
     */
    async pollInterrupt() {
        await this._ready;
        const status = (await this._conn.readReg(REG_INT_SOURCE, 1))[0];
        const clear = [
            [FXOS8700Source.SOURCE_AUTO_SLEEP, REG_SYSMOD],
            [FXOS8700Source.SOURCE_FIFO, REG_STATUS],
            [FXOS8700Source.SOURCE_TRANSIENT, REG_TRANSIENT_SRC],
            [FXOS8700Source.SOURCE_ORIENTATION, REG_PL_STATUS],
            [FXOS8700Source.SOURCE_PULSE, REG_PULSE_SRC],
            [FXOS8700Source.SOURCE_FF_MT, REG_A_FFMT_SRC],
        ];
        for (const [bit, reg] of clear) {
            if (status & bit) {
                await this._conn.readReg(reg, 1);
            }
        }
        return status;
    }

    /**
     * Subscribe to the INT1 or INT2 line. The edge follows the configured
     * polarity. Without an INT pin on the connection a 5 ms polling timer
     * takes over.
     *
     * @param {function(number): void} callback - Receives the `pollInterrupt()` mask.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     * @throws {Error} If the pin is not 1 or 2.
     */
    async onInterrupt(callback, pin = 1) {
        await this._ready;
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        this._callback = callback;
        const intPin = this._conn.intPin || null;
        this._intPinUsed = intPin;
        if (intPin) {
            const ctrl3 = await this._conn.readReg(REG_CTRL3, 1);
            const activeHigh = (ctrl3[0] & 0x02) !== 0;
            this._intHandler = this._intHandler.bind(this);
            intPin.onEdge(this._intHandler, activeHigh ? 'rising' : 'falling');
        } else {
            this._lastStatus = await this.pollInterrupt();
            this._pollTimer = setInterval(() => {
                this._pollLoop();
            }, 5);
        }
    }

    /**
     * Unsubscribe from the INT1 or INT2 line.
     *
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     */
    async offInterrupt(pin = 1) {
        if (this._intPinUsed) {
            this._intPinUsed.offEdge(this._intHandler);
            this._intPinUsed = null;
        } else if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }

    /**
     * Handle an INT edge: read the status and dispatch it.
     *
     * @returns {Promise<void>}
     */
    async _intHandler() {
        const status = await this.pollInterrupt();
        if (this._callback) {
            this._callback(status);
        }
    }

    /**
     * Poll the interrupt status and dispatch on change.
     *
     * @returns {Promise<void>}
     */
    async _pollLoop() {
        const status = await this.pollInterrupt();
        if (status !== this._lastStatus) {
            this._lastStatus = status;
            if (this._callback) {
                this._callback(status);
            }
        }
    }

    // --- Escape hatch / self-test ------------------------------------------

    /**
     * Read raw register bytes (vector-magnitude, magnetic threshold and
     * per-axis FFMT registers). No STANDBY handling.
     *
     * @param {number} reg - Start register address.
     * @param {number} [length=1] - Number of bytes.
     * @returns {Promise<Buffer>} The register contents.
     */
    async readRegister(reg, length = 1) {
        await this._ready;
        return this._conn.readReg(reg, length);
    }

    /**
     * Write raw register bytes. No STANDBY handling.
     *
     * @param {number} reg - Start register address.
     * @param {(Buffer|number[]|number)} data - Bytes to write.
     * @returns {Promise<void>}
     */
    async writeRegister(reg, data) {
        await this._ready;
        await this._conn.writeReg(reg, data);
    }

    /**
     * Average 8 accelerometer readings (accel-only, 14-bit).
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Mean counts.
     */
    async _averageCounts() {
        const samples = 8;
        let sx = 0;
        let sy = 0;
        let sz = 0;
        for (let i = 0; i < samples; i++) {
            await _delayMs(12);
            const c = this._decodeAccel(await this._conn.readReg(REG_OUT_X_MSB, 6));
            sx += c.x;
            sy += c.y;
            sz += c.z;
        }
        return { x: Math.floor(sx / samples), y: Math.floor(sy / samples), z: Math.floor(sz / samples) };
    }

    /**
     * Run the accelerometer self-test and restore the configuration. STANDBY,
     * ±2 g, 100 Hz accelerometer-only, normal oversampling; the difference of
     * two 8-sample averages (before / after `st`) is checked against the
     * datasheet minimum X >= +192, Y >= +270, Z >= +1275 counts.
     *
     * @returns {Promise<{ok:boolean, dx:number, dy:number, dz:number}>} Differences in 14-bit counts.
     */
    async selfTest() {
        await this._ready;
        const regs = [REG_CTRL1, REG_CTRL2, REG_XYZ_DATA_CFG, REG_M_CTRL1];
        const saved = {};
        for (const r of regs) {
            saved[r] = (await this._conn.readReg(r, 1))[0];
        }
        const cache = [this._mode, this._dr, this._fsG, this._mods, this._lnoise, this._fRead];
        await this.standby();
        await this._conn.writeReg(REG_XYZ_DATA_CFG, 0x00);
        await this._conn.writeReg(REG_M_CTRL1, saved[REG_M_CTRL1] & ~M_CTRL1_HMS_MASK & 0xFF);
        await this._conn.writeReg(REG_CTRL2, 0x00);
        this._mode = 0;
        this._dr = 3;
        this._fsG = 2;
        this._mods = 0;
        this._lnoise = false;
        this._fRead = false;
        await this._conn.writeReg(REG_CTRL1, (3 << 3) | CTRL1_ACTIVE);
        await _delayMs(this._turnOnMs());
        const before = await this._averageCounts();
        await this._conn.writeReg(REG_CTRL2, CTRL2_ST);
        await _delayMs(25);
        const after = await this._averageCounts();
        await this._conn.writeReg(REG_CTRL2, 0x00);
        await this.standby();
        await this._conn.writeReg(REG_XYZ_DATA_CFG, saved[REG_XYZ_DATA_CFG]);
        await this._conn.writeReg(REG_M_CTRL1, saved[REG_M_CTRL1]);
        await this._conn.writeReg(REG_CTRL2, saved[REG_CTRL2]);
        [this._mode, this._dr, this._fsG, this._mods, this._lnoise, this._fRead] = cache;
        await this._conn.writeReg(REG_CTRL1, saved[REG_CTRL1]);
        if (saved[REG_CTRL1] & CTRL1_ACTIVE) {
            await _delayMs(this._turnOnMs());
        }
        const dx = after.x - before.x;
        const dy = after.y - before.y;
        const dz = after.z - before.z;
        return { ok: dx >= 192 && dy >= 270 && dz >= 1275, dx, dy, dz };
    }
};

/**
 * FXOS8700 full interface — the complete chip functionality.
 *
 * Methods that change a STANDBY-only register put the chip into STANDBY,
 * write, and restore ACTIVE internally, so the caller never manages the mode.
 * Every such change flushes the FIFO and resets the detectors' debounce
 * counters.
 */
class FXOS8700Full extends _FXOS8700FullMixin(FXOS8700Minimal) {}

FXOS8700Full.MODE_ACCEL_ONLY = 0;
FXOS8700Full.MODE_MAG_ONLY = 1;
FXOS8700Full.MODE_HYBRID = 2;
FXOS8700Full.FIFO_DISABLED = 0;
FXOS8700Full.FIFO_CIRCULAR = 1;
FXOS8700Full.FIFO_FILL = 2;
FXOS8700Full.FIFO_TRIGGER = 3;
FXOS8700Full.I2C_ADDRESS = I2C_ADDRESS;
FXOS8700Minimal.I2C_ADDRESS = I2C_ADDRESS;

// The interrupt source constants are also exposed directly on the class.
Object.assign(FXOS8700Full, FXOS8700Source);

module.exports = {
    FXOS8700Minimal,
    FXOS8700Full,
    FXOS8700Source,
    I2C_ADDRESS,
    I2C_ADDRESSES,
};
