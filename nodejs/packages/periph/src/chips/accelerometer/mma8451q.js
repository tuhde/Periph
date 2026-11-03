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
 * MMA8451Q full interface — extends {@link MMA8451QMinimal} with the complete
 * chip feature set: configurable range / data rate / oversampling, STANDBY
 * and ACTIVE handling, the 32-sample FIFO, the freefall / motion, transient,
 * tap (pulse) and portrait/landscape orientation detectors, Auto-SLEEP /
 * Auto-WAKE, per-axis offset calibration, interrupt enable / routing, the
 * interrupt API and the electrostatic self-test.
 */
class MMA8451QFull extends MMA8451QMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    /**
     * Read 3-axis linear acceleration in *g*.
     *
     * Delegates to {@link MMA8451QMinimal#read}.
     * @returns {Promise<number[]>}
     */
    async read() {
        return super.read();
    }

    // -- range, rate, oversampling ------------------------------------------

    /**
     * Set the full-scale measurement range.
     *
     * @param {number} rangeG - 2, 4 or 8 (*g*).
     * @returns {Promise<void>}
     * @throws {Error} If the range is not 2/4/8, or ±8 *g* is requested while low-noise mode is on.
     */
    async setRange(rangeG) {
        if (!(rangeG in _RANGE_FS)) {
            throw new Error('rangeG must be one of 2, 4, 8');
        }
        if (rangeG === 8 && this._lowNoise) {
            throw new Error('range ±8 g is not available while low-noise mode is enabled');
        }
        this._rangeG = rangeG;
        await this._withStandby(async () => {
            const cfg = await this._readReg(_REG_XYZ_DATA_CFG);
            const out = (cfg & ~_XDC_FS_MASK & 0xFF) | _RANGE_FS[rangeG];
            await this._writeReg(_REG_XYZ_DATA_CFG, out);
        });
    }

    /**
     * Set the output data rate.
     *
     * @param {number} rateHz - One of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 (Hz).
     * @returns {Promise<void>}
     * @throws {Error} If the rate is not one of the supported values.
     */
    async setDataRate(rateHz) {
        const entry = _ODR_CODES.find(e => Math.abs(e[0] - rateHz) < 1e-9);
        if (!entry) {
            throw new Error('rateHz must be one of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56');
        }
        this._odrHz = entry[0];
        await this._withStandby(async () => {
            const cr1 = await this._readReg(_REG_CTRL_REG1);
            const out = (cr1 & ~_CR1_DR_MASK & 0xFF) | (entry[1] << 3);
            await this._writeReg(_REG_CTRL_REG1, out);
        });
    }

    /**
     * Set the oversampling mode used in WAKE/ACTIVE mode (and optionally in
     * SLEEP mode).
     *
     * @param {number} mode - 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power.
     * @param {number} [sleepMode=null] - Optional SLEEP-mode oversampling (0..3).
     * @returns {Promise<void>}
     * @throws {Error} If a mode is out of range.
     */
    async setOversampling(mode, sleepMode = null) {
        if (!(mode >= 0 && mode <= 3)) {
            throw new Error('mode must be 0..3');
        }
        if (sleepMode !== null && !(sleepMode >= 0 && sleepMode <= 3)) {
            throw new Error('sleepMode must be 0..3');
        }
        this._oversampling = mode;
        await this._withStandby(async () => {
            const cr2 = await this._readReg(_REG_CTRL_REG2);
            let out = (cr2 & ~_CR2_MODS & 0xFF) | (mode & 0x03);
            if (sleepMode !== null) {
                this._sleepOversampling = sleepMode;
                out = (out & ~_CR2_SMODS & 0xFF) | ((sleepMode & 0x03) << 3);
            }
            await this._writeReg(_REG_CTRL_REG2, out);
        });
    }

    /**
     * Enable or disable the reduced-noise (LNOISE) mode.
     *
     * Low-noise mode limits the dynamic range to ±4 *g*.
     *
     * @param {boolean} enabled - True to enable reduced-noise mode.
     * @returns {Promise<void>}
     * @throws {Error} If the range is currently ±8 *g*.
     */
    async setLowNoise(enabled) {
        if (enabled && this._rangeG === 8) {
            throw new Error('low-noise mode is not available with the ±8 g range');
        }
        this._lowNoise = !!enabled;
        await this._withStandby(async () => {
            const cr1 = await this._readReg(_REG_CTRL_REG1);
            const out = enabled ? (cr1 | _CR1_LNOISE) : (cr1 & ~_CR1_LNOISE & 0xFF);
            await this._writeReg(_REG_CTRL_REG1, out);
        });
    }

    /**
     * Select the output resolution.
     *
     * 14-bit gives 4096 counts/*g* at ±2 *g*; 8-bit (fast read) gives
     * 64 counts/*g* and only the MSB registers carry data.
     *
     * @param {number} bits - 14 or 8.
     * @returns {Promise<void>}
     * @throws {Error} If the resolution is not 14 or 8, or the FIFO is enabled.
     */
    async setResolution(bits) {
        if (bits !== 14 && bits !== 8) {
            throw new Error('bits must be 14 or 8');
        }
        if (this._fifoMode !== 0) {
            throw new Error('the resolution cannot be changed while the FIFO is enabled');
        }
        this._fastRead = (bits === 8);
        await this._withStandby(async () => {
            const cr1 = await this._readReg(_REG_CTRL_REG1);
            const out = this._fastRead ? (cr1 | _CR1_F_READ) : (cr1 & ~_CR1_F_READ & 0xFF);
            await this._writeReg(_REG_CTRL_REG1, out);
        });
    }

    /**
     * Enable or disable the high-pass-filtered output.
     *
     * The cutoff frequency in Hz depends on the data rate and the
     * oversampling mode (datasheet Table 23); `cutoff` is the raw `SEL`
     * code 0..3.
     *
     * @param {boolean} enabled - True to output high-pass-filtered data.
     * @param {number} [cutoff=0] - High-pass cutoff selection code (0..3).
     * @returns {Promise<void>}
     * @throws {Error} If the cutoff code is out of range.
     */
    async setHighPassOutput(enabled, cutoff = 0) {
        if (!(cutoff >= 0 && cutoff <= 3)) {
            throw new Error('cutoff must be 0..3');
        }
        this._hpfOut = !!enabled;
        await this._withStandby(async () => {
            const xdc = await this._readReg(_REG_XYZ_DATA_CFG);
            const xdcOut = enabled ? (xdc | _XDC_HPF_OUT) : (xdc & ~_XDC_HPF_OUT & 0xFF);
            await this._writeReg(_REG_XYZ_DATA_CFG, xdcOut);

            const hpf = await this._readReg(_REG_HP_FILTER);
            const hpfOut = (hpf & ~_HPF_SEL_MASK & 0xFF) | (cutoff & 0x03);
            await this._writeReg(_REG_HP_FILTER, hpfOut);
        });
    }

    // -- power modes and reset ----------------------------------------------

    /**
     * Put the chip into STANDBY mode.
     *
     * @returns {Promise<void>}
     */
    async standby() {
        const cr1 = await this._readReg(_REG_CTRL_REG1);
        if ((cr1 & _CR1_ACTIVE) === 0) {
            this._active = false;
            return;
        }
        await this._writeReg(_REG_CTRL_REG1, cr1 & ~_CR1_ACTIVE & 0xFF);
        this._active = false;
    }

    /**
     * Put the chip into ACTIVE mode and wait for the turn-on time.
     *
     * @returns {Promise<void>}
     */
    async activate() {
        const cr1 = await this._readReg(_REG_CTRL_REG1);
        if ((cr1 & _CR1_ACTIVE) !== 0) {
            this._active = true;
            await this._waitTurnOn();
            return;
        }
        await this._writeReg(_REG_CTRL_REG1, cr1 | _CR1_ACTIVE);
        this._active = true;
        await this._waitTurnOn();
    }

    /**
     * Perform a software reset.
     *
     * All registers return to their reset values and the chip ends up in
     * STANDBY; run {@link MMA8451QMinimal#init} afterwards to reconfigure it.
     *
     * @returns {Promise<void>}
     */
    async reset() {
        await this._writeReg(_REG_CTRL_REG2, _CR2_RST);
        _delayMs(1);
        const deadline = Date.now() + 10;
        for (;;) {
            let ctrl2 = 0;
            try {
                ctrl2 = await this._readReg(_REG_CTRL_REG2);
            } catch (e) {
                ctrl2 = 0xFF;
            }
            if ((ctrl2 & ~_CR2_RST & 0xFF) === 0) break;
            if (Date.now() > deadline) {
                throw new Error('MMA8451Q reset did not complete (CTRL_REG2 = 0x' + ctrl2.toString(16) + ')');
            }
            _delayMs(1);
        }
        this._active = false;
        this._rangeG = 2;
        this._odrHz = 100;
        this._oversampling = 0;
        this._sleepOversampling = 0;
        this._lowNoise = false;
        this._fastRead = false;
        this._hpfOut = false;
        this._fifoMode = 0;
    }

    // -- status --------------------------------------------------------------

    /**
     * Read the STATUS register.
     * @returns {Promise<number>} STATUS byte (data-ready and overwrite flags).
     */
    async readStatus() {
        return this._readReg(_REG_STATUS);
    }

    /**
     * Check whether a new data sample is available.
     *
     * Not valid while the FIFO is enabled — use {@link MMA8451QFull#fifoLevel}.
     *
     * @returns {Promise<boolean>} True when `ZYXDR` is set.
     */
    async newDataAvailable() {
        const status = await this._readReg(_REG_STATUS);
        return (status & 0x08) !== 0;
    }

    /**
     * Read the current system mode.
     *
     * @returns {Promise<number>} SYSMOD bits 1:0 — 0 standby, 1 wake, 2 sleep.
     */
    async readSystemMode() {
        const sysmod = await this._readReg(_REG_SYSMOD);
        return sysmod & 0x03;
    }

    /**
     * Read the signed acceleration counts of one sample.
     *
     * @returns {Promise<number[]>} [x, y, z] signed counts at the current resolution.
     */
    async readRaw() {
        return this._readCounts();
    }

    // -- offsets -------------------------------------------------------------

    /**
     * Set the per-axis offset correction registers.
     *
     * The offsets are 2 mg/LSB and limited to ±128 counts (±0.256 *g*).
     *
     * @param {number} xG - X offset in *g*.
     * @param {number} yG - Y offset in *g*.
     * @param {number} zG - Z offset in *g*.
     * @returns {Promise<void>}
     */
    async setOffset(xG, yG, zG) {
        const x = _clamp(Math.round(xG / 0.002), -128, 127) & 0xFF;
        const y = _clamp(Math.round(yG / 0.002), -128, 127) & 0xFF;
        const z = _clamp(Math.round(zG / 0.002), -128, 127) & 0xFF;
        await this._withStandby(async () => {
            await this._writeReg(_REG_OFF_X, x);
            await this._writeReg(_REG_OFF_Y, y);
            await this._writeReg(_REG_OFF_Z, z);
        });
    }

    /**
     * Read the per-axis offset correction registers.
     * @returns {Promise<number[]>} [x, y, z] offsets in *g*.
     */
    async getOffset() {
        const x = (await this._readReg(_REG_OFF_X) << 24 >> 24) * 0.002;
        const y = (await this._readReg(_REG_OFF_Y) << 24 >> 24) * 0.002;
        const z = (await this._readReg(_REG_OFF_Z) << 24 >> 24) * 0.002;
        return [x, y, z];
    }

    // -- FIFO ----------------------------------------------------------------

    /**
     * Configure the 32-sample FIFO.
     *
     * The mode must pass through "disabled" when switching between two
     * enabled modes; the driver does that automatically.
     *
     * @param {number} mode - 0 disabled, 1 circular, 2 fill, 3 trigger.
     * @param {number} [watermark=0] - Watermark 0..32 (0 disables the flag).
     * @returns {Promise<void>}
     * @throws {Error} If the mode or the watermark is out of range.
     */
    async setFifo(mode, watermark = 0) {
        if (!(mode >= 0 && mode <= 3)) {
            throw new Error('mode must be 0..3');
        }
        if (!(watermark >= 0 && watermark <= 32)) {
            throw new Error('watermark must be 0..32');
        }
        const wmrk = watermark === 32 ? 0 : watermark;
        const wasEnabled = this._fifoMode !== 0;
        this._fifoMode = mode;
        await this._withStandby(async () => {
            if (wasEnabled && mode !== 0) {
                await this._writeReg(_REG_F_SETUP, 0x00);
            }
            const value = (mode << _FSETUP_MODE_SHIFT) | (wmrk & _FSETUP_WMRK_MASK);
            await this._writeReg(_REG_F_SETUP, value);
        });
    }

    /**
     * Configure the FIFO trigger sources.
     *
     * @param {boolean} [transient=false] - Trigger on the transient detector.
     * @param {boolean} [orientation=false] - Trigger on the orientation detector.
     * @param {boolean} [pulse=false] - Trigger on the tap detector.
     * @param {boolean} [motion=false] - Trigger on the freefall/motion detector.
     * @returns {Promise<void>}
     */
    async setFifoTrigger(transient = false, orientation = false, pulse = false, motion = false) {
        let value = 0;
        if (transient) value |= _TRIG_TRANS;
        if (orientation) value |= _TRIG_LNDPRT;
        if (pulse) value |= _TRIG_PULSE;
        if (motion) value |= _TRIG_FF_MT;
        await this._withStandby(async () => {
            await this._writeReg(_REG_TRIG_CFG, value);
        });
    }

    /**
     * Read the number of samples currently in the FIFO.
     * @returns {Promise<number>} FIFO level, 0..32.
     */
    async fifoLevel() {
        const fstatus = await this._readReg(_REG_STATUS);
        return fstatus & 0x3F;
    }

    /**
     * Read the FIFO status.
     *
     * Reading this register clears the FIFO interrupt.
     *
     * @returns {Promise<{overflow: boolean, watermark: boolean, count: number}>} Decoded F_STATUS.
     */
    async readFifoStatus() {
        const fstatus = await this._readReg(_REG_STATUS);
        return {
            overflow: (fstatus & 0x80) !== 0,
            watermark: (fstatus & 0x40) !== 0,
            count: fstatus & 0x3F,
        };
    }

    /**
     * Drain the FIFO.
     *
     * Each sample is read in its own transaction starting at register 0x01,
     * which is what the datasheet prescribes for FIFO reads.
     *
     * @param {number} [count=null] - Number of samples to read (default: all).
     * @returns {Promise<number[][]>} Array of [x, y, z] samples in *g*.
     */
    async readFifo(count = null) {
        const samples = [];
        const bytesPerSample = this._fastRead ? 3 : 6;
        const max = count === null ? 32 : count;
        for (let i = 0; i < max; i++) {
            const raw = await this._conn.readReg(_REG_OUT_X_MSB, bytesPerSample);
            if (this._fastRead) {
                samples.push([
                    (raw[0] << 24 >> 24),
                    (raw[1] << 24 >> 24),
                    (raw[2] << 24 >> 24),
                ]);
            } else {
                const s = [];
                for (let a = 0; a < 3; a++) {
                    const raw16 = (raw[a * 2] << 8) | raw[a * 2 + 1];
                    s.push((raw16 << 16 >> 16) >> 2);
                }
                samples.push(s);
            }
        }
        const scale = this._fastRead
            ? _RANGE_COUNTS_PER_G_8BIT[this._rangeG]
            : _RANGE_COUNTS_PER_G[this._rangeG];
        return samples.map(s => [s[0] / scale, s[1] / scale, s[2] / scale]);
    }

    // -- freefall / motion ---------------------------------------------------

    /**
     * Configure the freefall / motion detector.
     *
     * @param {boolean} motion - False for freefall (AND of the enabled axes), true for motion (OR).
     * @param {number} [axes=7] - Enabled axes mask (X = 1, Y = 2, Z = 4).
     * @param {number} [thresholdG=0] - Threshold in *g* (0.063 *g* per LSB, valid to 8 *g*).
     * @param {number} [debounceMs=0] - Debounce time in ms.
     * @param {boolean} [latch=false] - Latch the event flags until the source register is read.
     * @param {boolean} [debounceClear=false] - Clear the debounce counter when the condition is lost.
     * @returns {Promise<void>}
     */
    async setFreefallMotion(motion, axes = 7, thresholdG = 0, debounceMs = 0, latch = false, debounceClear = false) {
        const ths = _clamp(Math.round(thresholdG / 0.063), 0, 127);
        const count = this._debounceCount(debounceMs);
        await this._withStandby(async () => {
            let cfg = 0;
            if (latch) cfg |= _FFMT_ELE;
            if (motion) cfg |= _FFMT_OAE;
            cfg |= (axes & 0x07) << 3;
            await this._writeReg(_REG_FF_MT_CFG, cfg);

            const thsOut = (debounceClear ? 0x80 : 0x00) | (ths & 0x7F);
            await this._writeReg(_REG_FF_MT_THS, thsOut);
            await this._writeReg(_REG_FF_MT_COUNT, count);
        });
    }

    /**
     * Read the freefall / motion source flags.
     *
     * @returns {Promise<number>} FF_MT_SRC byte (clears the latched flags).
     */
    async readMotionSource() {
        return this._readReg(_REG_FF_MT_SRC);
    }

    // -- transient -----------------------------------------------------------

    /**
     * Configure the transient (high-pass filtered motion) detector.
     *
     * @param {number} [axes=7] - Enabled axes mask (X = 1, Y = 2, Z = 4).
     * @param {number} [thresholdG=0] - Threshold in *g* (0.063 *g* per LSB).
     * @param {number} [debounceMs=0] - Debounce time in ms.
     * @param {boolean} [bypassHpf=false] - Bypass the high-pass filter (behaves like motion detection).
     * @param {boolean} [latch=false] - Latch the event flags until the source register is read.
     * @returns {Promise<void>}
     */
    async setTransient(axes = 7, thresholdG = 0, debounceMs = 0, bypassHpf = false, latch = false) {
        const ths = _clamp(Math.round(thresholdG / 0.063), 0, 127);
        const count = this._debounceCount(debounceMs);
        await this._withStandby(async () => {
            let cfg = 0;
            if (latch) cfg |= _TRS_ELE;
            cfg |= (axes & 0x07) << 1;
            if (bypassHpf) cfg |= _TRS_HPF;
            await this._writeReg(_REG_TRANSIENT_CFG, cfg);
            await this._writeReg(_REG_TRANSIENT_THS, ths & 0x7F);
            await this._writeReg(_REG_TRANSIENT_COUNT, count);
        });
    }

    /**
     * Read the transient source flags.
     *
     * Reading this register clears the interrupt.
     *
     * @returns {Promise<number>} TRANSIENT_SRC byte.
     */
    async readTransientSource() {
        return this._readReg(_REG_TRANSIENT_SRC);
    }

    // -- tap (pulse) ---------------------------------------------------------

    /**
     * Configure the single / double tap detector.
     *
     * @param {number} [single=0] - Single-tap enabled axes mask (X = 1, Y = 2, Z = 4).
     * @param {number} [double=0] - Double-tap enabled axes mask (X = 1, Y = 2, Z = 4).
     * @param {number[]} [thresholdG=[0.5, 0.5, 0.5]] - Per-axis thresholds in *g* (X, Y, Z).
     * @param {number} [timeLimitMs=30] - Pulse time limit in ms.
     * @param {number} [latencyMs=100] - Latency time in ms.
     * @param {number} [windowMs=300] - Second-pulse window in ms.
     * @param {boolean} [latch=false] - Latch the event flags until the source register is read.
     * @param {boolean} [doubleAbort=false] - Abort double-tap detection when a pulse starts during the latency window.
     * @param {boolean} [lpf=true] - Enable the low-pass filter for the pulse detector.
     * @param {boolean} [hpf=true] - Enable the high-pass filter for the pulse detector.
     * @returns {Promise<void>}
     */
    async setPulse(single = 0, double = 0, thresholdG = [0.5, 0.5, 0.5],
                    timeLimitMs = 30, latencyMs = 100, windowMs = 300,
                    latch = false, doubleAbort = false, lpf = true, hpf = true) {
        const thsx = _clamp(Math.round(thresholdG[0] / 0.063), 0, 127);
        const thsy = _clamp(Math.round(thresholdG[1] / 0.063), 0, 127);
        const thsz = _clamp(Math.round(thresholdG[2] / 0.063), 0, 127);

        const tmlt = this._pulseCount(timeLimitMs, lpf, 0);
        const ltcy = this._pulseCount(latencyMs, lpf, 4);
        const wind = this._pulseCount(windowMs, lpf, 4);

        await this._withStandby(async () => {
            let cfg = 0;
            if (doubleAbort) cfg |= _PULSE_DPA;
            if (latch) cfg |= _PULSE_ELE;
            cfg |= (double & 0x07) | ((single & 0x07) << 3);
            await this._writeReg(_REG_PULSE_CFG, cfg);

            await this._writeReg(_REG_PULSE_THSX, thsx);
            await this._writeReg(_REG_PULSE_THSY, thsy);
            await this._writeReg(_REG_PULSE_THSZ, thsz);
            await this._writeReg(_REG_PULSE_TMLT, tmlt);
            await this._writeReg(_REG_PULSE_LTCY, ltcy);
            await this._writeReg(_REG_PULSE_WIND, wind);

            const hpfReg = await this._readReg(_REG_HP_FILTER);
            let out = hpfReg & ~(_HPF_PULSE_HPF_BYP | _HPF_PULSE_LPF_EN) & 0xFF;
            if (!hpf) out |= _HPF_PULSE_HPF_BYP;
            if (lpf) out |= _HPF_PULSE_LPF_EN;
            await this._writeReg(_REG_HP_FILTER, out);
        });
    }

    /**
     * Read the tap source flags.
     *
     * Reading this register clears the interrupt.
     *
     * @returns {Promise<number>} PULSE_SRC byte.
     */
    async readPulseSource() {
        return this._readReg(_REG_PULSE_SRC);
    }

    // -- orientation ---------------------------------------------------------

    /**
     * Configure the portrait/landscape + back/front orientation detector.
     *
     * @param {boolean} [enabled=true] - Enable the orientation detector.
     * @param {number} [debounceMs=0] - Debounce time in ms.
     * @param {number} [thresholdDeg=45] - Trip angle: 15, 20, 30, 35, 40, 45, 55, 60, 70 or 75 degrees.
     * @param {number} [hysteresis=4] - Hysteresis code 0..7 (±0/±4/±7/±11/±14/±17/±21/±24 degrees).
     * @param {number} [zLock=4] - Z-lock code 0..7 (14/18/21/25/29/33/37/42 degrees).
     * @param {number} [backFront=1] - Back/front threshold code 0..3.
     * @returns {Promise<void>}
     * @throws {Error} If the trip angle is not a Table 32 value, or `THS + HYS` is outside 1..31.
     */
    async setOrientation(enabled = true, debounceMs = 0, thresholdDeg = 45, hysteresis = 4,
                          zLock = 4, backFront = 1) {
        const thsEntry = _ORIENTATION_THRESHOLDS.find(e => e[0] === thresholdDeg);
        if (!thsEntry) {
            throw new Error('thresholdDeg must be one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75');
        }
        if (!(hysteresis >= 0 && hysteresis <= 7)) {
            throw new Error('hysteresis must be 0..7');
        }
        const ths = thsEntry[1];
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw new Error('orientation threshold plus hysteresis must be in 1..31');
        }
        if (!(zLock >= 0 && zLock <= 7)) {
            throw new Error('zLock must be 0..7');
        }
        if (!(backFront >= 0 && backFront <= 3)) {
            throw new Error('backFront must be 0..3');
        }
        const count = this._debounceCount(debounceMs);
        await this._withStandby(async () => {
            const cfg = await this._readReg(_REG_PL_CFG);
            const out = enabled ? (cfg | _PL_EN) : (cfg & ~_PL_EN & 0xFF);
            await this._writeReg(_REG_PL_CFG, out);
            await this._writeReg(_REG_PL_COUNT, count);
            await this._writeReg(_REG_PL_BF_ZCOMP, ((backFront & 0x03) << 6) | (zLock & 0x07));
            await this._writeReg(_REG_PL_THS, ((ths & 0x1F) << _PL_THS_SHIFT) | (hysteresis & 0x07));
        });
    }

    /**
     * Read the orientation status.
     *
     * Reading this register clears the `NEWLP` flag.
     *
     * @returns {Promise<{changed: boolean, lockout: boolean, portraitLandscape: number, back: boolean}>} Decoded PL_STATUS.
     */
    async readOrientation() {
        const status = await this._readReg(_REG_PL_STATUS);
        return {
            changed: (status & 0x80) !== 0,
            lockout: (status & 0x40) !== 0,
            portraitLandscape: (status >> 1) & 0x03,
            back: (status & 0x01) !== 0,
        };
    }

    // -- Auto-SLEEP / Auto-WAKE ---------------------------------------------

    /**
     * Configure Auto-SLEEP and the wake sources.
     *
     * @param {boolean} enabled - Enable Auto-SLEEP.
     * @param {number} [sleepRateHz=50] - Sleep-mode output rate: 50, 12.5, 6.25 or 1.56 Hz.
     * @param {number} [timeoutS=10] - Inactivity timeout in seconds (0..81 s; 0..162 s at 1.56 Hz).
     * @param {number} [wakeSources=0] - Mask of wake sources (SOURCE_TRANSIENT, SOURCE_ORIENTATION, SOURCE_PULSE, SOURCE_FF_MT).
     * @returns {Promise<void>}
     * @throws {Error} If the sleep rate or a wake source is invalid.
     */
    async setAutoSleep(enabled, sleepRateHz = 50, timeoutS = 10, wakeSources = 0) {
        const rateEntry = _ASLP_RATE_CODES.find(e => Math.abs(e[0] - sleepRateHz) < 1e-9);
        if (!rateEntry) {
            throw new Error('sleepRateHz must be one of 50, 12.5, 6.25, 1.56');
        }
        const valid = MMA8451QFull.SOURCE_TRANSIENT | MMA8451QFull.SOURCE_ORIENTATION
                    | MMA8451QFull.SOURCE_PULSE | MMA8451QFull.SOURCE_FF_MT;
        if (wakeSources & ~valid) {
            throw new Error('wakeSources may only contain transient, orientation, pulse and freefall/motion');
        }
        const stepS = this._odrHz >= 6.25 ? 0.32 : 0.64;
        const count = _clamp(Math.round(timeoutS / stepS), 0, 255);

        await this._withStandby(async () => {
            const cr2 = await this._readReg(_REG_CTRL_REG2);
            const cr2Out = enabled ? (cr2 | _CR2_SLPE) : (cr2 & ~_CR2_SLPE & 0xFF);
            await this._writeReg(_REG_CTRL_REG2, cr2Out);

            const cr1 = await this._readReg(_REG_CTRL_REG1);
            const cr1Out = (cr1 & ~_CR1_ASLP_RATE_MASK & 0xFF) | (rateEntry[1] << 6);
            await this._writeReg(_REG_CTRL_REG1, cr1Out);

            await this._writeReg(_REG_ASLP_COUNT, count);

            const cr3 = await this._readReg(_REG_CTRL_REG3);
            let wake = 0;
            if (wakeSources & MMA8451QFull.SOURCE_TRANSIENT) wake |= _CR3_WAKE_TRANS;
            if (wakeSources & MMA8451QFull.SOURCE_ORIENTATION) wake |= _CR3_WAKE_LNDPRT;
            if (wakeSources & MMA8451QFull.SOURCE_PULSE) wake |= _CR3_WAKE_PULSE;
            if (wakeSources & MMA8451QFull.SOURCE_FF_MT) wake |= _CR3_WAKE_FF_MT;
            const cr3Out = (cr3 & ~_CR3_WAKE_MASK & 0xFF) | wake;
            await this._writeReg(_REG_CTRL_REG3, cr3Out);
        });
    }

    /**
     * Enable or disable the FIFO gate.
     *
     * With the gate enabled the FIFO blocks new input around WAKE/SLEEP
     * transitions until it has been emptied.
     *
     * @param {boolean} enabled - True to gate the FIFO.
     * @returns {Promise<void>}
     */
    async setFifoGate(enabled) {
        await this._withStandby(async () => {
            const cr3 = await this._readReg(_REG_CTRL_REG3);
            const out = enabled ? (cr3 | _CR3_FIFO_GATE) : (cr3 & ~_CR3_FIFO_GATE & 0xFF);
            await this._writeReg(_REG_CTRL_REG3, out);
        });
    }

    /**
     * Configure the interrupt pin polarity and drive.
     *
     * Both INT1 and INT2 share these settings.
     *
     * @param {boolean} [activeHigh=false] - True for active-high interrupts (default active-low).
     * @param {boolean} [openDrain=false] - True for open-drain output (default push-pull).
     * @returns {Promise<void>}
     */
    async setInterruptPins(activeHigh = false, openDrain = false) {
        await this._withStandby(async () => {
            const cr3 = await this._readReg(_REG_CTRL_REG3);
            let out = cr3 & ~(_CR3_IPOL | _CR3_PP_OD) & 0xFF;
            if (activeHigh) out |= _CR3_IPOL;
            if (openDrain) out |= _CR3_PP_OD;
            await this._writeReg(_REG_CTRL_REG3, out);
        });
    }

    // -- interrupt API -------------------------------------------------------

    /**
     * Enable an interrupt source and route it to INT1 or INT2.
     *
     * @param {number} source - One of the SOURCE_* constants.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     * @throws {Error} If the source or the pin is invalid.
     */
    async enableInterrupt(source, pin = 1) {
        if (!(pin === 1 || pin === 2)) {
            throw new Error('pin must be 1 or 2');
        }
        if (!_isValidSource(source)) {
            throw new Error('unknown interrupt source');
        }
        await this._withStandby(async () => {
            const cr4 = await this._readReg(_REG_CTRL_REG4);
            await this._writeReg(_REG_CTRL_REG4, cr4 | source);

            const cr5 = await this._readReg(_REG_CTRL_REG5);
            const out = pin === 1 ? (cr5 | source) : (cr5 & ~source & 0xFF);
            await this._writeReg(_REG_CTRL_REG5, out);
        });
    }

    /**
     * Disable an interrupt source.
     *
     * @param {number} source - One of the SOURCE_* constants.
     * @returns {Promise<void>}
     * @throws {Error} If the source is invalid.
     */
    async disableInterrupt(source) {
        if (!_isValidSource(source)) {
            throw new Error('unknown interrupt source');
        }
        await this._withStandby(async () => {
            const cr4 = await this._readReg(_REG_CTRL_REG4);
            await this._writeReg(_REG_CTRL_REG4, cr4 & ~source & 0xFF);
        });
    }

    /**
     * Read the interrupt status and clear the latched flags.
     *
     * Reads `INT_SOURCE` and returns it. For every set bit except
     * `SOURCE_DATA_READY` the matching source register is read as well, so
     * that the flag and the interrupt pin are cleared. `SOURCE_DATA_READY`
     * stays set — read the data registers to clear it.
     *
     * @returns {Promise<number>} INT_SOURCE bitmask.
     */
    async pollInterrupt() {
        const status = await this._readReg(_REG_INT_SOURCE);
        if (status & MMA8451QFull.SOURCE_FIFO) {
            await this._readReg(_REG_STATUS);
        }
        if (status & MMA8451QFull.SOURCE_FF_MT) {
            await this._readReg(_REG_FF_MT_SRC);
        }
        if (status & MMA8451QFull.SOURCE_PULSE) {
            await this._readReg(_REG_PULSE_SRC);
        }
        if (status & MMA8451QFull.SOURCE_ORIENTATION) {
            await this._readReg(_REG_PL_STATUS);
        }
        if (status & MMA8451QFull.SOURCE_TRANSIENT) {
            await this._readReg(_REG_TRANSIENT_SRC);
        }
        if (status & MMA8451QFull.SOURCE_AUTO_SLEEP) {
            await this._readReg(_REG_SYSMOD);
        }
        return status;
    }

    /**
     * Subscribe to interrupt events on INT1 or INT2.
     *
     * Uses `connection.intPin.onEdge()` when an InputPin is wired — the chip
     * drives its interrupt pins active-low by default, so a falling edge is
     * expected unless {@link MMA8451QFull#setInterruptPins} changed the
     * polarity. Without an InputPin the driver falls back to a 5 ms polling
     * loop that reports every status change.
     *
     * @param {function(number): void} callback - Receives the `pollInterrupt()` mask.
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, pin = 1) {
        if (!(pin === 1 || pin === 2)) {
            throw new Error('pin must be 1 or 2');
        }
        await this.offInterrupt(pin);
        this._callback = callback;
        const intPin = this._conn.intPin;
        if (intPin) {
            let activeHigh = false;
            try {
                const cr3 = await this._readReg(_REG_CTRL_REG3);
                activeHigh = (cr3 & _CR3_IPOL) !== 0;
            } catch (e) { /* keep the default polarity */ }
            this._edgeHandler = async () => {
                const status = await this.pollInterrupt();
                if (this._callback) this._callback(status);
            };
            await intPin.onEdge(this._edgeHandler, activeHigh ? 'rising' : 'falling');
        } else {
            let last = await this.pollInterrupt();
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this.pollInterrupt();
                    if (status !== last) {
                        last = status;
                        if (this._callback) this._callback(status);
                    }
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /**
     * Unsubscribe from interrupt events.
     *
     * @param {number} [pin=1] - 1 for INT1, 2 for INT2.
     * @returns {Promise<void>}
     */
    async offInterrupt(pin = 1) {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }

    // -- self-test -----------------------------------------------------------

    /**
     * Run the electrostatic self-test.
     *
     * Puts the chip into STANDBY, configures ±4 *g* at 100 Hz with normal
     * oversampling, averages a few samples, applies the electrostatic force,
     * averages again and reports the count deltas. The previous configuration
     * is restored afterwards.
     *
     * @returns {Promise<{ok: boolean, dx: number, dy: number, dz: number}>} Self-test result.
     */
    async selfTest() {
        const savedRange = this._rangeG;
        const savedOdr = this._odrHz;
        const savedOversampling = this._oversampling;

        await this.setRange(4);
        await this.setDataRate(100);
        await this.setOversampling(0);
        await this.activate();

        const before = await this._averageCounts(4);
        await this._writeReg(_REG_CTRL_REG2, _CR2_ST);
        _delayMs((2.0 / this._odrHz) * 1000.0 + 2.0);
        const after = await this._averageCounts(4);
        await this._writeReg(_REG_CTRL_REG2, 0x00);

        const dx = after[0] - before[0];
        const dy = after[1] - before[1];
        const dz = after[2] - before[2];
        const ok = dx > _SELF_TEST_X / 2 && dy > _SELF_TEST_Y / 2 && dz > _SELF_TEST_Z / 2;

        await this.setRange(savedRange);
        await this.setDataRate(savedOdr);
        await this.setOversampling(savedOversampling);

        return { ok, dx, dy, dz };
    }

    /**
     * Average `n` samples of the raw counts.
     * @param {number} n - Number of samples.
     * @returns {Promise<number[]>} [x, y, z] mean counts.
     */
    async _averageCounts(n) {
        const sum = [0, 0, 0];
        for (let i = 0; i < n; i++) {
            const s = await this._readCounts();
            for (let a = 0; a < 3; a++) sum[a] += s[a];
        }
        return sum.map(v => v / n);
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Run `fn` with the chip temporarily in STANDBY, then restore ACTIVE.
     *
     * Most configuration registers may only be written while
     * `CTRL_REG1.ACTIVE` is 0; this helper takes care of that and of the
     * turn-on wait afterwards.
     *
     * @param {function(): Promise<void>} fn - The register write(s) to perform.
     * @returns {Promise<void>}
     */
    async _withStandby(fn) {
        const cr1 = await this._readReg(_REG_CTRL_REG1);
        const wasActive = (cr1 & _CR1_ACTIVE) !== 0;
        if (wasActive) {
            await this._writeReg(_REG_CTRL_REG1, cr1 & ~_CR1_ACTIVE & 0xFF);
        }
        try {
            await fn();
        } finally {
            if (wasActive) {
                const now = await this._readReg(_REG_CTRL_REG1);
                await this._writeReg(_REG_CTRL_REG1, now | _CR1_ACTIVE);
                this._active = true;
                await this._waitTurnOn();
            } else {
                this._active = false;
            }
        }
    }

    /**
     * Convert a debounce time in ms into a debounce counter value.
     * @param {number} debounceMs - Debounce time in ms.
     * @returns {number} Counter value 0..255.
     */
    _debounceCount(debounceMs) {
        const steps = _DEBOUNCE_STEPS[this._odrHz] || _DEBOUNCE_STEPS[100];
        const step = steps[this._oversampling] || steps[0];
        return _clamp(Math.round(debounceMs / step), 0, 255);
    }

    /**
     * Convert a pulse time in ms into a PULSE_TMLT / PULSE_LTCY / PULSE_WIND
     * counter value.
     * @param {number} timeMs - Time in ms.
     * @param {boolean} lpf - True when the low-pass filter is enabled.
     * @param {number} offset - 0 for TMLT, 4 for LTCY/WIND within the step table.
     * @returns {number} Counter value 0..255.
     */
    _pulseCount(timeMs, lpf, offset) {
        const table = lpf ? _PULSE_STEPS_LPF : _PULSE_STEPS_NO_LPF;
        const steps = table[this._odrHz] || table[100];
        const step = steps[offset + this._oversampling];
        return _clamp(Math.round(timeMs / step), 0, 255);
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

MMA8451QFull.SOURCE_DATA_READY  = SOURCE_DATA_READY;
MMA8451QFull.SOURCE_FF_MT       = SOURCE_FF_MT;
MMA8451QFull.SOURCE_PULSE       = SOURCE_PULSE;
MMA8451QFull.SOURCE_ORIENTATION = SOURCE_ORIENTATION;
MMA8451QFull.SOURCE_TRANSIENT   = SOURCE_TRANSIENT;
MMA8451QFull.SOURCE_FIFO        = SOURCE_FIFO;
MMA8451QFull.SOURCE_AUTO_SLEEP  = SOURCE_AUTO_SLEEP;

module.exports = {
    MMA8451QMinimal,
    MMA8451QFull,
    SOURCE_DATA_READY,
    SOURCE_FF_MT,
    SOURCE_PULSE,
    SOURCE_ORIENTATION,
    SOURCE_TRANSIENT,
    SOURCE_FIFO,
    SOURCE_AUTO_SLEEP,
};
