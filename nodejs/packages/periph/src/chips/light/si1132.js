'use strict';

const _REG_PART_ID = 0x00;
const _REG_SEQ_ID = 0x02;
const _REG_INT_CFG = 0x03;
const _REG_IRQ_ENABLE = 0x04;
const _REG_HW_KEY = 0x07;
const _REG_MEAS_RATE0 = 0x08;
const _REG_MEAS_RATE1 = 0x09;
const _REG_MEAS_RATE_SWAPPED = 0x0A;
const _REG_UCOEF = 0x13;
const _REG_PARAM_WR = 0x17;
const _REG_COMMAND = 0x18;
const _REG_RESPONSE = 0x20;
const _REG_IRQ_STATUS = 0x21;
const _REG_DATA = 0x22;
const _REG_PARAM_RD = 0x2E;

const _PART_ID = 0x32;
const _HW_KEY_VALUE = 0x17;
const _UCOEF_DEFAULT = [0x7B, 0x6B, 0x01, 0x00];

const _CMD_PARAM_QUERY = 0x80;
const _CMD_PARAM_SET = 0xA0;
const _CMD_NOP = 0x00;
const _CMD_RESET = 0x01;
const _CMD_BUSADDR = 0x02;
const _CMD_ALS_FORCE = 0x06;
const _CMD_ALS_PAUSE = 0x0A;
const _CMD_ALS_AUTO = 0x0E;
const _CMD_GET_CAL = 0x12;

const _PARAM_I2C_ADDR = 0x00;
const _PARAM_CHLIST = 0x01;
const _PARAM_ALS_ENCODING = 0x06;
const _PARAM_AUX_ADCMUX = 0x0F;
const _PARAM_VIS_COUNTER = 0x10;
const _PARAM_VIS_GAIN = 0x11;
const _PARAM_VIS_MISC = 0x12;
const _PARAM_IR_COUNTER = 0x1D;
const _PARAM_IR_GAIN = 0x1E;
const _PARAM_IR_MISC = 0x1F;

const _CHLIST_ALL = 0xB0;   // EN_UV | EN_ALS_IR | EN_ALS_VIS
const _CHLIST_AUX = 0x40;
const _IRQ_ALS_INT = 0x01;
const _IRQ_CLEAR = 0x21;    // ALS_INT | CMD_INT (write 1 to clear)
const _RESP_ERROR = 0x80;
const _TIMEOUT_MS = 500;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Si1132 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 * The chip is a small sequencer: configuration lives partly in registers and partly in a parameter
 * RAM written with PARAM_WR + PARAM_SET. `init()` waits 25 ms, verifies PART_ID = 0x32, writes HW_KEY,
 * the default UV coefficients and enables the UV, infrared and visible channels in forced mode (gain 0,
 * normal range). `read()` starts a forced measurement, waits for ALS_INT (timeout 500 ms) and returns
 * the UV index (AUX_DATA / 100) plus the raw 16-bit visible and infrared counts. The I²C address is
 * 0x60. The constructor does not touch the bus.
 */
class Si1132Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x60).
     */
    constructor(connection) {
        this._conn = connection;
        this._chlist = _CHLIST_ALL;
        this._seqId = 0;
    }

    /**
     * Verify PART_ID and configure forced UV + visible + infrared measurements.
     * @returns {Promise<void>}
     * @throws {Error} If PART_ID is not 0x32 or a command fails.
     */
    async init() {
        await _sleep(25);
        if ((await this._readReg(_REG_PART_ID)) !== _PART_ID) {
            throw new Error('Si1132 not found (PART_ID mismatch)');
        }
        this._seqId = await this._readReg(_REG_SEQ_ID);
        await this._writeReg(_REG_HW_KEY, _HW_KEY_VALUE);
        await this._conn.writeReg(_REG_UCOEF, Buffer.from(_UCOEF_DEFAULT));
        // ALS_IE lets IRQ_STATUS.ALS_INT flag completed measurements; INT stays released (INT_OE = 0).
        await this._writeReg(_REG_IRQ_ENABLE, _IRQ_ALS_INT);
        this._chlist = _CHLIST_ALL;
        await this._paramSet(_PARAM_CHLIST, this._chlist);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _waitResponse(before) {
        for (let i = 0; i < 20; i++) {
            const resp = await this._readReg(_REG_RESPONSE);
            if (resp & _RESP_ERROR) {
                await this._writeReg(_REG_COMMAND, _CMD_NOP);
                throw new Error('Si1132 command error 0x' + resp.toString(16));
            }
            if ((resp & 0x0F) !== before) return;
            await _sleep(1);
        }
        throw new Error('Si1132 command timeout');
    }

    async _command(cmd) {
        const before = (await this._readReg(_REG_RESPONSE)) & 0x0F;
        await this._writeReg(_REG_COMMAND, cmd);
        await this._waitResponse(before);
    }

    async _paramSet(offset, value) {
        const before = (await this._readReg(_REG_RESPONSE)) & 0x0F;
        await this._writeReg(_REG_PARAM_WR, value);
        await this._writeReg(_REG_COMMAND, _CMD_PARAM_SET | (offset & 0x1F));
        await this._waitResponse(before);
    }

    async _startMeasurement() {
        await this._writeReg(_REG_IRQ_STATUS, _IRQ_CLEAR);
        await this._command(_CMD_ALS_FORCE);
    }

    async _waitReady() {
        let waited = 0;
        while (!((await this._readReg(_REG_IRQ_STATUS)) & _IRQ_ALS_INT)) {
            if (waited >= _TIMEOUT_MS) throw new Error('Si1132 measurement timeout');
            await _sleep(1);
            waited++;
        }
        await this._writeReg(_REG_IRQ_STATUS, _IRQ_CLEAR);
    }

    async _readData() {
        const d = Buffer.from(await this._conn.readReg(_REG_DATA, 12));
        return {
            visible: d[0] | (d[1] << 8),
            infrared: d[2] | (d[3] << 8),
            aux: d[10] | (d[11] << 8),
        };
    }

    async _measure() {
        await this._startMeasurement();
        await this._waitReady();
        return this._readData();
    }

    /**
     * Run one forced measurement of the enabled channels.
     * @returns {Promise<{uv_index: number, visible: number, infrared: number}>} UV index (AUX_DATA / 100) and raw 16-bit counts.
     */
    async read() {
        const d = await this._measure();
        return { uv_index: d.aux / 100, visible: d.visible, infrared: d.infrared };
    }
}

/**
 * Si1132 full interface — single channels, gain/range, autonomous mode, calibration, auxiliary
 * channels and the INT pin. Extends Si1132Minimal.
 */
class Si1132Full extends Si1132Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x60).
     */
    constructor(connection) {
        super(connection);
        this._intCallback = null;
        this._intPin = null;
        this._intHandler = null;
        this._pollTimer = null;
    }

    /** @returns {Promise<number>} UV index from a forced measurement (AUX_DATA / 100). */
    async readUvIndex() {
        return (await this._measure()).aux / 100;
    }

    /** @returns {Promise<number>} Raw 16-bit visible count from a forced measurement. */
    async readVisible() {
        return (await this._measure()).visible;
    }

    /** @returns {Promise<number>} Raw 16-bit infrared count from a forced measurement. */
    async readInfrared() {
        return (await this._measure()).infrared;
    }

    /** @returns {Promise<{visible: number, infrared: number, aux: number}>} Latest data registers, no measurement started. */
    async readRaw() {
        return this._readData();
    }

    async _readAux(mux) {
        await this._paramSet(_PARAM_AUX_ADCMUX, mux);
        await this._paramSet(_PARAM_CHLIST, _CHLIST_AUX);
        try {
            return (await this._measure()).aux;
        } finally {
            await this._paramSet(_PARAM_CHLIST, this._chlist);
        }
    }

    /** @returns {Promise<number>} Raw auxiliary value as die temperature (AUX_ADCMUX = 0x65, relative only). */
    async readTemperatureRaw() {
        return this._readAux(0x65);
    }

    /** @returns {Promise<number>} Raw auxiliary value as supply voltage (AUX_ADCMUX = 0x75). */
    async readVddRaw() {
        return this._readAux(0x75);
    }

    /**
     * Select the measured channels (CHLIST).
     * @param {boolean} uv - Enable the UV channel (cannot be combined with aux).
     * @param {boolean} visible - Enable the visible channel.
     * @param {boolean} infrared - Enable the infrared channel.
     * @param {boolean} [aux=false] - Enable the auxiliary channel.
     */
    async setChannels(uv, visible, infrared, aux = false) {
        if (!(uv || visible || infrared || aux)) throw new RangeError('at least one channel must be enabled');
        if (uv && aux) throw new RangeError('uv and aux share the data registers');
        const chlist = (uv ? 0x80 : 0) | (aux ? 0x40 : 0) | (infrared ? 0x20 : 0) | (visible ? 0x10 : 0);
        await this._paramSet(_PARAM_CHLIST, chlist);
        this._chlist = chlist;
    }

    /**
     * Set the ADC gain (integration time x 2^n) and the recommended recovery count.
     * @param {number} [visible=0] - Visible gain 0–7.
     * @param {number} [infrared=0] - Infrared gain 0–7.
     */
    async setGain(visible = 0, infrared = 0) {
        if (!(visible >= 0 && visible <= 7 && infrared >= 0 && infrared <= 7)) throw new RangeError('gain must be 0-7');
        await this._paramSet(_PARAM_VIS_GAIN, visible);
        await this._paramSet(_PARAM_VIS_COUNTER, ((~visible) & 0x07) << 4);
        await this._paramSet(_PARAM_IR_GAIN, infrared);
        await this._paramSet(_PARAM_IR_COUNTER, ((~infrared) & 0x07) << 4);
    }

    /**
     * Select the high signal range (sensitivity ÷ 14.5, for direct sunlight).
     * @param {boolean} visible
     * @param {boolean} infrared
     */
    async setHighRange(visible, infrared) {
        await this._paramSet(_PARAM_VIS_MISC, visible ? 0x20 : 0x00);
        await this._paramSet(_PARAM_IR_MISC, infrared ? 0x20 : 0x00);
    }

    /**
     * Select which 16 bits of the 17-bit ADC value are reported.
     * @param {boolean} visibleLow - true = least significant 16 bits, false = 16 MSBs.
     * @param {boolean} infraredLow - Same for the infrared channel.
     */
    async setAlignment(visibleLow, infraredLow) {
        await this._paramSet(_PARAM_ALS_ENCODING, (visibleLow ? 0x10 : 0) | (infraredLow ? 0x20 : 0));
    }

    /**
     * Write the four UV index coefficients UCOEF0..3.
     * @param {number[]} ucoef - Four byte values.
     */
    async setUvCoefficients(ucoef) {
        if (ucoef.length !== 4) throw new RangeError('four coefficients required');
        await this._conn.writeReg(_REG_UCOEF, Buffer.from(ucoef));
    }

    /** @returns {Promise<number[]>} The 12 factory calibration bytes (GET_CAL, then registers 0x22–0x2D). */
    async readCalibration() {
        await this._command(_CMD_GET_CAL);
        return Array.from(await this._conn.readReg(_REG_DATA, 12));
    }

    /**
     * Start (or stop) autonomous measurements.
     * @param {number} interval - Wake-up interval in seconds (multiple of 31.25 µs, at most ~2.05 s); 0 returns to forced mode.
     */
    async startAutonomous(interval) {
        const counts = Math.round(interval / 31.25e-6);
        if (interval < 0 || counts > 0xFFFF) throw new RangeError('interval out of range');
        if (this._seqId === 0x01) {   // erratum: MEAS_RATE bytes swapped
            await this._writeReg(_REG_MEAS_RATE_SWAPPED, counts & 0xFF);
            await this._writeReg(_REG_MEAS_RATE0, counts >> 8);
        } else {
            await this._writeReg(_REG_MEAS_RATE0, counts & 0xFF);
            await this._writeReg(_REG_MEAS_RATE1, counts >> 8);
        }
        await this._command(counts ? _CMD_ALS_AUTO : _CMD_ALS_PAUSE);
    }

    /** Pause autonomous measurements (ALS_PAUSE). */
    async pause() {
        await this._command(_CMD_ALS_PAUSE);
    }

    /** Start one forced measurement without waiting for it (ALS_FORCE). */
    async force() {
        await this._startMeasurement();
    }

    /** @returns {Promise<boolean>} true when IRQ_STATUS.ALS_INT is set. */
    async measurementReady() {
        return Boolean((await this._readReg(_REG_IRQ_STATUS)) & _IRQ_ALS_INT);
    }

    /** @returns {Promise<{counter: number, error: number}>} RESPONSE decoded; error 0 = none. */
    async readResponse() {
        const resp = await this._readReg(_REG_RESPONSE);
        return (resp & _RESP_ERROR) ? { counter: 0, error: resp } : { counter: resp & 0x0F, error: 0 };
    }

    /**
     * Change the I²C address (volatile; lost at reset).
     * @param {number} address - New 7-bit address written to the I2C_ADDR parameter.
     */
    async setI2cAddress(address) {
        await this._paramSet(_PARAM_I2C_ADDR, address);
        await this._command(_CMD_BUSADDR);
    }

    /** Software reset: RESET command, 2 ms wait, HW_KEY again. */
    async reset() {
        await this._writeReg(_REG_COMMAND, _CMD_RESET);
        await _sleep(2);
        await this._writeReg(_REG_HW_KEY, _HW_KEY_VALUE);
    }

    /** Drive INT low on completed measurements (INT_OE and ALS_IE). */
    async enableInterrupt() {
        await this._writeReg(_REG_IRQ_ENABLE, _IRQ_ALS_INT);
        await this._writeReg(_REG_INT_CFG, 0x01);
    }

    /** Release the INT pin (INT_OE = 0); ALS_IE stays set because forced readout polls ALS_INT. */
    async disableInterrupt() {
        await this._writeReg(_REG_INT_CFG, 0x00);
    }

    /** @returns {Promise<number>} IRQ_STATUS, written back to clear the flags (0 = nothing pending). */
    async pollInterrupt() {
        const status = await this._readReg(_REG_IRQ_STATUS);
        if (status) await this._writeReg(_REG_IRQ_STATUS, status);
        return status;
    }

    /**
     * Subscribe to measurement-complete events. Uses `intPin` or the connection's `intPin` (falling
     * edge); without a pin a polling timer calls `pollInterrupt()` every 10 ms.
     * @param {function(number): void} callback - Called with the IRQ_STATUS value.
     * @param {object} [intPin] - Optional InputPin wired to INT.
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        await this.enableInterrupt();
        this._intCallback = callback;
        const pin = intPin || (this._conn ? this._conn.intPin : null);
        if (pin) {
            this._intPin = pin;
            this._intHandler = () => { this._dispatchInt(); };
            await pin.onEdge(this._intHandler, 'falling');
        } else {
            this._startPollingFallback();
        }
    }

    /** Unsubscribe from INT events and release the INT output. */
    async offInterrupt() {
        const had = this._intCallback !== null;
        this._intCallback = null;
        if (this._intPin && this._intHandler) {
            await this._intPin.offEdge(this._intHandler);
        }
        this._intPin = null;
        this._intHandler = null;
        if (this._pollTimer !== null) {
            clearTimeout(this._pollTimer);
            this._pollTimer = null;
        }
        if (had) await this.disableInterrupt();
    }

    async _dispatchInt() {
        const callback = this._intCallback;
        if (!callback) return;
        try {
            const status = await this.pollInterrupt();
            if (status) callback(status);
        } catch (e) {
            // A failing bus read must not kill the event loop.
        }
    }

    _startPollingFallback() {
        const tick = async () => {
            if (this._intCallback === null) return;
            await this._dispatchInt();
            if (this._intCallback !== null) this._pollTimer = setTimeout(tick, 10);
        };
        this._pollTimer = setTimeout(tick, 0);
    }
}

/** Fixed 7-bit I²C address. */
Si1132Minimal.I2C_ADDRESS = 0x60;

module.exports = { Si1132Minimal, Si1132Full };
