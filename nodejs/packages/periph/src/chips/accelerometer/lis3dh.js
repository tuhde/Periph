'use strict';

const { toSigned } = require('../../connection/register');

// ---------------------------------------------------------------------------
// Register map
// ---------------------------------------------------------------------------
const _REG_STATUS_AUX   = 0x07;
const _REG_OUT_ADC1_L   = 0x08;
const _REG_OUT_ADC1_H   = 0x09;
const _REG_OUT_ADC2_L   = 0x0A;
const _REG_OUT_ADC2_H   = 0x0B;
const _REG_OUT_ADC3_L   = 0x0C;
const _REG_OUT_ADC3_H   = 0x0D;
const _REG_WHO_AM_I     = 0x0F;
const _REG_CTRL_REG0    = 0x1E;
const _REG_TEMP_CFG     = 0x1F;
const _REG_CTRL_REG1    = 0x20;
const _REG_CTRL_REG2    = 0x21;
const _REG_CTRL_REG3    = 0x22;
const _REG_CTRL_REG4    = 0x23;
const _REG_CTRL_REG5    = 0x24;
const _REG_CTRL_REG6    = 0x25;
const _REG_REFERENCE    = 0x26;
const _REG_STATUS       = 0x27;
const _REG_OUT_X_L      = 0x28;
const _REG_OUT_X_H      = 0x29;
const _REG_OUT_Y_L      = 0x2A;
const _REG_OUT_Y_H      = 0x2B;
const _REG_OUT_Z_L      = 0x2C;
const _REG_OUT_Z_H      = 0x2D;
const _REG_FIFO_CTRL    = 0x2E;
const _REG_FIFO_SRC     = 0x2F;
const _REG_INT1_CFG     = 0x30;
const _REG_INT1_SRC     = 0x31;
const _REG_INT1_THS     = 0x32;
const _REG_INT1_DUR     = 0x33;
const _REG_INT2_CFG     = 0x34;
const _REG_INT2_SRC     = 0x35;
const _REG_INT2_THS     = 0x36;
const _REG_INT2_DUR     = 0x37;
const _REG_CLICK_CFG    = 0x38;
const _REG_CLICK_SRC    = 0x39;
const _REG_CLICK_THS    = 0x3A;
const _REG_TIME_LIMIT   = 0x3B;
const _REG_TIME_LATENCY = 0x3C;
const _REG_TIME_WINDOW  = 0x3D;
const _REG_ACT_THS      = 0x3E;
const _REG_ACT_DUR      = 0x3F;

// WHO_AM_I (0x0F) reset value.
const _WHO_AM_I_VALUE = 0x33;

// Control-register defaults of the datasheet Initialization Sequence.
const _CTRL_REG4_DEFAULT = 0x88;   // BDU=1, FS=00 (±2 g), HR=1
const _CTRL_REG1_DEFAULT = 0x57;   // ODR=0101 (100 Hz), LPen=0, X/Y/Z enabled

// Defaults baked into the Minimal driver: ±2 g, 100 Hz, high-resolution.
const _DEFAULT_RANGE_G = 2;
const _DEFAULT_RATE_HZ = 100;
const _DEFAULT_MODE = 2;

// Full-scale ranges in g.
const _RANGES = [2, 4, 8, 16];

// Operating modes: 0 = low-power (8-bit), 1 = normal (10-bit),
// 2 = high-resolution (12-bit).
const _MODE_LP = 0;
const _MODE_NORMAL = 1;
const _MODE_HR = 2;

// Output data rates in Hz and their CTRL_REG1 ODR<3:0> codes (bits 7:4).
const _RATE_CODES = new Map([
    [0, 0x00],      // power-down
    [1, 0x10],
    [10, 0x20],
    [25, 0x30],
    [50, 0x40],
    [100, 0x50],
    [200, 0x60],
    [400, 0x70],
    [1620, 0x80],   // low-power mode only
    [1344, 0x90],   // normal / high-resolution mode only
    [5376, 0x90],   // low-power mode only (same code as 1344)
]);

// Rates only legal in low-power mode, and only legal outside it.
const _LP_ONLY_RATES = [1620, 5376];
const _NON_LP_ONLY_RATES = [1344];

// Sensitivity in mg/LSB by mode and full-scale range (datasheet Table 4).
const _SENSITIVITY_MG = {
    [_MODE_HR]:     {2: 1, 4: 2, 8: 4, 16: 12},
    [_MODE_NORMAL]: {2: 4, 4: 8, 8: 16, 16: 48},
    [_MODE_LP]:     {2: 16, 4: 32, 8: 64, 16: 192},
};

// Right-shift applied to the left-justified 16-bit output pair, by mode.
const _SHIFT = {[_MODE_HR]: 4, [_MODE_NORMAL]: 6, [_MODE_LP]: 8};

// Right-shift for the auxiliary ADC output (10-bit in normal and
// high-resolution mode, 8-bit in low-power mode).
const _ADC_SHIFT = {[_MODE_HR]: 6, [_MODE_NORMAL]: 6, [_MODE_LP]: 8};

// Interrupt-threshold LSB in mg by full-scale range (datasheet scale,
// full-scale / 128; the ±16 g value is taken as printed: 186 mg).
const _THRESHOLD_LSB_MG = {2: 16, 4: 32, 8: 62, 16: 186};

/**
 * Round half away from zero — matches the Python/C/Java/Go/Rust drivers, so
 * every language computes the same register codes.
 * @param {number} x - Value to round.
 * @returns {number} The rounded value.
 */
function _round(x) {
    return x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5);
}

/**
 * Round a sub-millisecond delay up to a whole millisecond.
 * @param {number} x - Delay in milliseconds.
 * @returns {number} Whole milliseconds, 0 for a non-positive input.
 */
function _ceilMs(x) {
    return x > 0 ? Math.ceil(x) : 0;
}

/**
 * Sleep without blocking the Node.js event loop.
 * @param {number} ms - Delay in milliseconds.
 * @returns {Promise<void>}
 */
function _sleep(ms) {
    if (!(ms > 0)) return Promise.resolve();
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Format a byte as 0xNN.
 * @param {number} value - Byte value.
 * @returns {string} Uppercase hexadecimal representation.
 */
function _hex2(value) {
    return '0x' + (value & 0xFF).toString(16).toUpperCase().padStart(2, '0');
}

/**
 * Format a 16-bit value as 0xNNNN.
 * @param {number} value - Value.
 * @returns {string} Uppercase hexadecimal representation.
 */
function _hex4(value) {
    return '0x' + (value & 0xFFFF).toString(16).toUpperCase().padStart(4, '0');
}

/**
 * LIS3DH 3-axis ultra-low-power MEMS accelerometer (STMicroelectronics) —
 * minimal interface.
 *
 * Triaxial accelerometer with 16-bit left-justified output and four
 * selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating modes
 * trade resolution for current: low-power (8-bit), normal (10-bit) and
 * high-resolution (12-bit), across output data rates from 1 Hz to 5.376 kHz.
 *
 * Communicates over I²C at address 0x18 (SA0 = GND) or 0x19 (SA0 = VDD_IO).
 * The chip also supports 3-/4-wire SPI but that is out of scope for this
 * driver. After the slave address the master sends an 8-bit sub-address:
 * the 7 LSbs are the register address and the MSb enables address
 * auto-increment, so every multi-byte transfer ORs 0x80 into the register
 * address (single-register accesses do not).
 *
 * The driver is platform-agnostic — it only calls the connection interface.
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (1 mg/LSB)
 * - 100 Hz output data rate
 * - High-resolution (12-bit) mode, all three axes enabled
 * - `BDU` = 1 (block data update)
 * - High-pass filter bypassed, FIFO bypassed
 * - All interrupt routing cleared
 * - Aux ADC and temperature sensor off
 * - `CTRL_REG0` untouched
 *
 * Constructor caveat: JS constructors cannot be async, so the datasheet
 * Initialization Sequence — including the `WHO_AM_I` identity check — starts
 * in the constructor and every public method awaits it first. A wrong or
 * absent device therefore rejects the first call made on the driver; call
 * `await accel.init()` right after construction to surface it at a
 * predictable point.
 */
class LIS3DHMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection (0x18 with SA0 = GND, 0x19 with SA0 = VDD_IO).
     */
    constructor(connection) {
        this._conn = connection;
        this._rangeG = _DEFAULT_RANGE_G;
        this._mode = _DEFAULT_MODE;
        this._rateHz = _DEFAULT_RATE_HZ;
        this._ready = this._runInitSequence();
        // The rejection is re-raised by every public method; mark it handled
        // here so it never surfaces as an unhandled rejection on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the Initialization Sequence started by the constructor.
     * @returns {Promise<void>}
     * @throws {Error} If `WHO_AM_I` is not 0x33 (wrong chip, wrong address, or wiring problem).
     */
    async init() {
        await this._ready;
    }

    /**
     * Run the datasheet Initialization Sequence (steps 2-6).
     * @returns {Promise<void>}
     * @private
     */
    async _runInitSequence() {
        // Step 2: verify WHO_AM_I.
        const chipId = await this._readReg(_REG_WHO_AM_I);
        if (chipId !== _WHO_AM_I_VALUE) {
            throw new Error('LIS3DH WHO_AM_I: expected ' + _hex2(_WHO_AM_I_VALUE) +
                            ', got ' + _hex2(chipId));
        }

        // Step 3: no soft reset — put the control registers in a known
        // state. CTRL_REG0 and the interrupt-generator registers are left
        // untouched (reserved registers are never written).
        await this._writeReg(_REG_CTRL_REG2, 0x00);
        await this._writeReg(_REG_CTRL_REG3, 0x00);
        await this._writeReg(_REG_CTRL_REG5, 0x00);
        await this._writeReg(_REG_CTRL_REG6, 0x00);
        await this._writeReg(_REG_FIFO_CTRL, 0x00);
        await this._writeReg(_REG_TEMP_CFG, 0x00);

        // Step 4: block data update, ±2 g, high-resolution 12-bit mode.
        await this._writeReg(_REG_CTRL_REG4, _CTRL_REG4_DEFAULT);

        // Step 5: leave power-down — this starts the conversions.
        await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);

        // Step 6: wait for the high-resolution turn-on (7/ODR = 70 ms at
        // 100 Hz) — the longer of the turn-on time and one ODR period.
        await _sleep(this._settleMs());
    }

    /**
     * Write one register.
     * @param {number} reg - Register address.
     * @param {number} value - Byte value.
     * @returns {Promise<void>}
     * @private
     */
    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    /**
     * Read one register.
     * @param {number} reg - Register address.
     * @returns {Promise<number>} The register byte.
     * @private
     */
    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    /**
     * Read one or more consecutive registers. Multi-byte I²C transfers set
     * the sub-address MSb (address auto-increment).
     * @param {number} reg - First register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} The register bytes.
     * @private
     */
    async _readBurst(reg, length) {
        const sub = length > 1 ? (reg | 0x80) : reg;
        return this._conn.readReg(sub, length);
    }

    /**
     * Milliseconds to wait after an ODR / mode change: the longer of the
     * mode's turn-on time (datasheet Table 10) and one ODR period.
     * @returns {number} Delay in milliseconds.
     * @private
     */
    _settleMs() {
        if (this._rateHz <= 0) return 0;
        const periodMs = 1000.0 / this._rateHz;
        let turnOnMs;
        if (this._mode === _MODE_LP) {
            turnOnMs = 1.0;
        } else if (this._mode === _MODE_NORMAL) {
            turnOnMs = 1.6;
        } else {
            turnOnMs = 7.0 * periodMs;
        }
        return _ceilMs(Math.max(turnOnMs, periodMs));
    }

    /**
     * Sign-extend the left-justified 16-bit output pair and shift it down to
     * the current resolution.
     * @param {number} low - Low byte (LSB first, `BLE` = 0).
     * @param {number} high - High byte.
     * @returns {number} Signed counts at the current resolution.
     * @private
     */
    _decodeAxis(low, high) {
        const raw16 = toSigned(low | (high << 8), 16);
        return raw16 >> _SHIFT[this._mode];
    }

    /**
     * @returns {number} Sensitivity of the current mode and range in mg/LSB.
     * @private
     */
    _sensitivityMg() {
        return _SENSITIVITY_MG[this._mode][this._rangeG];
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads the six output bytes (0x28–0x2D) with auto-increment so
     * X, Y and Z come from a single sample (`BDU` = 1), sign-extends the
     * left-justified 16-bit pairs and scales them with the sensitivity of
     * the current mode and range.
     *
     * @returns {Promise<{x: number, y: number, z: number}>} Acceleration per axis in *g*.
     */
    async read() {
        await this._ready;
        const raw = await this._readBurst(_REG_OUT_X_L, 6);
        const scale = this._sensitivityMg() / 1000.0;
        return {
            x: this._decodeAxis(raw[0], raw[1]) * scale,
            y: this._decodeAxis(raw[2], raw[3]) * scale,
            z: this._decodeAxis(raw[4], raw[5]) * scale,
        };
    }
}

/**
 * Interrupt source constants for the LIS3DH Full driver.
 *
 * The values match the `enableInterrupt()` / `disableInterrupt()` source
 * argument and the bit layout returned by `LIS3DHFull.pollInterrupt()` for
 * bits 0-6. `SOURCE_ACTIVITY` and `SOURCE_BOOT` are pin-only: the chip has
 * no register flag for them, so `pollInterrupt()` never reports them.
 */
class LIS3DHSource {
    /** New XYZ sample set (`STATUS_REG.ZYXDA`) — INT1 only. */
    static SOURCE_DATA_READY = 0x0001;
    /** New aux ADC sample (`STATUS_REG_AUX.321DA`) — INT1 only. */
    static SOURCE_AUX_DATA_READY = 0x0002;
    /** FIFO content exceeds the watermark (`FIFO_SRC_REG.WTM`) — INT1 only. */
    static SOURCE_WATERMARK = 0x0004;
    /** FIFO overrun (`FIFO_SRC_REG.OVRN_FIFO`) — INT1 only. */
    static SOURCE_OVERRUN = 0x0008;
    /** Inertial generator 1 event (`INT1_SRC.IA`) — INT1 or INT2. */
    static SOURCE_IA1 = 0x0010;
    /** Inertial generator 2 event (`INT2_SRC.IA`) — INT1 or INT2. */
    static SOURCE_IA2 = 0x0020;
    /** Single / double click (`CLICK_SRC.IA`) — INT1 or INT2. */
    static SOURCE_CLICK = 0x0040;
    /** Sleep-to-wake activity — INT2 only. */
    static SOURCE_ACTIVITY = 0x0080;
    /** Boot procedure running — INT2 only. */
    static SOURCE_BOOT = 0x0100;
}

/**
 * LIS3DH full interface — extends {@link LIS3DHMinimal} with the complete
 * chip functionality.
 *
 * Adds range / data-rate / mode selection, raw counts, status and
 * temperature reads, the auxiliary ADC, the high-pass filter, the 32-level
 * FIFO, both inertial interrupt generators (free-fall, 6D / 4D orientation),
 * single / double click detection, the sleep-to-wake activity function,
 * interrupt routing and delivery on INT1 / INT2, memory reboot, SDO
 * pull-up control and the electrostatic self-test.
 */
class LIS3DHFull extends LIS3DHMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection (0x18 with SA0 = GND, 0x19 with SA0 = VDD_IO).
     */
    constructor(connection) {
        super(connection);
        this._enabled1 = 0;
        this._enabled2 = 0;
        this._intCallback = null;
        this._intHandler = null;
        this._pollTimer = null;
        this._intActiveHigh = true;
    }

    // ---- configuration ----------------------------------------------------

    /**
     * Set the full-scale measurement range.
     *
     * Read-modify-writes the `FS` field of `CTRL_REG4` and updates the
     * cached sensitivity used by `read()` / `readRaw()` conversions.
     *
     * @param {number} rangeG - One of 2, 4, 8, 16 (*g*).
     * @returns {Promise<void>}
     * @throws {Error} If `rangeG` is not one of 2, 4, 8, 16.
     */
    async setRange(rangeG) {
        await this._ready;
        if (!_RANGES.includes(rangeG)) {
            throw new Error('rangeG must be one of 2, 4, 8, 16');
        }
        const fs = _RANGES.indexOf(rangeG) << 4;
        const ctrl4 = await this._readReg(_REG_CTRL_REG4);
        await this._writeReg(_REG_CTRL_REG4, (ctrl4 & ~0x30 & 0xFF) | fs);
        this._rangeG = rangeG;
    }

    /**
     * Set the output data rate (or power down).
     *
     * Read-modify-writes the `ODR` field of `CTRL_REG1` and waits for the
     * turn-on time before returning.
     *
     * @param {number} rateHz - One of 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376. 1620 and 5376 exist in low-power mode only; 1344 in normal / high-resolution mode only.
     * @returns {Promise<void>}
     * @throws {Error} If the rate is unknown or not allowed in the current operating mode.
     */
    async setDataRate(rateHz) {
        await this._ready;
        const code = _RATE_CODES.get(rateHz);
        if (code === undefined) {
            throw new Error('rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376');
        }
        if (_LP_ONLY_RATES.includes(rateHz) && this._mode !== _MODE_LP) {
            throw new Error('rate ' + rateHz + ' Hz is low-power mode only');
        }
        if (_NON_LP_ONLY_RATES.includes(rateHz) && this._mode === _MODE_LP) {
            throw new Error('rate ' + rateHz + ' Hz is not available in low-power mode');
        }
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        await this._writeReg(_REG_CTRL_REG1, (ctrl1 & 0x0F) | code);
        this._rateHz = rateHz;
        await _sleep(this._settleMs());
    }

    /**
     * Set the operating mode (resolution / power trade-off).
     *
     * Read-modify-writes `LPen` in `CTRL_REG1` and `HR` in `CTRL_REG4`,
     * updates the cached resolution and waits for the mode's turn-on time.
     *
     * @param {number} mode - 0 = low-power (8-bit), 1 = normal (10-bit), 2 = high-resolution (12-bit).
     * @returns {Promise<void>}
     * @throws {Error} If `mode` is not 0, 1 or 2, or if the current data rate is illegal in that mode (`LPen` = 1 with `HR` = 1 is never written).
     */
    async setMode(mode) {
        await this._ready;
        if (mode !== _MODE_LP && mode !== _MODE_NORMAL && mode !== _MODE_HR) {
            throw new Error('mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)');
        }
        if (mode === _MODE_LP && _NON_LP_ONLY_RATES.includes(this._rateHz)) {
            throw new Error('rate ' + this._rateHz + ' Hz is not available in low-power mode');
        }
        if (mode !== _MODE_LP && _LP_ONLY_RATES.includes(this._rateHz)) {
            throw new Error('rate ' + this._rateHz + ' Hz is low-power mode only');
        }
        let ctrl1 = await this._readReg(_REG_CTRL_REG1);
        let ctrl4 = await this._readReg(_REG_CTRL_REG4);
        if (mode === _MODE_LP) {
            ctrl1 |= 0x08;                    // LPen = 1
            ctrl4 &= ~0x08 & 0xFF;            // HR = 0 (LPen=1 with HR=1 not allowed)
        } else {
            ctrl1 &= ~0x08 & 0xFF;            // LPen = 0
            if (mode === _MODE_HR) {
                ctrl4 |= 0x08;                // HR = 1
            } else {
                ctrl4 &= ~0x08 & 0xFF;        // HR = 0
            }
        }
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
        await this._writeReg(_REG_CTRL_REG4, ctrl4);
        this._mode = mode;
        await _sleep(this._settleMs());
    }

    /**
     * Enable or disable the individual measurement axes.
     *
     * @param {boolean} [x=true] - Enable the X axis.
     * @param {boolean} [y=true] - Enable the Y axis.
     * @param {boolean} [z=true] - Enable the Z axis.
     * @returns {Promise<void>}
     */
    async setAxes(x = true, y = true, z = true) {
        await this._ready;
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        const axes = (x ? 1 : 0) | ((y ? 1 : 0) << 1) | ((z ? 1 : 0) << 2);
        await this._writeReg(_REG_CTRL_REG1, (ctrl1 & 0xF8) | axes);
    }

    /**
     * Stop conversions by setting `ODR` = 0000; registers are retained.
     * @returns {Promise<void>}
     */
    async powerDown() {
        await this._ready;
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        await this._writeReg(_REG_CTRL_REG1, ctrl1 & 0x0F);
        this._rateHz = 0;
    }

    // ---- data reads -------------------------------------------------------

    /**
     * Read the raw acceleration counts at the current resolution.
     * @returns {Promise<{x: number, y: number, z: number}>} Signed counts — 12-bit in high-resolution mode, 10-bit in normal mode, 8-bit in low-power mode.
     */
    async readRaw() {
        await this._ready;
        const raw = await this._readBurst(_REG_OUT_X_L, 6);
        return {
            x: this._decodeAxis(raw[0], raw[1]),
            y: this._decodeAxis(raw[2], raw[3]),
            z: this._decodeAxis(raw[4], raw[5]),
        };
    }

    /**
     * Check whether a complete X/Y/Z sample set is ready.
     * @returns {Promise<boolean>} `STATUS_REG.ZYXDA` — true when all three axes have new data.
     */
    async newDataAvailable() {
        await this._ready;
        return (await this._readReg(_REG_STATUS) & 0x08) !== 0;
    }

    /**
     * Read the STATUS register.
     * @returns {Promise<number>} `STATUS_REG` byte — overrun (`ZYXOR`..`XOR`) and data-available (`ZYXDA`..`XDA`) flags.
     */
    async readStatus() {
        await this._ready;
        return this._readReg(_REG_STATUS);
    }

    /**
     * Read the relative on-chip temperature.
     *
     * Enables `ADC_EN` and `TEMP_EN` in `TEMP_CFG_REG` on first use (the ADC
     * channel-3 conversions run through the same ADC) and waits one ODR
     * period for the first conversion.
     *
     * The datasheet specifies only 1 digit/°C with no zero-degree reference,
     * so the value is a relative delta, never an absolute temperature.
     *
     * @returns {Promise<number>} Relative temperature in °C (8-bit two's complement, 1 digit/°C).
     */
    async readTemperatureDelta() {
        await this._ready;
        const cfg = await this._readReg(_REG_TEMP_CFG);
        if ((cfg & 0xC0) !== 0xC0) {
            await this._writeReg(_REG_TEMP_CFG, cfg | 0xC0);
            if (this._rateHz > 0) {
                await _sleep(_ceilMs(1000.0 / this._rateHz));
            }
        }
        return toSigned(await this._readReg(_REG_OUT_ADC3_H), 8);
    }

    /**
     * Read one auxiliary ADC channel.
     *
     * @param {number} channel - 1, 2 or 3. Channel 3 is the temperature-sensor input while `TEMP_EN` = 1.
     * @returns {Promise<number>} Input voltage in V (1.200 V centre, ±400 mV range; the mapping is derived from the datasheet's stated input range).
     * @throws {Error} If `channel` is not 1, 2 or 3, or if channel 3 is requested while `TEMP_EN` = 1.
     */
    async readAdc(channel) {
        await this._ready;
        if (channel !== 1 && channel !== 2 && channel !== 3) {
            throw new Error('channel must be 1, 2 or 3');
        }
        if (channel === 3) {
            const cfg = await this._readReg(_REG_TEMP_CFG);
            if (cfg & 0x40) {
                throw new Error('channel 3 is the temperature sensor while TEMP_EN = 1');
            }
        }
        const base = _REG_OUT_ADC1_L + (channel - 1) * 2;
        const raw = await this._readBurst(base, 2);
        const raw16 = toSigned(raw[0] | (raw[1] << 8), 16);
        const counts = raw16 >> _ADC_SHIFT[this._mode];
        return 1.200 + counts * (0.400 / 512.0);
    }

    /**
     * Enable or disable the auxiliary ADC (`ADC_EN` in `TEMP_CFG_REG`).
     * @param {boolean} enabled - True to enable the ADC, false to disable it.
     * @returns {Promise<void>}
     */
    async enableAdc(enabled) {
        await this._ready;
        const cfg = await this._readReg(_REG_TEMP_CFG);
        await this._writeReg(_REG_TEMP_CFG, enabled ? (cfg | 0x80) : (cfg & ~0x80 & 0xFF));
    }

    // ---- high-pass filter -------------------------------------------------

    /**
     * Configure the high-pass filter (`CTRL_REG2`).
     *
     * @param {object|number} [options] - High-pass filter settings. A bare
     *   number is accepted as a shorthand for the `mode` option.
     * @param {number} [options.mode=0] - `HPM` — 0 normal (reset by reading `REFERENCE`), 1 reference signal for filtering, 2 normal, 3 auto-reset on interrupt event.
     * @param {number} [options.cutoff=0] - `HPCF<2:1>` code 0-3 (the datasheet tabulates no Hz values; they scale with ODR).
     * @param {boolean} [options.filterOutput=false] - Route the filtered data to the output registers and FIFO (`FDS`).
     * @param {boolean} [options.onIa1=false] - Apply the filter to interrupt generator 1.
     * @param {boolean} [options.onIa2=false] - Apply the filter to interrupt generator 2.
     * @param {boolean} [options.onClick=false] - Apply the filter to the click function.
     * @returns {Promise<void>}
     * @throws {Error} If `mode` is not 0-3 or `cutoff` not 0-3.
     */
    async setHighPass(options = {}) {
        await this._ready;
        if (typeof options === 'number') options = {mode: options};
        const mode = options.mode === undefined ? 0 : options.mode;
        const cutoff = options.cutoff === undefined ? 0 : options.cutoff;
        const filterOutput = options.filterOutput === undefined ? false : options.filterOutput;
        const onIa1 = options.onIa1 === undefined ? false : options.onIa1;
        const onIa2 = options.onIa2 === undefined ? false : options.onIa2;
        const onClick = options.onClick === undefined ? false : options.onClick;
        if (mode < 0 || mode > 3) {
            throw new Error('mode must be 0-3');
        }
        if (cutoff < 0 || cutoff > 3) {
            throw new Error('cutoff must be 0-3');
        }
        let value = (mode << 6) | (cutoff << 4);
        if (filterOutput) value |= 0x08;
        if (onClick) value |= 0x04;
        if (onIa2) value |= 0x02;
        if (onIa1) value |= 0x01;
        await this._writeReg(_REG_CTRL_REG2, value);
    }

    /**
     * Reset the filter by reading `REFERENCE` (`HPM` = 00).
     * @returns {Promise<number>} The `REFERENCE` register value that was read.
     */
    async resetHighPass() {
        await this._ready;
        return this._readReg(_REG_REFERENCE);
    }

    /**
     * Write the high-pass reference value (`HPM` = 01).
     * @param {number} value - 0-255.
     * @returns {Promise<void>}
     * @throws {Error} If `value` is outside 0-255.
     */
    async setReference(value) {
        await this._ready;
        if (!Number.isInteger(value) || value < 0 || value > 255) {
            throw new Error('value must be 0-255');
        }
        await this._writeReg(_REG_REFERENCE, value);
    }

    // ---- FIFO -------------------------------------------------------------

    /**
     * Configure the 32-level FIFO (`FIFO_CTRL_REG`).
     *
     * Sets `FIFO_EN` in `CTRL_REG5` for modes 1-3; returning to bypass
     * (mode 0) clears the buffer. Only bypass mode empties the buffer —
     * restart a FIFO mode through bypass after a full drain.
     *
     * @param {number} mode - 0 = bypass, 1 = FIFO, 2 = stream, 3 = stream-to-FIFO.
     * @param {number} [watermark=0] - Watermark level `FTH` 0-31.
     * @param {boolean} [triggerInt2=false] - Trigger source for stream-to-FIFO: false = INT1, true = INT2 (`TR`).
     * @returns {Promise<void>}
     * @throws {Error} If `mode` is not 0-3 or `watermark` outside 0-31.
     */
    async setFifo(mode, watermark = 0, triggerInt2 = false) {
        await this._ready;
        if (mode < 0 || mode > 3) {
            throw new Error('mode must be 0 (bypass), 1 (FIFO), 2 (stream) or 3 (stream-to-FIFO)');
        }
        if (watermark < 0 || watermark > 31) {
            throw new Error('watermark must be 0-31');
        }
        const value = (mode << 6) | (triggerInt2 ? 0x20 : 0x00) | watermark;
        await this._writeReg(_REG_FIFO_CTRL, value);
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, mode === 0 ? (ctrl5 & ~0x40 & 0xFF) : (ctrl5 | 0x40));
    }

    /**
     * Read the number of unread sample sets in the FIFO.
     * @returns {Promise<number>} `FSS<4:0>` — 0-32 unread sample sets.
     */
    async fifoLevel() {
        await this._ready;
        return (await this._readReg(_REG_FIFO_SRC)) & 0x1F;
    }

    /**
     * Drain sample sets from the FIFO.
     *
     * Each sample set is one 6-byte burst of 0x28–0x2D; the address
     * auto-increment wraps back to 0x28 so a full drain is 32 reads.
     *
     * @param {number} [count=null] - Maximum number of sample sets to read (default: all `FSS` entries currently in the FIFO). Never reads more than the FIFO actually holds.
     * @returns {Promise<Array<{x: number, y: number, z: number}>>} Sample sets in *g*, oldest sample first.
     */
    async readFifo(count = null) {
        await this._ready;
        const level = await this.fifoLevel();
        const n = (count === null || count === undefined) ? level : Math.min(count, level);
        const samples = [];
        const scale = this._sensitivityMg() / 1000.0;
        for (let i = 0; i < n; i++) {
            const raw = await this._readBurst(_REG_OUT_X_L, 6);
            samples.push({
                x: this._decodeAxis(raw[0], raw[1]) * scale,
                y: this._decodeAxis(raw[2], raw[3]) * scale,
                z: this._decodeAxis(raw[4], raw[5]) * scale,
            });
        }
        return samples;
    }

    /**
     * Decode `FIFO_SRC_REG`.
     * @returns {Promise<{watermark: boolean, overrun: boolean, empty: boolean, level: number}>} The three flags are booleans, `level` is `FSS<4:0>` (0-32).
     */
    async readFifoStatus() {
        await this._ready;
        const raw = await this._readReg(_REG_FIFO_SRC);
        return {
            watermark: (raw & 0x80) !== 0,
            overrun: (raw & 0x40) !== 0,
            empty: (raw & 0x20) !== 0,
            level: raw & 0x1F,
        };
    }

    // ---- inertial interrupt generators -----------------------------------

    /**
     * Configure an inertial interrupt generator (motion / threshold).
     *
     * @param {number} generator - 1 or 2 — the `INT1` or `INT2` register block.
     * @param {number} events - 6-bit `ZHIE`..`XLIE` event-enable mask (0-0x3F).
     * @param {number} thresholdG - Threshold in *g*; scaled with the current full-scale range (16 / 32 / 62 / 186 mg per LSb).
     * @param {number} [durationMs=0] - Minimum duration the events must hold, in ms (`INTx_DURATION`, 1 LSb = 1/ODR).
     * @param {boolean} [andMode=false] - True for an AND combination of the enabled events (`AOI` = 1), false for OR.
     * @returns {Promise<void>}
     * @throws {Error} If `generator` is not 1 or 2 or `events` is outside 0-0x3F.
     */
    async setMotionInterrupt(generator, events, thresholdG, durationMs = 0, andMode = false) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        if (events < 0 || events > 0x3F) {
            throw new Error('events must be a 6-bit mask (0-0x3F)');
        }
        const cfg = (andMode ? 0x80 : 0x00) | (events & 0x3F);
        await this._writeReg(regs.cfg, cfg);
        await this._writeReg(regs.ths, this._thresholdCode(thresholdG));
        await this._writeReg(regs.dur, this._durationCode(durationMs));
    }

    /**
     * Configure the free-fall detector: all three axes low, AND mode.
     *
     * Convenience wrapper around the generator's `INTx_CFG` = 0x95
     * (`AOI` + `XLIE`/`YLIE`/`ZLIE`).
     *
     * @param {number} [generator=1] - 1 or 2.
     * @param {number} [thresholdG=0.35] - Free-fall threshold in *g*.
     * @param {number} [durationMs=30] - Minimum time below the threshold in ms.
     * @returns {Promise<void>}
     * @throws {Error} If `generator` is not 1 or 2.
     */
    async setFreeFall(generator = 1, thresholdG = 0.35, durationMs = 30) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        await this._writeReg(regs.cfg, 0x95);
        await this._writeReg(regs.ths, this._thresholdCode(thresholdG));
        await this._writeReg(regs.dur, this._durationCode(durationMs));
    }

    /**
     * Configure 6D / 4D orientation detection.
     *
     * `AOI` / `6D` = 01 is *movement* recognition (a one-ODR pulse), 11 is
     * *position* recognition (level, active while the orientation is inside
     * a known zone).
     *
     * @param {number} [generator=1] - 1 or 2.
     * @param {boolean} [position=false] - False = 6D movement recognition, true = 6D position recognition.
     * @param {boolean} [fourD=false] - Enable 4D detection (`D4D_INTx`) — disables the Z-axis zones.
     * @returns {Promise<void>}
     * @throws {Error} If `generator` is not 1 or 2.
     */
    async setOrientation(generator = 1, position = false, fourD = false) {
        await this._ready;
        const regs = this._generatorRegs(generator);
        const axisZones = fourD ? 0x0F : 0x3F;
        const cfg = (position ? 0xC0 : 0x40) | axisZones;
        await this._writeReg(regs.cfg, cfg);
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        const d4dBit = generator === 1 ? 0x04 : 0x01;
        await this._writeReg(_REG_CTRL_REG5, fourD ? (ctrl5 | d4dBit) : (ctrl5 & ~d4dBit & 0xFF));
    }

    /**
     * Latch a generator's interrupt until its `INTx_SRC` is read.
     * @param {number} generator - 1 or 2.
     * @param {boolean} enabled - True to latch (`LIR_INTx`), false for a self-resetting interrupt.
     * @returns {Promise<void>}
     * @throws {Error} If `generator` is not 1 or 2.
     */
    async setLatch(generator, enabled) {
        await this._ready;
        if (generator !== 1 && generator !== 2) {
            throw new Error('generator must be 1 or 2');
        }
        const bit = generator === 1 ? 0x08 : 0x02;
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, enabled ? (ctrl5 | bit) : (ctrl5 & ~bit & 0xFF));
    }

    /**
     * Read a generator's `INTx_SRC` — **clears** the interrupt.
     * @param {number} generator - 1 or 2.
     * @returns {Promise<number>} `INTx_SRC` byte (`IA` = bit 6, per-axis event flags in bits 5-0).
     * @throws {Error} If `generator` is not 1 or 2.
     */
    async readInterruptSource(generator) {
        await this._ready;
        if (generator !== 1 && generator !== 2) {
            throw new Error('generator must be 1 or 2');
        }
        return this._readReg(generator === 1 ? _REG_INT1_SRC : _REG_INT2_SRC);
    }

    // ---- click detection --------------------------------------------------

    /**
     * Configure single / double click detection (`CLICK_*`).
     *
     * @param {number} [single=0] - Axis mask of single-click enables (X = 1, Y = 2, Z = 4).
     * @param {number} [double=0] - Axis mask of double-click enables (X = 1, Y = 2, Z = 4).
     * @param {number} [thresholdG=0.5] - Click threshold in *g* (same scale as `INTx_THS`).
     * @param {number} [timeLimitMs=10] - Maximum time between the two clicks of a double click.
     * @param {number} [latencyMs=20] - Minimum time between two consecutive clicks.
     * @param {number} [windowMs=50] - Maximum time after the first click of a double click.
     * @param {boolean} [latch=false] - Keep the interrupt high until `CLICK_SRC` is read (`LIR_Click`).
     * @returns {Promise<void>}
     * @throws {Error} If either axis mask is outside 0-7.
     */
    async setClick(single = 0, double = 0, thresholdG = 0.5, timeLimitMs = 10, latencyMs = 20, windowMs = 50, latch = false) {
        await this._ready;
        if (single < 0 || single > 7 || double < 0 || double > 7) {
            throw new Error('single / double must be axis masks (0-7: X=1, Y=2, Z=4)');
        }
        const cfg = ((single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                     ((double & 0x01) << 1) | ((double & 0x02) << 2) | ((double & 0x04) << 3));
        const ths = this._thresholdCode(thresholdG);
        await this._writeReg(_REG_CLICK_CFG, cfg);
        await this._writeReg(_REG_CLICK_THS, ths | (latch ? 0x80 : 0x00));
        await this._writeReg(_REG_TIME_LIMIT, this._durationCode(timeLimitMs, 127));
        await this._writeReg(_REG_TIME_LATENCY, this._durationCode(latencyMs, 255));
        await this._writeReg(_REG_TIME_WINDOW, this._durationCode(windowMs, 255));
    }

    /**
     * Read `CLICK_SRC` — **clears** the click interrupt.
     * @returns {Promise<number>} `CLICK_SRC` byte (`IA` = bit 6, `DCLICK` = bit 5, `SCLICK` = bit 4, `Sign` = bit 3, axis flags in bits 2-0).
     */
    async readClickSource() {
        await this._ready;
        return this._readReg(_REG_CLICK_SRC);
    }

    // ---- sleep-to-wake activity -------------------------------------------

    /**
     * Configure the sleep-to-wake / return-to-sleep activity function.
     *
     * While the function has dropped the chip into its 10 Hz low-power state
     * the `ODR`, `LPen` and `HR` settings are ignored; the chip restores them
     * on activity. Pair with `SOURCE_ACTIVITY`.
     *
     * @param {number} thresholdG - Activation threshold in *g* (`ACT_THS`).
     * @param {number} durationMs - Activation duration in ms (`ACT_DUR`, where 1 LSb = (8·ACT_DUR + 1)/ODR).
     * @returns {Promise<void>}
     * @throws {Error} If the threshold rounds to 0 counts (`ACT_THS` must be non-zero).
     */
    async setActivity(thresholdG, durationMs) {
        await this._ready;
        const code = this._thresholdCode(thresholdG);
        if (code === 0) {
            throw new Error('thresholdG is below 1 LSb — ACT_THS must be non-zero');
        }
        let dur = _round((durationMs * this._rateHz / 1000.0 - 1) / 8.0);
        dur = Math.max(0, Math.min(255, dur));
        await this._writeReg(_REG_ACT_THS, code);
        await this._writeReg(_REG_ACT_DUR, dur);
    }

    /**
     * Set the shared INT1 / INT2 output polarity (`INT_POLARITY`).
     * @param {boolean} activeLow - True for active-low, false for active-high (the reset default).
     * @returns {Promise<void>}
     */
    async setInterruptPolarity(activeLow) {
        await this._ready;
        const ctrl6 = await this._readReg(_REG_CTRL_REG6);
        await this._writeReg(_REG_CTRL_REG6, activeLow ? (ctrl6 | 0x02) : (ctrl6 & ~0x02 & 0xFF));
        this._intActiveHigh = !activeLow;
        // Re-arm the edge subscription so it follows the new polarity.
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin && this._intHandler) {
            await intPin.offEdge(this._intHandler);
            await intPin.onEdge(this._intHandler, this._intActiveHigh ? 'rising' : 'falling');
        }
    }

    // ---- interrupt routing and delivery -----------------------------------

    /**
     * Route one interrupt source to INT`pin`.
     *
     * Only routes the source — the generator or function behind it is
     * configured with the `set*` methods. `pin` is ignored for INT1-only
     * (data-ready, aux data-ready, watermark, overrun) and INT2-only
     * (activity, boot) sources.
     *
     * @param {number} source - One of the {@link LIS3DHSource} constants.
     * @param {number} [pin=1] - 1 or 2.
     * @returns {Promise<void>}
     * @throws {Error} If `source` is unknown or `pin` is not 1 or 2.
     */
    async enableInterrupt(source, pin = 1) {
        await this._ready;
        const route = this._route(source, pin);
        const value = await this._readReg(route.reg);
        await this._writeReg(route.reg, value | route.mask);
        if (route.track === 1) {
            this._enabled1 |= (1 << this._sourceBit(source));
        } else {
            this._enabled2 |= (1 << this._sourceBit(source));
        }
    }

    /**
     * Remove one interrupt source from INT`pin`.
     * @param {number} source - One of the {@link LIS3DHSource} constants.
     * @param {number} [pin=1] - 1 or 2.
     * @returns {Promise<void>}
     * @throws {Error} If `source` is unknown or `pin` is not 1 or 2.
     */
    async disableInterrupt(source, pin = 1) {
        await this._ready;
        const route = this._route(source, pin);
        const value = await this._readReg(route.reg);
        await this._writeReg(route.reg, value & ~route.mask & 0xFF);
        if (route.track === 1) {
            this._enabled1 &= ~(1 << this._sourceBit(source));
        } else {
            this._enabled2 &= ~(1 << this._sourceBit(source));
        }
    }

    /**
     * Subscribe to INT`pin` assertions.
     *
     * The callback receives the combined status int returned by
     * `pollInterrupt()`, and is only invoked for a non-zero status. Uses
     * the connection's `intPin`; the edge follows the configured
     * `INT_POLARITY` (rising for the active-high default). When no INT pin
     * is wired the driver falls back to a 5 ms polling loop.
     *
     * @param {function(number): void} callback - Function called with the status int.
     * @param {number} [pin=1] - 1 or 2 — the INT line the connection's `intPin` is wired to.
     * @returns {Promise<void>}
     * @throws {Error} If `pin` is not 1 or 2.
     */
    async onInterrupt(callback, pin = 1) {
        await this._ready;
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        this._intCallback = callback;
        const intPin = this._conn ? this._conn.intPin : null;
        if (intPin) {
            this._intHandler = () => { this._dispatchInt(); };
            await intPin.onEdge(this._intHandler, this._intActiveHigh ? 'rising' : 'falling');
        } else {
            this._startPollingFallback();
        }
    }

    /**
     * Unsubscribe from INT`pin` assertions.
     * @param {number} [pin=1] - 1 or 2.
     * @returns {Promise<void>}
     */
    async offInterrupt(pin = 1) {
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

    /**
     * Read the source registers of every currently enabled source.
     *
     * **Reading `INT1_SRC` / `INT2_SRC` / `CLICK_SRC` clears their latched
     * flags** (and the INT pin).
     *
     * @returns {Promise<number>} Combined status int — bit 0 `SOURCE_DATA_READY`, bit 1 `SOURCE_AUX_DATA_READY`, bit 2 `SOURCE_WATERMARK`, bit 3 `SOURCE_OVERRUN`, bit 4 `SOURCE_IA1`, bit 5 `SOURCE_IA2`, bit 6 `SOURCE_CLICK`. Sources that are not enabled read as 0; `SOURCE_ACTIVITY` and `SOURCE_BOOT` have no register flag and are never reported.
     */
    async pollInterrupt() {
        await this._ready;
        const e1 = this._enabled1;
        const e2 = this._enabled2;
        const enabled = e1 | e2;
        let status = 0;
        // INT1-only sources are routed through CTRL_REG3 (e1).
        if ((e1 & 0x01) && ((await this._readReg(_REG_STATUS)) & 0x08)) {
            status |= LIS3DHSource.SOURCE_DATA_READY;
        }
        if ((e1 & 0x02) && ((await this._readReg(_REG_STATUS_AUX)) & 0x08)) {
            status |= LIS3DHSource.SOURCE_AUX_DATA_READY;
        }
        if ((e1 & 0x04) || (e1 & 0x08)) {
            const fifo = await this._readReg(_REG_FIFO_SRC);
            if ((e1 & 0x04) && (fifo & 0x80)) {
                status |= LIS3DHSource.SOURCE_WATERMARK;
            }
            if ((e1 & 0x08) && (fifo & 0x40)) {
                status |= LIS3DHSource.SOURCE_OVERRUN;
            }
        }
        // Generator flags live in their own source register no matter which
        // pin the generator is routed to.
        if ((enabled & 0x10) && ((await this._readReg(_REG_INT1_SRC)) & 0x40)) {
            status |= LIS3DHSource.SOURCE_IA1;
        }
        if ((enabled & 0x20) && ((await this._readReg(_REG_INT2_SRC)) & 0x40)) {
            status |= LIS3DHSource.SOURCE_IA2;
        }
        if ((enabled & 0x40) && ((await this._readReg(_REG_CLICK_SRC)) & 0x40)) {
            status |= LIS3DHSource.SOURCE_CLICK;
        }
        return status;
    }

    // ---- system ------------------------------------------------------------

    /**
     * Reboot the memory content (`BOOT` = 1) and re-run the Initialization
     * Sequence.
     *
     * Reloads the factory trim and waits 5 ms. The datasheet does not say
     * whether the control registers survive, so the Initialization Sequence
     * is re-run afterwards — the configuration returns to the Minimal
     * defaults.
     *
     * @returns {Promise<void>}
     */
    async reboot() {
        await this._ready;
        const ctrl5 = await this._readReg(_REG_CTRL_REG5);
        await this._writeReg(_REG_CTRL_REG5, ctrl5 | 0x80);
        await _sleep(5);
        this._rangeG = _DEFAULT_RANGE_G;
        this._mode = _DEFAULT_MODE;
        this._rateHz = _DEFAULT_RATE_HZ;
        this._enabled1 = 0;
        this._enabled2 = 0;
        await this._runInitSequence();
    }

    /**
     * Connect or disconnect the pull-up on SDO/SA0 (`CTRL_REG0`).
     *
     * Read-modify-write of bit 7 only; bit 4 is forced to 1 and the
     * reserved bits stay 0 (`CTRL_REG0` is not a normal register).
     *
     * @param {boolean} connected - True to connect the pull-up (`SDO_PU_DISC` = 0), false to disconnect it.
     * @returns {Promise<void>}
     */
    async setSdoPullup(connected) {
        await this._ready;
        const value = 0x10 | (connected ? 0x00 : 0x80);
        await this._writeReg(_REG_CTRL_REG0, value);
    }

    /**
     * Run the datasheet electrostatic self-test.
     *
     * Configures ±2 *g*, normal (10-bit) mode, 50 Hz with `BDU` on,
     * averages a few samples with `ST` = 00, enables `ST` (01 = self-test
     * 0, 10 = self-test 1), waits 2 samples and averages again. Every axis
     * must show a 17-360 LSb change (1 LSb = 4 mg at 10-bit / ±2 *g*). The
     * previous `CTRL_REG1` / `CTRL_REG4` configuration is restored
     * afterwards.
     *
     * @param {number} [mode=1] - 1 = self-test 0, 2 = self-test 1.
     * @returns {Promise<boolean>} True if every axis showed a change inside 17-360 LSb.
     * @throws {Error} If `mode` is not 1 or 2.
     */
    async selfTest(mode = 1) {
        await this._ready;
        if (mode !== 1 && mode !== 2) {
            throw new Error('mode must be 1 (self-test 0) or 2 (self-test 1)');
        }
        const savedCtrl1 = await this._readReg(_REG_CTRL_REG1);
        const savedCtrl4 = await this._readReg(_REG_CTRL_REG4);
        const savedRange = this._rangeG;
        const savedMode = this._mode;
        const savedRate = this._rateHz;
        const sampleMs = 20;    // 50 Hz
        const configure = async (stBits) => {
            await this._writeReg(_REG_CTRL_REG4, 0x80 | stBits);
            await this._writeReg(_REG_CTRL_REG1, 0x47);   // 50 Hz, LPen=0, X/Y/Z
            this._rangeG = _DEFAULT_RANGE_G;
            this._mode = _MODE_NORMAL;
            this._rateHz = 50;
        };
        let before = [0, 0, 0];
        let after = [0, 0, 0];
        try {
            await configure(0x00);
            await _sleep(10);
            before = await this._averageRaw(5, sampleMs);
            await configure(mode === 1 ? 0x02 : 0x04);
            await _sleep(40);                              // 2 samples at 50 Hz
            after = await this._averageRaw(5, sampleMs);
        } finally {
            await this._writeReg(_REG_CTRL_REG1, savedCtrl1);
            await this._writeReg(_REG_CTRL_REG4, savedCtrl4);
            this._rangeG = savedRange;
            this._mode = savedMode;
            this._rateHz = savedRate;
        }
        for (let i = 0; i < 3; i++) {
            const delta = Math.abs(after[i] - before[i]);
            if (delta < 17 || delta > 360) return false;
        }
        return true;
    }

    // ---- internal helpers --------------------------------------------------

    /**
     * Average `samples` raw readings, `sampleMs` apart.
     * @param {number} samples - Number of samples.
     * @param {number} sampleMs - Delay between samples in ms.
     * @returns {Promise<number[]>} Averaged (x, y, z) counts.
     * @private
     */
    async _averageRaw(samples, sampleMs) {
        const total = [0, 0, 0];
        for (let i = 0; i < samples; i++) {
            const raw = await this.readRaw();
            total[0] += raw.x;
            total[1] += raw.y;
            total[2] += raw.z;
            await _sleep(sampleMs);
        }
        return [total[0] / samples, total[1] / samples, total[2] / samples];
    }

    /**
     * Register addresses of an inertial interrupt generator.
     * @param {number} generator - 1 or 2.
     * @returns {{cfg: number, ths: number, dur: number}} The register addresses.
     * @private
     * @throws {Error} If `generator` is not 1 or 2.
     */
    _generatorRegs(generator) {
        if (generator === 1) {
            return {cfg: _REG_INT1_CFG, ths: _REG_INT1_THS, dur: _REG_INT1_DUR};
        }
        if (generator === 2) {
            return {cfg: _REG_INT2_CFG, ths: _REG_INT2_THS, dur: _REG_INT2_DUR};
        }
        throw new Error('generator must be 1 or 2');
    }

    /**
     * Convert a threshold in *g* into the 7-bit `INTx_THS` / `ACT_THS` /
     * `CLICK_THS` code of the current full-scale range.
     * @param {number} thresholdG - Threshold in *g*.
     * @returns {number} The 7-bit register code.
     * @private
     */
    _thresholdCode(thresholdG) {
        const lsbMg = _THRESHOLD_LSB_MG[this._rangeG];
        const code = _round(thresholdG * 1000.0 / lsbMg);
        return Math.max(0, Math.min(127, code));
    }

    /**
     * Convert a duration in ms into the `INTx_DURATION` / `TIME_*` code
     * (1 LSb = 1/ODR).
     * @param {number} durationMs - Duration in ms.
     * @param {number} [limit=127] - Maximum register code.
     * @returns {number} The register code.
     * @private
     */
    _durationCode(durationMs, limit = 127) {
        // 1 LSb = 1/ODR; while powered down there is no ODR to convert
        // with, so the code is 0.
        if (this._rateHz <= 0) return 0;
        const code = _round(durationMs * this._rateHz / 1000.0);
        return Math.max(0, Math.min(limit, code));
    }

    /**
     * Bit index of an interrupt source constant.
     * @param {number} source - One of the {@link LIS3DHSource} constants.
     * @returns {number} The bit index (0-8).
     * @private
     */
    _sourceBit(source) {
        let bit = 0;
        let value = source;
        while (value > 1) {
            value >>= 1;
            bit++;
        }
        return bit;
    }

    /**
     * Routing information of an interrupt source.
     * @param {number} source - One of the {@link LIS3DHSource} constants.
     * @param {number} pin - 1 or 2.
     * @returns {{reg: number, mask: number, track: number}} The routing register, its bit mask and the tracked pin (1 or 2).
     * @private
     * @throws {Error} If `source` is unknown or `pin` is not 1 or 2.
     */
    _route(source, pin) {
        if (pin !== 1 && pin !== 2) {
            throw new Error('pin must be 1 or 2');
        }
        const entry = _ROUTE[source];
        if (entry === undefined) {
            throw new Error('unknown interrupt source: ' + _hex4(source));
        }
        if (entry.kind === 'int1') {
            return {reg: _REG_CTRL_REG3, mask: entry.mask, track: 1};
        }
        if (entry.kind === 'int2') {
            return {reg: _REG_CTRL_REG6, mask: entry.mask, track: 2};
        }
        if (pin === 1) {
            return {reg: _REG_CTRL_REG3, mask: entry.mask, track: 1};
        }
        return {reg: _REG_CTRL_REG6, mask: entry.mask, track: 2};
    }

    /**
     * Read the interrupt sources and hand the combined status to the
     * registered callback.
     * @returns {Promise<void>}
     * @private
     */
    async _dispatchInt() {
        const callback = this._intCallback;
        if (callback === null || callback === undefined) return;
        try {
            const status = await this.pollInterrupt();
            if (status) callback(status);
        } catch (e) {
            // A failing bus read must not kill the event loop.
        }
    }

    /**
     * Start the 5 ms polling fallback used when no INT pin is wired.
     * @private
     */
    _startPollingFallback() {
        if (this._pollTimer !== null) return;
        let busy = false;
        this._pollTimer = setInterval(async () => {
            if (busy) return;
            busy = true;
            try {
                await this._dispatchInt();
            } finally {
                busy = false;
            }
        }, 5);
    }
}

// Source routing: 'int1' (INT1 only), 'int2' (INT2 only), 'both'.
// The value is the bit in CTRL_REG3 (INT1) / CTRL_REG6 (INT2).
const _ROUTE = {
    [LIS3DHSource.SOURCE_DATA_READY]:     {kind: 'int1', mask: 0x10},
    [LIS3DHSource.SOURCE_AUX_DATA_READY]: {kind: 'int1', mask: 0x08},
    [LIS3DHSource.SOURCE_WATERMARK]:      {kind: 'int1', mask: 0x04},
    [LIS3DHSource.SOURCE_OVERRUN]:        {kind: 'int1', mask: 0x02},
    [LIS3DHSource.SOURCE_IA1]:            {kind: 'both', mask: 0x40},
    [LIS3DHSource.SOURCE_IA2]:            {kind: 'both', mask: 0x20},
    [LIS3DHSource.SOURCE_CLICK]:          {kind: 'both', mask: 0x80},
    [LIS3DHSource.SOURCE_ACTIVITY]:       {kind: 'int2', mask: 0x08},
    [LIS3DHSource.SOURCE_BOOT]:           {kind: 'int2', mask: 0x10},
};

// Convenience aliases so the source constants are reachable from the Full
// driver as well.
LIS3DHFull.SOURCE_DATA_READY = LIS3DHSource.SOURCE_DATA_READY;
LIS3DHFull.SOURCE_AUX_DATA_READY = LIS3DHSource.SOURCE_AUX_DATA_READY;
LIS3DHFull.SOURCE_WATERMARK = LIS3DHSource.SOURCE_WATERMARK;
LIS3DHFull.SOURCE_OVERRUN = LIS3DHSource.SOURCE_OVERRUN;
LIS3DHFull.SOURCE_IA1 = LIS3DHSource.SOURCE_IA1;
LIS3DHFull.SOURCE_IA2 = LIS3DHSource.SOURCE_IA2;
LIS3DHFull.SOURCE_CLICK = LIS3DHSource.SOURCE_CLICK;
LIS3DHFull.SOURCE_ACTIVITY = LIS3DHSource.SOURCE_ACTIVITY;
LIS3DHFull.SOURCE_BOOT = LIS3DHSource.SOURCE_BOOT;

module.exports = { LIS3DHMinimal, LIS3DHFull, LIS3DHSource };
