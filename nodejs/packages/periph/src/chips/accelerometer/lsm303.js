'use strict';

/**
 * LSM303 3-axis accelerometer + magnetometer e-compass driver (STMicroelectronics).
 *
 * The LSM303DLHC combines a three-axis MEMS accelerometer and a three-axis
 * magnetometer in one package. The two dies sit behind **two independent I²C
 * slave addresses** on the same bus: the accelerometer at 0x19 and the
 * magnetometer at 0x1E. The driver therefore takes two connections.
 *
 * Accelerometer (0x19)
 *   Four full-scale ranges (±2 / ±4 / ±8 / ±16 g), 12-bit output at 1 mg/LSB
 *   in the default high-resolution mode, output data rates from 1 Hz to
 *   5.376 kHz, a 32-level FIFO, a selectable high-pass filter, two
 *   programmable inertial interrupt generators (threshold, free-fall, 6D/4D
 *   orientation) and single/double click detection.
 *
 * Magnetometer (0x1E)
 *   Seven full-scale ranges (±130 µT to ±810 µT), 12-bit output, output data
 *   rates from 0.75 Hz to 220 Hz and a relative-only temperature sensor.
 *
 * The compass heading is computed in software from both sensors.
 *
 * I²C framing differs between the two slaves:
 *
 * - **Accelerometer** — after the slave address the master sends an 8-bit
 *   sub-address: the 7 LSbs are the register address and the **MSb enables
 *   address auto-increment**. A single-register access uses `reg`; a
 *   multi-byte access uses `reg | 0x80`.
 * - **Magnetometer** — an address pointer without an auto-increment bit. The
 *   pointer always advances by one; after register 0x08 it rolls back to 0x03
 *   and from 0x0C upwards it wraps to 0x00. A 6-byte read from 0x03 therefore
 *   returns all six output registers in one transaction.
 *
 * The magnetometer output order is **X, Z, Y** (not X, Y, Z) and the byte order
 * is **high byte first** — the opposite of the accelerometer.
 */

// Fixed I²C slave addresses of the two dies.
const I2C_ADDRESS_ACCEL = 0x19;
const I2C_ADDRESS_MAG = 0x1E;

// Magnetometer identity (IRA_REG_M, IRB_REG_M, IRC_REG_M) — ASCII "H43".
const MAG_IDENTITY = Buffer.from([0x48, 0x34, 0x33]);

// --- Accelerometer registers -----------------------------------------------
const REG_CTRL1_A = 0x20;
const REG_CTRL2_A = 0x21;
const REG_CTRL3_A = 0x22;
const REG_CTRL4_A = 0x23;
const REG_CTRL5_A = 0x24;
const REG_CTRL6_A = 0x25;
const REG_REFERENCE_A = 0x26;
const REG_STATUS_A = 0x27;
const REG_OUT_X_L_A = 0x28;
const REG_FIFO_CTRL_A = 0x2E;
const REG_FIFO_SRC_A = 0x2F;
const REG_INT1_CFG_A = 0x30;
const REG_INT1_SOURCE_A = 0x31;
const REG_INT1_THS_A = 0x32;
const REG_INT1_DURATION_A = 0x33;
const REG_INT2_CFG_A = 0x34;
const REG_INT2_SOURCE_A = 0x35;
const REG_INT2_THS_A = 0x36;
const REG_INT2_DURATION_A = 0x37;
const REG_CLICK_CFG_A = 0x38;
const REG_CLICK_SRC_A = 0x39;
const REG_CLICK_THS_A = 0x3A;
const REG_TIME_LIMIT_A = 0x3B;
const REG_TIME_LATENCY_A = 0x3C;
const REG_TIME_WINDOW_A = 0x3D;

// --- Magnetometer registers -------------------------------------------------
const REG_CRA_M = 0x00;
const REG_CRB_M = 0x01;
const REG_MR_M = 0x02;
const REG_OUT_X_H_M = 0x03;
const REG_SR_M = 0x09;
const REG_IRA_M = 0x0A;
const REG_TEMP_OUT_H_M = 0x31;

// Bit 7 of the accelerometer sub-address enables address auto-increment.
const ACCEL_AUTO_INCREMENT = 0x80;

// Accelerometer operating modes.
const MODE_LOW_POWER = 0;
const MODE_NORMAL = 1;
const MODE_HIGH_RESOLUTION = 2;

// FIFO modes (FIFO_CTRL_REG_A.FM<1:0>).
const FIFO_BYPASS = 0;
const FIFO_MODE = 1;
const FIFO_STREAM = 2;
const FIFO_TRIGGER = 3;

// Magnetometer operating modes (MR_REG_M.MD<1:0>).
const MAG_MODE_CONTINUOUS = 0;
const MAG_MODE_SINGLE = 1;
const MAG_MODE_SLEEP = 2;

// Click axis masks.
const CLICK_AXIS_X = 0x01;
const CLICK_AXIS_Y = 0x02;
const CLICK_AXIS_Z = 0x04;

// CTRL_REG1_A bits.
const CTRL1_ODR_MASK = 0xF0;
const CTRL1_LPEN = 0x08;
const CTRL1_AXES_MASK = 0x07;

// CTRL_REG2_A bits.
const CTRL2_HPM_MASK = 0xC0;
const CTRL2_HPCF_MASK = 0x30;
const CTRL2_FDS = 0x08;
const CTRL2_HPCLICK = 0x04;
const CTRL2_HPIS2 = 0x02;
const CTRL2_HPIS1 = 0x01;

// CTRL_REG3_A bits (routing to INT1).
const CTRL3_I1_CLICK = 0x80;
const CTRL3_I1_AOI1 = 0x40;
const CTRL3_I1_AOI2 = 0x20;
const CTRL3_I1_DRDY1 = 0x10;
const CTRL3_I1_WTM = 0x04;
const CTRL3_I1_OVERRUN = 0x02;

// CTRL_REG4_A bits.
const CTRL4_BDU = 0x80;
const CTRL4_FS_MASK = 0x30;
const CTRL4_HR = 0x08;

// CTRL_REG5_A bits.
const CTRL5_BOOT = 0x80;
const CTRL5_FIFO_EN = 0x40;
const CTRL5_LIR_INT1 = 0x08;
const CTRL5_D4D_INT1 = 0x04;
const CTRL5_LIR_INT2 = 0x02;
const CTRL5_D4D_INT2 = 0x01;

// CTRL_REG6_A bits (routing to INT2).
const CTRL6_I2_CLICK = 0x80;
const CTRL6_I2_INT1 = 0x40;
const CTRL6_I2_INT2 = 0x20;
const CTRL6_BOOT_I1 = 0x10;
const CTRL6_H_LACTIVE = 0x02;

// STATUS_REG_A bits.
const STATUS_ZYXDA = 0x08;

// FIFO_SRC_REG_A bits.
const FIFO_SRC_WTM = 0x80;
const FIFO_SRC_OVERRUN = 0x40;
const FIFO_SRC_EMPTY = 0x20;
const FIFO_SRC_LEVEL_MASK = 0x1F;

// INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
const SOURCE_IA = 0x40;

// CRA_REG_M bits.
const CRA_TEMP_EN = 0x80;
const CRA_DO_MASK = 0x1C;

// CRB_REG_M bits.
const CRB_GN_MASK = 0xE0;

// SR_REG_M bits.
const SR_LOCK = 0x02;
const SR_DRDY = 0x01;

// Accelerometer output data rate codes (CTRL_REG1_A.ODR<3:0>).
const ODR_CODES = {
    0: 0x0, 1: 0x1, 10: 0x2, 25: 0x3, 50: 0x4,
    100: 0x5, 200: 0x6, 400: 0x7, 1620: 0x8, 1344: 0x9, 5376: 0x9,
};

// Magnetometer output data rates (CRA_REG_M.DO<2:0>).
const MAG_ODR_RATES = [0.75, 1.5, 3, 7.5, 15, 30, 75, 220];

/**
 * Interrupt source constants for the LSM303.
 *
 * The values are the bit positions of `pollInterrupt()`'s return value.
 */
const LSM303Source = {
    /** New accelerometer XYZ sample (`STATUS_REG_A.ZYXDA`), INT1. */
    SOURCE_DATA_READY: 0x0001,
    /** FIFO content exceeds the watermark (`FIFO_SRC_REG_A.WTM`), INT1. */
    SOURCE_WATERMARK: 0x0002,
    /** FIFO overrun (`FIFO_SRC_REG_A.OVRN_FIFO`), INT1. */
    SOURCE_OVERRUN: 0x0004,
    /** Inertial generator 1 event (`INT1_SOURCE_A.IA`), INT1 or INT2. */
    SOURCE_IA1: 0x0008,
    /** Inertial generator 2 event (`INT2_SOURCE_A.IA`), INT1 or INT2. */
    SOURCE_IA2: 0x0010,
    /** Single / double click (`CLICK_SRC_A.IA`), INT1 or INT2. */
    SOURCE_CLICK: 0x0020,
    /** Boot procedure running — INT2 only, never reported by pollInterrupt(). */
    SOURCE_BOOT: 0x0040,
    /** New magnetometer sample (`SR_REG_M.DRDY`) — dedicated DRDY pin. */
    SOURCE_MAG_DATA_READY: 0x0080,
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
 * Convert an unsigned 16-bit value into a signed 16-bit integer.
 *
 * @param {number} value - Raw 16-bit value.
 * @returns {number} Signed value.
 */
function _toSigned16(value) {
    return value & 0x8000 ? value - 0x10000 : value;
}

/**
 * LSM303 minimal interface — acceleration and magnetic field.
 *
 * The constructor runs the full initialization sequence of the datasheet:
 * it verifies the magnetometer identity (0x48 0x34 0x33), puts the
 * accelerometer control registers into a known state and starts both dies.
 *
 * Default configuration:
 *   - Accelerometer: ±2 g, 100 Hz, high-resolution (12-bit), all three
 *     axes enabled, `BDU` set, high-pass filter bypassed, FIFO bypassed,
 *     all interrupt routing cleared.
 *   - Magnetometer: ±130 µT (±1.3 gauss), 15 Hz, continuous conversion,
 *     temperature sensor off.
 */
class LSM303Minimal {
    /**
     * @param {object} accelConnection - Register connection (I²C) bound to 0x19.
     * @param {object} magConnection - Register connection (I²C) bound to 0x1E.
     *
     * Both connections must sit on the same bus.
     *
     * @throws {Error} If the magnetometer identity does not match.
     */
    constructor(accelConnection, magConnection) {
        this._accel = accelConnection;
        this._mag = magConnection;
        // Cached configuration, kept in sync with the registers.
        this._mode = MODE_HIGH_RESOLUTION;
        this._fsG = 2;
        this._odrHz = 100.0;
        this._magRangeUt = 130;
        this._initPromise = this._init();
    }

    /**
     * Run the datasheet initialization sequence.
     *
     * Verifies the magnetometer identity, clears the accelerometer's
     * interrupt routing, FIFO and high-pass configuration, selects
     * ±2 g / 100 Hz / high-resolution on the accelerometer and
     * ±130 µT / 15 Hz / continuous conversion on the magnetometer, then
     * waits for the first samples of both dies.
     *
     * @returns {Promise<void>}
     */
    async _init() {
        // Boot download of the factory trim values.
        await _delayMs(5);

        const identity = await this._magRead(REG_IRA_M, 3);
        if (!identity.equals(MAG_IDENTITY)) {
            const value = (identity[0] << 16) | (identity[1] << 8) | identity[2];
            throw new Error(
                `LSM303 magnetometer not found: expected identity 0x483433, got 0x${value.toString(16)}`);
        }

        // Accelerometer: known state for the high-pass filter, the interrupt
        // routing and the FIFO.
        await this._accelWrite(REG_CTRL2_A, 0x00);
        await this._accelWrite(REG_CTRL3_A, 0x00);
        await this._accelWrite(REG_CTRL5_A, 0x00);
        await this._accelWrite(REG_CTRL6_A, 0x00);
        await this._accelWrite(REG_FIFO_CTRL_A, 0x00);

        // Accelerometer: BDU = 1, ±2 g, high-resolution mode.
        await this._accelWrite(REG_CTRL4_A, 0x88);
        // Accelerometer: 100 Hz, X/Y/Z enabled.
        await this._accelWrite(REG_CTRL1_A, 0x57);

        // Magnetometer: 15 Hz, ±1.3 gauss, continuous conversion (the part
        // resets into sleep mode).
        await this._magWrite(REG_CRA_M, 0x10);
        await this._magWrite(REG_CRB_M, 0x20);
        await this._magWrite(REG_MR_M, 0x00);

        // Accelerometer turn-on: 7/ODR = 70 ms at 100 Hz.
        await _delayMs(70);
        // Magnetometer first sample: one output period = 67 ms at 15 Hz.
        await _delayMs(67);

        this._mode = MODE_HIGH_RESOLUTION;
        this._fsG = 2;
        this._odrHz = 100.0;
    }

    // --- Low level register access -----------------------------------------

    /**
     * Read `length` bytes from an accelerometer register.
     *
     * Multi-byte reads set bit 7 of the sub-address so the accelerometer
     * auto-increments the register pointer.
     *
     * @param {number} reg - Register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} The register contents.
     */
    async _accelRead(reg, length) {
        let addr = reg & 0x7F;
        if (length > 1) {
            addr |= ACCEL_AUTO_INCREMENT;
        }
        return this._accel.readReg(addr, length);
    }

    /**
     * Write one byte to an accelerometer register.
     *
     * @param {number} reg - Register address.
     * @param {number} value - Value to write.
     * @returns {Promise<void>}
     */
    async _accelWrite(reg, value) {
        await this._accel.writeReg(reg, value & 0xFF);
    }

    /**
     * Read `length` bytes from a magnetometer register.
     *
     * The magnetometer address pointer always auto-increments, so no
     * auto-increment bit is needed.
     *
     * @param {number} reg - Register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} The register contents.
     */
    async _magRead(reg, length) {
        return this._mag.readReg(reg, length);
    }

    /**
     * Write one byte to a magnetometer register.
     *
     * @param {number} reg - Register address.
     * @param {number} value - Value to write.
     * @returns {Promise<void>}
     */
    async _magWrite(reg, value) {
        await this._mag.writeReg(reg, value & 0xFF);
    }

    // --- Minimal API --------------------------------------------------------

    /**
     * Read the acceleration.
     *
     * Burst-reads the six output registers so all three axes belong to the
     * same sample.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Acceleration in *g*.
     */
    async readAcceleration() {
        const raw = await this._accelRead(REG_OUT_X_L_A, 6);
        return this._convertAccel(raw);
    }

    /**
     * Read the magnetic flux density.
     *
     * Burst-reads the six output registers (X, Z, Y on the wire) and
     * reorders them to X, Y, Z.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Magnetic flux density in µT.
     */
    async readMagnetic() {
        const raw = await this._magRead(REG_OUT_X_H_M, 6);
        const { x, y, z } = this._reorderMag(raw);
        const [gainX, gainZ] = this._magGains();
        return { x: x / gainX * 100.0, y: y / gainX * 100.0, z: z / gainZ * 100.0 };
    }

    // --- Conversion helpers -------------------------------------------------

    /**
     * Return the right-shift that turns the raw 16-bit pair into counts.
     *
     * @returns {number} Shift in bits.
     */
    _shift() {
        if (this._mode === MODE_LOW_POWER) return 8;
        if (this._mode === MODE_NORMAL) return 6;
        return 4;
    }

    /**
     * Return the sensitivity in mg/LSB for the current mode and range.
     *
     * @returns {number} Sensitivity in mg/LSB.
     */
    _sensitivityMg() {
        let table;
        if (this._mode === MODE_LOW_POWER) {
            table = [16, 32, 64, 192];
        } else if (this._mode === MODE_NORMAL) {
            table = [4, 8, 16, 48];
        } else {
            table = [1, 2, 4, 12];
        }
        const index = { 2: 0, 4: 1, 8: 2, 16: 3 }[this._fsG];
        return table[index];
    }

    /**
     * Convert a six-byte accelerometer burst into (x, y, z) in *g*.
     *
     * @param {Buffer} raw - Six raw bytes, little-endian per axis.
     * @returns {{x:number, y:number, z:number}} Acceleration in *g*.
     */
    _convertAccel(raw) {
        const shift = this._shift();
        const sens = this._sensitivityMg() / 1000.0;
        const x = _toSigned16(raw[0] | (raw[1] << 8)) >> shift;
        const y = _toSigned16(raw[2] | (raw[3] << 8)) >> shift;
        const z = _toSigned16(raw[4] | (raw[5] << 8)) >> shift;
        return { x: x * sens, y: y * sens, z: z * sens };
    }

    /**
     * Convert a six-byte accelerometer burst into raw counts.
     *
     * @param {Buffer} raw - Six raw bytes, little-endian per axis.
     * @returns {{x:number, y:number, z:number}} Signed counts.
     */
    _rawAccel(raw) {
        const shift = this._shift();
        return {
            x: _toSigned16(raw[0] | (raw[1] << 8)) >> shift,
            y: _toSigned16(raw[2] | (raw[3] << 8)) >> shift,
            z: _toSigned16(raw[4] | (raw[5] << 8)) >> shift,
        };
    }

    /**
     * Reorder a six-byte magnetometer burst (X, Z, Y) into (X, Y, Z).
     *
     * @param {Buffer} raw - Six raw bytes, high byte first.
     * @returns {{x:number, y:number, z:number}} Signed counts.
     */
    _reorderMag(raw) {
        const x = _toSigned16((raw[0] << 8) | raw[1]);
        const z = _toSigned16((raw[2] << 8) | raw[3]);
        const y = _toSigned16((raw[4] << 8) | raw[5]);
        return { x, y, z };
    }

    /**
     * Return the (X/Y, Z) gains in LSB per gauss for the current range.
     *
     * @returns {[number, number]} The X/Y gain and the Z gain.
     */
    _magGains() {
        const table = {
            130: [1100, 980],
            190: [855, 760],
            250: [670, 600],
            400: [450, 400],
            470: [400, 355],
            560: [330, 295],
            810: [230, 205],
        };
        return table[this._magRangeUt];
    }
}

/**
 * Mixin that adds Full-stage functionality on top of `LSM303Minimal`.
 *
 * Usage: `class LSM303Full extends _LSM303FullMixin(LSM303Minimal) {}`.
 */
const _LSM303FullMixin = (Base) => class extends Base {
    /**
     * @param {object} accelConnection - Register connection (I²C) bound to 0x19.
     * @param {object} magConnection - Register connection (I²C) bound to 0x1E.
     *
     * @throws {Error} If the magnetometer identity does not match.
     */
    constructor(accelConnection, magConnection) {
        super(accelConnection, magConnection);
        this._tempEnabled = false;
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._lastStatus = 0;
        this._enabledSources = 0;
    }

    // --- Accelerometer configuration ----------------------------------------

    /**
     * Set the accelerometer full-scale range.
     *
     * @param {number} rangeG - 2, 4, 8 or 16 (*g*).
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If rangeG is not one of the supported ranges.
     */
    async setRange(rangeG) {
        const codes = { 2: 0x00, 4: 0x10, 8: 0x20, 16: 0x30 };
        if (!(rangeG in codes)) {
            throw new Error('rangeG must be one of 2, 4, 8, 16');
        }
        const ctrl4 = await this._accelRead(REG_CTRL4_A, 1);
        const value = (ctrl4[0] & ~CTRL4_FS_MASK) | codes[rangeG];
        await this._accelWrite(REG_CTRL4_A, value);
        this._fsG = rangeG;
        return true;
    }

    /**
     * Set the accelerometer output data rate.
     *
     * @param {number} rateHz - 0 (power-down), 1, 10, 25, 50, 100, 200, 400,
     *   1344, 1620 or 5376. The rates 1620 and 5376 Hz exist in low-power mode
     *   only, 1344 Hz in normal and high-resolution mode only.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the rate is not supported or not allowed in the
     *   current operating mode.
     */
    async setDataRate(rateHz) {
        if (!(rateHz in ODR_CODES)) {
            throw new Error('rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376');
        }
        const lowPower = this._mode === MODE_LOW_POWER;
        if ((rateHz === 1620 || rateHz === 5376) && !lowPower) {
            throw new Error(`${rateHz} Hz is only available in low-power mode`);
        }
        if (rateHz === 1344 && lowPower) {
            throw new Error('1344 Hz is not available in low-power mode (use 5376 Hz)');
        }
        const ctrl1 = await this._accelRead(REG_CTRL1_A, 1);
        const value = (ctrl1[0] & ~CTRL1_ODR_MASK) | (ODR_CODES[rateHz] << 4);
        await this._accelWrite(REG_CTRL1_A, value);
        this._odrHz = Number(rateHz);
        return true;
    }

    /**
     * Set the accelerometer operating mode.
     *
     * Low-power mode uses 8-bit output, normal mode 10-bit and
     * high-resolution mode 12-bit; the sensitivity changes accordingly.
     *
     * @param {number} mode - MODE_LOW_POWER (0), MODE_NORMAL (1) or
     *   MODE_HIGH_RESOLUTION (2).
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If mode is not one of the three operating modes.
     */
    async setMode(mode) {
        let lpen, hr;
        if (mode === MODE_LOW_POWER) {
            lpen = true; hr = false;
        } else if (mode === MODE_NORMAL) {
            lpen = false; hr = false;
        } else if (mode === MODE_HIGH_RESOLUTION) {
            lpen = false; hr = true;
        } else {
            throw new Error('mode must be MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION');
        }
        const ctrl1 = await this._accelRead(REG_CTRL1_A, 1);
        const ctrl4 = await this._accelRead(REG_CTRL4_A, 1);
        const v1 = lpen ? (ctrl1[0] | CTRL1_LPEN) : (ctrl1[0] & ~CTRL1_LPEN);
        const v4 = hr ? (ctrl4[0] | CTRL4_HR) : (ctrl4[0] & ~CTRL4_HR);
        await this._accelWrite(REG_CTRL1_A, v1);
        await this._accelWrite(REG_CTRL4_A, v4);
        this._mode = mode;
        return true;
    }

    /**
     * Enable or disable the individual accelerometer axes.
     *
     * @param {boolean} [x=true] - True to enable the X axis.
     * @param {boolean} [y=true] - True to enable the Y axis.
     * @param {boolean} [z=true] - True to enable the Z axis.
     * @returns {Promise<void>}
     */
    async setAxes(x = true, y = true, z = true) {
        const ctrl1 = await this._accelRead(REG_CTRL1_A, 1);
        let value = ctrl1[0] & ~CTRL1_AXES_MASK;
        if (x) value |= 0x01;
        if (y) value |= 0x02;
        if (z) value |= 0x04;
        await this._accelWrite(REG_CTRL1_A, value);
    }

    /**
     * Put the accelerometer into power-down mode.
     *
     * The output data rate is set to 0000; the register contents are
     * retained, so `setDataRate()` resumes the conversions.
     *
     * @returns {Promise<void>}
     */
    async powerDown() {
        const ctrl1 = await this._accelRead(REG_CTRL1_A, 1);
        await this._accelWrite(REG_CTRL1_A, ctrl1[0] & ~CTRL1_ODR_MASK);
        this._odrHz = 0.0;
    }

    // --- Accelerometer readings ---------------------------------------------

    /**
     * Read the acceleration as raw counts.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Signed counts at the
     *   current resolution.
     */
    async readAccelerationRaw() {
        const raw = await this._accelRead(REG_OUT_X_L_A, 6);
        return this._rawAccel(raw);
    }

    /**
     * Report whether a new accelerometer sample is available.
     *
     * @returns {Promise<boolean>} True if `STATUS_REG_A.ZYXDA` is set.
     */
    async newDataAvailable() {
        const status = await this._accelRead(REG_STATUS_A, 1);
        return (status[0] & STATUS_ZYXDA) !== 0;
    }

    /**
     * Read the raw accelerometer status register.
     *
     * @returns {Promise<number>} The `STATUS_REG_A` byte (overrun and
     *   data-available flags).
     */
    async readStatus() {
        const status = await this._accelRead(REG_STATUS_A, 1);
        return status[0];
    }

    // --- High-pass filter ----------------------------------------------------

    /**
     * Configure the accelerometer high-pass filter.
     *
     * @param {number} [mode=0] - HPM code 0–3 (0 = normal, 1 = reference
     *   signal, 2 = normal, 3 = autoreset on interrupt event).
     * @param {number} [cutoff=0] - HPCF code 0–3 (high-pass cutoff selection).
     * @param {boolean} [filterOutput=false] - True to route the filtered data
     *   to the output registers and the FIFO (`FDS`).
     * @param {boolean} [onIa1=false] - True to high-pass the input of
     *   interrupt generator 1.
     * @param {boolean} [onIa2=false] - True to high-pass the input of
     *   interrupt generator 2.
     * @param {boolean} [onClick=false] - True to high-pass the click detector
     *   input.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If mode or cutoff is out of range.
     */
    async setHighPass(mode = 0, cutoff = 0, filterOutput = false,
                       onIa1 = false, onIa2 = false, onClick = false) {
        if (mode < 0 || mode > 3) {
            throw new Error('mode must be 0..3');
        }
        if (cutoff < 0 || cutoff > 3) {
            throw new Error('cutoff must be 0..3');
        }
        let ctrl2 = (mode << 6) | (cutoff << 4);
        if (filterOutput) ctrl2 |= CTRL2_FDS;
        if (onClick) ctrl2 |= CTRL2_HPCLICK;
        if (onIa2) ctrl2 |= CTRL2_HPIS2;
        if (onIa1) ctrl2 |= CTRL2_HPIS1;
        await this._accelWrite(REG_CTRL2_A, ctrl2);
        return true;
    }

    /**
     * Reset the high-pass filter and return the reference value.
     *
     * Reading `REFERENCE_A` resets the filter in HPM = 00 mode.
     *
     * @returns {Promise<number>} The reference value (0–255).
     */
    async resetHighPass() {
        const ref = await this._accelRead(REG_REFERENCE_A, 1);
        return ref[0];
    }

    /**
     * Write the high-pass reference value.
     *
     * @param {number} value - Reference value 0–255.
     * @returns {Promise<void>}
     */
    async setReference(value) {
        await this._accelWrite(REG_REFERENCE_A, value & 0xFF);
    }

    // --- FIFO ----------------------------------------------------------------

    /**
     * Configure the accelerometer FIFO.
     *
     * @param {number} mode - FIFO_BYPASS (0), FIFO_MODE (1), FIFO_STREAM (2)
     *   or FIFO_TRIGGER (3).
     * @param {number} [watermark=0] - Watermark level 0–31.
     * @param {boolean} [triggerInt2=false] - True to use INT2 as the trigger
     *   source in trigger mode, False for INT1.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If mode or watermark is out of range.
     */
    async setFifo(mode, watermark = 0, triggerInt2 = false) {
        if (mode < 0 || mode > 3) {
            throw new Error('mode must be 0..3');
        }
        if (watermark < 0 || watermark > 31) {
            throw new Error('watermark must be 0..31');
        }
        let fifo = (mode << 6) | (watermark & 0x1F);
        if (triggerInt2) fifo |= 0x20;
        await this._accelWrite(REG_FIFO_CTRL_A, fifo);
        const ctrl5 = await this._accelRead(REG_CTRL5_A, 1);
        const value = mode === FIFO_BYPASS
            ? (ctrl5[0] & ~CTRL5_FIFO_EN)
            : (ctrl5[0] | CTRL5_FIFO_EN);
        await this._accelWrite(REG_CTRL5_A, value);
        return true;
    }

    /**
     * Return the number of unread sample sets in the FIFO.
     *
     * @returns {Promise<number>} The number of unread sample sets (0–32).
     */
    async fifoLevel() {
        const src = await this._accelRead(REG_FIFO_SRC_A, 1);
        return src[0] & FIFO_SRC_LEVEL_MASK;
    }

    /**
     * Read sample sets from the FIFO.
     *
     * Each sample set is popped with one six-byte burst read.
     *
     * @param {number} [count] - Number of sample sets to read; undefined
     *   reads all of them.
     * @returns {Promise<Array<{x:number, y:number, z:number}>>} The sample
     *   sets in *g*.
     */
    async readFifo(count) {
        let n = count;
        if (n === undefined || n === null) {
            n = await this.fifoLevel();
        }
        const result = [];
        for (let i = 0; i < n; i++) {
            const raw = await this._accelRead(REG_OUT_X_L_A, 6);
            result.push(this._convertAccel(raw));
        }
        return result;
    }

    /**
     * Read the decoded FIFO status.
     *
     * @returns {Promise<{watermark:boolean, overrun:boolean, empty:boolean, level:number}>}
     *   The decoded FIFO status.
     */
    async readFifoStatus() {
        const src = await this._accelRead(REG_FIFO_SRC_A, 1);
        return {
            watermark: (src[0] & FIFO_SRC_WTM) !== 0,
            overrun: (src[0] & FIFO_SRC_OVERRUN) !== 0,
            empty: (src[0] & FIFO_SRC_EMPTY) !== 0,
            level: src[0] & FIFO_SRC_LEVEL_MASK,
        };
    }

    // --- Inertial interrupt generators ---------------------------------------

    /**
     * Configure one inertial interrupt generator.
     *
     * @param {number} generator - 1 or 2.
     * @param {number} events - Bit mask of the enabled events — `ZHIE` (0x20),
     *   `ZLIE` (0x10), `YHIE` (0x08), `YLIE` (0x04), `XHIE` (0x02) or
     *   `XLIE` (0x01).
     * @param {number} thresholdG - Threshold in *g*, relative to the current
     *   range.
     * @param {number} [durationMs=0] - Minimum duration in milliseconds; 0
     *   disables the duration filter.
     * @param {boolean} [andMode=false] - True to combine the events with AND
     *   instead of OR.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the generator or the event mask is invalid.
     */
    async setMotionInterrupt(generator, events, thresholdG, durationMs = 0, andMode = false) {
        const [cfg, ths, dur] = this._intRegs(generator);
        if (events < 0 || events > 0x3F) {
            throw new Error('events must be a 6-bit mask (0..63)');
        }
        let value = events & 0x3F;
        if (andMode) value |= 0x80;
        await this._accelWrite(cfg, value);
        await this._accelWrite(ths, this._encodeThreshold(thresholdG));
        await this._accelWrite(dur, this._encodeDuration(durationMs));
        return true;
    }

    /**
     * Arm the free-fall detector of one generator.
     *
     * All three axes are watched for a low event and combined with AND.
     *
     * @param {number} [generator=1] - 1 or 2.
     * @param {number} [thresholdG=0.35] - Threshold in *g*.
     * @param {number} [durationMs=30] - Minimum duration in milliseconds.
     * @returns {Promise<boolean>} True on success.
     */
    async setFreeFall(generator = 1, thresholdG = 0.35, durationMs = 30) {
        return this.setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
    }

    /**
     * Arm the 6D orientation detector of one generator.
     *
     * @param {number} [generator=1] - 1 or 2.
     * @param {boolean} [position=false] - False for movement recognition (a
     *   one-ODR pulse), True for position recognition (a level).
     * @param {boolean} [fourD=false] - True for 4D detection, which drops the
     *   Z axis from the zone detection.
     * @returns {Promise<boolean>} True on success.
     */
    async setOrientation(generator = 1, position = false, fourD = false) {
        const [cfg] = this._intRegs(generator);
        let events = 0x3F;
        if (fourD) events &= ~0x30;
        let value = events | 0x40;
        if (position) value |= 0x80;
        await this._accelWrite(cfg, value);
        const ctrl5 = await this._accelRead(REG_CTRL5_A, 1);
        const bit = generator === 1 ? CTRL5_D4D_INT1 : CTRL5_D4D_INT2;
        const updated = fourD ? (ctrl5[0] | bit) : (ctrl5[0] & ~bit);
        await this._accelWrite(REG_CTRL5_A, updated);
        return true;
    }

    /**
     * Latch or release the interrupt of one generator.
     *
     * A latched interrupt stays asserted until `INTx_SOURCE_A` is read.
     *
     * @param {number} generator - 1 or 2.
     * @param {boolean} enabled - True to latch, False to release.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the generator is not 1 or 2.
     */
    async setLatch(generator, enabled) {
        if (generator !== 1 && generator !== 2) {
            throw new Error('generator must be 1 or 2');
        }
        const ctrl5 = await this._accelRead(REG_CTRL5_A, 1);
        const bit = generator === 1 ? CTRL5_LIR_INT1 : CTRL5_LIR_INT2;
        const value = enabled ? (ctrl5[0] | bit) : (ctrl5[0] & ~bit);
        await this._accelWrite(REG_CTRL5_A, value);
        return true;
    }

    /**
     * Read the interrupt source register of one generator.
     *
     * Reading the register clears the latched interrupt.
     *
     * @param {number} generator - 1 or 2.
     * @returns {Promise<number>} The raw `INTx_SOURCE_A` byte.
     * @throws {Error} If the generator is not 1 or 2.
     */
    async readInterruptSource(generator) {
        if (generator !== 1 && generator !== 2) {
            throw new Error('generator must be 1 or 2');
        }
        const reg = generator === 1 ? REG_INT1_SOURCE_A : REG_INT2_SOURCE_A;
        const src = await this._accelRead(reg, 1);
        return src[0];
    }

    /**
     * Return the (config, threshold, duration) register triple of a generator.
     *
     * @param {number} generator - 1 or 2.
     * @returns {[number, number, number]} The three register addresses.
     * @throws {Error} If the generator is not 1 or 2.
     */
    _intRegs(generator) {
        if (generator === 1) {
            return [REG_INT1_CFG_A, REG_INT1_THS_A, REG_INT1_DURATION_A];
        }
        if (generator === 2) {
            return [REG_INT2_CFG_A, REG_INT2_THS_A, REG_INT2_DURATION_A];
        }
        throw new Error('generator must be 1 or 2');
    }

    /**
     * Convert a threshold in *g* into a 7-bit register code.
     *
     * @param {number} thresholdG - Threshold in *g*.
     * @returns {number} The register code (0–127).
     */
    _encodeThreshold(thresholdG) {
        let code = Math.round(thresholdG * 128.0 / this._fsG);
        if (code < 0) code = 0;
        if (code > 127) code = 127;
        return code;
    }

    /**
     * Convert a duration in milliseconds into a register code.
     *
     * One LSB is 1/ODR; the code is clamped to the register width.
     *
     * @param {number} durationMs - Duration in milliseconds.
     * @param {number} [bits=7] - Register width in bits.
     * @returns {number} The register code.
     */
    _encodeDuration(durationMs, bits = 7) {
        let code;
        if (this._odrHz <= 0) {
            code = 0;
        } else {
            code = Math.round(durationMs * this._odrHz / 1000.0);
        }
        if (code < 0) code = 0;
        if (code > (1 << bits) - 1) code = (1 << bits) - 1;
        return code;
    }

    // --- Click detection -----------------------------------------------------

    /**
     * Configure the click detector.
     *
     * @param {number} [single=0] - Axis mask of the enabled single-click
     *   detectors (X = 1, Y = 2, Z = 4).
     * @param {number} [double=0] - Axis mask of the enabled double-click
     *   detectors (X = 1, Y = 2, Z = 4).
     * @param {number} [thresholdG=0.5] - Click threshold in *g*.
     * @param {number} [timeLimitMs=10] - Maximum time between the two clicks
     *   of a double click, in milliseconds.
     * @param {number} [latencyMs=20] - Minimum time between two consecutive
     *   clicks, in milliseconds.
     * @param {number} [windowMs=50] - Time the click detector observes, in
     *   milliseconds.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If an axis mask is out of range.
     */
    async setClick(single = 0, double = 0, thresholdG = 0.5,
                   timeLimitMs = 10, latencyMs = 20, windowMs = 50) {
        if (single < 0 || single > 0x07) {
            throw new Error('single must be an axis mask 0..7');
        }
        if (double < 0 || double > 0x07) {
            throw new Error('double must be an axis mask 0..7');
        }
        let cfg = 0;
        if (single & CLICK_AXIS_X) cfg |= 0x01;
        if (single & CLICK_AXIS_Y) cfg |= 0x04;
        if (single & CLICK_AXIS_Z) cfg |= 0x10;
        if (double & CLICK_AXIS_X) cfg |= 0x02;
        if (double & CLICK_AXIS_Y) cfg |= 0x08;
        if (double & CLICK_AXIS_Z) cfg |= 0x20;
        await this._accelWrite(REG_CLICK_CFG_A, cfg);
        await this._accelWrite(REG_CLICK_THS_A, this._encodeThreshold(thresholdG));
        await this._accelWrite(REG_TIME_LIMIT_A, this._encodeDuration(timeLimitMs, 7));
        await this._accelWrite(REG_TIME_LATENCY_A, this._encodeDuration(latencyMs, 8));
        await this._accelWrite(REG_TIME_WINDOW_A, this._encodeDuration(windowMs, 8));
        return true;
    }

    /**
     * Read the click source register.
     *
     * Reading the register clears the latched click interrupt.
     *
     * @returns {Promise<number>} The raw `CLICK_SRC_A` byte.
     */
    async readClickSource() {
        const src = await this._accelRead(REG_CLICK_SRC_A, 1);
        return src[0];
    }

    /**
     * Set the polarity of the INT1 / INT2 outputs.
     *
     * @param {boolean} activeLow - True for active-low, False for
     *   active-high (default).
     * @returns {Promise<void>}
     */
    async setInterruptPolarity(activeLow) {
        const ctrl6 = await this._accelRead(REG_CTRL6_A, 1);
        const value = activeLow
            ? (ctrl6[0] | CTRL6_H_LACTIVE)
            : (ctrl6[0] & ~CTRL6_H_LACTIVE);
        await this._accelWrite(REG_CTRL6_A, value);
    }

    // --- Magnetometer --------------------------------------------------------

    /**
     * Set the magnetometer full-scale range.
     *
     * @param {number} rangeUt - 130, 190, 250, 400, 470, 560 or 810 (µT).
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the range is not supported.
     */
    async setMagRange(rangeUt) {
        const codes = { 130: 0x20, 190: 0x40, 250: 0x60, 400: 0x80,
                        470: 0xA0, 560: 0xC0, 810: 0xE0 };
        if (!(rangeUt in codes)) {
            throw new Error('rangeUt must be one of 130, 190, 250, 400, 470, 560, 810');
        }
        const crb = await this._magRead(REG_CRB_M, 1);
        const value = (crb[0] & ~CRB_GN_MASK) | codes[rangeUt];
        await this._magWrite(REG_CRB_M, value);
        this._magRangeUt = rangeUt;
        return true;
    }

    /**
     * Set the magnetometer output data rate.
     *
     * @param {number} rateHz - 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the rate is not supported.
     */
    async setMagDataRate(rateHz) {
        for (let code = 0; code < MAG_ODR_RATES.length; code++) {
            if (Math.abs(MAG_ODR_RATES[code] - rateHz) < 1e-6) {
                const cra = await this._magRead(REG_CRA_M, 1);
                const value = (cra[0] & ~CRA_DO_MASK) | (code << 2);
                await this._magWrite(REG_CRA_M, value);
                return true;
            }
        }
        throw new Error('rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220');
    }

    /**
     * Set the magnetometer operating mode.
     *
     * @param {number} mode - MAG_MODE_CONTINUOUS (0), MAG_MODE_SINGLE (1) or
     *   MAG_MODE_SLEEP (2).
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the mode is not supported.
     */
    async setMagMode(mode) {
        if (mode !== MAG_MODE_CONTINUOUS && mode !== MAG_MODE_SINGLE && mode !== MAG_MODE_SLEEP) {
            throw new Error('mode must be MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP');
        }
        const mr = await this._magRead(REG_MR_M, 1);
        const value = (mr[0] & ~0x03) | mode;
        await this._magWrite(REG_MR_M, value);
        return true;
    }

    /**
     * Read the magnetic field as raw counts.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Signed counts in
     *   X, Y, Z order.
     */
    async readMagneticRaw() {
        const raw = await this._magRead(REG_OUT_X_H_M, 6);
        return this._reorderMag(raw);
    }

    /**
     * Report whether a new magnetometer sample is available.
     *
     * @returns {Promise<boolean>} True if `SR_REG_M.DRDY` is set.
     */
    async magDataReady() {
        const sr = await this._magRead(REG_SR_M, 1);
        return (sr[0] & SR_DRDY) !== 0;
    }

    /**
     * Read the decoded magnetometer status register.
     *
     * @returns {Promise<{drdy:boolean, lock:boolean}>} The decoded status.
     */
    async readMagStatus() {
        const sr = await this._magRead(REG_SR_M, 1);
        return { drdy: (sr[0] & SR_DRDY) !== 0, lock: (sr[0] & SR_LOCK) !== 0 };
    }

    /**
     * Read the magnetometer identity bytes.
     *
     * @returns {Promise<Buffer>} The three identity bytes (0x48 0x34 0x33 on a
     *   real part).
     */
    async readMagIdentity() {
        return this._magRead(REG_IRA_M, 3);
    }

    /**
     * Enable or disable the magnetometer temperature sensor.
     *
     * @param {boolean} enabled - True to enable the sensor, False to disable
     *   it.
     * @returns {Promise<void>}
     */
    async enableTemperature(enabled) {
        const cra = await this._magRead(REG_CRA_M, 1);
        const value = enabled ? (cra[0] | CRA_TEMP_EN) : (cra[0] & ~CRA_TEMP_EN);
        await this._magWrite(REG_CRA_M, value);
        this._tempEnabled = Boolean(enabled);
    }

    /**
     * Read the relative temperature of the magnetometer.
     *
     * The sensor is enabled on first use. The datasheet gives no zero
     * reference, so the value is a *delta* relative to an unspecified
     * temperature and not an absolute °C reading.
     *
     * @returns {Promise<number>} Temperature difference in °C (8 LSB/°C).
     */
    async readTemperatureDelta() {
        if (!this._tempEnabled) {
            await this.enableTemperature(true);
        }
        const raw = await this._magRead(REG_TEMP_OUT_H_M, 2);
        const value = _toSigned16((raw[0] << 8) | raw[1]) >> 4;
        return value / 8.0;
    }

    // --- Compass --------------------------------------------------------------

    /**
     * Compute the compass heading.
     *
     * The heading is measured in the sensor frame: the datasheet does not
     * document the axis alignment between the two dies, so the tilt
     * compensation assumes both report in the same body frame.
     *
     * @param {boolean} [tiltCompensate=true] - True to compensate for the tilt
     *   reported by the accelerometer, False for the flat (magnetic-only)
     *   heading.
     * @returns {Promise<number>} The heading in degrees, 0–360.
     */
    async heading(tiltCompensate = true) {
        const m = await this.readMagnetic();
        const mx = m.x, my = m.y, mz = m.z;
        if (!tiltCompensate) {
            return this._wrapDegrees(Math.atan2(my, mx) * 180.0 / Math.PI);
        }
        const a = await this.readAcceleration();
        const ax = a.x, ay = a.y, az = a.z;
        const roll = Math.atan2(ay, az);
        const pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll));
        const xh = mx * Math.cos(pitch) + mz * Math.sin(pitch);
        const yh = (mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll)
                    - mz * Math.sin(roll) * Math.cos(pitch));
        return this._wrapDegrees(Math.atan2(yh, xh) * 180.0 / Math.PI);
    }

    /**
     * Wrap an angle in degrees into the 0–360 range.
     *
     * @param {number} degrees - The angle in degrees.
     * @returns {number} The wrapped angle.
     */
    _wrapDegrees(degrees) {
        let value = degrees % 360.0;
        if (value < 0) value += 360.0;
        return value;
    }

    // --- Interrupt API ---------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2.
     *
     * Only the routing is configured; the generator or function behind the
     * source is set up with the `set*` methods.
     *
     * @param {number} source - One of the `LSM303Source.SOURCE_*` constants.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2. The argument is
     *   ignored for sources that can only appear on one of the two lines.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the source cannot be routed or the pin is invalid.
     */
    async enableInterrupt(source, pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        if (source === LSM303Source.SOURCE_MAG_DATA_READY) {
            throw new Error('SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed');
        }
        const [ctrl3Bit, ctrl6Bit] = this._sourceBits(source);
        if (ctrl3Bit === null && ctrl6Bit === null) {
            throw new Error('unknown interrupt source');
        }
        if (ctrl3Bit !== null && (pin === 1 || ctrl6Bit === null)) {
            const ctrl3 = await this._accelRead(REG_CTRL3_A, 1);
            await this._accelWrite(REG_CTRL3_A, ctrl3[0] | ctrl3Bit);
        }
        if (ctrl6Bit !== null && (pin === 2 || ctrl3Bit === null)) {
            const ctrl6 = await this._accelRead(REG_CTRL6_A, 1);
            await this._accelWrite(REG_CTRL6_A, ctrl6[0] | ctrl6Bit);
        }
        this._enabledSources |= source;
        return true;
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     *
     * @param {number} source - One of the `LSM303Source.SOURCE_*` constants.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<boolean>} True on success.
     * @throws {Error} If the source cannot be routed or the pin is invalid.
     */
    async disableInterrupt(source, pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        if (source === LSM303Source.SOURCE_MAG_DATA_READY) {
            throw new Error('SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed');
        }
        const [ctrl3Bit, ctrl6Bit] = this._sourceBits(source);
        if (ctrl3Bit === null && ctrl6Bit === null) {
            throw new Error('unknown interrupt source');
        }
        if (ctrl3Bit !== null && (pin === 1 || ctrl6Bit === null)) {
            const ctrl3 = await this._accelRead(REG_CTRL3_A, 1);
            await this._accelWrite(REG_CTRL3_A, ctrl3[0] & ~ctrl3Bit);
        }
        if (ctrl6Bit !== null && (pin === 2 || ctrl3Bit === null)) {
            const ctrl6 = await this._accelRead(REG_CTRL6_A, 1);
            await this._accelWrite(REG_CTRL6_A, ctrl6[0] & ~ctrl6Bit);
        }
        this._enabledSources &= ~source;
        return true;
    }

    /**
     * Return the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a source.
     *
     * @param {number} source - One of the `LSM303Source.SOURCE_*` constants.
     * @returns {[number|null, number|null]} The two routing bits.
     */
    _sourceBits(source) {
        switch (source) {
            case LSM303Source.SOURCE_DATA_READY: return [CTRL3_I1_DRDY1, null];
            case LSM303Source.SOURCE_WATERMARK: return [CTRL3_I1_WTM, null];
            case LSM303Source.SOURCE_OVERRUN: return [CTRL3_I1_OVERRUN, null];
            case LSM303Source.SOURCE_IA1: return [CTRL3_I1_AOI1, CTRL6_I2_INT1];
            case LSM303Source.SOURCE_IA2: return [CTRL3_I1_AOI2, CTRL6_I2_INT2];
            case LSM303Source.SOURCE_CLICK: return [CTRL3_I1_CLICK, CTRL6_I2_CLICK];
            case LSM303Source.SOURCE_BOOT: return [null, CTRL6_BOOT_I1];
            default: return [null, null];
        }
    }

    /**
     * Read the interrupt status of every enabled source.
     *
     * Reading `INTx_SOURCE_A` and `CLICK_SRC_A` clears their latched flags.
     *
     * @returns {Promise<number>} A mask of the `LSM303Source.SOURCE_*`
     *   constants.
     */
    async pollInterrupt() {
        let status = 0;
        const regStatus = await this._accelRead(REG_STATUS_A, 1);
        if (regStatus[0] & STATUS_ZYXDA) {
            status |= LSM303Source.SOURCE_DATA_READY;
        }
        const fifo = await this._accelRead(REG_FIFO_SRC_A, 1);
        if (fifo[0] & FIFO_SRC_WTM) {
            status |= LSM303Source.SOURCE_WATERMARK;
        }
        if (fifo[0] & FIFO_SRC_OVERRUN) {
            status |= LSM303Source.SOURCE_OVERRUN;
        }
        if (this._enabledSources & LSM303Source.SOURCE_IA1) {
            const src = await this._accelRead(REG_INT1_SOURCE_A, 1);
            if (src[0] & SOURCE_IA) status |= LSM303Source.SOURCE_IA1;
        }
        if (this._enabledSources & LSM303Source.SOURCE_IA2) {
            const src = await this._accelRead(REG_INT2_SOURCE_A, 1);
            if (src[0] & SOURCE_IA) status |= LSM303Source.SOURCE_IA2;
        }
        if (this._enabledSources & LSM303Source.SOURCE_CLICK) {
            const src = await this._accelRead(REG_CLICK_SRC_A, 1);
            if (src[0] & SOURCE_IA) status |= LSM303Source.SOURCE_CLICK;
        }
        const sr = await this._magRead(REG_SR_M, 1);
        if (sr[0] & SR_DRDY) {
            status |= LSM303Source.SOURCE_MAG_DATA_READY;
        }
        return status;
    }

    /**
     * Subscribe to the INT1 or INT2 line.
     *
     * The edge direction follows the polarity set with
     * `setInterruptPolarity`. Without an INT pin — for example on a Linux host
     * without a GPIO wired up — a 5 ms polling timer takes over.
     *
     * @param {function(number):void} callback - Receives the
     *   `pollInterrupt()` mask.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2. The line must be
     *   wired to the INT pin of the accelerometer connection.
     * @returns {Promise<void>}
     * @throws {Error} If the pin is not 1 or 2.
     */
    async onInterrupt(callback, pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        this._callback = callback;
        const intPin = this._accel.intPin || null;
        this._intPinUsed = intPin;
        if (intPin) {
            const ctrl6 = await this._accelRead(REG_CTRL6_A, 1);
            const activeLow = (ctrl6[0] & CTRL6_H_LACTIVE) !== 0;
            intPin.onEdge(this._intHandler, activeLow ? 'falling' : 'rising');
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
     * Poll the interrupt status every 5 ms and dispatch on change.
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

    // --- Miscellaneous ---------------------------------------------------------

    /**
     * Reboot the accelerometer's memory content.
     *
     * The `BOOT` bit reloads the factory trim values. The datasheet does
     * not say whether the control registers survive, so the initialization
     * sequence is run again afterwards and any configuration applied after
     * `init()` has to be repeated.
     *
     * @returns {Promise<void>}
     */
    async reboot() {
        const ctrl5 = await this._accelRead(REG_CTRL5_A, 1);
        await this._accelWrite(REG_CTRL5_A, ctrl5[0] | CTRL5_BOOT);
        await _delayMs(5);
        await this._init();
        this._tempEnabled = false;
    }
};

/**
 * LSM303 full interface — extends `LSM303Minimal` with the complete chip API.
 *
 * Adds:
 *   - Accelerometer range, output data rate, operating mode and per-axis
 *     enable, plus power-down.
 *   - Raw acceleration and raw magnetic readings.
 *   - High-pass filter configuration and the reference value.
 *   - The 32-level FIFO in bypass / FIFO / stream / trigger mode.
 *   - Two inertial interrupt generators with thresholds, durations,
 *     free-fall and 6D/4D orientation recognition, plus latching.
 *   - Single and double click detection.
 *   - Magnetometer gain, data rate, operating mode, identity, status and
 *     the relative temperature sensor.
 *   - A software compass heading, optionally tilt-compensated.
 *   - The Level-3 interrupt API (INT1 / INT2).
 */
class LSM303Full extends _LSM303FullMixin(LSM303Minimal) {}

LSM303Full.MODE_LOW_POWER = MODE_LOW_POWER;
LSM303Full.MODE_NORMAL = MODE_NORMAL;
LSM303Full.MODE_HIGH_RESOLUTION = MODE_HIGH_RESOLUTION;
LSM303Full.FIFO_BYPASS = FIFO_BYPASS;
LSM303Full.FIFO_MODE = FIFO_MODE;
LSM303Full.FIFO_STREAM = FIFO_STREAM;
LSM303Full.FIFO_TRIGGER = FIFO_TRIGGER;
LSM303Full.MAG_MODE_CONTINUOUS = MAG_MODE_CONTINUOUS;
LSM303Full.MAG_MODE_SINGLE = MAG_MODE_SINGLE;
LSM303Full.MAG_MODE_SLEEP = MAG_MODE_SLEEP;
LSM303Full.CLICK_AXIS_X = CLICK_AXIS_X;
LSM303Full.CLICK_AXIS_Y = CLICK_AXIS_Y;
LSM303Full.CLICK_AXIS_Z = CLICK_AXIS_Z;
LSM303Full.I2C_ADDRESS_ACCEL = I2C_ADDRESS_ACCEL;
LSM303Full.I2C_ADDRESS_MAG = I2C_ADDRESS_MAG;

// The interrupt source constants are also exposed directly on the class, so
// `LSM303Full.SOURCE_CLICK` works next to the standalone `LSM303Source`.
Object.assign(LSM303Full, LSM303Source);

LSM303Minimal.MODE_LOW_POWER = MODE_LOW_POWER;
LSM303Minimal.MODE_NORMAL = MODE_NORMAL;
LSM303Minimal.MODE_HIGH_RESOLUTION = MODE_HIGH_RESOLUTION;
LSM303Minimal.I2C_ADDRESS_ACCEL = I2C_ADDRESS_ACCEL;
LSM303Minimal.I2C_ADDRESS_MAG = I2C_ADDRESS_MAG;

module.exports = {
    LSM303Minimal,
    LSM303Full,
    LSM303Source,
    I2C_ADDRESS_ACCEL,
    I2C_ADDRESS_MAG,
};
