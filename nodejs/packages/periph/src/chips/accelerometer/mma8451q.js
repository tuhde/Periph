'use strict';

// Register map (datasheet Rev 8, Table 8).
const _REG_STATUS       = 0x00;   // STATUS / F_STATUS
const _REG_OUT_X_MSB    = 0x01;
const _REG_OUT_X_LSB    = 0x02;
const _REG_OUT_Y_MSB    = 0x03;
const _REG_OUT_Y_LSB    = 0x04;
const _REG_OUT_Z_MSB    = 0x05;
const _REG_OUT_Z_LSB    = 0x06;
const _REG_F_SETUP      = 0x09;
const _REG_TRIG_CFG     = 0x0A;
const _REG_SYSMOD       = 0x0B;
const _REG_INT_SOURCE   = 0x0C;
const _REG_WHO_AM_I     = 0x0D;
const _REG_XYZ_DATA_CFG = 0x0E;
const _REG_HP_FILTER    = 0x0F;
const _REG_PL_STATUS    = 0x10;
const _REG_PL_CFG       = 0x11;
const _REG_PL_COUNT     = 0x12;
const _REG_PL_BF_ZCOMP  = 0x13;
const _REG_PL_THS       = 0x14;
const _REG_FF_MT_CFG    = 0x15;
const _REG_FF_MT_SRC    = 0x16;
const _REG_FF_MT_THS    = 0x17;
const _REG_FF_MT_COUNT  = 0x18;
const _REG_TRANSIENT_CFG  = 0x1D;
const _REG_TRANSIENT_SRC  = 0x1E;
const _REG_TRANSIENT_THS  = 0x1F;
const _REG_TRANSIENT_COUNT = 0x20;
const _REG_PULSE_CFG    = 0x21;
const _REG_PULSE_SRC    = 0x22;
const _REG_PULSE_THSX   = 0x23;
const _REG_PULSE_THSY   = 0x24;
const _REG_PULSE_THSZ   = 0x25;
const _REG_PULSE_TMLT   = 0x26;
const _REG_PULSE_LTCY   = 0x27;
const _REG_PULSE_WIND   = 0x28;
const _REG_ASLP_COUNT   = 0x29;
const _REG_CTRL_REG1    = 0x2A;
const _REG_CTRL_REG2    = 0x2B;
const _REG_CTRL_REG3    = 0x2C;
const _REG_CTRL_REG4    = 0x2D;
const _REG_CTRL_REG5    = 0x2E;
const _REG_OFF_X        = 0x2F;
const _REG_OFF_Y        = 0x30;
const _REG_OFF_Z        = 0x31;

const _WHO_AM_I_VALUE = 0x1A;

// CTRL_REG1 bit positions.
const _CR1_ASLP_RATE_MASK = 0xC0;
const _CR1_DR_MASK        = 0x38;
const _CR1_LNOISE         = 0x04;
const _CR1_F_READ         = 0x02;
const _CR1_ACTIVE         = 0x01;

// CTRL_REG2 bit positions.
const _CR2_ST    = 0x80;
const _CR2_RST   = 0x40;
const _CR2_SMODS = 0x18;
const _CR2_SLPE  = 0x04;
const _CR2_MODS  = 0x03;

// CTRL_REG3 bit positions.
const _CR3_FIFO_GATE   = 0x80;
const _CR3_WAKE_MASK   = 0x78;
const _CR3_WAKE_TRANS  = 0x40;
const _CR3_WAKE_LNDPRT = 0x20;
const _CR3_WAKE_PULSE  = 0x10;
const _CR3_WAKE_FF_MT  = 0x08;
const _CR3_IPOL        = 0x02;
const _CR3_PP_OD       = 0x01;

// XYZ_DATA_CFG bits.
const _XDC_HPF_OUT = 0x10;
const _XDC_FS_MASK = 0x03;

// FF_MT_CFG bits.
const _FFMT_ELE   = 0x80;
const _FFMT_OAE   = 0x40;
const _FFMT_AXES  = 0x38;

// TRANSIENT_CFG bits.
const _TRS_ELE   = 0x10;
const _TRS_AXES  = 0x0E;
const _TRS_HPF   = 0x01;

// PULSE_CFG bits.
const _PULSE_DPA = 0x80;
const _PULSE_ELE = 0x40;
const _PULSE_AXES = 0x3F;

// PL_CFG bits.
const _PL_DBCNTM = 0x80;
const _PL_EN     = 0x40;

// PL_BF_ZCOMP bits.
const _ZCOMP_BKFR_MASK = 0xC0;
const _ZCOMP_ZLOCK     = 0x07;

// P_L_THS_REG bits.
const _PL_THS_MASK = 0xF8;
const _PL_THS_SHIFT = 3;
const _PL_HYS_MASK = 0x07;

// F_SETUP bits.
const _FSETUP_MODE_MASK = 0xC0;
const _FSETUP_MODE_SHIFT = 6;
const _FSETUP_WMRK_MASK = 0x3F;

// TRIG_CFG bits.
const _TRIG_TRANS = 0x20;
const _TRIG_LNDPRT = 0x10;
const _TRIG_PULSE = 0x08;
const _TRIG_FF_MT = 0x04;

// HP_FILTER_CUTOFF bits.
const _HPF_PULSE_HPF_BYP = 0x20;
const _HPF_PULSE_LPF_EN  = 0x10;
const _HPF_SEL_MASK      = 0x03;

// Full-scale range → XYZ_DATA_CFG FS code and counts per g (14-bit).
const _RANGE_FS = { 2: 0x00, 4: 0x01, 8: 0x02 };
const _RANGE_COUNTS_PER_G = { 2: 4096, 4: 2048, 8: 1024 };
// 8-bit (fast read) counts per g.
const _RANGE_COUNTS_PER_G_8BIT = { 2: 64, 4: 32, 8: 16 };

// Output data rate → CTRL_REG1 DR code.
const _ODR_CODES = [
    [800, 0b000], [400, 0b001], [200, 0b010], [100, 0b011],
    [50, 0b100], [12.5, 0b101], [6.25, 0b110], [1.56, 0b111],
];

// Auto-SLEEP rate → CTRL_REG1 ASLP_RATE code.
const _ASLP_RATE_CODES = [[50, 0b00], [12.5, 0b01], [6.25, 0b10], [1.56, 0b11]];

// Debounce step (ms) per ODR and oversampling mode (datasheet Table 27).
// Columns: normal, low-noise low-power, high resolution, low power.
const _DEBOUNCE_STEPS = {
    800:  [1.25, 1.25, 1.25, 1.25],
    400:  [2.5, 2.5, 2.5, 2.5],
    200:  [5, 5, 2.5, 5],
    100:  [10, 10, 2.5, 10],
    50:   [20, 20, 2.5, 20],
    12.5: [20, 80, 2.5, 80],
    6.25: [20, 80, 2.5, 160],
    1.56: [20, 80, 2.5, 160],
};

// Pulse time steps (ms): TMLT and LTCY/WIND per ODR, lpf setting and
// oversampling mode (datasheet Tables 50/51 and 53/54).
// [lpf ? TMLT : TMLT, lpf ? LTCY : LTCY] — each entry is
// [tmltNormal, tmltLpln, tmltHr, tmltLp, ltcyNormal, ltcyLpln, ltcyHr, ltcyLp].
const _PULSE_STEPS_LPF = {
    800:  [1.25, 1.25, 1.25, 1.25, 2.5, 2.5, 2.5, 2.5],
    400:  [2.5, 2.5, 2.5, 2.5, 5, 5, 5, 5],
    200:  [5, 5, 2.5, 5, 10, 10, 5, 10],
    100:  [10, 10, 2.5, 10, 20, 20, 5, 20],
    50:   [20, 20, 2.5, 20, 40, 40, 5, 40],
    12.5: [20, 80, 2.5, 80, 40, 160, 5, 160],
    6.25: [20, 80, 2.5, 160, 40, 160, 5, 320],
    1.56: [20, 80, 2.5, 160, 40, 160, 5, 320],
};
const _PULSE_STEPS_NO_LPF = {
    800:  [0.625, 0.625, 0.625, 0.625, 1.25, 1.25, 1.25, 1.25],
    400:  [0.625, 0.625, 0.625, 1.25, 1.25, 1.25, 1.25, 2.5],
    200:  [1.25, 1.25, 0.625, 2.5, 2.5, 2.5, 1.25, 5],
    100:  [2.5, 2.5, 0.625, 5, 5, 5, 1.25, 10],
    50:   [5, 5, 0.625, 10, 10, 10, 1.25, 20],
    12.5: [5, 20, 0.625, 40, 10, 40, 1.25, 80],
    6.25: [5, 20, 0.625, 40, 10, 40, 1.25, 80],
    1.56: [5, 20, 0.625, 40, 10, 40, 1.25, 80],
};

// Orientation trip angles (degrees) → P_L_THS code (datasheet Table 32).
const _ORIENTATION_THRESHOLDS = [
    [15, 0x07], [20, 0x09], [30, 0x0C], [35, 0x0D], [40, 0x0F],
    [45, 0x10], [55, 0x13], [60, 0x14], [70, 0x17], [75, 0x19],
];

// Self-test typical count deltas (14-bit counts) per axis.
const _SELF_TEST_X = 181;
const _SELF_TEST_Y = 255;
const _SELF_TEST_Z = 1680;

function _clamp(value, lo, hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
}

function _delayMs(ms) {
    if (!(ms > 0)) return;
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * MMA8451Q 3-axis MEMS accelerometer (NXP) — minimal interface.
 *
 * Low-power capacitive 3-axis accelerometer with 14-bit output (4096
 * counts/*g* at ±2 *g*), selectable ±2/±4/±8 *g* full scale and output data
 * rates from 1.56 Hz to 800 Hz. Communicates over I²C at address 0x1C
 * (SA0 = GND) or 0x1D (SA0 = VDDIO).
 *
 * Default configuration (written by {@link MMA8451QMinimal#init}):
 * - Range ±2 *g* (4096 counts/*g*)
 * - Output data rate 100 Hz
 * - 14-bit output, normal oversampling
 * - Low-noise mode off, high-pass-filtered output off
 * - FIFO disabled, all detectors and interrupt routing off
 * - Interrupts active-low, push-pull
 * - Offsets 0
 *
 * Constructor caveat: JS constructors cannot be async, so the init
 * sequence is fired off unawaited from the constructor. A `WHO_AM_I`
 * mismatch therefore surfaces as an *unhandled promise rejection* shortly
 * after construction rather than a synchronous throw from
 * `new MMA8451QMinimal(...)`. Callers that need deterministic behaviour
 * should `await chip.init()` — the same sequence, exposed as a public
 * async method — and handle its rejection.
 */
class MMA8451QMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._rangeG = 2;
        this._odrHz = 100;
        this._oversampling = 0;
        this._sleepOversampling = 0;
        this._lowNoise = false;
        this._fastRead = false;
        this._hpfOut = false;
        this._fifoMode = 0;
        this._active = false;
        // The init sequence is fired off unawaited — a JS constructor cannot
        // await. `init()` exposes the same sequence so callers that need a
        // deterministic result can await it.
        this._ready = this._initSequence();
        // Surface an init failure (e.g. wrong device) as an unhandled
        // rejection rather than silently swallowing it.
        this._ready.catch(() => {});
    }

    /**
     * Run the initialization sequence.
     *
     * Waits for the I²C interface to come up, verifies `WHO_AM_I` is 0x1A,
     * performs a software reset, then configures ±2 *g*, 100 Hz, 14-bit
     * output with normal oversampling and puts the chip into ACTIVE mode.
     *
     * The constructor starts this sequence unawaited; call `init()` explicitly
     * (`await chip.init()`) when the outcome must be observed. Awaiting it
     * after the constructor has already started the sequence waits for that
     * same run rather than starting a second one.
     *
     * @returns {Promise<void>} Resolves once the chip is in ACTIVE mode.
     * @throws {Error} If `WHO_AM_I` does not read 0x1A, or the reset never completes.
     */
    async init() {
        await this._ready;
    }

    /**
     * The actual initialization sequence.
     * @returns {Promise<void>}
     */
    async _initSequence() {
        _delayMs(1);

        const who = await this._readReg(_REG_WHO_AM_I);
        if (who !== _WHO_AM_I_VALUE) {
            throw new Error('MMA8451Q WHO_AM_I: expected 0x1a, got 0x' + who.toString(16));
        }

        // Software reset: the I²C interface is reset too, so the write may
        // complete without ACK. Wait and re-read CTRL_REG2 until the
        // meaningful bits are 0 again. RST is a self-clearing bit (reading
        // it returns 0), so it is masked out of the check.
        await this._writeReg(_REG_CTRL_REG2, _CR2_RST);
        _delayMs(1);
        const deadline = Date.now() + 10;
        for (;;) {
            let ctrl2 = 0;
            try {
                ctrl2 = await this._readReg(_REG_CTRL_REG2);
            } catch (e) {
                ctrl2 = 0xFF;   // NACK: the interface is still resetting
            }
            if ((ctrl2 & ~_CR2_RST & 0xFF) === 0) break;
            if (Date.now() > deadline) {
                throw new Error('MMA8451Q reset did not complete (CTRL_REG2 = 0x' + ctrl2.toString(16) + ')');
            }
            _delayMs(1);
        }

        await this._writeReg(_REG_XYZ_DATA_CFG, 0x00);   // ±2 g, no HPF output
        await this._writeReg(_REG_CTRL_REG2, 0x00);     // normal oversampling, Auto-SLEEP off
        await this._writeReg(_REG_CTRL_REG1, 0x19);     // DR = 011 (100 Hz), ACTIVE = 1

        this._rangeG = 2;
        this._odrHz = 100;
        this._oversampling = 0;
        this._sleepOversampling = 0;
        this._lowNoise = false;
        this._fastRead = false;
        this._hpfOut = false;
        this._fifoMode = 0;
        this._active = true;

        await this._waitTurnOn();
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    /**
     * Wait for the STANDBY → ACTIVE turn-on time (2/ODR + 2 ms).
     * @returns {Promise<void>}
     */
    async _waitTurnOn() {
        _delayMs((2.0 / this._odrHz) * 1000.0 + 2.0);
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Reads the six data bytes (0x01..0x06) in one burst so X, Y and Z come
     * from a single measurement.
     *
     * @returns {Promise<number[]>} [x, y, z] acceleration in *g*.
     */
    async read() {
        const counts = await this._readCounts();
        const scale = this._fastRead
            ? _RANGE_COUNTS_PER_G_8BIT[this._rangeG]
            : _RANGE_COUNTS_PER_G[this._rangeG];
        return [counts[0] / scale, counts[1] / scale, counts[2] / scale];
    }

    /**
     * Read the raw signed counts of one 14-bit (or 8-bit) sample.
     * @returns {Promise<number[]>} [x, y, z] signed counts.
     */
    async _readCounts() {
        if (this._fastRead) {
            const raw = await this._conn.readReg(_REG_OUT_X_MSB, 3);
            return [(raw[0] << 24 >> 24), (raw[1] << 24 >> 24), (raw[2] << 24 >> 24)];
        }
        const raw = await this._conn.readReg(_REG_OUT_X_MSB, 6);
        const counts = [];
        for (let i = 0; i < 3; i++) {
            const raw16 = (raw[i * 2] << 8) | raw[i * 2 + 1];
            counts.push((raw16 << 16 >> 16) >> 2);   // arithmetic shift: 14-bit signed
        }
        return counts;
    }
}

/**
 * New XYZ sample (`ZYXDR` / `ZYXOW`).
 * @constant {number}
 */
const SOURCE_DATA_READY = 0x01;
/**
 * Freefall / motion event (`FF_MT_SRC.EA`).
 * @constant {number}
 */
const SOURCE_FF_MT = 0x04;
/**
 * Single / double tap event (`PULSE_SRC.EA`).
 * @constant {number}
 */
const SOURCE_PULSE = 0x08;
/**
 * Orientation change (`PL_STATUS.NEWLP`).
 * @constant {number}
 */
const SOURCE_ORIENTATION = 0x10;
/**
 * Transient event (`TRANSIENT_SRC.EA`).
 * @constant {number}
 */
const SOURCE_TRANSIENT = 0x20;
/**
 * FIFO overflow or watermark reached.
 * @constant {number}
 */
const SOURCE_FIFO = 0x40;
/**
 * WAKE ↔ SLEEP transition.
 * @constant {number}
 */
const SOURCE_AUTO_SLEEP = 0x80;

function _isValidSource(source) {
    return source === SOURCE_DATA_READY || source === SOURCE_FF_MT || source === SOURCE_PULSE
        || source === SOURCE_ORIENTATION || source === SOURCE_TRANSIENT
        || source === SOURCE_FIFO || source === SOURCE_AUTO_SLEEP;
}


module.exports = {
    MMA8451QMinimal,
    SOURCE_DATA_READY,
    SOURCE_FF_MT,
    SOURCE_PULSE,
    SOURCE_ORIENTATION,
    SOURCE_TRANSIENT,
    SOURCE_FIFO,
    SOURCE_AUTO_SLEEP,
};
