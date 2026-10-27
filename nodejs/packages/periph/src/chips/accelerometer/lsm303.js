'use strict';

// Accelerometer register map (sub-address bit 7 = auto-increment).
const _REG_CTRL1_A      = 0x20;
const _REG_CTRL2_A      = 0x21;
const _REG_CTRL3_A      = 0x22;
const _REG_CTRL4_A      = 0x23;
const _REG_CTRL5_A      = 0x24;
const _REG_CTRL6_A      = 0x25;
const _REG_REFERENCE_A  = 0x26;
const _REG_STATUS_A     = 0x27;
const _REG_OUT_X_L_A    = 0x28;
const _REG_FIFO_CTRL_A  = 0x2E;
const _REG_FIFO_SRC_A   = 0x2F;
const _REG_INT1_CFG_A   = 0x30;
const _REG_INT1_SRC_A   = 0x31;
const _REG_INT1_THS_A   = 0x32;
const _REG_INT1_DUR_A   = 0x33;
const _REG_INT2_CFG_A   = 0x34;
const _REG_INT2_SRC_A   = 0x35;
const _REG_INT2_THS_A   = 0x36;
const _REG_INT2_DUR_A   = 0x37;
const _REG_CLICK_CFG_A  = 0x38;
const _REG_CLICK_SRC_A  = 0x39;
const _REG_CLICK_THS_A  = 0x3A;
const _REG_TIME_LIMIT_A = 0x3B;
const _REG_TIME_LAT_A   = 0x3C;
const _REG_TIME_WIN_A   = 0x3D;

// Magnetometer register map (address pointer auto-increments; no auto-inc bit).
const _REG_CRA_M     = 0x00;
const _REG_CRB_M     = 0x01;
const _REG_MR_M      = 0x02;
const _REG_OUT_X_H_M = 0x03;
const _REG_SR_M      = 0x09;
const _REG_IRA_M     = 0x0A;
const _REG_TEMP_H_M  = 0x31;

// Magnetometer identity: IRA/IRB/IRC = ASCII "H43"; identical to HMC5883L.
const _MAG_ID = [0x48, 0x34, 0x33];

// Valid accelerometer output data rates in Hz (CTRL_REG1_A ODR bits 7:4).
const _ACCEL_ODR_CODES = [
    [1, 0x1],
    [10, 0x2],
    [25, 0x3],
    [50, 0x4],
    [100, 0x5],
    [200, 0x6],
    [400, 0x7],
    [1620, 0x8],
    [1344, 0x9],
    [5376, 0x9],
];

// Sensitivity in mg/LSB, indexed [mode][rangeBits]; mode 0 = low-power (8-bit),
// 1 = normal (10-bit), 2 = high-resolution (12-bit); range 0..3 = ±2/4/8/16 g.
const _ACCEL_SENSITIVITY_MG = [
    [16.0, 32.0, 64.0, 192.0],
    [4.0, 8.0, 16.0, 48.0],
    [1.0, 2.0, 4.0, 12.0],
];

// Arithmetic shift per mode: 8-bit low-power → 8, 10-bit normal → 6, 12-bit HR → 4.
const _ACCEL_SHIFT = [8, 6, 4];

// Magnetometer gain codes GN<2:0> 1..7: full scale in µT and LSB/gauss for X/Y and Z.
const _MAG_RANGES = [
    [130, 1100.0, 980.0],
    [190, 855.0, 760.0],
    [250, 670.0, 600.0],
    [400, 450.0, 400.0],
    [470, 400.0, 355.0],
    [560, 330.0, 295.0],
    [810, 230.0, 205.0],
];

// Valid magnetometer output data rates in Hz (CRA_REG_M DO bits 4:2).
const _MAG_ODR_CODES = [
    [0.75, 0x0],
    [1.5, 0x1],
    [3.0, 0x2],
    [7.5, 0x3],
    [15.0, 0x4],
    [30.0, 0x5],
    [75.0, 0x6],
    [220.0, 0x7],
];

// Sign-extend a 16-bit two's-complement value to a JS number.
function _toSigned16(value) {
    return ((value & 0xFFFF) << 16) >> 16;
}

function _delayMs(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * LSM303DLHC — 3-axis accelerometer + 3-axis magnetometer e-compass
 * (STMicroelectronics) — minimal interface.
 *
 * System-in-package combining a three-axis MEMS accelerometer (I²C address
 * 0x19) and a three-axis magnetometer (I²C address 0x1E) behind two
 * independent I²C slave addresses on one bus. The driver treats the module as
 * one logical device with two connections; the compass heading is computed in
 * software from both sensors.
 *
 * Default configuration (written at construction):
 * - Accelerometer: ±2 g (1 mg/LSB), 100 Hz ODR, high-resolution mode,
 *   all axes enabled, BDU=1, high-pass filter bypassed, FIFO bypassed,
 *   all interrupt routing cleared
 * - Magnetometer: ±1.3 gauss (±130 µT), 15 Hz ODR, continuous conversion,
 *   temperature sensor off
 *
 * The two slaves use different I²C framing: the accelerometer sub-address
 * bit 7 enables address auto-increment (the driver sets it for every
 * multi-byte transfer); the magnetometer has an address pointer that always
 * auto-increments (wrapping from 0x08 back to 0x03 and from 0x0C to 0x00) and
 * needs no extra bit. Magnetometer outputs are X, Z, Y with the high byte
 * first — the driver reorders to X, Y, Z. The accelerometer outputs are X, Y,
 * Z low byte first.
 *
 * Constructor caveat: JS constructors cannot be async, but the canonical
 * (Python) constructor validates the magnetometer identity and throws
 * synchronously on mismatch — that guarantee cannot be preserved exactly. The
 * whole init sequence is fired off unawaited; a wrong or absent device now
 * surfaces as an *unhandled promise rejection* shortly after construction
 * rather than a synchronous throw from `new LSM303Minimal(...)`. Callers that
 * need to detect "wrong or absent device" deterministically should call an
 * async method (e.g. `await lsm.readAcceleration()`) right after construction
 * and handle its rejection.
 * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Register connection bound to the accelerometer slave address 0x19.
 * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Register connection bound to the magnetometer slave address 0x1E, on the same bus.
 */
class LSM303Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Register connection bound to the accelerometer slave address 0x19.
     * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Register connection bound to the magnetometer slave address 0x1E, on the same bus.
     */
    constructor(accelConnection, magConnection) {
        this._accel = accelConnection;
        this._mag = magConnection;
        this._rangeBits = 0;        // 0..3: ±2/4/8/16 g
        this._mode = 2;             // 0 low-power, 1 normal, 2 high-resolution
        this._odrHz = 100.0;        // accelerometer output data rate
        this._magGn = 0;            // 0..6 index into _MAG_RANGES
        this._magOdrHz = 15.0;
        this._tempEnabled = false;
        this._init();
    }

    /**
     * Run the Initialization Sequence: identity check, known-state control
     * registers, ±2 g / 100 Hz / high-resolution accelerometer start, and
     * 15 Hz / ±1.3 gauss / continuous-conversion magnetometer start.
     * @returns {Promise<void>}
     * @throws {Error} If the magnetometer identity registers do not read 0x48 0x34 0x33.
     */
    async _init() {
        // Boot download of the factory trim values (≥ 5 ms, as for sibling parts).
        _delayMs(5);

        const identity = await this._mag.readReg(_REG_IRA_M, 3);
        if (identity[0] !== _MAG_ID[0] || identity[1] !== _MAG_ID[1] || identity[2] !== _MAG_ID[2]) {
            const got = Array.from(identity).map(v => v.toString(16).padStart(2, '0')).join('');
            throw new Error('LSM303 magnetometer ID: expected 0x483433, got 0x' + got.toUpperCase());
        }

        // Accelerometer: no soft reset — write the control registers to known values.
        await this._accel.writeReg(_REG_CTRL2_A, 0x00);
        await this._accel.writeReg(_REG_CTRL3_A, 0x00);
        await this._accel.writeReg(_REG_CTRL5_A, 0x00);
        await this._accel.writeReg(_REG_CTRL6_A, 0x00);
        await this._accel.writeReg(_REG_FIFO_CTRL_A, 0x00);
        // BDU=1, FS=00 (±2 g), HR=1 (high-resolution).
        await this._accel.writeReg(_REG_CTRL4_A, 0x88);
        // ODR=0101 (100 Hz), LPen=0, all axes enabled — leaves power-down.
        await this._accel.writeReg(_REG_CTRL1_A, 0x57);

        // Magnetometer: the part resets into sleep mode, so write all three
        // control registers explicitly (15 Hz, ±1.3 gauss, continuous).
        await this._mag.writeReg(_REG_CRA_M, 0x10);
        await this._mag.writeReg(_REG_CRB_M, 0x20);
        await this._mag.writeReg(_REG_MR_M, 0x00);

        // Turn-on waits: 7/ODR (70 ms at 100 Hz) for the accelerometer and one
        // output period (67 ms at 15 Hz) for the first magnetometer sample.
        _delayMs(70);
        _delayMs(67);
    }

    /**
     * Sensitivity in g/LSB at the current mode and range.
     * @returns {number} Sensitivity in g/LSB.
     */
    _accelSensitivityG() {
        return _ACCEL_SENSITIVITY_MG[this._mode][this._rangeBits] / 1000.0;
    }

    /**
     * Convert a 6-byte accelerometer burst to [x, y, z] signed counts.
     * @param {Buffer|number[]} raw - Six bytes, X/Y/Z low byte first, left-justified 16-bit.
     * @returns {number[]} Signed counts right-aligned per the current mode.
     */
    _convertAccelSample(raw) {
        const rx = _toSigned16(raw[0] | (raw[1] << 8));
        const ry = _toSigned16(raw[2] | (raw[3] << 8));
        const rz = _toSigned16(raw[4] | (raw[5] << 8));
        const shift = _ACCEL_SHIFT[this._mode];
        return [rx >> shift, ry >> shift, rz >> shift];
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads OUT_X_L_A..OUT_Z_H_A with the auto-increment bit set so all
     * six bytes belong to one sample (BDU=1).
     * @returns {Promise<number[]>} [x, y, z] acceleration in *g*.
     */
    async readAcceleration() {
        const raw = await this._accel.readReg(_REG_OUT_X_L_A | 0x80, 6);
        const [cx, cy, cz] = this._convertAccelSample(raw);
        const scale = this._accelSensitivityG();
        return [cx * scale, cy * scale, cz * scale];
    }

    /**
     * Read 3-axis magnetic flux density.
     *
     * Burst-reads the six output registers 0x03..0x08 (on the wire: X, Z, Y,
     * each high byte first) in one transaction and reorders to X, Y, Z.
     * @returns {Promise<number[]>} [x, y, z] magnetic flux density in µT.
     */
    async readMagnetic() {
        const raw = await this._mag.readReg(_REG_OUT_X_H_M, 6);
        const mx = _toSigned16((raw[0] << 8) | raw[1]);
        const mz = _toSigned16((raw[2] << 8) | raw[3]);
        const my = _toSigned16((raw[4] << 8) | raw[5]);
        const gainXy = _MAG_RANGES[this._magGn][1];
        const gainZ = _MAG_RANGES[this._magGn][2];
        return [mx / gainXy * 100.0, my / gainXy * 100.0, mz / gainZ * 100.0];
    }
}

/**
 * LSM303 full interface — extends LSM303Minimal with complete functionality.
 *
 * Adds accelerometer range / data-rate / power-mode configuration, per-axis
 * enable, raw data access, the high-pass filter, the 32-level FIFO, two
 * inertial interrupt generators with free-fall and 6D/4D orientation,
 * single/double click detection, interrupt routing across INT1/INT2, the
 * magnetometer gain / data-rate / operating-mode controls, the relative
 * temperature sensor, the software compass heading, and memory reboot.
 * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Register connection bound to the accelerometer slave address 0x19.
 * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Register connection bound to the magnetometer slave address 0x1E, on the same bus.
 */
class LSM303Full extends LSM303Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} accelConnection - Register connection bound to the accelerometer slave address 0x19.
     * @param {import('../../connection/register_connection').RegisterConnection} magConnection - Register connection bound to the magnetometer slave address 0x1E, on the same bus.
     */
    constructor(accelConnection, magConnection) {
        super(accelConnection, magConnection);
        this._intCallbacks = { 1: null, 2: null };
        this._intSources = new Set();
        this._intHandler = null;
        this._pollTimer = null;
        this._dispatchBusy = false;
    }

    // ------------------------------------------------------------------
    // Accelerometer configuration
    // ------------------------------------------------------------------

    /**
     * Set the accelerometer full-scale range.
     * @param {number} rangeG - One of 2, 4, 8, 16 (*g*). Updates the cached sensitivity.
     * @returns {Promise<void>}
     * @throws {Error} If rangeG is not one of 2, 4, 8, 16.
     */
    async setRange(rangeG) {
        const codes = { 2: 0, 4: 1, 8: 2, 16: 3 };
        if (!(rangeG in codes)) {
            throw new Error('rangeG must be one of 2, 4, 8, 16');
        }
        const code = codes[rangeG];
        this._rangeBits = code;
        const ctrl4 = (await this._accel.readReg(_REG_CTRL4_A, 1))[0];
        await this._accel.writeReg(_REG_CTRL4_A, (ctrl4 & 0xCF) | (code << 4));
    }

    /**
     * Set the accelerometer output data rate.
     * @param {number} rateHz - One of 0 (power-down), 1, 10, 25, 50, 100, 200,
     *     400, 1344, 1620 or 5376 Hz. 1620 Hz and 5376 Hz exist in low-power
     *     mode only, 1344 Hz in normal / high-resolution mode.
     * @returns {Promise<void>}
     * @throws {Error} If the rate is not allowed in the current mode.
     */
    async setDataRate(rateHz) {
        let code = null;
        for (const [rate, c] of _ACCEL_ODR_CODES) {
            if (rate === rateHz) { code = c; break; }
        }
        if (code === null) {
            throw new Error('rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376');
        }
        if (code === 0x8 && this._mode !== 0) {
            throw new Error('1620 Hz requires low-power mode (setMode(0))');
        }
        if (code === 0x9 && rateHz === 5376 && this._mode !== 0) {
            throw new Error('5376 Hz requires low-power mode (setMode(0))');
        }
        if (code === 0x9 && rateHz === 1344 && this._mode === 0) {
            throw new Error('1344 Hz requires normal or high-resolution mode');
        }
        const ctrl1 = (await this._accel.readReg(_REG_CTRL1_A, 1))[0];
        await this._accel.writeReg(_REG_CTRL1_A, (ctrl1 & 0x0F) | (code << 4));
        this._odrHz = rateHz;
    }

    /**
     * Set the accelerometer operating mode (resolution).
     * @param {number} mode - 0 = low-power (8-bit), 1 = normal (10-bit),
     *     2 = high-resolution (12-bit). Updates the cached sensitivity.
     * @returns {Promise<void>}
     * @throws {Error} If mode is not 0, 1 or 2.
     */
    async setMode(mode) {
        if (mode !== 0 && mode !== 1 && mode !== 2) {
            throw new Error('mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)');
        }
        let ctrl1 = (await this._accel.readReg(_REG_CTRL1_A, 1))[0];
        let ctrl4 = (await this._accel.readReg(_REG_CTRL4_A, 1))[0];
        if (mode === 0) {
            ctrl1 |= 0x08;              // LPen = 1
            ctrl4 &= ~0x08 & 0xFF;      // HR = 0
        } else if (mode === 1) {
            ctrl1 &= ~0x08 & 0xFF;
            ctrl4 &= ~0x08 & 0xFF;
        } else {
            ctrl1 &= ~0x08 & 0xFF;
            ctrl4 |= 0x08;
        }
        await this._accel.writeReg(_REG_CTRL1_A, ctrl1);
        await this._accel.writeReg(_REG_CTRL4_A, ctrl4);
        this._mode = mode;
    }

    /**
     * Enable or disable the accelerometer axes.
     * @param {boolean} [x=true] - Enable the X axis.
     * @param {boolean} [y=true] - Enable the Y axis.
     * @param {boolean} [z=true] - Enable the Z axis.
     * @returns {Promise<void>}
     */
    async setAxes(x = true, y = true, z = true) {
        const ctrl1 = (await this._accel.readReg(_REG_CTRL1_A, 1))[0];
        const axes = ((z ? 1 : 0) << 2) | ((y ? 1 : 0) << 1) | (x ? 1 : 0);
        await this._accel.writeReg(_REG_CTRL1_A, (ctrl1 & 0xF8) | axes);
    }

    /**
     * Enter power-down (ODR = 0000). Register contents are retained.
     * @returns {Promise<void>}
     */
    async powerDown() {
        const ctrl1 = (await this._accel.readReg(_REG_CTRL1_A, 1))[0];
        await this._accel.writeReg(_REG_CTRL1_A, ctrl1 & 0x0F);
        this._odrHz = 0.0;
    }

    /**
     * Read raw accelerometer counts at the current resolution.
     * @returns {Promise<number[]>} [x, y, z] signed counts (12/10/8-bit right-aligned per mode).
     */
    async readAccelerationRaw() {
        const raw = await this._accel.readReg(_REG_OUT_X_L_A | 0x80, 6);
        return this._convertAccelSample(raw);
    }

    /**
     * Check whether a new accelerometer sample is available.
     * @returns {Promise<boolean>} True when STATUS_REG_A.ZYXDA is set.
     */
    async newDataAvailable() {
        return !!((await this._accel.readReg(_REG_STATUS_A, 1))[0] & 0x08);
    }

    /**
     * Read the accelerometer status register.
     * @returns {Promise<number>} STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun, ZYXDA/*DA data-ready).
     */
    async readStatus() {
        return (await this._accel.readReg(_REG_STATUS_A, 1))[0];
    }

    /**
     * Configure the high-pass filter.
     * @param {number} [mode=0] - HPM<1:0> — 0 normal (reset by reading
     *     REFERENCE_A), 1 reference signal for filtering, 2 normal, 3
     *     autoreset on interrupt event.
     * @param {number} [cutoff=0] - HPCF<2:1> raw code 0–3 (no Hz values tabulated for this part).
     * @param {boolean} [filterOutput=false] - True = filtered data goes to the output registers and FIFO.
     * @param {boolean} [onIa1=false] - True = high-pass filter enabled for interrupt generator 1.
     * @param {boolean} [onIa2=false] - True = high-pass filter enabled for interrupt generator 2.
     * @param {boolean} [onClick=false] - True = high-pass filter enabled for click detection.
     * @returns {Promise<void>}
     */
    async setHighPass(mode = 0, cutoff = 0, filterOutput = false, onIa1 = false, onIa2 = false, onClick = false) {
        let ctrl2 = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4);
        if (filterOutput) ctrl2 |= 0x08;
        if (onClick) ctrl2 |= 0x04;
        if (onIa2) ctrl2 |= 0x02;
        if (onIa1) ctrl2 |= 0x01;
        await this._accel.writeReg(_REG_CTRL2_A, ctrl2);
    }

    /**
     * Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.
     * @returns {Promise<number>} The REFERENCE_A register value.
     */
    async resetHighPass() {
        return (await this._accel.readReg(_REG_REFERENCE_A, 1))[0];
    }

    /**
     * Write the high-pass reference register (for HPM = 01).
     * @param {number} value - Reference value 0–255.
     * @returns {Promise<void>}
     */
    async setReference(value) {
        await this._accel.writeReg(_REG_REFERENCE_A, value & 0xFF);
    }

    // ------------------------------------------------------------------
    // FIFO
    // ------------------------------------------------------------------

    /**
     * Configure the 32-level FIFO.
     * @param {number} mode - 0 bypass, 1 FIFO, 2 stream, 3 trigger
     *     (stream-to-FIFO). Returning to bypass clears the buffer.
     * @param {number} [watermark=0] - Watermark level 0–31.
     * @param {boolean} [triggerInt2=false] - Trigger mode source — false = INT1, true = INT2.
     * @returns {Promise<void>}
     */
    async setFifo(mode, watermark = 0, triggerInt2 = false) {
        const fifoCtrl = ((mode & 0x03) << 6) | (triggerInt2 ? 0x20 : 0x00) | (watermark & 0x1F);
        await this._accel.writeReg(_REG_FIFO_CTRL_A, fifoCtrl);
        let ctrl5 = (await this._accel.readReg(_REG_CTRL5_A, 1))[0];
        if (mode === 1 || mode === 2 || mode === 3) {
            ctrl5 |= 0x40;
        } else {
            ctrl5 &= ~0x40 & 0xFF;
        }
        await this._accel.writeReg(_REG_CTRL5_A, ctrl5);
    }

    /**
     * Return the number of unread sample sets in the FIFO (0–32).
     * @returns {Promise<number>} FSS<4:0> from FIFO_SRC_REG_A.
     */
    async fifoLevel() {
        return (await this._accel.readReg(_REG_FIFO_SRC_A, 1))[0] & 0x1F;
    }

    /**
     * Drain up to `count` sample sets from the FIFO.
     *
     * Each sample is popped with one 6-byte burst read of OUT_X_L_A..OUT_Z_H_A.
     * @param {number|null} [count=null] - Maximum number of sample sets to
     *     read (default: all unread samples reported by FSS).
     * @returns {Promise<number[][]>} List of [x, y, z] samples in *g*.
     */
    async readFifo(count = null) {
        if (count === null) {
            count = await this.fifoLevel();
        }
        const out = [];
        const scale = this._accelSensitivityG();
        for (let i = 0; i < count; i++) {
            const raw = await this._accel.readReg(_REG_OUT_X_L_A | 0x80, 6);
            const [cx, cy, cz] = this._convertAccelSample(raw);
            out.push([cx * scale, cy * scale, cz * scale]);
        }
        return out;
    }

    /**
     * Read the decoded FIFO status.
     * @returns {Promise<number[]>} [watermark, overrun, empty, level] — the
     *     first three are booleans, `level` is the FSS<4:0> sample count.
     */
    async readFifoStatus() {
        const src = (await this._accel.readReg(_REG_FIFO_SRC_A, 1))[0];
        return [!!(src & 0x80), !!(src & 0x40), !!(src & 0x20), src & 0x1F];
    }

    // ------------------------------------------------------------------
    // Inertial interrupt generators and click detection
    // ------------------------------------------------------------------

    /**
     * Register quadruple [CFG, SRC, THS, DUR] for generator 1 or 2.
     * @param {number} generator - 1 or 2.
     * @returns {number[]} [cfg, src, ths, dur] register addresses.
     */
    _intRegs(generator) {
        if (generator === 1) {
            return [_REG_INT1_CFG_A, _REG_INT1_SRC_A, _REG_INT1_THS_A, _REG_INT1_DUR_A];
        }
        if (generator === 2) {
            return [_REG_INT2_CFG_A, _REG_INT2_SRC_A, _REG_INT2_THS_A, _REG_INT2_DUR_A];
        }
        throw new Error('generator must be 1 or 2');
    }

    /**
     * Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code.
     * @param {number} thresholdG - Threshold in *g*.
     * @returns {number} 7-bit register code, clamped 0..127.
     */
    _encodeThreshold(thresholdG) {
        const fullScaleG = { 0: 2, 1: 4, 2: 8, 3: 16 }[this._rangeBits];
        let code = Math.round(thresholdG * 128.0 / fullScaleG);
        if (code > 127) code = 127;
        if (code < 0) code = 0;
        return code;
    }

    /**
     * Convert a duration in ms to the 1/ODR code, clamped to the width.
     * @param {number} durationMs - Duration in ms.
     * @param {number} [maxBits=7] - Register width in bits (7 or 8).
     * @returns {number} Register code, clamped 0..(2^maxBits - 1).
     */
    _encodeDuration(durationMs, maxBits = 7) {
        let code = Math.round(durationMs * this._odrHz / 1000.0);
        const limit = (1 << maxBits) - 1;
        if (code > limit) code = limit;
        if (code < 0) code = 0;
        return code;
    }

    /**
     * Configure one inertial interrupt generator.
     * @param {number} generator - 1 or 2 (INT1 generator / INT2 generator).
     * @param {number} events - 6-bit event mask (bit 5 = Z high, 4 = Z low,
     *     3 = Y high, 2 = Y low, 1 = X high, 0 = X low; the same bits select
     *     the direction zones in 6D modes).
     * @param {number} thresholdG - Event threshold in *g* (1 LSb = full-scale / 128).
     * @param {number} [durationMs=0] - Minimum event duration in ms (1 LSb = 1/ODR).
     * @param {boolean} [andMode=false] - True = AND combination of the enabled events, False = OR.
     * @returns {Promise<void>}
     * @throws {Error} If generator is not 1 or 2.
     */
    async setMotionInterrupt(generator, events, thresholdG, durationMs = 0, andMode = false) {
        const [cfg, , ths, dur] = this._intRegs(generator);
        await this._accel.writeReg(cfg, (andMode ? 0x80 : 0x00) | (events & 0x3F));
        await this._accel.writeReg(ths, this._encodeThreshold(thresholdG));
        await this._accel.writeReg(dur, this._encodeDuration(durationMs));
    }

    /**
     * Configure free-fall detection on one generator.
     *
     * All three axes' low events, AND-combined (INTx_CFG_A = 0x95).
     * @param {number} [generator=1] - 1 (default) or 2.
     * @param {number} [thresholdG=0.35] - Free-fall threshold in *g*.
     * @param {number} [durationMs=30] - Minimum free-fall duration in ms.
     * @returns {Promise<void>}
     */
    async setFreeFall(generator = 1, thresholdG = 0.35, durationMs = 30) {
        await this.setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
    }

    /**
     * Configure 6D / 4D orientation recognition on one generator.
     * @param {number} [generator=1] - 1 (default) or 2.
     * @param {boolean} [position=false] - False = 6D movement recognition
     *     (one-ODR pulse when the orientation moves into a known zone), true =
     *     6D position recognition (stays active inside a known zone).
     * @param {boolean} [fourD=false] - True = 4D detection (Z-axis zone disabled; needs the 6D bit).
     * @returns {Promise<void>}
     * @throws {Error} If generator is not 1 or 2.
     */
    async setOrientation(generator = 1, position = false, fourD = false) {
        const [cfg] = this._intRegs(generator);
        let value = 0x40 | 0x3F;    // 6D + all six direction zones
        if (position) {
            value |= 0x80;
        }
        await this._accel.writeReg(cfg, value);
        let ctrl5 = (await this._accel.readReg(_REG_CTRL5_A, 1))[0];
        const d4dBit = (generator === 1) ? 0x04 : 0x01;
        if (fourD) {
            ctrl5 |= d4dBit;
        } else {
            ctrl5 &= ~d4dBit & 0xFF;
        }
        await this._accel.writeReg(_REG_CTRL5_A, ctrl5);
    }

    /**
     * Latch (or unlatch) one generator's interrupt until its source is read.
     * @param {number} generator - 1 or 2.
     * @param {boolean} enabled - True = latch INT output until INTx_SOURCE_A is read.
     * @returns {Promise<void>}
     * @throws {Error} If generator is not 1 or 2.
     */
    async setLatch(generator, enabled) {
        this._intRegs(generator);   // validates generator 1..2
        let ctrl5 = (await this._accel.readReg(_REG_CTRL5_A, 1))[0];
        const lirBit = (generator === 1) ? 0x08 : 0x02;
        if (enabled) {
            ctrl5 |= lirBit;
        } else {
            ctrl5 &= ~lirBit & 0xFF;
        }
        await this._accel.writeReg(_REG_CTRL5_A, ctrl5);
    }

    /**
     * Read one generator's source register — clears the interrupt.
     * @param {number} generator - 1 or 2.
     * @returns {Promise<number>} INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags).
     * @throws {Error} If generator is not 1 or 2.
     */
    async readInterruptSource(generator) {
        const [, src] = this._intRegs(generator);
        return (await this._accel.readReg(src, 1))[0];
    }

    /**
     * Configure single / double click detection.
     * @param {number} [single=0] - Axis mask for single click (X=1, Y=2, Z=4; 0 = off).
     * @param {number} [double=0] - Axis mask for double click (X=1, Y=2, Z=4; 0 = off).
     * @param {number} [thresholdG=0.5] - Click threshold in *g* (1 LSb = full-scale / 128).
     * @param {number} [timeLimitMs=10] - Maximum click duration in ms (1 LSb = 1/ODR, 7-bit).
     * @param {number} [latencyMs=20] - Time after a click in which the click
     *     interrupt is not re-armed (1 LSb = 1/ODR, 8-bit).
     * @param {number} [windowMs=50] - Time window for a second click (1 LSb = 1/ODR, 8-bit).
     * @returns {Promise<void>}
     * @throws {Error} If single or double is not an axis mask 0..7.
     */
    async setClick(single = 0, double = 0, thresholdG = 0.5, timeLimitMs = 10, latencyMs = 20, windowMs = 50) {
        if (!(single >= 0 && single <= 7) || !(double >= 0 && double <= 7)) {
            throw new Error('single / double are axis masks: X=1, Y=2, Z=4');
        }
        // Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits: XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
        const singleCode = ((single & 1) << 1) | ((single & 2) << 2) | ((single & 4) << 3);
        const doubleCode = ((double & 1) << 0) | ((double & 2) << 1) | ((double & 4) << 2);
        await this._accel.writeReg(_REG_CLICK_CFG_A, singleCode | doubleCode);
        await this._accel.writeReg(_REG_CLICK_THS_A, this._encodeThreshold(thresholdG));
        await this._accel.writeReg(_REG_TIME_LIMIT_A, this._encodeDuration(timeLimitMs));
        await this._accel.writeReg(_REG_TIME_LAT_A, this._encodeDuration(latencyMs, 8));
        await this._accel.writeReg(_REG_TIME_WIN_A, this._encodeDuration(windowMs, 8));
    }

    /**
     * Read the click source register — clears the click interrupt.
     * @returns {Promise<number>} CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK,
     *     bit 4 = SCLICK, bit 3 = sign, bits 2:0 = Z/Y/X event axis).
     */
    async readClickSource() {
        return (await this._accel.readReg(_REG_CLICK_SRC_A, 1))[0];
    }

    /**
     * Set the INT1 / INT2 output polarity.
     * @param {boolean} activeLow - True = INT pins active-low, false = active-high (default).
     * @returns {Promise<void>}
     */
    async setInterruptPolarity(activeLow) {
        let ctrl6 = (await this._accel.readReg(_REG_CTRL6_A, 1))[0];
        if (activeLow) {
            ctrl6 |= 0x02;
        } else {
            ctrl6 &= ~0x02 & 0xFF;
        }
        await this._accel.writeReg(_REG_CTRL6_A, ctrl6);
    }

    // ------------------------------------------------------------------
    // Magnetometer configuration
    // ------------------------------------------------------------------

    /**
     * Set the magnetometer full-scale range (gain).
     * @param {number} rangeUt - One of 130, 190, 250, 400, 470, 560, 810 (µT).
     *     Updates the cached conversion gains.
     * @returns {Promise<void>}
     * @throws {Error} If rangeUt is not one of the listed values.
     */
    async setMagRange(rangeUt) {
        const codes = { 130: 0, 190: 1, 250: 2, 400: 3, 470: 4, 560: 5, 810: 6 };
        if (!(rangeUt in codes)) {
            throw new Error('rangeUt must be one of 130, 190, 250, 400, 470, 560, 810');
        }
        const index = codes[rangeUt];
        this._magGn = index;
        await this._mag.writeReg(_REG_CRB_M, (index + 1) << 5);
    }

    /**
     * Set the magnetometer output data rate.
     * @param {number} rateHz - One of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz.
     *     Preserves the TEMP_EN setting.
     * @returns {Promise<void>}
     * @throws {Error} If rateHz is not one of the listed values.
     */
    async setMagDataRate(rateHz) {
        let code = null;
        for (const [rate, c] of _MAG_ODR_CODES) {
            if (rate === rateHz) { code = c; break; }
        }
        if (code === null) {
            throw new Error('rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220');
        }
        const cra = (await this._mag.readReg(_REG_CRA_M, 1))[0];
        await this._mag.writeReg(_REG_CRA_M, (cra & 0x80) | (code << 2));
        this._magOdrHz = rateHz;
    }

    /**
     * Set the magnetometer operating mode.
     * @param {number} mode - 0 continuous conversion, 1 single conversion
     *     (one conversion, then back to sleep), 2 sleep.
     * @returns {Promise<void>}
     * @throws {Error} If mode is not 0, 1 or 2.
     */
    async setMagMode(mode) {
        if (mode !== 0 && mode !== 1 && mode !== 2) {
            throw new Error('mode must be 0 (continuous), 1 (single) or 2 (sleep)');
        }
        await this._mag.writeReg(_REG_MR_M, mode);
    }

    /**
     * Read raw magnetometer counts (X, Y, Z order, after reordering).
     * @returns {Promise<number[]>} [x, y, z] signed 12-bit counts.
     */
    async readMagneticRaw() {
        const raw = await this._mag.readReg(_REG_OUT_X_H_M, 6);
        const mx = _toSigned16((raw[0] << 8) | raw[1]);
        const mz = _toSigned16((raw[2] << 8) | raw[3]);
        const my = _toSigned16((raw[4] << 8) | raw[5]);
        return [mx, my, mz];
    }

    /**
     * Check whether a new magnetometer sample is available.
     * @returns {Promise<boolean>} True when SR_REG_M.DRDY is set.
     */
    async magDataReady() {
        return !!((await this._mag.readReg(_REG_SR_M, 1))[0] & 0x01);
    }

    /**
     * Read the decoded magnetometer status register.
     * @returns {Promise<number[]>} [drdy, lock] — DRDY = new set of
     *     measurements available, LOCK = output registers locked mid-read.
     */
    async readMagStatus() {
        const sr = (await this._mag.readReg(_REG_SR_M, 1))[0];
        return [!!(sr & 0x01), !!(sr & 0x02)];
    }

    /**
     * Read the magnetometer identification registers.
     * @returns {Promise<Buffer>} IRA/IRB/IRC (0x48 0x34 0x33 — identical to HMC5883L).
     */
    async readMagIdentity() {
        return Buffer.from(await this._mag.readReg(_REG_IRA_M, 3));
    }

    /**
     * Enable or disable the relative temperature sensor.
     * @param {boolean} enabled - True = TEMP_EN set (updates at the magnetometer data rate).
     * @returns {Promise<void>}
     */
    async enableTemperature(enabled) {
        const cra = (await this._mag.readReg(_REG_CRA_M, 1))[0];
        let out;
        if (enabled) {
            out = cra | 0x80;
        } else {
            out = cra & ~0x80 & 0xFF;
        }
        await this._mag.writeReg(_REG_CRA_M, out);
        this._tempEnabled = enabled;
    }

    /**
     * Read the relative temperature.
     *
     * The sensor is relative only — the datasheet gives no zero-degree
     * reference — so the value is a delta, never an absolute °C. Enables
     * TEMP_EN on first use (read-modify-write).
     * @returns {Promise<number>} Relative temperature in °C (8 LSB/°C).
     */
    async readTemperatureDelta() {
        if (!this._tempEnabled) {
            await this.enableTemperature(true);
        }
        const raw = await this._mag.readReg(_REG_TEMP_H_M, 2);
        const value = _toSigned16((raw[0] << 8) | raw[1]) >> 4;
        return value / 8.0;
    }

    // ------------------------------------------------------------------
    // Software compass
    // ------------------------------------------------------------------

    /**
     * Compute the compass heading from both sensors.
     *
     * Uses the `atan2(y, x)` convention, wrapped to 0–360°. The datasheet
     * documents neither the axis alignment between the two dies nor a
     * declination, so the result assumes both sensors report in the same body
     * frame and is magnetic (not true) north.
     * @param {boolean} [tiltCompensate=true] - True (default) = project the
     *     magnetic field onto the horizontal plane using the current
     *     acceleration; false = flat compass from the magnetic X/Y components only.
     * @returns {Promise<number>} Heading in degrees, 0–360.
     */
    async heading(tiltCompensate = true) {
        const [mx, my, mz] = await this.readMagnetic();
        let angle;
        if (!tiltCompensate) {
            angle = Math.atan2(my, mx) * 180.0 / Math.PI;
        } else {
            let [ax, ay, az] = await this.readAcceleration();
            let norm = Math.sqrt(ax * ax + ay * ay + az * az);
            if (norm === 0.0) {
                norm = 1.0;
            }
            ax /= norm; ay /= norm; az /= norm;
            const roll = Math.atan2(ay, az);
            const pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll));
            const xh = mx * Math.cos(pitch) + mz * Math.sin(pitch);
            const yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) -
                       mz * Math.sin(roll) * Math.cos(pitch);
            angle = Math.atan2(yh, xh) * 180.0 / Math.PI;
        }
        return angle >= 0.0 ? angle : angle + 360.0;
    }

    // ------------------------------------------------------------------
    // Interrupts (Level 3: INT1 / INT2 routing)
    // ------------------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2.
     *
     * Only routes the source; the generator or function behind it is
     * configured with the `setMotionInterrupt` / `setClick` / ... methods.
     * `pin` is ignored for INT1-only / INT2-only sources.
     * `SOURCE_MAG_DATA_READY` is rejected (it is always on the dedicated
     * magnetometer DRDY pin and is not routable).
     * @param {number} source - One of the SOURCE_* constants.
     * @param {number} [pin=1] - 1 (default) or 2.
     * @returns {Promise<void>}
     * @throws {Error} If the source cannot be routed to the given pin.
     */
    async enableInterrupt(source, pin = 1) {
        if (source === LSM303Full.SOURCE_MAG_DATA_READY) {
            throw new Error('SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable');
        }
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        let ctrl3 = (await this._accel.readReg(_REG_CTRL3_A, 1))[0];
        let ctrl6 = (await this._accel.readReg(_REG_CTRL6_A, 1))[0];
        if (source === LSM303Full.SOURCE_DATA_READY) {
            ctrl3 |= LSM303Full._I1_DRDY1;
        } else if (source === LSM303Full.SOURCE_WATERMARK) {
            ctrl3 |= LSM303Full._I1_WTM;
        } else if (source === LSM303Full.SOURCE_OVERRUN) {
            ctrl3 |= LSM303Full._I1_OVERRUN;
        } else if (source === LSM303Full.SOURCE_IA1) {
            if (pin === 1) {
                ctrl3 |= LSM303Full._I1_AOI1;
            } else {
                ctrl6 |= LSM303Full._I2_INT1;
            }
        } else if (source === LSM303Full.SOURCE_IA2) {
            if (pin === 1) {
                ctrl3 |= LSM303Full._I1_AOI2;
            } else {
                ctrl6 |= LSM303Full._I2_INT2;
            }
        } else if (source === LSM303Full.SOURCE_CLICK) {
            if (pin === 1) {
                ctrl3 |= LSM303Full._I1_CLICK;
            } else {
                ctrl6 |= LSM303Full._I2_CLICK;
            }
        } else if (source === LSM303Full.SOURCE_BOOT) {
            if (pin !== 2) {
                throw new Error('SOURCE_BOOT is INT2-only');
            }
            ctrl6 |= LSM303Full._BOOT_I1;
        } else {
            throw new Error('unknown interrupt source 0x' + source.toString(16).toUpperCase());
        }
        await this._accel.writeReg(_REG_CTRL3_A, ctrl3);
        await this._accel.writeReg(_REG_CTRL6_A, ctrl6);
        this._intSources.add(source);
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     * @param {number} source - One of the SOURCE_* constants.
     * @param {number} [pin=1] - 1 (default) or 2.
     * @returns {Promise<void>}
     * @throws {Error} If the source cannot be removed from the given pin.
     */
    async disableInterrupt(source, pin = 1) {
        if (source === LSM303Full.SOURCE_MAG_DATA_READY) {
            throw new Error('SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable');
        }
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        let ctrl3 = (await this._accel.readReg(_REG_CTRL3_A, 1))[0];
        let ctrl6 = (await this._accel.readReg(_REG_CTRL6_A, 1))[0];
        if (source === LSM303Full.SOURCE_DATA_READY) {
            ctrl3 &= ~LSM303Full._I1_DRDY1 & 0xFF;
        } else if (source === LSM303Full.SOURCE_WATERMARK) {
            ctrl3 &= ~LSM303Full._I1_WTM & 0xFF;
        } else if (source === LSM303Full.SOURCE_OVERRUN) {
            ctrl3 &= ~LSM303Full._I1_OVERRUN & 0xFF;
        } else if (source === LSM303Full.SOURCE_IA1) {
            if (pin === 1) {
                ctrl3 &= ~LSM303Full._I1_AOI1 & 0xFF;
            } else {
                ctrl6 &= ~LSM303Full._I2_INT1 & 0xFF;
            }
        } else if (source === LSM303Full.SOURCE_IA2) {
            if (pin === 1) {
                ctrl3 &= ~LSM303Full._I1_AOI2 & 0xFF;
            } else {
                ctrl6 &= ~LSM303Full._I2_INT2 & 0xFF;
            }
        } else if (source === LSM303Full.SOURCE_CLICK) {
            if (pin === 1) {
                ctrl3 &= ~LSM303Full._I1_CLICK & 0xFF;
            } else {
                ctrl6 &= ~LSM303Full._I2_CLICK & 0xFF;
            }
        } else if (source === LSM303Full.SOURCE_BOOT) {
            if (pin !== 2) {
                throw new Error('SOURCE_BOOT is INT2-only');
            }
            ctrl6 &= ~LSM303Full._BOOT_I1 & 0xFF;
        } else {
            throw new Error('unknown interrupt source 0x' + source.toString(16).toUpperCase());
        }
        await this._accel.writeReg(_REG_CTRL3_A, ctrl3);
        await this._accel.writeReg(_REG_CTRL6_A, ctrl6);
        this._intSources.delete(source);
    }

    /**
     * Read the source registers of every enabled interrupt source.
     *
     * Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
     * poll acknowledges the INT line.
     * @returns {Promise<number>} Combined status — bit 0 SOURCE_DATA_READY,
     *     bit 1 SOURCE_WATERMARK, bit 2 SOURCE_OVERRUN, bit 3 SOURCE_IA1,
     *     bit 4 SOURCE_IA2, bit 5 SOURCE_CLICK, bit 7 SOURCE_MAG_DATA_READY.
     *     SOURCE_BOOT is pin-only and never reported.
     */
    async pollInterrupt() {
        let status = 0;
        if (this._intSources.has(LSM303Full.SOURCE_DATA_READY)) {
            if ((await this._accel.readReg(_REG_STATUS_A, 1))[0] & 0x08) {
                status |= LSM303Full.SOURCE_DATA_READY;
            }
        }
        if (this._intSources.has(LSM303Full.SOURCE_WATERMARK) || this._intSources.has(LSM303Full.SOURCE_OVERRUN)) {
            const fifoSrc = (await this._accel.readReg(_REG_FIFO_SRC_A, 1))[0];
            if (fifoSrc & 0x80) {
                status |= LSM303Full.SOURCE_WATERMARK;
            }
            if (fifoSrc & 0x40) {
                status |= LSM303Full.SOURCE_OVERRUN;
            }
        }
        if (this._intSources.has(LSM303Full.SOURCE_IA1)) {
            if ((await this._accel.readReg(_REG_INT1_SRC_A, 1))[0] & 0x40) {
                status |= LSM303Full.SOURCE_IA1;
            }
        }
        if (this._intSources.has(LSM303Full.SOURCE_IA2)) {
            if ((await this._accel.readReg(_REG_INT2_SRC_A, 1))[0] & 0x40) {
                status |= LSM303Full.SOURCE_IA2;
            }
        }
        if (this._intSources.has(LSM303Full.SOURCE_CLICK)) {
            if ((await this._accel.readReg(_REG_CLICK_SRC_A, 1))[0] & 0x40) {
                status |= LSM303Full.SOURCE_CLICK;
            }
        }
        if (this._mag !== null && this._mag !== undefined) {
            if ((await this._mag.readReg(_REG_SR_M, 1))[0] & 0x01) {
                status |= LSM303Full.SOURCE_MAG_DATA_READY;
            }
        }
        return status;
    }

    /**
     * Subscribe to INT1 or INT2 assertions.
     *
     * Uses the accelerometer connection's `intPin` when wired (both INT lines
     * typically share one edge input); falls back to a 5 ms polling interval
     * otherwise. The callback receives the combined status int from
     * `pollInterrupt()`.
     * @param {function(number): void} callback - Called with the combined status int.
     * @param {number} [pin=1] - 1 (default) or 2.
     * @returns {Promise<void>}
     * @throws {Error} If pin is not 1 or 2.
     */
    async onInterrupt(callback, pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        this._intCallbacks[pin] = callback;
        await this._startIntDelivery();
    }

    /**
     * Unsubscribe from INT1 or INT2 assertions.
     * @param {number} [pin=1] - 1 (default) or 2.
     * @returns {Promise<void>}
     * @throws {Error} If pin is not 1 or 2.
     */
    async offInterrupt(pin = 1) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        this._intCallbacks[pin] = null;
        if (this._intCallbacks[1] === null && this._intCallbacks[2] === null) {
            await this._stopIntDelivery();
        }
    }

    /**
     * Poll the source registers and dispatch to any active callback.
     * @returns {Promise<void>}
     */
    async _dispatchInt() {
        if (this._dispatchBusy) return;
        this._dispatchBusy = true;
        try {
            const callbacks = [this._intCallbacks[1], this._intCallbacks[2]]
                .filter(cb => cb !== null && cb !== undefined);
            if (callbacks.length === 0) {
                return;
            }
            let status;
            try {
                status = await this.pollInterrupt();
            } catch (e) {
                return;
            }
            for (const cb of callbacks) {
                cb(status);
            }
        } finally {
            this._dispatchBusy = false;
        }
    }

    /**
     * Deliver interrupts via the accelerometer connection's INT pin, or a
     * 5 ms polling interval fallback.
     * @returns {Promise<void>}
     */
    async _startIntDelivery() {
        const intPin = this._accel.intPin;
        if (intPin !== null && intPin !== undefined && typeof intPin.onEdge === 'function') {
            if (this._intHandler === null) {
                this._intHandler = () => { this._dispatchInt(); };
                await intPin.onEdge(this._intHandler, 'falling');
            }
            return;
        }
        if (this._pollTimer !== null) {
            return;
        }
        this._pollTimer = setInterval(() => { this._dispatchInt(); }, 5);
    }

    /**
     * Stop edge delivery or the polling fallback interval.
     * @returns {Promise<void>}
     */
    async _stopIntDelivery() {
        const intPin = this._accel.intPin;
        if (intPin !== null && intPin !== undefined && this._intHandler !== null &&
            typeof intPin.offEdge === 'function') {
            await intPin.offEdge(this._intHandler);
        }
        this._intHandler = null;
        if (this._pollTimer !== null) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
    }

    // ------------------------------------------------------------------
    // Reboot
    // ------------------------------------------------------------------

    /**
     * Reboot the accelerometer memory content (BOOT = 1).
     *
     * Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not
     * say whether the control registers survive, so re-run the initialisation
     * (construct a fresh driver) afterwards.
     * @returns {Promise<void>}
     */
    async reboot() {
        const ctrl5 = (await this._accel.readReg(_REG_CTRL5_A, 1))[0];
        await this._accel.writeReg(_REG_CTRL5_A, ctrl5 | 0x80);
        _delayMs(5);
    }
}

/** New accelerometer XYZ sample (STATUS_REG_A.ZYXDA). */
LSM303Full.SOURCE_DATA_READY = 0x0001;
/** FIFO content exceeds the watermark (FIFO_SRC_REG_A.WTM). */
LSM303Full.SOURCE_WATERMARK = 0x0002;
/** FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO). */
LSM303Full.SOURCE_OVERRUN = 0x0004;
/** Inertial generator 1 event (INT1_SOURCE_A.IA). */
LSM303Full.SOURCE_IA1 = 0x0008;
/** Inertial generator 2 event (INT2_SOURCE_A.IA). */
LSM303Full.SOURCE_IA2 = 0x0010;
/** Single / double click (CLICK_SRC_A.IA). */
LSM303Full.SOURCE_CLICK = 0x0020;
/** Boot procedure running (pin-only — pollInterrupt() never reports it). */
LSM303Full.SOURCE_BOOT = 0x0040;
/** New magnetometer sample (SR_REG_M.DRDY; dedicated DRDY pin, not routable). */
LSM303Full.SOURCE_MAG_DATA_READY = 0x0080;

// CTRL_REG3_A routing bits (INT1 side).
LSM303Full._I1_CLICK   = 0x80;
LSM303Full._I1_AOI1    = 0x40;
LSM303Full._I1_AOI2    = 0x20;
LSM303Full._I1_DRDY1   = 0x10;
LSM303Full._I1_WTM     = 0x04;
LSM303Full._I1_OVERRUN = 0x01;

// CTRL_REG6_A routing bits (INT2 side).
LSM303Full._I2_CLICK = 0x80;
LSM303Full._I2_INT1  = 0x40;
LSM303Full._I2_INT2  = 0x20;
LSM303Full._BOOT_I1  = 0x10;

module.exports = { LSM303Minimal, LSM303Full };
