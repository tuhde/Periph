'use strict';

const { toSigned } = require('../../connection/register');

const _REG_WHO_AM_I = 0x0F;
const _REG_AV_CONF = 0x10;
const _REG_CTRL_REG1 = 0x20;
const _REG_CTRL_REG2 = 0x21;
const _REG_CTRL_REG3 = 0x22;
const _REG_STATUS = 0x27;
const _REG_HUMIDITY_OUT = 0x28;
const _REG_CALIB = 0x30;

const _AUTO_INC = 0x80;
const _CTRL_REG1_DEFAULT = 0x84;   // PD=1, BDU=1, ODR=00 (one-shot)
const _CTRL1_PD = 0x80;
const _CTRL1_BDU = 0x04;
const _CTRL1_ODR_MASK = 0x03;
const _CTRL2_BOOT = 0x80;
const _CTRL2_HEATER = 0x02;
const _CTRL2_ONE_SHOT = 0x01;
const _STATUS_BOTH = 0x03;
const _TIMEOUT_MS = 100;

const _TEMP_SAMPLES = [2, 4, 8, 16, 32, 64, 128, 256];
const _HUM_SAMPLES = [4, 8, 16, 32, 64, 128, 256, 512];
const _ODR_HZ = [0, 1, 7, 12.5];

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * HTS221 capacitive relative-humidity and temperature sensor (STMicroelectronics) — minimal interface.
 *
 * `init()` reads the factory calibration (registers 0x30–0x3F) and writes CTRL_REG1 = 0x84 (active,
 * block data update, one-shot). `read()` starts a one-shot conversion, waits for it (timeout 100 ms)
 * and converts the outputs by linear interpolation. The I²C address is fixed at 0x5F. The constructor
 * does not touch the bus.
 */
class HTS221Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x5F).
     */
    constructor(connection) {
        this._conn = connection;
        this._cal = null;
    }

    /**
     * Read the calibration block and start one-shot mode (CTRL_REG1 = 0x84).
     * @returns {Promise<void>}
     */
    async init() {
        await this._readCalibration();
        await this._writeReg(_REG_CTRL_REG1, _CTRL_REG1_DEFAULT);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _readBurst(reg, length) {
        return Buffer.from(await this._conn.readReg(reg | _AUTO_INC, length));
    }

    async _readCalibration() {
        const c = await this._readBurst(_REG_CALIB, 16);
        const t0 = ((c[5] & 0x03) << 8) | c[2];
        const t1 = ((c[5] & 0x0C) << 6) | c[3];
        this._cal = {
            H0_rH: c[0] / 2.0,
            H1_rH: c[1] / 2.0,
            T0_degC: t0 / 8.0,
            T1_degC: t1 / 8.0,
            H0_T0_OUT: toSigned((c[7] << 8) | c[6], 16),
            H1_T0_OUT: toSigned((c[11] << 8) | c[10], 16),
            T0_OUT: toSigned((c[13] << 8) | c[12], 16),
            T1_OUT: toSigned((c[15] << 8) | c[14], 16),
        };
    }

    _humidityFromRaw(raw) {
        const c = this._cal;
        const span = c.H1_T0_OUT - c.H0_T0_OUT;
        if (span === 0) return 0.0;
        const v = (raw - c.H0_T0_OUT) * (c.H1_rH - c.H0_rH) / span + c.H0_rH;
        return Math.max(0.0, Math.min(100.0, v));
    }

    _temperatureFromRaw(raw) {
        const c = this._cal;
        const span = c.T1_OUT - c.T0_OUT;
        if (span === 0) return 0.0;
        return (raw - c.T0_OUT) * (c.T1_degC - c.T0_degC) / span + c.T0_degC;
    }

    async _readRawPair() {
        const d = await this._readBurst(_REG_HUMIDITY_OUT, 4);
        return [toSigned((d[1] << 8) | d[0], 16), toSigned((d[3] << 8) | d[2], 16)];
    }

    async _oneShotRaw() {
        const ctrl2 = await this._readReg(_REG_CTRL_REG2);
        await this._writeReg(_REG_CTRL_REG2, ctrl2 | _CTRL2_ONE_SHOT);
        let status = 0;
        for (let i = 0; i < _TIMEOUT_MS; i++) {
            status = await this._readReg(_REG_STATUS);
            if ((status & _STATUS_BOTH) === _STATUS_BOTH) return this._readRawPair();
            await _sleep(1);
        }
        throw new Error('HTS221 conversion timeout (STATUS=0x' + status.toString(16) + ')');
    }

    /**
     * Measure temperature and relative humidity (one-shot).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} °C and %rH (humidity clamped to 0–100)
     */
    async read() {
        const [h, t] = await this._oneShotRaw();
        return { temperature_c: this._temperatureFromRaw(t), humidity_pct: this._humidityFromRaw(h) };
    }
}

/**
 * HTS221 full interface — single values, raw outputs, output data rate, averaging, power modes, heater,
 * DRDY pin, calibration access, dew point and data-ready interrupt support.
 *
 * Extends HTS221Minimal.
 */
class HTS221Full extends HTS221Minimal {
    /** @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x5F). */
    constructor(connection) {
        super(connection);
        this._intCallback = null;
        this._intHandler = null;
        this._pollTimer = null;
    }

    /** @returns {Promise<number>} Relative humidity in %rH (one-shot, clamped to 0–100). */
    async readHumidity() {
        return this._humidityFromRaw((await this._oneShotRaw())[0]);
    }

    /** @returns {Promise<number>} Temperature in °C (one-shot). */
    async readTemperature() {
        return this._temperatureFromRaw((await this._oneShotRaw())[1]);
    }

    /** @returns {Promise<number>} Signed 16-bit raw humidity output (one-shot). */
    async readRawHumidity() {
        return (await this._oneShotRaw())[0];
    }

    /** @returns {Promise<number>} Signed 16-bit raw temperature output (one-shot). */
    async readRawTemperature() {
        return (await this._oneShotRaw())[1];
    }

    /**
     * Read the latest sample without starting a conversion (continuous output data rate).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>}
     */
    async readContinuous() {
        const [h, t] = await this._readRawPair();
        return { temperature_c: this._temperatureFromRaw(t), humidity_pct: this._humidityFromRaw(h) };
    }

    /** @returns {Promise<number>} Dew point in °C from one measurement (Magnus formula). */
    async dewPoint() {
        const m = await this.read();
        const t = m.temperature_c;
        const h = Math.max(m.humidity_pct, 0.01);
        const g = Math.log(h / 100.0) + 17.62 * t / (243.12 + t);
        return 243.12 * g / (17.62 - g);
    }

    /**
     * Set the output data rate (CTRL_REG1.ODR).
     * @param {number} hz - 0 (one-shot), 1, 7 or 12.5.
     * @throws {Error} For any other value.
     */
    async setOutputDataRate(hz) {
        const code = _ODR_HZ.indexOf(hz);
        if (code < 0) throw new Error('output data rate must be 0, 1, 7 or 12.5 Hz');
        const ctrl1 = await this._readReg(_REG_CTRL_REG1);
        await this._writeReg(_REG_CTRL_REG1, (ctrl1 & ~_CTRL1_ODR_MASK) | code);
        // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
        await this._readRawPair();
    }

    /** @returns {Promise<number>} Output data rate in Hz (0, 1, 7 or 12.5). */
    async getOutputDataRate() {
        return _ODR_HZ[(await this._readReg(_REG_CTRL_REG1)) & _CTRL1_ODR_MASK];
    }

    /**
     * Set the internal averaging (AV_CONF).
     * @param {number} temperatureSamples - 2, 4, 8, 16, 32, 64, 128 or 256.
     * @param {number} humiditySamples - 4, 8, 16, 32, 64, 128, 256 or 512.
     * @throws {Error} For any other value.
     */
    async setAveraging(temperatureSamples, humiditySamples) {
        const t = _TEMP_SAMPLES.indexOf(temperatureSamples);
        const h = _HUM_SAMPLES.indexOf(humiditySamples);
        if (t < 0) throw new Error('temperatureSamples must be a power of two, 2..256');
        if (h < 0) throw new Error('humiditySamples must be a power of two, 4..512');
        await this._writeReg(_REG_AV_CONF, (t << 3) | h);
    }

    /** @returns {Promise<[number, number]>} [temperature samples, humidity samples]. */
    async getAveraging() {
        const v = await this._readReg(_REG_AV_CONF);
        return [_TEMP_SAMPLES[(v >> 3) & 0x07], _HUM_SAMPLES[v & 0x07]];
    }

    /** @param {boolean} enabled - Block data update (CTRL_REG1.BDU). */
    async setBlockDataUpdate(enabled) {
        let ctrl1 = await this._readReg(_REG_CTRL_REG1);
        ctrl1 = enabled ? (ctrl1 | _CTRL1_BDU) : (ctrl1 & ~_CTRL1_BDU);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }

    /** Enter power-down mode (CTRL_REG1.PD = 0). */
    async powerDown() {
        await this._writeReg(_REG_CTRL_REG1, (await this._readReg(_REG_CTRL_REG1)) & ~_CTRL1_PD);
    }

    /** Leave power-down mode (CTRL_REG1.PD = 1). */
    async powerUp() {
        await this._writeReg(_REG_CTRL_REG1, (await this._readReg(_REG_CTRL_REG1)) | _CTRL1_PD);
    }

    /** @param {boolean} on - Switch the heater on or off (do not measure while heating). */
    async setHeater(on) {
        let ctrl2 = await this._readReg(_REG_CTRL_REG2);
        ctrl2 = on ? (ctrl2 | _CTRL2_HEATER) : (ctrl2 & ~_CTRL2_HEATER);
        await this._writeReg(_REG_CTRL_REG2, ctrl2);
    }

    /** @returns {Promise<boolean>} True when the heater is on. */
    async isHeaterOn() {
        return ((await this._readReg(_REG_CTRL_REG2)) & _CTRL2_HEATER) !== 0;
    }

    /**
     * Reload the trimming registers (CTRL_REG2.BOOT), wait for it to clear (timeout 100 ms) and re-read
     * the calibration.
     * @throws {Error} If BOOT does not clear in time.
     */
    async reboot() {
        const ctrl2 = await this._readReg(_REG_CTRL_REG2);
        await this._writeReg(_REG_CTRL_REG2, ctrl2 | _CTRL2_BOOT);
        for (let i = 0; i < _TIMEOUT_MS; i++) {
            if (((await this._readReg(_REG_CTRL_REG2)) & _CTRL2_BOOT) === 0) {
                await this._readCalibration();
                return;
            }
            await _sleep(1);
        }
        throw new Error('HTS221 reboot timeout');
    }

    /** @returns {Promise<number>} WHO_AM_I register (0xBC). */
    async whoAmI() {
        return this._readReg(_REG_WHO_AM_I);
    }

    /** @returns {Promise<number>} STATUS_REG (H_DA = 0x02, T_DA = 0x01). */
    async status() {
        return this._readReg(_REG_STATUS);
    }

    /** @returns {Promise<boolean>} True when H_DA and T_DA are both set. */
    async dataReady() {
        return ((await this.status()) & _STATUS_BOTH) === _STATUS_BOTH;
    }

    /** @returns {Promise<object>} Decoded calibration: H0_rH, H1_rH, T0_degC, T1_degC, H0_T0_OUT, H1_T0_OUT, T0_OUT, T1_OUT. */
    async readCalibration() {
        return Object.assign({}, this._cal);
    }

    /**
     * Configure the DRDY output pin (CTRL_REG3).
     * @param {boolean} enabled - Route data-ready to the DRDY pin.
     * @param {boolean} [activeHigh=true] - false selects active-low.
     * @param {boolean} [openDrain=false] - true selects open-drain.
     */
    async configureDrdy(enabled, activeHigh = true, openDrain = false) {
        let v = 0;
        if (!activeHigh) v |= 0x80;
        if (openDrain) v |= 0x40;
        if (enabled) v |= 0x04;
        await this._writeReg(_REG_CTRL_REG3, v);
    }

    /**
     * Subscribe to data-ready events. Enables DRDY (active high, push-pull) and uses the connection's
     * `intPin` (rising edge); without a pin a polling timer calls `pollInterrupt()` at the output data rate.
     * @param {function(number): void} callback - Called with the STATUS_REG value.
     * @param {object} [intPin] - Optional InputPin wired to DRDY (default: the connection's `intPin`).
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        await this.configureDrdy(true);
        this._intCallback = callback;
        const pin = intPin || (this._conn ? this._conn.intPin : null);
        if (pin) {
            this._intPin = pin;
            this._intHandler = () => { this._dispatchInt(); };
            await pin.onEdge(this._intHandler, 'rising');
        } else {
            await this._startPollingFallback();
        }
    }

    /** Unsubscribe from data-ready events and disable the DRDY output. */
    async offInterrupt() {
        const had = this._intCallback !== null || this._pollTimer !== null;
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
        if (had) await this.configureDrdy(false);
    }

    /** @returns {Promise<number>} STATUS_REG; the flags clear when the output registers are read. */
    async pollInterrupt() {
        return this.status();
    }

    async _dispatchInt() {
        const callback = this._intCallback;
        if (!callback) return;
        try {
            callback(await this.pollInterrupt());
        } catch (e) {
            // A failing bus read must not kill the event loop.
        }
    }

    async _startPollingFallback() {
        const tick = async () => {
            if (this._intCallback === null) return;
            try {
                if (await this.dataReady()) await this._dispatchInt();
                const hz = await this.getOutputDataRate();
                this._pollTimer = setTimeout(tick, hz > 0 ? Math.floor(1000 / hz) : 100);
            } catch (e) {
                this._pollTimer = setTimeout(tick, 100);
            }
        };
        this._pollTimer = setTimeout(tick, 0);
    }
}

/** Default 7-bit I²C address (fixed, no address pins). */
HTS221Minimal.I2C_ADDRESS = 0x5F;

module.exports = { HTS221Minimal, HTS221Full };
